# Contract: Web UI Behaviour (feature 008)

The UI is a client of the existing backend surfaces — no new server endpoints. This documents the
client-side behaviour contract.

## Consumed backend surfaces (unchanged)

- `GET /api/repositories` → list incl. `kind` (HOSTED/PROXY/GROUP).
- `GET /api/repositories/{repo}/contents/**` → folder listing (empty for a group).
- `GET /api/repositories/{repo}/file/**` → file details.
- `GET /{repo}/{path}` → download bytes (Maven path).
- `PUT /{repo}/{path}` → upload (publish), gated by publish authorization.
- `DELETE /api/repositories/{repo}/**` → delete, gated by delete authorization.
All gated per feature 007: open reads need no auth; restricted actions need credentials.

## Authentication (client-side)

- Credentials are entered via a login affordance and stored in `sessionStorage` (survive reload, clear
  on tab close). They are sent as `Authorization: Basic …` on every request when present.
- Logout clears them; the UI then makes anonymous requests.
- There is no login endpoint; credentials are validated lazily by retrying the action that prompted
  login (a repeated `401` ⇒ "invalid credentials").

## Status-driven UX

| Backend status | UI behaviour |
|----------------|--------------|
| `200`/`201`/`204` | success; refresh the listing where relevant |
| `401` (unauthenticated) | prompt to log in; retry the pending action on success |
| `403` (authenticated, not permitted) | clear "forbidden" message; do **not** prompt for login |
| `404` | "not found" |
| `405` | (upload) not offered for proxy/group repos |
| `409` | (upload) "immutable release" conflict shown; not reported as success |

## Repository presentation

- Each repository row shows its **kind** (hosted / proxy / group).
- A **proxy** browses its locally cached contents (same contents endpoint).
- A **group** is presented as an aggregate of members (not a misleading empty folder).

## Upload

- Offered only for **hosted** repositories.
- The user picks one file and confirms a target path (prefilled `{currentFolder}/{filename}`); the UI
  `PUT`s the raw bytes with the session credentials. No POM/checksum/metadata synthesis; no bulk upload.

## Backward compatibility & module separation

- Anonymous browsing and downloading of open repositories works with no login (no regression).
- The UI remains a separable static module, optionally bundled into the backend under `/ui`
  (`BASE_PATH=/ui`), unchanged from feature 005. Storybook is dev-only and not bundled.

## Component catalog

- Reusable components are catalogued in Storybook with representative states and render in isolation
  (no full app, no live backend). `npm run build-storybook` produces a static catalog that the frontend
  CI verifies builds.
