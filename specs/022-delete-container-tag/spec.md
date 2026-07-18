# Feature Specification: Delete a Container Image Tag from the Web UI

**Feature Branch**: `022-delete-container-tag`

**Created**: 2026-07-17

**Status**: Draft

**Input**: User description: "Delete a container image tag from the web UI. Extend the container browse UI
(features 018/020) so a user with delete permission on a hosted container repository can delete a tag from
the tag view (/c/{repo}/{image}) — removing the mutable tag pointer while leaving the immutable manifest
digest and shared blobs intact, exactly like the existing OCI DELETE-by-tag semantics. Add a delete
affordance per tag row (hosted repos only; proxy repos are a read-only pull-through cache and offer no
delete), a confirmation step, and a graceful result: on success the tag disappears from the list and, if it
was the last tag on the image, the image drops out of the images list; on 401 prompt login, on 403 show a
not-permitted message. This reuses the existing per-repository DELETE authorization (feature 007). It
changes no wire protocol for docker clients; the /v2 DELETE path is unchanged. Keep it consistent with the
vault-themed frontend. Out of scope: deleting a manifest by digest from the UI, garbage-collecting
now-untagged manifests or blobs, and any delete on proxy repositories."

## Clarifications

### Session 2026-07-17

- Q: What exactly does deleting a tag remove? → A: Only the mutable tag→digest pointer. The manifest (by its
  `sha256:…` digest) and the shared config/layer blobs are left intact — identical to the existing OCI
  `DELETE …/manifests/{tag}` behavior. No garbage collection of the now-untagged manifest or its blobs.
- Q: Who may delete a tag? → A: Exactly the principals allowed to delete in that repository under the
  existing per-repository authorization (feature 007). A user without delete permission is refused; the UI
  surfaces the refusal rather than hiding the failure.
- Q: Which repositories offer tag deletion in the UI? → A: Hosted container repositories only. A proxy
  repository is a read-only pull-through cache with no stored tag list, so it offers no delete affordance.
- Q: Is a confirmation required? → A: Yes. Deletion is destructive to the tag pointer, so the UI requires an
  explicit confirmation before issuing the delete.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Delete a tag from a hosted image (Priority: P1)

A maintainer browsing a hosted container repository opens an image's tag list, decides a tag is no longer
wanted, clicks its delete affordance, confirms, and the tag is removed. The tag disappears from the list;
the underlying manifest is still reachable by its digest (nothing is garbage-collected), and other tags on
the image are unaffected. If that tag was the image's only tag, the image no longer appears in the
repository's image list.

**Why this priority**: Removing a stale or mistaken tag is the core need — today a user must reach for a
`docker`/`skopeo` client or the raw API to do something the browse UI otherwise fully supports (listing,
inspecting). This one capability makes the container UI usable for everyday cleanup.

**Independent Test**: With a hosted repo holding an image tagged twice, delete one tag from the UI and
confirm it disappears while the other tag and the manifest-by-digest remain; then delete the last tag and
confirm the image drops out of the image list.

**Acceptance Scenarios**:

1. **Given** a hosted image with tags `1.0.0` and `latest`, **When** the user deletes `1.0.0` and confirms,
   **Then** `1.0.0` disappears from the tag list, `latest` remains, and the manifest is still retrievable by
   its digest.
2. **Given** an image whose only tag is `1.0.0`, **When** the user deletes `1.0.0` and confirms, **Then** the
   image no longer appears in the repository's image list.
3. **Given** the delete affordance, **When** the user starts a delete but cancels the confirmation, **Then**
   nothing is deleted and the tag remains.

---

### User Story 2 - Permission and repository-kind guardrails (Priority: P2)

The delete affordance and its outcome respect who may delete and where. A user without delete permission on
the repository is refused with a clear message (not a silent no-op or a confusing error); an anonymous user
is prompted to sign in and, once signed in with sufficient permission, can complete the delete. A proxy
container repository shows no delete affordance at all, because it is a read-only pull-through cache.

**Why this priority**: Deletion is destructive and permissioned; getting the guardrails right prevents both
accidental exposure of the action and confusing failures. It builds directly on User Story 1's flow.

**Independent Test**: As an anonymous user, attempt a delete and get the login prompt; as a signed-in user
without delete permission, attempt it and get a not-permitted message; confirm a proxy repository's tag view
offers no delete control.

**Acceptance Scenarios**:

1. **Given** an anonymous user on a repository that requires permission, **When** they attempt to delete a
   tag, **Then** they are prompted to sign in, and after signing in with delete permission the delete
   completes.
2. **Given** a signed-in user without delete permission on the repository, **When** they attempt to delete a
   tag, **Then** they see a clear "not permitted" message and the tag is not deleted.
3. **Given** a proxy container repository, **When** the user views an image's tags, **Then** no delete
   affordance is shown.

---

### Edge Cases

- **Already gone**: deleting a tag that another actor already removed reports a clear "not found / already
  gone" result and refreshes the list rather than erroring.
- **Last tag**: deleting the last tag empties the image; the image list no longer includes it, and the empty
  tag view communicates there are no tags rather than showing a blank table.
- **Shared digest**: deleting one of two tags that point at the same digest removes only that tag pointer;
  the digest remains retrievable and the other tag still resolves.
- **Manifest retained**: after a tag delete, the manifest is still reachable by digest (this feature does not
  garbage-collect), matching the OCI delete-by-tag contract.
- **Non-container repo**: the tag-delete capability applies only to container repositories; it does not alter
  Maven repository deletion.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A user MUST be able to delete a tag of a hosted container image from that image's tag view,
  removing only the mutable tag→digest pointer.
- **FR-002**: Deleting a tag MUST leave the referenced manifest retrievable by its digest and MUST NOT
  garbage-collect the manifest or its blobs (matching the existing OCI delete-by-tag semantics).
- **FR-003**: The UI MUST require an explicit confirmation before performing a delete, and MUST NOT delete if
  the user cancels.
- **FR-004**: On a successful delete, the tag MUST disappear from the tag list without a full-page reload; if
  it was the image's last tag, the image MUST no longer appear in the repository's image list.
- **FR-005**: Tag deletion MUST reuse the existing per-repository DELETE authorization — a principal who
  cannot delete in the repository cannot delete a tag.
- **FR-006**: An anonymous user attempting a delete MUST be prompted to sign in; after signing in with
  sufficient permission the delete MUST be able to proceed.
- **FR-007**: A signed-in user lacking delete permission MUST receive a clear "not permitted" message and the
  tag MUST NOT be deleted.
- **FR-008**: The delete affordance MUST be shown only for hosted container repositories; a proxy container
  repository MUST NOT offer it.
- **FR-009**: Deleting a tag that no longer exists MUST produce a clear "not found / already gone" outcome and
  refresh the view rather than surfacing an unhandled error.
- **FR-010**: The feature MUST NOT change the container `/v2` wire protocol used by `docker`/`podman`/`skopeo`
  clients; the existing OCI delete path is unchanged.
- **FR-011**: The delete affordance and its feedback MUST be visually consistent with the existing
  vault-themed container browse UI.

### Key Entities

- **Container tag**: a mutable pointer from `(repository, image, tag)` to a manifest digest; the unit deleted
  by this feature.
- **Container manifest**: the immutable, digest-addressed image descriptor a tag points at; retained after a
  tag delete.
- **Repository delete permission**: the per-repository authorization that governs who may delete in a
  repository (feature 007), reused unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A permitted user can delete a tag from the tag view in at most two interactions (activate the
  affordance, confirm) and see it removed from the list without reloading the page.
- **SC-002**: After deleting a tag, the manifest remains retrievable by its digest 100% of the time (no
  garbage collection), and any other tag on the same image still resolves.
- **SC-003**: Deleting an image's last tag removes the image from the repository's image list.
- **SC-004**: Authorization is honored with zero leakage — an unpermitted or anonymous attempt never deletes
  a tag, and the user is guided (sign in / not permitted) rather than shown an unhandled error.
- **SC-005**: Proxy container repositories present no delete affordance, and no delete request against a proxy
  repository succeeds.

## Assumptions

- The server already deletes a tag by name via the existing OCI hosted path (feature 018); this feature
  exposes that capability through the browse/manage surface and the UI, rather than adding new delete
  semantics.
- Per-repository DELETE authorization (feature 007) is the sole permission model; no new roles or policies
  are introduced.
- "Delete a tag" means the tag pointer only. Deleting a manifest by digest, and reclaiming storage for
  now-untagged manifests/blobs, are explicitly out of scope (potential later features).
- Proxy container repositories are out of scope for deletion because they have no authoritative stored tag
  list (tags resolve live upstream), consistent with how the browse UI already treats them.
- The images/tags browse surface from features 018/020 is the integration point; no change to Maven
  browsing or deletion is intended.
