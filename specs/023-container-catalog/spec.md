# Feature Specification: Container-Aware Catalog & Dashboard

**Feature Branch**: `023-container-catalog`

**Created**: 2026-07-18

**Status**: Draft

**Input**: User description: "Container-aware catalog and dashboard. The web UI's default landing view is a
searchable cross-repo catalog (feature 016) that today lists only Maven group:artifact coordinates —
container images (018/020/022) never appear in it, and the dashboard's summary figures don't acknowledge
container content. Extend the cross-repo catalog so container images are first-class, searchable entries
alongside Maven artifacts: each container entry shows its repository, image name, latest tag, tag count, and
total size, and is visually distinguished by a type indicator. Selecting a container catalog entry takes the
user to that image's existing tag view (/c/{repo}/{image}); a Maven entry keeps its current behavior.
Catalog entries continue to respect per-repository READ authorization. Also surface container content on the
dashboard: add an 'images' figure (the number of distinct container images across the readable repositories)
alongside the existing repository/artifact/storage figures. Keep it consistent with the vault-themed
frontend, and keep the change additive. Out of scope: enumerating a proxy repository's live upstream tags in
the catalog (only what it has cached is shown), changing how Maven coordinates are cataloged, and full-text
search beyond the existing name filter."

## Clarifications

### Session 2026-07-18

- Q: What does a container catalog entry show, and how is it distinguished from a Maven one? → A: Each
  container entry carries its repository, image name, latest tag, tag count, and total size, and a **type
  indicator** (container vs. Maven artifact) so the two are visually distinct in the same searchable list.
- Q: What happens when a user selects a container catalog entry? → A: It navigates to that image's existing
  tag view (`/c/{repo}/{image}`, features 018/020/022). Maven entries keep their current behavior.
- Q: Which container images appear, especially for a proxy? → A: Only images the repository authoritatively
  stores. A **hosted** repo contributes its tagged images; a **proxy** repo contributes only what it has
  cached (it has no authoritative upstream image/tag list). No live upstream enumeration.
- Q: How does the catalog respect privacy? → A: Exactly as today — an entry (Maven or container) appears only
  for repositories the requesting user may READ (feature 007). A user never sees images from a repo they
  cannot read.
- Q: What is the dashboard "images" figure? → A: The number of **distinct container images** across the
  repositories, shown alongside the existing repository, artifact, and storage figures.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Discover container images in the catalog (Priority: P1)

A user opens the web UI's default catalog view and searches it. Container images appear as first-class,
searchable rows alongside Maven artifacts — each container row labeled with a type indicator and showing its
repository, image name, latest tag, tag count, and total size. The user types part of an image name into the
existing filter and the matching container images narrow just like Maven rows. Selecting a container row
opens that image's tag view; selecting a Maven row behaves as before.

**Why this priority**: The catalog is the app's front door for discovery. Today it silently omits container
images, so a user who pushed images cannot find them from the landing view — they must already know the repo
and deep-link. Making images first-class in the catalog is the core value and is independently useful even if
the dashboard figure is never added.

**Independent Test**: With a hosted repo holding a tagged container image and a Maven repo holding an
artifact, load the catalog and confirm both appear, are type-distinguished, both respond to the name filter,
and the container row links to `/c/{repo}/{image}` while the Maven row keeps its behavior.

**Acceptance Scenarios**:

1. **Given** a hosted container repo with a tagged image and a Maven repo with an artifact, **When** the user
   loads the catalog, **Then** both appear as rows, each marked with its type (container / Maven).
2. **Given** the catalog is shown, **When** the user filters by part of an image name, **Then** the matching
   container image rows are shown and non-matching rows are hidden, consistent with Maven filtering.
3. **Given** a container catalog row, **When** the user selects it, **Then** they are taken to that image's
   tag view (`/c/{repo}/{image}`).
4. **Given** a container image with multiple tags, **When** it is shown in the catalog, **Then** its latest
   tag, tag count, and total size are displayed.

---

### User Story 2 - Privacy and repository-kind correctness (Priority: P2)

The catalog shows only what a user is allowed to see and only what a repository authoritatively holds. A user
without read access to a repository never sees its images in the catalog; a proxy repository contributes only
the images it has actually cached, never a live enumeration of its upstream.

**Why this priority**: Discovery must not leak private content or misrepresent a proxy as hosting images it
has never fetched. This guardrail builds directly on User Story 1 and preserves the catalog's existing
privacy contract.

**Independent Test**: As a user without read access to a private container repo, load the catalog and confirm
its images are absent; as a permitted user, confirm they appear. Confirm a proxy repo lists only cached
images, not its full upstream.

**Acceptance Scenarios**:

1. **Given** a private container repo the user cannot read, **When** the user loads the catalog, **Then** none
   of that repo's images appear.
2. **Given** the same private repo and a user who can read it, **When** that user loads the catalog, **Then**
   its images appear.
3. **Given** a proxy container repo, **When** the catalog is shown, **Then** only images the proxy has cached
   appear, and no live upstream enumeration is performed.

---

### User Story 3 - Dashboard reflects container content (Priority: P3)

The dashboard overview shows an "images" figure — the number of distinct container images across the
repositories — beside the existing repository, artifact, and storage figures, so at a glance the overview
acknowledges the server hosts container images.

**Why this priority**: A nice completion of the discovery story, but secondary to making images findable in
the catalog itself.

**Independent Test**: With one or more container images present, load the dashboard and confirm the images
figure reflects the distinct image count; with none, confirm it reads zero.

**Acceptance Scenarios**:

1. **Given** container images are present, **When** the user loads the dashboard, **Then** an images figure
   shows the count of distinct container images alongside the existing figures.
2. **Given** no container images are present, **When** the user loads the dashboard, **Then** the images
   figure reads zero and the other figures are unaffected.

---

### Edge Cases

- **Untagged/dangling image**: a container image with no tags (e.g. after its last tag was deleted) is not
  shown as a hosted catalog entry, consistent with how the image list already treats it.
- **Same image name in two repos**: the same image name in two different repositories appears as two distinct
  catalog entries, each attributed to its repository.
- **Mixed-format repository set**: a repository set containing Maven and container repositories yields a
  single catalog that interleaves both, each row type-marked; the name filter applies uniformly.
- **Empty catalog**: with no artifacts or images, the catalog shows its existing empty state.
- **Proxy with nothing cached**: a proxy container repo that has cached nothing contributes no catalog
  entries.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The cross-repo catalog MUST include container images as entries alongside Maven artifact
  entries, in the same searchable list.
- **FR-002**: Each container catalog entry MUST show its repository, image name, latest tag, tag count, and
  total size.
- **FR-003**: Each catalog entry MUST carry a type indicator distinguishing a container image from a Maven
  artifact, and the UI MUST render that distinction visibly.
- **FR-004**: The existing catalog name filter MUST match container image entries the same way it matches
  Maven entries.
- **FR-005**: Selecting a container catalog entry MUST navigate to that image's tag view
  (`/c/{repo}/{image}`); selecting a Maven entry MUST retain its current behavior.
- **FR-006**: Catalog entries MUST respect per-repository READ authorization — a user MUST NOT see entries
  (Maven or container) for a repository they cannot read.
- **FR-007**: For a hosted container repository, the catalog MUST list its tagged images; an untagged
  (dangling) image MUST NOT appear.
- **FR-008**: For a proxy container repository, the catalog MUST list only images it has cached and MUST NOT
  perform a live enumeration of the upstream.
- **FR-009**: The dashboard MUST show an "images" figure equal to the number of distinct container images
  across the repositories, alongside the existing repository, artifact, and storage figures.
- **FR-010**: The change MUST be additive — it MUST NOT alter the container `/v2` or Maven wire protocols,
  resolution/publish behavior, or any configuration key; Maven catalog entries MUST be unchanged in meaning.
- **FR-011**: The catalog and dashboard additions MUST be visually consistent with the existing vault-themed
  frontend.

### Key Entities

- **Catalog entry**: a discoverable item in the cross-repo catalog — either a Maven artifact (group:artifact
  with latest version, version count, size) or a container image (image name with latest tag, tag count,
  size), attributed to its repository and marked with its type.
- **Container image (catalog view)**: an image name in a repository, summarized by its latest tag, tag count,
  and total size.
- **Dashboard summary**: the overview figures — repositories, artifacts, storage, and (new) distinct
  container images.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A container image pushed to a readable repository appears in the catalog's default view without
  the user needing to know or navigate to the repository first.
- **SC-002**: Every catalog row is unambiguously identifiable as a container image or a Maven artifact (100%
  of rows carry a correct type indicator).
- **SC-003**: The name filter returns matching container images and Maven artifacts together, with no
  container image requiring a different search interaction than a Maven artifact.
- **SC-004**: Selecting a container catalog entry lands the user on that image's tag view in a single action.
- **SC-005**: Authorization leakage is zero — across the read-authorization test matrix, no user sees a
  catalog entry (Maven or container) for a repository they cannot read.
- **SC-006**: The dashboard's images figure equals the number of distinct container images a permitted view
  would show (and reads zero when there are none).

## Assumptions

- Container images to catalog come from the same stored state the container browse UI already uses (features
  018/020/022): a hosted repo's tags/manifests, a proxy repo's cached manifests. No new stored state is
  introduced.
- "Total size" for a container image reuses the size notion already presented in the container UI (the
  stored/declared manifest sizes), not a recomputation over blobs.
- The catalog's existing per-repository READ authorization and name-filter behavior (feature 016) are reused
  unchanged; container entries simply participate in them.
- The dashboard's existing figures (repositories, artifacts, storage) and their current semantics are
  unchanged; the images figure is added beside them.
- Proxy repositories contribute only cached images because they have no authoritative upstream image/tag
  list, consistent with how the container image list already treats them.
