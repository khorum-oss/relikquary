# Implementation Plan: Frontend Theme — User-Selectable Palette & Custom Accent

**Branch**: `019-frontend-theme` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/019-frontend-theme/spec.md`

**Status**: Implemented (this plan documents the as-built design).

## Summary

Let a user change the web UI's theme — a curated **palette preset** (Vault Gold default, plus Emerald,
Crimson, Slate) with an optional **custom accent colour** — applied live by swapping the existing `--rq-*`
design tokens (feature 016) on the document root, so the whole app re-skins with no component markup
changes. The choice persists two ways: to **localStorage** always (instant apply on the next load, and a
working experience for an anonymous visitor) and, for a signed-in user, **per user on the server** so it
follows them across devices (the server copy wins on sign-in). Server persistence adds a small
`user_preference` table (Liquibase changeset `003`, keyed by username so it covers both config and managed
users), a `UserPreferenceService`, and a `GET/PUT /api/me/preferences` controller scoped to the
authenticated principal and authorized for **any authenticated user** (no publish/admin role), with the
submitted theme validated (known preset + `#rrggbb` accent) before storage. The frontend adds a theme
store, the presets/derivation module, the Settings theme panel, and an inline `app.html` boot step that
applies the pre-resolved token map before first paint to avoid a flash.

## Technical Context

**Language/Version**: Backend — Kotlin on the JDK 21 toolchain (unchanged). Frontend — SvelteKit 5 (Svelte
runes) + TypeScript, built with Vite (unchanged).

**Primary Dependencies**: Backend — Spring Boot Web + Security, Spring Data JPA + Liquibase (existing
persistence, feature 016), Jackson Kotlin module (existing — the theme is (de)serialized as a Kotlin data
class). Frontend — the existing SvelteKit app; no new dependency. **No new production dependency added.**

**Storage**: The existing application-state datastore (embedded SQLite by default, external PostgreSQL when
configured) via a new Liquibase changeset — a single `user_preference` table keyed by username. Artifact
storage is untouched (the theme is UI state, not artifact bytes).

**Testing**: `@SpringBootTest` HTTP round-trip for the endpoint (save/read, per-user isolation, validation,
anonymous rejection) against the real datastore (Principle II for a change touching auth + persistence);
plus a real Playwright round-trip in a real Chromium for the UI (preset + custom accent re-skin, persistence
across reload, accent reset).

**Target Platform**: The existing Spring Boot backend + the SvelteKit static UI served at `/ui`.

**Project Type**: Additive change across the existing `backend` and `frontend` modules — no new module.

**Performance Goals**: N/A. A theme apply is a handful of `style.setProperty` calls; persistence is one
small row / one small localStorage entry.

**Constraints**: Purely additive — no existing screen, API, config key, or the Maven/container surfaces
change (Principle I; MINOR bump). The endpoint is scoped to the authenticated principal (a user can only
read/write their own theme) and open to any authenticated role (not the `/api/admin` publish gate). A
malformed theme is rejected before storage. The stored bytes of artifacts are irrelevant here and untouched
(Principle IV).

**Scale/Scope**: One new backend package (`preferences/`), one entity + repository, one controller, one
Liquibase changeset, one small authorization rule; one frontend theme store + presets module, the Settings
panel, the `app.html` boot step, and two API calls. `VERSION` 1.1.0 → 1.2.0 (additive capability).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). No change to the Maven/container
  repository layout, resolution/publish protocol, or any existing configuration key. The new
  `/api/me/preferences` endpoint is a NEW, additive HTTP surface; the UI change is additive. Adding a
  capability (not breaking one) implies a **MINOR** `VERSION` bump (1.1.0 → 1.2.0).
- **II. Test-First & Integration-Verified Discipline** — PASS (adapted). The change touches authorization
  and persistence, so it ships a real `@SpringBootTest` HTTP round-trip against the real datastore
  (save/read, per-user isolation, validation, anonymous 401), and the UI behaviour is proven by a real
  Playwright round-trip in a real browser. No mocked store, no mocked client.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin satisfies detekt (zero violations); the
  frontend passes `svelte-check` and the production build. Nothing exempted; no gate weakened. No test-only
  or production dependency added, so `gradle/verification-metadata.xml` needs no change.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS (not implicated). This feature stores small UI
  state (a theme choice), not third-party artifacts; no stored artifact bytes, checksums, or signatures are
  touched. No secret is introduced or committed.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/019-frontend-theme/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — entity + storage-key scheme + token model
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/
│   └── preferences-api.md          # the /api/me/preferences contract
└── checklists/
    └── requirements.md  # spec quality checklist
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── preferences/
│   ├── ThemePreference.kt                 # theme value type + validation (preset allow-list, #rrggbb)
│   └── UserPreferenceService.kt           # read/write the per-user theme (Kotlin-aware JSON)
├── persistence/
│   ├── UserPreference.kt                  # @Entity user_preference (username PK, theme json, updated_at)
│   └── UserPreferenceRepository.kt        # Spring Data JPA repository
├── protocol/PreferenceController.kt       # @RequestMapping("/api/me") — GET/PUT /preferences
├── security/RepositoryAuthorizationManager.kt  # + `api/me` → any authenticated principal
└── security/RepositoryAuthorizer.kt       # + permitsAuthenticated(...)

backend/src/main/resources/db/changelog/
├── 003-user-preference.xml               # user_preference table
└── db.changelog-master.yaml              # include the new changeset

backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
└── PreferenceApiTest.kt                  # save/read round-trip, per-user isolation, 400/401

frontend/src/
├── lib/theme/presets.ts                  # preset palettes + single-hex accent-ramp derivation
├── lib/theme/theme.svelte.ts             # runes store: apply --rq-* tokens; localStorage + server sync
├── lib/api.ts                            # + getMyPreferences / saveMyPreferences
├── routes/settings/+page.svelte          # the theme panel (palette picker + accent input)
├── routes/+layout.svelte                 # apply on load; sync on user change
└── app.html                              # inline boot step: apply saved tokens before first paint

frontend/tests/
└── theme.spec.ts                         # Playwright: preset + accent re-skin, persist across reload

VERSION                                   # 1.1.0 → 1.2.0 (additive capability)
```

**Structure Decision**: Additive change to the existing `backend` and `frontend` modules — no new Gradle or
Node module. Backend theme logic lives in a new `preferences/` package with the entity in the existing
`persistence/` package and the controller alongside the other `/api` controllers, plus two surgical edits to
the authorization layer to recognize `/api/me`. Frontend theme logic lives under `lib/theme/`, mirroring the
existing token surface (feature 016).

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds one additive endpoint and one additive table, keeps every
existing contract untouched (default-safe: no theme ⇒ the current default look), touches no stored artifact
bytes, and proves itself with a real HTTP round-trip against the real datastore plus a real browser
round-trip.
