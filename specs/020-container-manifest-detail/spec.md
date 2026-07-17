# Feature Specification: Container Image Manifest & Layer Detail View

**Feature Branch**: `020-container-manifest-detail`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "Container image manifest and layer detail view. Extend the container-image
browse UI (feature 018) so a user can drill from a tag into a manifest detail view. For a single-platform
image manifest, show the config digest, total image size, and the ordered layer list with each layer's
digest, media type, and size. For a multi-arch manifest list / OCI image index, show each platform entry
(os/architecture/variant) with its sub-manifest digest and size, and let the user drill into a specific
platform's manifest to see its layers. This is a read-only extension of the existing hosted container
browse API and the /c/{repo}/{image} tag view; it must parse stored manifest bytes on the backend (never
alter them) and reuse the existing per-repo read authorization. Keep it consistent with the vault-themed
frontend."

## Clarifications

### Session 2026-07-17

- Q: Which manifest shapes must the detail view understand? → A: The two shapes current clients push — a
  Docker/OCI **image manifest** (schema 2, with a config descriptor and an ordered layer list) and a
  Docker **manifest list** / OCI **image index** (a list of platform sub-manifests). Legacy schema-1
  manifests are not produced by modern `docker`/`podman`/`buildx` and are out of scope.
- Q: What does "total image size" mean? → A: The sum of the config blob size and every layer size **as
  declared in the manifest** (the compressed download size a client would pull), presented human-readably.
  It is not the uncompressed on-disk size, which is not derivable from the manifest.
- Q: Do proxy container repositories participate? → A: No. A proxy repo resolves tags live upstream and
  has no stored tag list, so the browse UI already offers no tag to drill from. Manifest detail applies to
  **hosted** container repositories. If a proxy has a cached manifest reachable by digest, showing its
  detail is a possible later enhancement, not part of this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Inspect a single-platform image's layers (Priority: P1)

A developer browsing a hosted container repository opens an image's tag list, then drills into a tag to
see what that image is actually made of: the config object it references, how large the image is to pull,
and the ordered list of layers with each layer's digest, media type, and size. This is the everyday case —
most application images built by a CI pipeline are single-platform.

**Why this priority**: The layer breakdown is the core value of a manifest detail view — it answers "what
is in this image and how big is it?" without a client having to `docker pull` and `docker inspect`. It
delivers a complete, useful feature on its own even if the multi-arch case is never built.

**Independent Test**: Push a single-platform image (config + two layers) to a hosted container repo, open
its tag in the UI, and confirm the detail shows the config digest, a total size equal to the config plus
both layers, and both layers in order with their digests, media types, and sizes.

**Acceptance Scenarios**:

1. **Given** a hosted container repo holding a single-platform image tagged `1.0.0`, **When** the user
   opens that tag's manifest detail, **Then** the config digest, the total (config + layers) size, and the
   ordered layer list — each layer's digest, media type, and size — are shown.
2. **Given** the manifest detail is displayed, **When** the user compares the shown config and layer
   digests to what a container client reports for the same tag, **Then** they match exactly (the bytes were
   read, not altered).
3. **Given** a tag that points directly at a digest reference, **When** the user opens its detail, **Then**
   the same breakdown is shown for that digest.

---

### User Story 2 - Understand a multi-arch image and drill into one platform (Priority: P2)

A user opens a tag that resolves to a multi-arch image (a manifest list / image index — e.g. an official
base image republished into the hosted repo). Instead of a single layer list, they see the set of platforms
the image covers (operating system, architecture, and variant where present), each with its sub-manifest
digest and size, and can drill into one platform to see that platform's own layers.

**Why this priority**: Multi-arch images are common for base and library images. Surfacing the platform set
and letting a user reach a specific platform's layers rounds out the feature, but it builds on and is
secondary to the single-platform breakdown.

**Independent Test**: Push a manifest list referencing two platform image manifests (e.g. linux/amd64 and
linux/arm64) to a hosted repo, open its tag, confirm both platforms are listed with their digests and
sizes, drill into one, and confirm that platform's layers are shown as in User Story 1.

**Acceptance Scenarios**:

1. **Given** a tag resolving to a manifest list with two platform entries, **When** the user opens its
   detail, **Then** each platform is listed with its os/architecture (and variant when present), its
   sub-manifest digest, and its size.
2. **Given** the platform list is shown, **When** the user selects one platform, **Then** that platform's
   image manifest detail (config + ordered layers) is shown as in User Story 1.
3. **Given** a platform entry whose sub-manifest is not stored locally, **When** the user views the list,
   **Then** that platform still appears with its declared digest and size, marked as not present locally,
   and drilling into it reports the manifest is unavailable rather than erroring.

---

### Edge Cases

- **Unparseable manifest bytes**: a stored manifest whose bytes are not a recognized image-manifest or
  index shape is shown with its digest, media type, and size and an indication that a detailed breakdown is
  unavailable — the view never errors or shows a blank page.
- **Missing referenced object**: when a config blob, layer, or sub-manifest referenced by a manifest is not
  stored locally (e.g. after a partial delete), the reference is still shown with its declared digest and
  size and marked as not present locally.
- **Empty layer set**: a manifest with a config but no layers (rare, e.g. an artifact/attestation manifest)
  shows the config and an empty-layers note.
- **Access without permission**: a user without read access to the repository cannot open a manifest detail
  (same result as being unable to see the repo's images/tags at all).
- **Deleted tag mid-view**: opening a detail for a tag/digest that no longer exists reports "not found"
  rather than erroring.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: From an image's tag list in a hosted container repository, a user MUST be able to open a
  manifest detail for the digest that a tag (or a direct digest reference) resolves to.
- **FR-002**: For a single-platform image manifest, the detail MUST show the config digest, the total image
  size (the sum of the config and all layer sizes as declared), and the ordered list of layers, each with
  its digest, media type, and size.
- **FR-003**: For a manifest list / image index, the detail MUST list every platform entry with its
  operating system, architecture, variant (when present), sub-manifest digest, and size.
- **FR-004**: A user MUST be able to drill from a platform entry into that platform's image manifest and see
  its layers as specified in FR-002.
- **FR-005**: The manifest detail MUST be derived from the exact stored manifest bytes without altering
  them; every digest, media type, and size shown MUST reflect what a container client would pull.
- **FR-006**: Viewing a manifest detail MUST reuse the existing per-repository read authorization — a user
  who cannot read the repository cannot view its manifest detail.
- **FR-007**: When a manifest's bytes cannot be parsed as a recognized image manifest or index, the system
  MUST degrade gracefully — showing the digest, media type, and size with an "unavailable" note — rather
  than failing.
- **FR-008**: When a referenced config, layer, or sub-manifest is not stored locally, the system MUST still
  present its declared digest and size and indicate the object is not present locally.
- **FR-009**: The manifest detail view MUST be visually consistent with the existing vault-themed container
  browse UI (feature 018).
- **FR-010**: The feature MUST be read-only — it introduces no way to modify, delete, or re-tag stored
  manifests, blobs, or tags.

### Key Entities

- **Image manifest**: the descriptor of a single-platform image — a media type, one config descriptor, and
  an ordered list of layer descriptors.
- **Image index (manifest list)**: an ordered list of platform sub-manifest descriptors, each annotated
  with the platform it targets.
- **Descriptor**: a reference to stored content — its digest, media type, and size; for an index entry it
  also carries the target platform.
- **Platform**: the operating system, architecture, and optional variant a sub-manifest targets.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From an image's tag, a user can reach the full ordered layer breakdown of a single-platform
  image in a single action (one drill-in), with every layer's digest, media type, and size visible.
- **SC-002**: For a multi-arch image, a user can identify every included platform and reach any one
  platform's layer breakdown in at most two drill-ins from the tag.
- **SC-003**: Every digest shown in a manifest detail matches, byte-for-byte, the digest a container client
  reports for the same tag or platform — verified against a real client-shaped push/pull in test.
- **SC-004**: A user without read permission on a repository is never shown its manifest detail (0% leakage
  across the authorization test matrix).
- **SC-005**: A manifest whose bytes are unparseable or whose references are partially missing renders a
  useful, non-erroring view 100% of the time (no blank pages, no stack traces surfaced to the user).

## Assumptions

- Only the two manifest shapes produced by current clients matter: the Docker image manifest (schema 2) /
  OCI image manifest, and the Docker manifest list / OCI image index. Legacy schema-1 manifests are out of
  scope.
- "Total image size" is the sum of the declared config and layer sizes (compressed pull size), not the
  uncompressed on-disk size.
- The feature targets hosted container repositories, reusing the stored manifest bytes (keyed by digest)
  and the browse images/tags surface added in feature 018. Proxy repositories are out of scope for
  drill-in.
- Read authorization reuses the per-repository access model from feature 007, exactly as the feature-018
  browse endpoints already do.
- The manifest bytes needed for the detail are already persisted by the hosted push path (feature 018); no
  new storage or upload behavior is introduced.
