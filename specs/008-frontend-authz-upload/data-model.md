# Data Model: Web UI Catch-up

Client-side view models and modules (no backend/database changes). Builds on the feature 005 API client.

## Session (new) — `src/lib/auth.svelte.ts`

The current login state, reactive via Svelte 5 `$state`, persisted in `sessionStorage`.

| Field | Type | Notes |
|-------|------|-------|
| `username` | `string \| null` | The logged-in user, or null when anonymous. |
| `header` | `string \| null` | The `Basic …` Authorization value, or null. |

Operations:
- `login(username, password)` — set state + persist to `sessionStorage`.
- `logout()` — clear state + remove from `sessionStorage`.
- `current()` — the session (or anonymous).
- `authHeader()` — `header` or null; used by the API client.

Lifecycle: hydrated from `sessionStorage` on module load; survives reload within the tab; cleared on tab
close (sessionStorage semantics) or `logout()`.

## ApiError (new) — `src/lib/api.ts`

`class ApiError extends Error { status: number }` thrown on non-OK responses so callers branch on
`status` (`401` → login, `403` → forbidden, `404` → not found, `405` → method not allowed, `409` →
conflict).

## RepositorySummary (extended) — `src/lib/api.ts`

| Field | Type | Notes |
|-------|------|-------|
| `name` | `string` | unchanged |
| `type` | `string` | unchanged (hosted acceptance type) |
| `kind` | `string` | NEW: `HOSTED` / `PROXY` / `GROUP` (already returned by the API). |

## Upload (new, transient) — browse page state

| Field | Type | Notes |
|-------|------|-------|
| `file` | `File` | The user-selected file. |
| `targetPath` | `string` | Confirmable, prefilled `{currentPath}/{file.name}`. |
| `repo` | `string` | The current hosted repository. |

`upload(repo, targetPath, file)` → `PUT /{repo}/{targetPath}` with the file bytes + session auth;
returns the HTTP status. Offered only when the current repo's `kind === HOSTED`.

## API client functions (changed/new) — `src/lib/api.ts`

- All existing fns (`listRepositories`, `listContents`, `fileDetails`, `deleteEntry`) attach
  `authHeader()` automatically and throw `ApiError` on failure.
- `deleteEntry(repo, path)` — no longer takes an explicit auth arg (uses the session); returns on `204`.
- `download(repo, path): Promise<Blob>` — NEW: credentialed `fetch` of `/{repo}/{path}`, returns the
  bytes as a Blob (so private downloads carry credentials).
- `upload(repo, path, file): Promise<number>` — NEW (above).
- `basicAuth(user, pass)` — retained (used internally by the auth module).

## Reusable components (new) — `src/lib/components/`

| Component | Renders | Key states (for stories) |
|-----------|---------|--------------------------|
| `KindBadge` | a repo kind label | hosted / proxy / group |
| `RepositoryRow` | one repo in the list | each kind |
| `Breadcrumbs` | path navigation | root / nested |
| `FileListing` / `FileRow` | the contents table | files, folders, empty |
| `FileDetailsPanel` | file size/checksums/download | with/without checksums |
| `LoginForm` | username/password + submit | idle / invalid-credentials |
| `UploadForm` | file picker + target path | idle / uploading / error |
| `ErrorBanner` | an error/forbidden message | 401 / 403 / 404 / 409 |
| `EmptyState` | empty listing / group aggregate | empty / group |

## UI status → behaviour mapping

| Response | UI behaviour |
|----------|--------------|
| `200`/`201`/`204` | success (refresh listing where relevant) |
| `401` | open login prompt; on retry success, continue |
| `403` | clear "forbidden" message; no login loop |
| `404` | "not found" |
| `405` | upload not available (proxy/group) |
| `409` | "immutable release conflict" on upload |
