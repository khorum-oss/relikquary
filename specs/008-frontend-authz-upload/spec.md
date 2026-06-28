# Feature Specification: Web UI Catch-up — Repo Kinds, Login, Upload & Component Catalog

**Feature Branch**: `008-frontend-authz-upload`

**Created**: 2026-06-28

**Status**: Draft

**Input**: User description: "Bring the SvelteKit UI up to date with the backend: show repository kinds
(hosted/proxy/group), add a login affordance so credentials are reused for browse/download/delete/upload
(making private repos usable and 401/403 handled gracefully), add artifact upload to a hosted repo, and
keep anonymous browsing of open repos working with optional bundling. Plus: add Storybook so the UI's
reusable components are catalogued and reusable."

## Clarifications

### Session 2026-06-28

- Q: How are the logged-in user's credentials held? → A: In session storage — they survive a page
  reload within the tab but clear when the tab/window closes; no cross-restart "remember me".
- Q: What is the upload form? → A: Pick a file and confirm a target path within the current hosted repo
  (prefilled from the folder being browsed); a single PUT of the raw bytes. No guided coordinate form,
  no bulk upload.
- Q: How are the new flows verified? → A: Extend the real-browser Playwright e2e (login → browse a
  private repo → upload → see the file → delete) against a live backend, plus a check that the Storybook
  build renders the components.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See and distinguish repository kinds (Priority: P1)

An operator opens the repositories list and can tell at a glance which repositories are hosted, proxy
(caching an upstream), or group (an aggregate of members). Browsing a proxy shows its locally cached
contents; a group is presented as an aggregate rather than a plain folder tree.

**Why this priority**: The UI currently misrepresents the server — proxy and group repos exist on the
backend (feature 006) but are invisible, so operators can't understand what they're looking at.

**Independent Test**: With hosted, proxy, and group repos configured, the list labels each by kind; the
proxy's browse view shows its cached entries; the group view indicates it aggregates members.

**Acceptance Scenarios**:

1. **Given** repositories of each kind, **When** the list loads, **Then** each row shows its kind
   (hosted / proxy / group).
2. **Given** a proxy repository, **When** it is opened, **Then** its cached contents are browsable.
3. **Given** a group repository, **When** it is opened, **Then** the UI presents it as an aggregate (not
   a misleading empty/normal folder).

---

### User Story 2 - Log in once; credentials reused everywhere (Priority: P1)

A user logs in through the UI with a username and password. From then on, those credentials are sent
with browse, download, delete, and upload requests for the rest of the session, so private repositories
(which refuse anonymous reads) become usable and credentialed actions no longer prompt ad-hoc. The user
can log out. Anonymous users can still browse open repositories with no login.

**Why this priority**: Per-repository authorization (feature 007) made private repos return `401`; the UI
has no way to authenticate reads, so private repos are currently unusable from the UI.

**Independent Test**: Log in as a user permitted to read a private repo and browse it successfully; a
`401` while anonymous prompts a login; a `403` after login shows a clear "forbidden" message; log out
returns to anonymous browsing.

**Acceptance Scenarios**:

1. **Given** a private repository, **When** an anonymous user tries to open it, **Then** the UI prompts
   for login (rather than showing a raw error).
2. **Given** a logged-in user permitted to read it, **When** they open that repository, **Then** its
   contents load.
3. **Given** a logged-in user *not* permitted, **When** they open it, **Then** the UI shows a clear
   "forbidden" message (not a login prompt).
4. **Given** a logged-in user, **When** they log out, **Then** subsequent requests are anonymous again
   and open repositories still work.

---

### User Story 3 - Upload an artifact through the UI (Priority: P2)

An authorized user uploads a file to a path within a hosted repository through the UI. The upload uses
their logged-in credentials; success refreshes the listing to show the new file, and backend rejections
(`401` not logged in, `403` not permitted, `409` immutable release) are surfaced with clear messages.

**Why this priority**: Upload was explicitly deferred in feature 005; it completes the "manage" surface
so operators can publish/correct artifacts without a build tool.

**Independent Test**: Logged in as a permitted publisher, upload a file to a hosted repo path; it appears
in the listing; uploading over an immutable release shows the `409` message; an unauthorized user sees a
clear refusal.

**Acceptance Scenarios**:

1. **Given** a logged-in publisher and a hosted repository, **When** they upload a file to a target path,
   **Then** it is stored and appears in the listing.
2. **Given** the upload targets an existing immutable release, **When** it is attempted, **Then** the UI
   shows the conflict (`409`) clearly and does not claim success.
3. **Given** a user without publish permission, **When** they attempt an upload, **Then** the UI shows a
   clear forbidden / not-authorized message.
4. **Given** a proxy or group repository, **When** the user views it, **Then** upload is not offered (it
   is rejected by the backend with `405`).

---

### User Story 4 - Component catalog for reuse (Priority: P3)

A developer browses a catalog of the UI's reusable components (repository row, breadcrumb, file-details
panel, login form, upload form, error/empty states) in isolation, with representative states, so
components are tracked, visually reviewed, and reused rather than re-implemented.

**Why this priority**: Developer-facing; it does not change end-user behaviour but improves
maintainability and consistency as the UI grows. Lowest priority of this feature.

**Independent Test**: The component catalog builds and renders each catalogued component in isolation
with its key states (e.g. a repository row for each kind; an error state; a loading state).

**Acceptance Scenarios**:

1. **Given** the component catalog, **When** it is started, **Then** it lists the UI's reusable
   components with representative states.
2. **Given** a catalogued component, **When** it is opened, **Then** it renders in isolation without
   needing the full app or a live backend.

### Edge Cases

- **Anonymous open browse unchanged**: no login is required to browse or download from open
  repositories.
- **401 vs 403**: an unauthenticated `401` triggers a login prompt; an authenticated `403` shows a
  forbidden message and does *not* loop the login prompt.
- **Wrong credentials**: a failed login (still `401`) reports invalid credentials without logging the
  user "in".
- **Session boundaries**: credentials apply only for the active session; logging out clears them.
- **Proxy/group upload**: upload is not offered for proxy/group repos; if attempted, the `405` is shown.
- **Download of a private artifact**: a download initiated while logged in carries credentials so it
  succeeds; while anonymous it is refused/prompts login.
- **Large/slow upload**: the UI indicates progress/pending and does not appear hung.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The repositories list MUST display each repository's kind (hosted, proxy, group), using the
  kind already returned by the browse API.
- **FR-002**: Opening a proxy repository MUST browse its locally cached contents; opening a group MUST
  present it as an aggregate of members rather than a plain (and misleading) folder view.
- **FR-003**: The UI MUST provide a login affordance (username + password) and a logout action; a
  logged-in state MUST be visible.
- **FR-004**: While logged in, the UI MUST send the user's credentials with browse, download, delete, and
  upload requests; while logged out it MUST send none (anonymous).
- **FR-005**: A `401` from any read MUST prompt the user to log in; a `403` MUST show a clear "forbidden"
  message and MUST NOT prompt for login.
- **FR-006**: Open repositories MUST remain browsable and downloadable with no login (backward
  compatible).
- **FR-007**: The UI MUST let an authorized user upload a single selected file to a target path within a
  hosted repository (the path prefilled from the folder being browsed and confirmable), using their
  credentials; on success the listing MUST refresh to show the new file.
- **FR-008**: Upload and delete MUST surface backend outcomes clearly: success, `401` (not logged in),
  `403` (not permitted), and `409` (immutable release conflict).
- **FR-009**: Upload MUST NOT be offered for proxy or group repositories.
- **FR-010**: The UI MUST remain a separable static module with optional bundling into the backend,
  unchanged from feature 005 (no required coupling).
- **FR-011**: A component catalog MUST present the UI's reusable components in isolation with
  representative states, runnable without the full app or a live backend.

### Key Entities

- **Session/Credentials**: the current user's login state used to authenticate UI-issued requests; held
  only for the active session and cleared on logout.
- **Repository (view model)**: a repository as shown in the UI, including its name and kind
  (hosted/proxy/group).
- **Upload**: a user-selected file plus the target hosted repository and path it will be written to.
- **Reusable Component**: a UI building block (repository row, breadcrumb, file-details panel, login
  form, upload form, error/empty state) catalogued for reuse.

## Success Criteria *(mandatory)*

- **SC-001**: An operator can distinguish hosted, proxy, and group repositories from the list without
  consulting configuration.
- **SC-002**: A user permitted to read a private repository can browse and download it through the UI
  after logging in; an anonymous attempt prompts login instead of failing opaquely.
- **SC-003**: After logging in once, a user performs browse, download, delete, and upload without being
  re-prompted for credentials each time.
- **SC-004**: An authorized user uploads a file through the UI and sees it appear in the listing; an
  immutable-release conflict (`409`) and a permission denial (`403`) are each shown with a clear message.
- **SC-005**: Anonymous browsing and downloading of open repositories works with no login (no
  regression).
- **SC-006**: The component catalog builds and renders each catalogued component in isolation with its
  representative states.

## Assumptions

- **Credential handling**: credentials are kept in session storage — surviving a page reload within the
  tab but cleared when the tab/window closes (no cross-restart persistence) — and sent via the existing
  HTTP Basic mechanism the backend already accepts; "log in" is a client-side affordance (the backend
  remains stateless).
- **Upload shape**: a single file is uploaded to a user-confirmed target path within a hosted repository
  (prefilled from the folder being browsed); the UI does not synthesize POMs, checksums, or
  `maven-metadata.xml` — it writes the bytes the user provides, like any other `PUT`. Guided coordinate
  forms and bulk/multi-file upload are out of scope.
- **Component catalog tool**: Storybook is used for the component catalog (as requested), kept as a
  dev-only tool that does not ship in the bundled app.
- **Group presentation**: a group is shown as an aggregate label/summary; resolving/merging a group's
  full member contents into one tree is out of scope (the backend returns an empty group listing today).
- **No new backend work**: this feature is frontend-only; it consumes the existing browse API, the Maven
  `PUT` (publish), and the `DELETE` manage endpoint, with their existing auth behaviour.
- **Login validation**: the UI validates credentials by making an authenticated request and treating
  `401` as invalid; there is no dedicated login endpoint.
