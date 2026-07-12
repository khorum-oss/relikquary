# Feature Specification: Frontend Theme — User-Selectable Palette & Custom Accent

**Feature Branch**: `019-frontend-theme`

**Created**: 2026-07-12

**Status**: Implemented

**Input**: User description: "I want the ability to change the theme of the frontend — especially color
choices." Refined via clarification to: a set of curated palette presets PLUS a custom accent colour, with
the choice persisted per user on the server so it follows them across devices.

## Clarifications

### Session 2026-07-12

- Q: How much colour control — curated presets, full DIY per-token pickers, or a middle ground? → A:
  **Presets + a custom accent.** Ship a few curated, fully-designed palettes and let the user override just
  the accent/gold with a colour picker. This is the best balance of "quick" and "especially colour choices"
  without letting a user assemble an unreadable palette from scratch.
- Q: Where should the theme choice persist — this browser only, or per user across devices? → A: **Per user
  on the server.** The choice follows the user across devices/browsers; it also caches to this browser so it
  applies instantly and still works for an anonymous visitor.
- Q: Which surface applies the theme? → A: The existing `--rq-*` design tokens (feature 016). Every UI
  component already reads `var(--rq-*)`, so a theme is a swap of those custom-property values on the
  document root; no component markup changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pick a palette and see it applied and remembered (Priority: P1)

A user opens **Settings → Theme** and picks one of the curated palettes (Vault Gold, Emerald, Crimson,
Slate). The whole UI re-skins immediately — surfaces, accents, borders, text — because the palette swaps the
`--rq-*` design tokens. The choice is remembered so the next visit (even a full reload) opens in the chosen
palette with no flash of the default.

**Why this priority**: Choosing the look is the headline value and must work on its own, including for an
anonymous visitor (persisted to this browser), before any cross-device sync matters.

**Independent Test**: On Settings, select a non-default palette; confirm the accent token changes and the
card shows as active; reload and confirm the palette persists.

**Acceptance Scenarios**:

1. **Given** the Settings theme panel, **When** a user selects a palette, **Then** the application re-skins
   immediately via the `--rq-*` tokens and the selected palette is marked active.
2. **Given** a selected palette, **When** the user reloads the page, **Then** the palette is re-applied
   before first paint (no flash of the default), read from local persistence.
3. **Given** an anonymous visitor (not signed in), **When** they choose a palette, **Then** it is saved to
   this browser and applied on subsequent loads without requiring a login.

---

### User Story 2 - A signed-in user's theme follows them across devices (Priority: P1)

A signed-in user's theme is saved to their account. When they sign in on another browser or device, the UI
adopts the theme they chose, because the server copy is authoritative on login.

**Why this priority**: The user explicitly asked for a per-user, server-persisted choice; a theme that only
lived in one browser would not meet that need.

**Independent Test**: As a signed-in user, save a theme; read it back from the server for the same
principal; confirm a different user does not see it.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they change their theme, **Then** the choice is persisted to their
   account (in addition to this browser).
2. **Given** a user who saved a theme, **When** they sign in from a fresh browser, **Then** the server copy
   is adopted and applied.
3. **Given** two different signed-in users, **When** each saves a theme, **Then** each reads back only their
   own theme; one user never sees another's choice.
4. **Given** an anonymous request to the preferences endpoint, **When** it is made, **Then** it is rejected
   with the standard unauthorized response and no theme is read or written.

---

### User Story 3 - Override the accent colour; invalid input rejected; nothing else regresses (Priority: P2)

On top of a chosen palette, a user can pick a **custom accent colour**; the accent and text tones update
live and persist. Clearing the custom accent falls back to the palette's own accent. Malformed choices are
rejected, and no existing screen, API, or the Maven/container surfaces are affected.

**Why this priority**: The custom accent is the "especially colour choices" ask, layered on the presets; it
must be validated and must not weaken any existing contract.

**Independent Test**: Set a custom accent hex; confirm the accent token equals it; clear it and confirm the
token reverts to the preset's accent; submit a malformed theme and confirm it is rejected.

**Acceptance Scenarios**:

1. **Given** a chosen palette, **When** the user picks a custom accent colour, **Then** the accent/text
   tokens are derived from that colour and applied live, overriding the palette's accent.
2. **Given** a custom accent is set, **When** the user clears it, **Then** the accent reverts to the
   palette's own accent.
3. **Given** the preferences endpoint, **When** it receives an unknown preset or a non-`#rrggbb` accent,
   **Then** it rejects the request (bad request) and stores nothing.
4. **Given** the existing screens, APIs, and the Maven/container repository surfaces, **When** the theme
   feature is added, **Then** none of their behaviour changes.

### Edge Cases

- **No-flash load**: a saved non-default theme is applied before first paint (an inline boot step reads the
  pre-resolved token map), so the page never flashes the default palette first.
- **Corrupt/absent stored value**: an unreadable local or server theme is treated as "no preference" and the
  default (Vault Gold) is used, rather than surfacing an error.
- **Server unreachable / 401 on load**: a signed-in load that cannot reach the server keeps the locally
  stored theme; the UI is never blocked on the network for its appearance.
- **Anonymous write**: the preferences endpoint denies anonymous reads/writes; the client silently falls
  back to local-only persistence rather than erroring in the UI.
- **Config vs managed users**: the choice is keyed by username so it works for both static-config and
  managed (DB) users, without a foreign key to either.
- **Forward-compatible payload**: a stored theme written by a newer client that fails to parse is discarded
  as "no preference" rather than breaking the load.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The web UI MUST let a user choose the application theme from a set of curated palette presets
  (at minimum: the default plus alternates), applied by swapping the existing `--rq-*` design tokens so the
  whole app re-skins without per-component changes.
- **FR-002**: The UI MUST let a user set a custom **accent colour** (a `#rrggbb` hex) that overrides the
  chosen palette's accent/text tones, and MUST let them clear it to fall back to the palette's accent.
- **FR-003**: A theme change MUST apply **live** (immediately, without a reload) and MUST be re-applied on
  subsequent loads **before first paint** (no flash of the default palette).
- **FR-004**: The chosen theme MUST persist to the local browser always, so it applies on the next load and
  works for an anonymous visitor with no account.
- **FR-005**: For a signed-in user, the theme MUST additionally persist **per user on the server** so it
  follows the user across browsers/devices; on sign-in the server copy MUST be authoritative (adopted and
  applied), and a locally chosen theme MUST seed the server when the account has none.
- **FR-006**: The server MUST expose an endpoint for the **current authenticated user** to read and write
  their own theme; it MUST be scoped to the authenticated principal so a user can only read/write their own
  preference, and MUST reject an anonymous request with the standard unauthorized response.
- **FR-007**: The preferences endpoint MUST be available to **any authenticated principal** (any role, or
  none) — it MUST NOT require the global publish/admin role that the `/api/admin` surface requires.
- **FR-008**: The server MUST validate a submitted theme — the preset MUST be one of the known presets and
  the accent MUST be null or a well-formed `#rrggbb` hex — and MUST reject a malformed theme (bad request)
  without storing it.
- **FR-009**: The per-user theme MUST persist through the existing application-state datastore (embedded
  SQLite by default, external PostgreSQL when configured) via a Liquibase-managed schema change, keyed by
  username so it applies to both config-defined and managed users.
- **FR-010**: Adding the theme feature MUST NOT change or regress any existing screen, the Maven/container
  repository surfaces, existing authentication/authorization, or any other API; it is purely additive.
- **FR-011**: An unreadable or absent stored theme (locally or on the server) MUST degrade gracefully to the
  default palette rather than surfacing an error or blocking the UI.

### Key Entities

- **Theme Choice**: a user's selection — a named **preset** plus an optional custom **accent** colour —
  that resolves to a set of `--rq-*` design-token values applied to the document root.
- **Preset**: one curated, fully-designed palette (a complete map of the `--rq-*` tokens). The default is
  Vault Gold; alternates ship alongside it.
- **User Preference**: the persisted, per-username record of a user's theme choice in the application-state
  datastore.
- **Design Tokens**: the existing `--rq-*` CSS custom properties (feature 016) that every UI component
  reads; the unit a theme swaps.

## Success Criteria *(mandatory)*

- **SC-001**: A user can select any shipped palette on Settings and the whole UI re-skins immediately; the
  choice survives a full reload with no flash of the default.
- **SC-002**: A signed-in user's theme, saved on one browser, is applied when the same user signs in on a
  different browser; a second user never sees the first user's theme.
- **SC-003**: A custom accent colour overrides the palette's accent live and persists; clearing it reverts
  to the palette's accent.
- **SC-004**: The preferences endpoint rejects an anonymous request (unauthorized) and a malformed theme
  (bad request), and accepts a valid theme for any authenticated user regardless of role.
- **SC-005**: No existing screen, API, or the Maven/container repository behaviour changes; the frontend
  type-check and production build succeed and the existing suites still pass.

## Assumptions

- **Token surface**: the app already themes entirely through the `--rq-*` custom properties (feature 016);
  a theme is a swap of those values, so no component markup changes.
- **Persistence model**: the theme choice is small and non-sensitive; storing it as a compact JSON string
  keyed by username in the application-state datastore is sufficient (no dedicated audit or history).
- **Anonymous fallback**: with security disabled or for a not-signed-in visitor, the theme is browser-local
  only; server persistence applies only to authenticated principals.
- **Accent derivation**: a custom accent overrides the accent/text ramp derived from that single colour;
  full per-token DIY customization is intentionally out of scope (rejected in clarification).
- **Out of scope (this feature)**: light/system-preference auto-switching, per-token DIY palettes,
  organization-wide/default themes set by an admin, and theming of anything outside the web UI. These are
  candidate follow-up specs.
- **Testing**: the server round-trip (save/read, per-user isolation, validation, anonymous rejection) is a
  real `@SpringBootTest` HTTP test against the real datastore; the UI behaviour (preset + custom accent
  re-skin, persistence across reload) is a real Playwright round-trip in a real browser.
