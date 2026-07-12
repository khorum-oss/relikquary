# Phase 0 Research: Frontend Theme — User-Selectable Palette & Custom Accent

Design decisions behind the theme feature. Each is stated as Decision / Rationale / Alternatives, matching
the repo's research style (features 016, 018).

## 1. How the theme is applied — swap the existing `--rq-*` design tokens

- **Decision**: A theme is a map of the existing `--rq-*` CSS custom properties (feature 016's design
  tokens) applied to `document.documentElement` via `style.setProperty`. Components are unchanged — they
  already read `var(--rq-*)`.
- **Rationale**: The whole UI is already token-driven, so re-skinning is a data change, not a markup change.
  Setting properties on the root element overrides the `:root` defaults in `tokens.css` with the highest
  precedence and is trivially reversible.
- **Alternatives rejected**: Per-component theme props (invasive, fragile); a `data-theme` attribute with N
  full CSS blocks per theme (duplicates every rule, and doesn't support an arbitrary custom accent);
  swapping stylesheets (heavier, flashes).

## 2. Colour control — presets plus a custom accent (not full DIY)

- **Decision**: Ship four curated, fully-designed palettes (Vault Gold default, Emerald, Crimson, Slate) and
  let the user override just the accent/text ramp with a single `#rrggbb` picker. The accent ramp is derived
  from the one colour by scaling RGB channels (a soft/dim/text set), layered over the chosen preset.
- **Rationale**: Directly answers "especially colour choices" while keeping every result coherent and
  readable. A single-hex accent covers the common case (brand accent) without exposing a dozen token pickers
  a user could turn into an unreadable palette.
- **Alternatives rejected**: Presets-only (doesn't satisfy "colour choices"); full per-token pickers
  (largest UI, easiest to make illegible — explicitly rejected in clarification).

## 3. Persistence — localStorage always + per-user on the server

- **Decision**: Persist to `localStorage` on every change (instant apply next load, and works anonymously),
  and — when signed in — also `PUT` to the server. On load/sign-in, the server copy is authoritative
  (adopted and applied); if the account has no theme, the local choice seeds the server.
- **Rationale**: The user asked for a per-user, cross-device choice (server), but the UI must still apply
  instantly and never block on the network for its appearance (local). "Server wins on login" gives a
  predictable single source of truth for authenticated users while keeping anonymous use working.
- **Alternatives rejected**: localStorage-only (doesn't follow the user across devices — fails the ask);
  server-only (flashes/blocks on load, breaks for anonymous visitors, extra round-trip on every paint).

## 4. Where per-user state lives — a dedicated `user_preference` table keyed by username

- **Decision**: A new `user_preference` table (username PK, `theme` JSON string, `updated_at`) via Liquibase
  changeset `003`. Keyed by username, not a foreign key to `managed_user`.
- **Rationale**: A username key works for BOTH static-config users (who have no `managed_user` row) and
  managed users, with no join. The generic `setting` table is semantically a global admin setting
  (`updated_by`, "administrator-editable"), so a dedicated table keeps intent clear and queries simple.
- **Alternatives rejected**: A column on `managed_user` (misses config users); reusing the `setting` table
  keyed by `theme:{username}` (overloads a global-settings store, muddies its meaning).

## 5. Endpoint shape and authorization — `/api/me/preferences`, any authenticated principal

- **Decision**: `GET`/`PUT /api/me/preferences`, resolving the username from the security context (like
  tokens record their owner). A new `api/me` branch in the authorization manager grants **any authenticated
  principal** (new `RepositoryAuthorizer.permitsAuthenticated`); anonymous ⇒ 401.
- **Rationale**: A user managing their OWN theme is self-service, not administration — it must not require
  the global publish role that `/api/admin` requires. Scoping to the security-context principal means a user
  can only ever read/write their own preference; no id in the path to spoof.
- **Alternatives rejected**: Putting it under `/api/admin` (wrong gate — every user themes themselves); a
  `/api/users/{id}/preferences` shape (invites cross-user access bugs and needs its own authz).

## 6. Validation — allow-listed preset + `#rrggbb` accent, rejected before storage

- **Decision**: The server validates the preset against a known allow-list and the accent against `#rrggbb`
  (or null), returning 400 on a malformed theme; the frontend keeps the same allow-list.
- **Rationale**: Keeps stored data clean and the round-trip testable, and prevents a client bug (or a curl)
  from persisting garbage that the UI would then have to defend against on every load.
- **Alternatives rejected**: Storing an opaque JSON blob unvalidated (garbage-in, and the UI must sanitize
  on read anyway); validating only client-side (bypassable).

## 7. No flash of the default palette — an inline boot step in `app.html`

- **Decision**: The theme store also caches the **pre-resolved** `--rq-*` map to `localStorage`; a tiny
  inline `<script>` in `app.html` reads it and applies the properties before first paint. The runes store
  re-applies on hydrate and reconciles with the server.
- **Rationale**: Applying the theme only after hydration (onMount) flashes the default first. Caching the
  resolved map lets the boot step apply the exact tokens with no palette data duplicated into `app.html`.
- **Alternatives rejected**: Inlining all preset palettes into `app.html` (duplicates the presets module);
  accepting the flash (poor for a non-default theme).

## 8. (De)serialization — a Kotlin-aware ObjectMapper

- **Decision**: `UserPreferenceService` uses `ObjectMapper().registerKotlinModule()` to (de)serialize the
  `ThemePreference` Kotlin data class; an unreadable stored value is discarded as "no preference".
- **Rationale**: A plain `ObjectMapper` can serialize a data class but cannot reconstruct it (no no-arg
  constructor) — deserialization silently fails without the Kotlin module. Discarding an unreadable value
  keeps a forward-written or hand-edited row from breaking the load. (The app has no injectable `ObjectMapper`
  bean; services construct their own, per the existing container-code convention.)
- **Alternatives rejected**: Injecting an `ObjectMapper` bean (none is exposed; would fail context startup);
  a plain `ObjectMapper` (deserialization returns null → the theme never round-trips).
