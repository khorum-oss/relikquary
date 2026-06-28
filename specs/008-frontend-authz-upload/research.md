# Research: Web UI Catch-up — Kinds, Login, Upload & Component Catalog

Phase 0 decisions for feature 008. Credential persistence, upload shape, and the test strategy were
settled in the spec's `## Clarifications` (2026-06-28); this records the technical choices.

## D1 — Auth/session store

**Decision**: A Svelte 5 rune module `src/lib/auth.svelte.ts` holding the current session as `$state`
(`{ username, header }` where `header` is the `Basic …` value), hydrated from and persisted to
`sessionStorage` (key `relikquary.auth`). Exposes `login(user, pass)`, `logout()`, `current()` and
`authHeader()`. The API client imports `authHeader()` and attaches `Authorization` to every request when
present.

**Rationale**: Clarified credential model (session storage — survives reload, clears on tab close). A
rune module is the idiomatic Svelte 5 reactive singleton; centralising header injection in `api.ts`
means every call (browse/download/delete/upload) is credentialed automatically without threading a
parameter through each component (replacing today's ad-hoc per-delete prompt).

**Alternatives**: classic `writable` store (works, but `.svelte.ts` runes are the project's idiom);
`localStorage` (rejected per clarification — weaker posture); passing the header into each API call
(verbose, error-prone).

## D2 — Typed errors & 401 vs 403 handling

**Decision**: `api.ts` throws `class ApiError extends Error { status: number }` on non-OK responses (in
place of `Error('404')`). Callers branch on `status`: `401` → open the login prompt (with the pending
action to retry on success); `403` → show a clear "forbidden" message; `404` → "not found"; `409` →
conflict (used by upload). A page-level `requireLogin` state drives the login modal.

**Rationale**: FR-005/FR-008 need precise status-driven UX. A typed error keeps that logic in the
components without string-parsing.

## D3 — Login affordance & validation

**Decision**: The header (`+layout.svelte`) shows either a "Log in" button (anonymous) or the username +
"Log out" (authenticated). Login is a small form (modal/inline) capturing username + password. There is
no login endpoint, so validation is contextual: when login is triggered by a `401` on an action, the
form stores the credentials and **retries that action**; if it still `401`s, the credentials are wrong →
show "invalid credentials" and clear. A proactive "Log in" from the header validates by re-fetching the
current view.

**Rationale**: The backend is stateless HTTP Basic with no login route (spec assumption). Lazy,
action-tied validation satisfies the "wrong credentials reported without logging in" edge case without a
new endpoint.

## D4 — Repository kind display & proxy/group presentation

**Decision**: Extend the client `RepositorySummary` with `kind` (already returned by `GET
/api/repositories`). The repositories list renders a kind badge (hosted/proxy/group). The browse page
looks up the current repo's kind (from the repositories list) to decide presentation: a **proxy**
browses its cached contents via the existing contents endpoint (its storage prefix); a **group** shows
an "aggregate of members" summary instead of the (empty) folder table the backend returns for groups.

**Rationale**: FR-001/FR-002. `kind` already exists server-side (006/007); this is purely a client
display change. No backend work.

## D5 — Upload

**Decision**: Add `upload(repo, path, file): Promise<number>` to `api.ts` doing `PUT /{repo}/{path}`
with the file's bytes as the body (and the session `Authorization`). The browse page offers an
**Upload** affordance only for hosted repos: pick a file, confirm a target path prefilled from the
current folder (`{currentPath}/{file.name}`), submit. On `201`/`200` refresh the listing; surface
`401`/`403`/`409`/`405` per D2.

**Rationale**: Clarified upload shape (single file + confirmable prefilled path; raw `PUT`). The backend
already accepts this on the Maven path, gated by publish authorization — no backend change.

## D6 — Dev proxy for arbitrary repo paths (downloads & uploads)

**Decision**: Broaden `vite.config.ts`'s dev proxy. Today it forwards `/api`, `/releases`, `/snapshots`.
Add a regex rule forwarding any first path segment that is **not** a SvelteKit-reserved prefix
(`r/`, `_app/`, `@…`, `src/`, `node_modules/`, `.svelte-kit/`, `favicon`) to the backend — so
downloads/uploads to any repo (`/private-libs/…`, `/maven-central/…`) work in dev. In the bundled
(production) build the UI is same-origin with the backend, so no proxy is involved.

**Rationale**: The app's own client routes all live under `/` and `/r/…` with assets under `/_app` and
`/@…`; everything else with a path is a Maven request. This keeps dev working for private/proxy repos
and uploads. (The implementer validates the exclusion list against the Playwright run.)

## D7 — Credentialed downloads

**Decision**: The Download action performs a credentialed `fetch` of the artifact (with the session
`Authorization`) and saves the response as a Blob, rather than a bare `<a download>` link. This lets a
logged-in user download from a **private** repo (a plain link would omit the auth header and `401`).

**Rationale**: Edge case "download of a private artifact while logged in carries credentials". For open
repos it works the same anonymously.

## D8 — Component catalog (Storybook)

**Decision**: Add Storybook for Svelte/SvelteKit (`@storybook/sveltekit` + Svelte-CSF addon) with
`.storybook/` config and `*.stories.svelte` files. Extract the UI's reusable pieces into
`src/lib/components/` — `KindBadge`, `RepositoryRow`, `Breadcrumbs`, `FileListing`/`FileRow`,
`FileDetailsPanel`, `LoginForm`, `UploadForm`, `ErrorBanner`, `EmptyState` — and give each a story with
representative states (e.g. a row per kind; error/loading/empty). Scripts: `storybook` (dev),
`build-storybook` (static). The frontend CI job runs `build-storybook` to verify it renders.

**Rationale**: FR-011 / the user's request. Extraction into components is a prerequisite for meaningful
stories and improves reuse. These are **npm devDependencies only** — no Gradle dependencies, so
`gradle/verification-metadata.xml` is untouched (Principle IV).

## D9 — e2e under authentication

**Decision**: Update `frontend/scripts/e2e.sh` to run the backend with **auth enabled**, a publisher
user, an open `releases` repo, and a `private` repo (read/publish/delete restricted to the test user);
seed with credentials. Revise `tests/browse.spec.ts` to the anonymous flow (browse + download an open
repo, no delete) and add `tests/authz.spec.ts`: log in → browse the private repo → upload a file → see
it listed → delete it; plus assertions that an anonymous private browse prompts login.

**Rationale**: The new behaviour is authentication-centric, so the e2e backend must enforce auth.
Splitting anonymous vs authenticated flows keeps each spec focused and matches the real surfaces.

## D10 — No backend changes; dependencies

**Decision**: Frontend-only feature. New npm devDependencies (Storybook) update
`frontend/package-lock.json`; **no Gradle dependency changes**, so `verification-metadata.xml` is
untouched. The frontend stays a separable, optionally-bundled module (FR-010) — Storybook is dev-only
and not part of the bundled `BASE_PATH=/ui` build.

**Rationale**: Keeps the constitution's supply-chain gate (Gradle) unaffected and preserves module
separation.
