# Internationalization (i18n)

## Overview

Docker Web Handler supports multiple languages in the web UI. All user-facing text is externalized into locale files and can be switched at runtime via the language switcher in the navigation bar.

---

## Supported Languages

| Code | Language |
|------|----------|
| `en` | English |
| `es` | Spanish |
| `pt-BR` | Brazilian Portuguese |

---

## How It Works

- Locale files are located in `src/main/webui/src/i18n/locales/`
- Each file exports a translation object with nested keys matching component needs
- The `LanguageSwitcher` component in the navbar allows users to change the language at runtime
- The selected language is persisted in the browser so it survives page reloads
- The backend `ui.locale` property controls the locale for date/time picker formatting (independent of the UI language)

---

## Adding a New Language

1. Create a new locale file in `src/main/webui/src/i18n/locales/` (e.g., `fr.ts`)
2. Copy the structure from `en.ts` and translate all values
3. Register the new locale in the i18n configuration
4. Add the language option to the `LanguageSwitcher` component

---

## Locale File Structure

Each locale file exports an object with sections matching the application areas:

```typescript
export default {
  navbar: {
    containers: "Containers",
    images: "Images",
    database: "Database",
    // ...
  },
  containers: {
    title: "Container Management",
    search: "Search containers...",
    // ...
  },
  images: {
    title: "Image Management",
    // ...
  },
  database: {
    dumps: { /* ... */ },
    snapshots: { /* ... */ },
    migrations: { /* ... */ },
  },
  common: {
    confirm: "Confirm",
    cancel: "Cancel",
    // ...
  },
  // ...
};
```
