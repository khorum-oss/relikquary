---
description: "Task list for the frontend theme (palette + custom accent) feature"
---

# Tasks: Frontend Theme — User-Selectable Palette & Custom Accent

**Input**: Design documents from `specs/019-frontend-theme/`

**Prerequisites**: spec.md, plan.md, research.md, data-model.md, contracts/preferences-api.md

**Tests**: REQUIRED. Principle II applies — this change touches authorization and persistence, so it ships a
real `@SpringBootTest` HTTP round-trip against the real datastore, and the UI behaviour is proven by a real
Playwright round-trip in a real browser.

**Status**: Implemented as-built on branch `claude/speckit-remaining-tasks-ky789o`. Every task below is
complete and verified in the execution environment (system Gradle 8.14.3 / Node): backend `detekt` clean and
`PreferenceApiTest` green (6/6) with no regression in the auth suites; frontend `svelte-check` clean,
production build succeeds, and `theme.spec.ts` passes in a real Chromium. The pinned Gradle 9.4.1 toolchain
and the Docker-gated suites remain CI's oracle (same environment note as feature 018).

Abbrev: `.../` = `backend/src/main/kotlin/org/khorum/oss/relikquary/`; `test/.../` =
`backend/src/test/kotlin/org/khorum/oss/relikquary/`; `fe/` = `frontend/src/`.

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` from `1.1.0` to `1.2.0` (additive capability — Principle I MINOR bump).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The persisted per-user store, the validated theme value type, and the authorization rule shared
by the endpoint and both persistence stories.

- [x] T002 Liquibase changeset `backend/src/main/resources/db/changelog/003-user-preference.xml` — table
  `user_preference` (`username` PK, `theme` VARCHAR(2000), `updated_at` TIMESTAMP; `modifySql` sqlite
  timestamp), registered in `db/changelog/db.changelog-master.yaml`.
- [x] T003 [P] `UserPreference` entity + `UserPreferenceRepository` (JavaBean-style, PK `username`) in
  `.../persistence/`.
- [x] T004 [P] `ThemePreference` value type + `ThemePreference.of(...)` validation (preset allow-list
  `{vault-gold, emerald, crimson, slate}`; accent null or `#rrggbb`; `InvalidThemeException` on a bad field)
  in `.../preferences/ThemePreference.kt`.
- [x] T005 `UserPreferenceService` — read/write the per-user theme as compact JSON, Kotlin-aware
  `ObjectMapper` so the data class round-trips; an unreadable stored value is discarded as "no preference";
  in `.../preferences/UserPreferenceService.kt` (depends T003, T004).
- [x] T006 Authorize the self-service surface: add `RepositoryAuthorizer.permitsAuthenticated(...)` (any
  authenticated principal, anonymous denied) and an `api/me` branch in
  `RepositoryAuthorizationManager.authorize(...)`; in `.../security/`.

---

## Phase 3: User Story 2 — Per-user, server-persisted theme (Priority: P1)

**Goal**: A signed-in user reads and writes their own theme over the wire; it is per-principal and validated.

### Tests for User Story 2 (write first, must fail) ⚠️

- [x] T007 [US2] `PreferenceApiTest` — real `@SpringBootTest` HTTP round-trip: anonymous → 401; a fresh user
  reads `null`; save → read-back round-trips; a preset with no accent stores null accent; themes are isolated
  per user; a malformed theme → 400; in `test/.../integration/PreferenceApiTest.kt`.

### Implementation for User Story 2

- [x] T008 [US2] `PreferenceController` `@RequestMapping("/api/me")` — `GET`/`PUT /preferences`, username
  from the security context (401 when anonymous), `@ExceptionHandler(InvalidThemeException)` → 400; in
  `.../protocol/PreferenceController.kt` (depends T005, T006).

**Checkpoint**: `GET/PUT /api/me/preferences` round-trips per user with validation — US2 is shippable.

---

## Phase 4: User Story 1 — Pick a palette; live apply + local persistence (Priority: P1)

**Goal**: Choosing a palette re-skins the app live via `--rq-*` tokens and persists (localStorage), with no
flash on reload — working even for an anonymous visitor.

- [x] T009 [P] [US1] `fe/lib/theme/presets.ts` — four full preset palettes (Vault Gold default, Emerald,
  Crimson, Slate), `isPreset`/`isHexColor`, single-hex `accentOverrides`, and `resolveVars(choice)`.
- [x] T010 [US1] `fe/lib/theme/theme.svelte.ts` — runes store: restore/sanitize the choice, `applyTheme()`
  (set `--rq-*` on the root), `setTheme()` (apply + localStorage `relikquary.theme` and the pre-resolved
  `relikquary.theme.vars`), and `syncTheme()` (server reconcile) (depends T009, T011).
- [x] T011 [US1] `fe/lib/api.ts` — `getMyPreferences()` / `saveMyPreferences()` against
  `/api/me/preferences` (401 → null, so the client falls back to local).
- [x] T012 [US1] `fe/app.html` — inline boot step: apply the cached `relikquary.theme.vars` before first
  paint (no-flash); `fe/routes/+layout.svelte` — `applyTheme()` on mount and `syncTheme()` on user change.

**Checkpoint**: A palette selection re-skins live and survives a reload with no flash — US1 shippable.

---

## Phase 5: User Story 3 — Custom accent + the Settings UI (Priority: P2)

**Goal**: Override the accent with a custom colour (live + persisted), reset to the preset accent, all from
the Settings panel; nothing else regresses.

- [x] T013 [US3] `fe/routes/settings/+page.svelte` — replace the placeholder with the theme panel: palette
  picker (active state), accent colour `<input type="color">` with live value + "Use preset default" reset,
  and a sign-in-state hint (depends T009, T010).
- [x] T014 [P] [US3] `fe/tests/theme.spec.ts` (Playwright) — preset switch + custom accent re-skin the app
  (assert `--rq-gold`), persist across reload, and reset falls back to the preset accent.

**Checkpoint**: Custom accent works end-to-end in a real browser; existing screens/APIs unchanged.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T015 Update the managed Spec Kit pointer in `CLAUDE.md` to this feature's plan.
- [x] T016 Verify gates in-env: `:backend:detekt` zero violations; `PreferenceApiTest` green; affected auth
  suites (authorizer, per-repo publish, tokens, operational) unchanged; frontend `svelte-check` clean and
  `npm run build` succeeds; `theme.spec.ts` green in a real Chromium. (Full `./gradlew build` incl. Kover and
  the Docker-gated suites, under the pinned Gradle 9.4.1, remains CI's oracle.)
- [x] T017 Confirm no dependency change: no new production or test-only dependency was added, so
  `gradle/libs.versions.toml` / `gradle/verification-metadata.xml` need no edit.

---

## Dependencies & Execution Order

- **Setup (T001)** → no deps.
- **Foundational (T002–T006)** → depends on Setup; blocks the stories.
- **US2 (T007–T008)** → depends on Foundational (persistence + authz).
- **US1 (T009–T012)** → depends on Foundational; the store's server sync (T010/T011) consumes the US2
  endpoint but degrades to local-only when it is absent, so US1 is independently usable.
- **US3 (T013–T014)** → depends on US1 (the store + presets) and exercises the endpoint from US2.
- **Polish (T015–T017)** → after the stories.

## Notes

- Purely additive: no existing screen, API, config key, or the Maven/container surfaces change (FR-010).
- The theme is applied by swapping the existing `--rq-*` design tokens (feature 016); no component markup
  changed.
- Total: 17 tasks — Setup 1, Foundational 5, US2 2, US1 4, US3 2, Polish 3.
