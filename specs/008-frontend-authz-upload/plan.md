# Implementation Plan: Web UI Catch-up — Kinds, Login, Upload & Component Catalog

**Branch**: `claude/spec-008-frontend-authz-upload` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-frontend-authz-upload/spec.md`

## Summary

A frontend-only feature bringing the SvelteKit UI up to date with the backend: show repository kinds
(hosted/proxy/group), add a session-storage login affordance whose credentials are attached to every
request (making private repos usable and 401/403 handled gracefully), add single-file artifact upload to
hosted repos, and add a Storybook component catalog over extracted reusable components. No backend
changes; the UI keeps consuming the existing browse API, the Maven `PUT`/`GET`, and the manage `DELETE`.

## Technical Context

**Language/Version**: TypeScript 5.7, Svelte 5.16 (runes), SvelteKit 2.15, Vite 6, adapter-static (SPA).

**Primary Dependencies**: existing (SvelteKit/Svelte/Vite/Playwright); **new npm devDependencies**:
Storybook for SvelteKit (`@storybook/sveltekit` + Svelte-CSF addon). No Gradle dependency changes.

**Storage**: client-side `sessionStorage` for credentials (survive reload, clear on tab close); no
backend storage change.

**Testing**: Playwright real-browser e2e against an auth-enabled backend (anonymous browse + login →
private browse → upload → delete); `svelte-check` types; `build-storybook` render check.

**Target Platform**: browser SPA, served standalone (`vite dev` / static) or bundled under `/ui`.

**Project Type**: web — `frontend/` module only; `backend/` untouched.

**Performance Goals**: standard SPA responsiveness; upload shows progress/pending state so it never
appears hung.

**Constraints**: UI stays a separable, optionally-bundled module (FR-010); HTTP Basic + 401/403
semantics preserved; **no Gradle deps** → `gradle/verification-metadata.xml` untouched.

**Scale/Scope**: one module; an auth store + typed API errors, repo-kind display, upload flow,
credentialed download, ~9 extracted components with stories, Storybook config, and the extended e2e.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. The UI is a *client*; the Maven/Gradle wire
  protocol and HTTP API are unchanged. HTTP Basic + 401 (challenge) / 403 semantics are consumed, not
  altered. No configuration contract changes.
- **II. Test-First & Integration-Verified** — PASS. The authentication/upload behaviour is proven by a
  real-browser Playwright e2e against a real backend (a real round-trip through publish/resolve/delete),
  not mocks; Storybook build verifies component rendering.
- **III. Quality Gates** — PASS. Backend detekt/Kover unaffected. Frontend gates (`svelte-check`,
  `build`, `build-storybook`, e2e) run in the existing frontend CI job; no gate weakened.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. New deps are npm devDependencies only
  (Storybook), so `gradle/verification-metadata.xml` is untouched. The UI uploads raw bytes via `PUT`
  (no mutation/re-checksum). Credentials are user-entered at runtime and held only in the browser
  session — never committed.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/008-frontend-authz-upload/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/ui-behavior.md
├── checklists/requirements.md
└── tasks.md   # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
frontend/
├── package.json                     # MODIFIED: storybook devDeps + scripts (storybook, build-storybook)
├── vite.config.ts                   # MODIFIED: dev proxy forwards arbitrary repo paths to the backend
├── .storybook/                      # NEW: main.ts + preview.ts (Svelte/SvelteKit framework)
├── src/
│   ├── lib/
│   │   ├── api.ts                   # MODIFIED: attach session auth; ApiError; kind; download(); upload()
│   │   ├── auth.svelte.ts           # NEW: session store (sessionStorage) + authHeader()
│   │   └── components/              # NEW: KindBadge, RepositoryRow, Breadcrumbs, FileListing/FileRow,
│   │       │                        #      FileDetailsPanel, LoginForm, UploadForm, ErrorBanner, EmptyState
│   │       └── *.stories.svelte     # NEW: a story per component with representative states
│   └── routes/
│       ├── +layout.svelte           # MODIFIED: login/logout affordance + logged-in state in the header
│       ├── +page.svelte             # MODIFIED: repo list uses RepositoryRow + KindBadge (kind)
│       └── r/[repo]/[...path]/+page.svelte  # MODIFIED: compose components; group/proxy presentation;
│                                            # login-on-401 / forbidden-on-403; upload; credentialed download
├── tests/
│   ├── browse.spec.ts               # MODIFIED: anonymous browse + download of an open repo (no delete)
│   └── authz.spec.ts                # NEW: login → private browse → upload → see file → delete
└── scripts/e2e.sh                   # MODIFIED: run backend auth-enabled + private repo + publisher; seed with creds

.github/workflows/ci.yml             # MODIFIED: frontend job also runs build-storybook
```

**Structure Decision**: Extract inline page markup into `src/lib/components/` so the same pieces drive
both the app and Storybook (the catalog is only meaningful over real components). The auth store is a
Svelte 5 rune module that `api.ts` reads, centralising credential injection. `backend/` is untouched.

## Implementation phases (high level)

1. **Auth foundation** — `auth.svelte.ts` (session store) + `api.ts` changes (attach auth, `ApiError`,
   `kind`, `download()`, `upload()`); header login/logout affordance (D1–D3, D7).
2. **Kinds & presentation** — repo-list kind badges; proxy cached browse; group aggregate view (D4).
3. **Upload** — UploadForm + browse-page integration (hosted only); 401/403/409/405 handling (D5, D2).
4. **Component catalog** — extract components; add Storybook config + stories; `build-storybook` script;
   CI step (D8).
5. **Dev proxy & e2e** — broaden the vite proxy (D6); auth-enabled `e2e.sh`; revise `browse.spec`, add
   `authz.spec` (D9); `npm run check`/`build`/`build-storybook`/e2e green; commit & push.

## Complexity Tracking

No constitution violations; section intentionally empty.
