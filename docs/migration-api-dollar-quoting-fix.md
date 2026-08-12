# Migration API: Dollar-Quoted SQL Blocks Are Being Broken by Statement Splitting

## The Problem

The Docker Web Handler consumes your migration API response and reassembles the `statements` array into a single SQL file that is executed via `psql -f`. The consumer does this:

```java
// For each statement in the JSON array:
// 1. Trims whitespace
// 2. Appends ";" if the statement doesn't already end with one
// 3. Joins with newlines
```

This works perfectly for simple DDL/DML. **However**, when your API splits SQL into statements by splitting on `;`, it breaks PostgreSQL dollar-quoted blocks (`$$ ... $$`) that contain semicolons inside the function body.

## Example: What Breaks

Your API receives this migration SQL:

```sql
CREATE OR REPLACE FUNCTION insertPackagesType(type text, ordinal integer)
RETURNS TEXT AS $$
DECLARE
    clients RECORD;
BEGIN
    FOR clients IN SELECT cl.id FROM mywms_client cl LOOP
        INSERT INTO spk_unitloadpackagetype(created, entity_lock, modified, version,
            amountOfItemData, packageName, packageType, client_id)
        VALUES (now(), 0, now(), 0, 0, type, ordinal, clients.id);
    END LOOP;
    RETURN '1';
END;
$$ LANGUAGE plpgsql;

SELECT insertPackagesType('MIXED', 0);
```

If your API splits on `;`, the `statements` array becomes something like:

```json
{
  "statements": [
    "CREATE OR REPLACE FUNCTION insertPackagesType(type text, ordinal integer)\nRETURNS TEXT AS $$\nDECLARE\n    clients RECORD",
    "\nBEGIN\n    FOR clients IN SELECT cl.id FROM mywms_client cl LOOP\n        INSERT INTO spk_unitloadpackagetype(...)\n        VALUES (now(), 0, now(), 0, 0, type, ordinal, clients.id)",
    "\n    END LOOP",
    "\n    RETURN '1'",
    "\nEND",
    "\n$$ LANGUAGE plpgsql",
    "SELECT insertPackagesType('MIXED', 0)"
  ]
}
```

Each fragment is invalid SQL on its own, and reassembling them with `;` between each one produces broken syntax.

## The Same Script Works in Manual Mode

When the user pastes the exact same SQL in manual mode, it is sent as a single string and written directly to a file for `psql -f` — no splitting occurs. That's why it works manually but fails via the API.

## What Needs to Change

Your statement splitter must be **dollar-quote aware**. When scanning for `;` to split statements, it must track whether the current position is inside a `$tag$...$tag$` block and skip semicolons within it.

**Dollar-quoting rules in PostgreSQL:**
- Starts with `$$` or `$identifier$` (e.g., `$func$`, `$body$`)
- Ends with the **same** tag (e.g., `$$...$$` or `$func$...$func$`)
- Everything between the tags is a literal string — semicolons inside are NOT statement terminators
- Tags can be nested if they use different identifiers

**Pseudocode for a safe splitter:**

```
function splitStatements(sql):
    statements = []
    current = ""
    i = 0
    dollarTag = null

    while i < sql.length:
        // Check for dollar-quote start/end
        if sql[i] == '$':
            tag = extractDollarTag(sql, i)  // e.g., "$$" or "$func$"
            if tag != null:
                if dollarTag == null:
                    dollarTag = tag          // entering dollar-quoted block
                elif dollarTag == tag:
                    dollarTag = null         // exiting dollar-quoted block
                current += tag
                i += tag.length
                continue

        // Only split on ";" when NOT inside a dollar-quoted block
        if sql[i] == ';' and dollarTag == null:
            current += ';'
            statements.add(current.trim())
            current = ""
        else:
            current += sql[i]

        i++

    if current.trim() is not empty:
        statements.add(current.trim())

    return statements
```

**Also handle:**
- Standard single-quoted strings (`'...'`) — semicolons inside `'it''s a test;'` are not terminators
- Line comments (`-- ...`) — ignore everything until newline
- Block comments (`/* ... */`) — ignore everything until `*/`

## Alternative: Return Plain SQL Instead of a Statements Array

If modifying the splitter is too complex, you can return the migration as **plain SQL text** instead of a JSON statements array. The Docker Web Handler already supports this format:

```
// Instead of:
{"statements": ["stmt1", "stmt2", ...]}

// Return:
{"statements": ["CREATE FUNCTION ... $$ ... $$;\nSELECT ...;\nALTER TABLE ...;"]}
```

Or return the entire SQL as a single element in the array — one string containing all statements. The consumer writes it to a file and `psql` handles the parsing correctly.

## Summary

| Format | Works with `$$`? |
|--------|-----------------|
| `{"statements": ["stmt1", "stmt2"]}` (split on `;`) | **Breaks** if any statement contains `$$` blocks |
| `{"statements": ["full SQL as one string"]}` | **Works** — `psql` parses it correctly |
| Plain SQL text response | **Works** — passed directly to `psql -f` |

The safest fix is making your splitter dollar-quote aware. The quickest workaround is returning the full SQL as a single string.
