# Phase 1 Data Model: Frontend Theme

The persisted entity, its storage, and the client-side token model.

## Persisted entity

### `UserPreference` → table `user_preference` (Liquibase `003-user-preference.xml`)

One row per user who has chosen a theme. Keyed by **username** (not a foreign key to `managed_user`) so it
applies to both static-config users and managed (DB) users.

| Column        | Type          | Notes                                                             |
|---------------|---------------|-------------------------------------------------------------------|
| `username`    | VARCHAR(200)  | **Primary key.** The authenticated principal's name.              |
| `theme`       | VARCHAR(2000) | The theme choice as a compact JSON string (see below). NOT NULL.  |
| `updated_at`  | TIMESTAMP     | Last write time. NOT NULL. (`modifySql` maps to `timestamp` on SQLite, matching changesets `001`/`002`.) |

- **Access pattern**: point read/upsert by username (`findById` / `save`). No secondary index needed.
- **Engines**: created by Liquibase on both SQLite (default) and PostgreSQL; Hibernate does not manage the
  schema (`ddl-auto=none`), consistent with features 016/018.

### Stored `theme` JSON

```json
{ "preset": "emerald", "accent": "#112233" }
```

- `preset` — one of the known preset names (see the token model). Required.
- `accent` — a `#rrggbb` hex, or `null` to use the preset's own accent.

A row whose `theme` fails to parse is treated as "no preference" (the default palette is used) rather than
surfaced as an error.

## Value type (backend)

### `ThemePreference(preset: String, accent: String?)`

Validated on the way in via `ThemePreference.of(preset, accent)`:

- `preset` is trimmed/lowercased and MUST be in `PRESETS = { vault-gold, emerald, crimson, slate }`, else
  `InvalidThemeException` ⇒ 400.
- `accent` is trimmed; empty ⇒ null; otherwise MUST match `#[0-9a-fA-F]{6}` (lowercased), else 400.

## Client-side token model (frontend)

### Presets — `lib/theme/presets.ts`

Each preset is a full map of the `--rq-*` design tokens (feature 016), so switching presets fully re-skins
the app and leaves no stale token. The keys mirror `tokens.css`:

`bg, shell, panel, inset, rail, gold, gold-soft, text, muted, dim, border, border-strong, border-subtle,
row-hover, nav-hover, danger, danger-bg` (applied as `--rq-<key>`).

| Preset       | Label      | Swatch    | Character                               |
|--------------|------------|-----------|-----------------------------------------|
| `vault-gold` | Vault Gold | `#c9a227` | The default (feature 016) — bronze/gold on near-black. |
| `emerald`    | Emerald    | `#3fb27f` | Green accent on a warm-dark shell.      |
| `crimson`    | Crimson    | `#d24b4b` | Red accent on a dark shell.             |
| `slate`      | Slate      | `#6f9fd0` | Cool steel-blue accent on a blue-gray shell. |

### Accent derivation — `accentOverrides(hex)`

A custom accent overrides only the accent/text ramp, derived from the single `#rrggbb` by scaling each RGB
channel (clamped to a valid byte):

| Token             | Value                |
|-------------------|----------------------|
| `--rq-gold`       | the accent hex       |
| `--rq-gold-soft`  | scale(hex, 0.78)     |
| `--rq-text`       | scale(hex, 1.14)     |
| `--rq-muted`      | scale(hex, 0.70)     |
| `--rq-dim`        | scale(hex, 0.60)     |
| `--rq-border-strong` | scale(hex, 0.50)  |

`resolveVars(choice)` = the chosen preset's token map, with the accent overrides merged on top when an
accent is set, emitted as `--rq-*` properties.

### Choice — `ThemeChoice { preset: string; accent: string | null }`

The single client-side shape. Structurally identical to the API's `ThemePreference`.

## Client-side persistence keys (localStorage)

| Key                       | Contents                                                        |
|---------------------------|-----------------------------------------------------------------|
| `relikquary.theme`        | The `ThemeChoice` JSON — the source of truth restored on load.  |
| `relikquary.theme.vars`   | The pre-resolved `--rq-*` map — read by the `app.html` boot step to apply the theme before first paint. |

Both are written on every `setTheme`; a corrupt/absent value falls back to the default palette.
