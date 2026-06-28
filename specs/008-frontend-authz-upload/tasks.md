---
description: "Task list for the web UI catch-up (kinds, login, upload, component catalog)"
---

# Tasks: Web UI Catch-up — Kinds, Login, Upload & Component Catalog

**Input**: `specs/008-frontend-authz-upload/`. **Tests**: included (Playwright e2e + Storybook build per
the spec/clarifications). All paths under `frontend/` unless noted. Frontend-only — no backend changes.

## Phase 1: Setup

- [ ] T001 Broaden the dev proxy in `frontend/vite.config.ts` to forward any non-SvelteKit path (exclude
  `r/`, `_app/`, `@…`, `src/`, `node_modules/`, `.svelte-kit/`, `favicon`) to `RELIKQUARY_BACKEND`, so
  downloads/uploads to any repo work in dev (keep the existing `/api`, `/releases`, `/snapshots` rules).

## Phase 2: Foundational (blocking prerequisites for US1–US3)

- [ ] T002 Add `frontend/src/lib/auth.svelte.ts`: a Svelte 5 rune session store backed by `sessionStorage`
  (key `relikquary.auth`) with `login(user, pass)`, `logout()`, `current()`, and `authHeader()`; hydrate
  on load, persist on login, clear on logout.
- [ ] T003 Update `frontend/src/lib/api.ts`: throw `ApiError extends Error { status }` on non-OK; attach
  `authHeader()` to every request; add `kind: string` to `RepositorySummary` (alongside the existing
  `type` — comment that `type` is the hosted acceptance policy and `kind` is hosted/proxy/group); make
  `deleteEntry(repo, path)` use the session (drop the explicit auth arg), keeping its existing
  `DELETE /api/repositories/{repo}/{path}` path; add `download(repo, path): Promise<Blob>` (credentialed
  fetch) and `upload(repo, path, file): Promise<number>` (`PUT /{repo}/{path}` raw bytes).

**Checkpoint**: the session store and a credential-aware, typed API client exist; the app still builds.

## Phase 3: User Story 1 — See and distinguish repository kinds (Priority: P1)

**Goal**: Repository kinds are visible; proxy shows cached contents; group shows an aggregate.

**Independent test**: With hosted/proxy/group repos, the list labels each kind; a proxy browses its
cache; a group shows an aggregate (not an empty folder).

- [ ] T004 [P] [US1] Add `frontend/src/lib/components/KindBadge.svelte` and `RepositoryRow.svelte`
  (name + kind badge; `data-testid="repo-row"`/`"repo-kind"`).
- [ ] T005 [US1] Update `frontend/src/routes/+page.svelte` to render the repositories with `RepositoryRow`
  showing each `kind`.
- [ ] T006 [US1] Update `frontend/src/routes/r/[repo]/[...path]/+page.svelte` to look up the current repo's
  `kind` (from `listRepositories`) and present a **group** as an aggregate-of-members summary
  (`EmptyState`/aggregate) instead of an empty table; a **proxy** browses its cached contents normally.
  Extract `Breadcrumbs.svelte`, `FileListing.svelte`/`FileRow.svelte`, and `EmptyState.svelte` used here.

**Checkpoint**: kinds are distinguishable and proxy/group views are sensible.

## Phase 4: User Story 2 — Log in once; credentials reused (Priority: P1)

**Goal**: A user logs in (session-stored credentials reused everywhere); private repos become usable;
`401`→login, `403`→forbidden; anonymous open browse still works.

**Independent test**: Log in as a permitted user and browse a private repo; anonymous private access
prompts login; a `403` shows forbidden; logout returns to anonymous.

- [ ] T007 [P] [US2] Add `frontend/src/lib/components/LoginForm.svelte` (username/password + submit,
  `data-testid="login-form"`, `"login-username"`, `"login-password"`, `"login-submit"`; an
  invalid-credentials state) and `ErrorBanner.svelte` (`data-testid="error"`, distinguishes 403/404/409).
- [ ] T008 [US2] Update `frontend/src/routes/+layout.svelte`: header shows "Log in" when anonymous or the
  username + "Log out" when authenticated (`data-testid="login-button"`/`"logout-button"`/`"current-user"`);
  wire to the auth store + `LoginForm`.
- [ ] T009 [US2] Update the browse page (`r/[repo]/[...path]/+page.svelte`) to handle `ApiError`: `401`
  opens the login prompt and retries the pending action on success; `403` shows a forbidden `ErrorBanner`
  (no login loop); make Download use `download()` (credentialed Blob save) via a `FileDetailsPanel.svelte`
  component.

**Checkpoint**: private repositories are usable after login; open repos still work anonymously.

## Phase 5: User Story 3 — Upload an artifact (Priority: P2)

**Goal**: An authorized user uploads a file to a hosted repo; success refreshes the listing; backend
rejections are surfaced; upload is hidden for proxy/group.

**Independent test**: Logged in, upload a file to a hosted path (appears in listing); a `409` shows the
conflict; an unauthorized user is refused; proxy/group offer no upload.

- [ ] T010 [P] [US3] Add `frontend/src/lib/components/UploadForm.svelte`: file picker + confirmable target
  path (prefilled `{currentPath}/{file.name}`) + submit; idle/uploading/error states
  (`data-testid="upload-form"`, `"upload-file"`, `"upload-path"`, `"upload-submit"`).
- [ ] T011 [US3] Integrate upload into the browse page for **hosted** repos only: call `upload(...)`, on
  `201`/`200` refresh the listing; surface `401` (login), `403` (forbidden), `409` (conflict); do not
  offer upload for proxy/group repos.

**Checkpoint**: artifact upload works end-to-end with clear error handling.

## Phase 6: User Story 4 — Component catalog (Priority: P3)

**Goal**: Reusable components are catalogued in Storybook and render in isolation.

**Independent test**: `npm run build-storybook` succeeds; each component renders with representative
states without the full app or a live backend.

- [ ] T012 [US4] Add Storybook for SvelteKit: install `@storybook/sveltekit` + the Svelte-CSF addon as
  devDependencies, add `storybook` and `build-storybook` scripts to `frontend/package.json`, and create
  `frontend/.storybook/main.ts` + `preview.ts`.
- [ ] T013 [P] [US4] Add `*.stories.svelte` next to each component (`KindBadge`, `RepositoryRow`,
  `Breadcrumbs`, `FileListing`/`FileRow`, `FileDetailsPanel`, `LoginForm`, `UploadForm`, `ErrorBanner`,
  `EmptyState`) with representative states (a row per kind; 403/404/409 banners; empty/group; idle vs
  invalid/uploading).
- [ ] T014 [US4] Add a `build-storybook` step to the frontend job in `.github/workflows/ci.yml`.

**Checkpoint**: the component catalog builds and renders.

## Phase 7: Polish, e2e & verify

- [ ] T015 [P] Update `frontend/scripts/e2e.sh` to run the backend **auth-enabled** with a publisher user,
  an open `releases` repo, and a `private` repo (read/publish/delete restricted to the test user), and to
  seed artifacts with credentials.
- [ ] T016 [P] Revise `frontend/tests/browse.spec.ts` to the anonymous flow: browse and download an open
  repo (remove the delete step, which now needs auth).
- [ ] T017 Add `frontend/tests/authz.spec.ts`: anonymous private browse prompts login; log in → browse the
  private repo → upload a file → see it listed → delete it; assert a `403`/forbidden message for a
  non-permitted action.
- [ ] T018 Run `npm run check`, `npm run build`, `npm run build-storybook`, and `bash scripts/e2e.sh` —
  all green; confirm the bundled `/ui` build is unaffected (`./gradlew :backend:bootJar -PbundleFrontend`
  still produces a working `/ui`, FR-010, since no bundling config is touched); commit & push to
  `claude/spec-008-frontend-authz-upload`.

## Dependencies

Setup (T001) and Foundational (T002–T003) block the stories. US1 (T004–T006) and US2 (T007–T009) are both
P1; US2 depends on the auth store/API from foundational. US3 (T010–T011) depends on foundational upload +
US2's login. US4 (T012–T014) depends on the components extracted across US1–US3. Polish/e2e (T015–T018)
runs last; T017 exercises US1–US3 together.

## Parallel opportunities

- Component-authoring tasks are `[P]` (different files): T004, T007, T010, T013.
- T015 (e2e harness) ∥ T016 (browse spec) — different files; T017 depends on both.

## MVP scope

Setup + Foundational + **US1** (kinds) + **US2** (login/private browse) is the MVP — it makes the UI
truthful about the server and unlocks private repositories. US3 (upload) and US4 (catalog) follow.
