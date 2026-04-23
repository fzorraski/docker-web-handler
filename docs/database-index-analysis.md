# Database Index Analysis Guide

This document explains how to interpret the Tables and Indexes tabs in Docker Web Handler's database health dialog, and how to identify performance problems related to indexes.

## Understanding the Statistics Source

The statistics shown in the Tables and Indexes tabs come from PostgreSQL's catalog views:
- **Tables tab**: `pg_stat_user_tables` — cumulative scan counts, tuple counts, vacuum timestamps
- **Indexes tab**: `pg_stat_user_indexes` — cumulative index scan counts

These counters are **cumulative since stats reset**. If the database was restored from a backup, these values accurately reflect the source database at the time of the dump. The `statsResetAt` date shown in the UI indicates when the counters started accumulating.

---

## Unused Indexes — Why They Matter

Unused indexes are indexes with **zero scans** (`idx_scan = 0`) since the stats reset. Primary keys and unique constraints are excluded since they serve data integrity purposes beyond query performance.

### Impact on Write Performance

Every `INSERT`, `UPDATE`, or `DELETE` on a table must update **all** indexes on that table, including unused ones. Each index update involves:
- Navigating the B-tree structure to find the correct page
- Inserting/updating the index entry
- Potentially splitting pages if they are full
- Writing the modification to the WAL (Write-Ahead Log)

**Estimated overhead**: ~2-5% per unused index per write operation. A table with 8 unused indexes is approximately **20% slower on writes** than it needs to be.

### Impact on WAL and Replication

Every index modification generates WAL records. More indexes = more WAL generated = more disk I/O = more data for replication if replicas exist. Unused indexes generate WAL continuously without any read benefit.

### Impact on VACUUM

VACUUM must clean dead tuples from **every index** on the table. With unused indexes:
- VACUUM takes longer to complete
- If VACUUM cannot keep up with dead tuple generation, the table bloats
- Table bloat causes sequential scans to read more pages, further degrading performance

### Impact on Memory (shared_buffers)

PostgreSQL caches index pages in `shared_buffers`. Unused indexes compete for cache space with data and indexes that are actually needed. With a typical `shared_buffers` of 25% of RAM (e.g., 4 GB on a 16 GB server), large unused indexes can occupy a significant portion of the cache, forcing useful data to be read from disk instead of memory.

The difference: ~0.1ms (memory) vs ~5-10ms (SSD) or ~50-100ms (HDD) per access — **50-1000x slower**.

### Impact on Checkpoints

During a checkpoint, PostgreSQL writes all dirty pages to disk. Pages of unused indexes modified by writes are included, making checkpoints longer and increasing I/O spikes.

---

## How to Assess the Impact

### Query: Unused Index Overhead per Table

Run this in the SQL Query tool to see which tables are most affected:

```sql
SELECT
  s.relname AS table_name,
  count(*) AS unused_indexes,
  pg_size_pretty(sum(pg_relation_size(s.indexrelid))) AS wasted_size,
  (SELECT n_tup_ins + n_tup_upd + n_tup_del
   FROM pg_stat_user_tables t
   WHERE t.relname = s.relname) AS total_writes
FROM pg_stat_user_indexes s
JOIN pg_index i ON s.indexrelid = i.indexrelid
WHERE s.idx_scan = 0
  AND NOT i.indisprimary
  AND NOT i.indisunique
GROUP BY s.relname
ORDER BY sum(pg_relation_size(s.indexrelid)) DESC
```

This shows per table: how many unused indexes exist, how much space they waste, and how many write operations have paid the overhead.

### Interpreting the Results

| Column | Meaning |
|--------|---------|
| `unused_indexes` | Number of indexes that were never scanned |
| `wasted_size` | Disk space occupied by unused indexes |
| `total_writes` | Total INSERT + UPDATE + DELETE operations on the table |

**High `total_writes` + high `unused_indexes`** = maximum impact. Each write paid overhead for indexes that never served a query.

---

## Low IDX USAGE — What It Means

The **IDX USAGE** column in the Tables tab shows the percentage of scans that used an index vs sequential scans:

```
idx_usage = idx_scan / (seq_scan + idx_scan) * 100
```

### Warning Signs

| IDX USAGE | Meaning |
|-----------|---------|
| > 90% | Healthy — most queries use indexes |
| 50-90% | Some queries may benefit from additional indexes |
| < 50% | Many queries are doing full table scans |
| < 20% | Critical — most queries read the entire table |

### Common Causes of Low IDX USAGE

1. **Missing indexes** — queries filter on columns that have no index
2. **Wrong indexes** — indexes exist but don't match the query patterns (wrong columns, wrong order)
3. **Small tables** — PostgreSQL intentionally uses sequential scan for small tables because it's faster than index lookup
4. **High selectivity queries** — queries that return most of the table's rows are faster with sequential scan

### The Ironic Pattern

A table can simultaneously have:
- **Unused indexes** (indexes that don't match any query pattern)
- **Low IDX USAGE** (missing indexes for the actual query patterns)

This means the table is paying the write cost for the wrong indexes while not benefiting from the right ones. This is the worst combination for performance.

---

## How to Investigate Slow Queries

### Step 1: Find the Slowest Queries on a Table

```sql
SELECT query, calls, mean_exec_time, rows
FROM pg_stat_statements
WHERE query ILIKE '%table_name%'
ORDER BY total_exec_time DESC
LIMIT 10
```

### Step 2: Analyze the Query Plan

Use the **Explain Analyze** button in the SQL Query tool on the slow queries. Look for:

- **Seq Scan** on large tables — indicates a missing index
- **High cost** nodes — the plan's bottleneck
- **Row estimate mismatch** — planned vs actual rows differ significantly, indicating stale table statistics (run `ANALYZE table_name`)

### Step 3: Check if Existing Indexes Cover the Query

The Explain plan shows which index (if any) was used. If it shows `Seq Scan` on a table that has indexes, the existing indexes don't match the query's `WHERE` clause columns.

---

## Data vs Index Size Ratio

When indexes are **larger than the data** (e.g., 324 MB data, 540 MB indexes), it usually indicates:
- Redundant indexes (multiple indexes covering similar columns)
- Too many unused indexes inflating the total
- Composite indexes with many columns

A healthy ratio depends on the workload, but indexes significantly exceeding data size warrant investigation.

---

## Summary Checklist

1. **Check UNUSED INDEXES** — identify and validate candidates for removal (start with the largest)
2. **Check IDX USAGE** — tables with < 50% usage on large tables need index investigation
3. **Check Dead Rows** — high dead row counts indicate VACUUM is not keeping up
4. **Check Data vs Index ratio** — indexes larger than data suggest redundancy
5. **Run Explain Analyze** — on the slowest queries from Top Queries to identify missing indexes
6. **Cross-reference** — unused indexes + low idx usage on the same table = wrong indexes exist, right ones are missing
