# Phase 0 Research: Delete a Container Image Tag from the Web UI

Decisions resolved from the existing feature-018/020 code; no open NEEDS CLARIFICATION remained.

## D1. How the tag is actually deleted

- **Decision**: The new browse endpoint delegates to the existing `ManifestService.delete(repo, image, ref)`
  with a tag `ref`, which calls `TagService.deleteTag(...)` — removing only the `ContainerTag` row and
  returning `true`/`false` (false ⇒ the tag did not exist).
- **Rationale**: This is the exact code the OCI `DELETE …/manifests/{tag}` path already uses, so the UI
  delete has identical semantics: the mutable pointer goes, the digest-addressed manifest and its blobs stay
  (no GC — Principle IV / FR-002). No new delete logic to test or drift from.
- **Alternatives considered**: A new dedicated delete method — rejected as redundant and a second source of
  truth for delete semantics.

## D2. Endpoint shape and routing vs. the Maven delete

- **Decision**: `DELETE /api/repositories/{repo}/containers/tags?image={image}&tag={tag}`, a sibling of the
  existing `GET …/containers/tags`. Image and tag are query parameters (image may contain slashes; tag is a
  simple string).
- **Rationale**: Keeping it under the `containers` sub-segment matches the feature-018/020 browse surface and
  the query-param style already used for the tags listing. The literal path `…/containers/tags` is **more
  specific** than `BrowseController`'s catch-all `@DeleteMapping("/repositories/{repo}/**")`, so Spring's
  path-pattern specificity routes the request to the container controller, not the Maven storage-key delete.
  A test asserts the delete actually removes the tag (proving the routing).
- **Alternatives considered**: A path segment for the tag (`…/tags/{tag}`) — works, but a slash-bearing image
  plus a tag segment is fiddly; query params keep both unambiguous and mirror the GET.

## D3. Authorization — reuse, plus a proxy guard

- **Decision**: No authorization-layer change. `RepositoryAuthorizationManager.browseTarget` already maps any
  `DELETE` under `/api/repositories/{repo}/**` to that repo's **DELETE** action (feature 007), so the new
  endpoint is permissioned identically to the Maven browse delete: anonymous → 401 (login), authenticated
  without permission → 403. The controller **additionally** rejects delete on a non-hosted (proxy) container
  repo with **405**, since a proxy is a read-only pull-through cache (FR-008/SC-005).
- **Rationale**: The DELETE→DELETE-action mapping is already covered and tested for the Maven path; container
  delete rides the same rule. The explicit proxy 405 is defense-in-depth so the API refuses even if a UI
  affordance were bypassed. A unit case is added to the authz-mapping test to pin the container path.
- **Alternatives considered**: A new authz rule for containers — unnecessary; the generic DELETE mapping
  already fits.

## D4. Making the image "drop out" on last-tag delete without GC

- **Decision**: Make `ContainerBrowseService.images(...)` **kind-aware**: a **hosted** repo lists images that
  have ≥1 tag (tag-driven); a **proxy** repo keeps listing distinct cached-manifest image names (it has no
  stored tags). Tag/manifest counts and last-pushed are computed as before.
- **Rationale**: Because a tag delete retains the manifest (D1), a manifest-driven list would keep showing an
  image after its last tag is deleted — contradicting FR-004/SC-003. Tag-driving the hosted list makes a
  fully-untagged image disappear (dangling manifests aren't shown as images, matching registry-UI
  convention) while **not** deleting anything. Proxy must stay manifest-driven because a proxy has no
  `ContainerTag` rows (feature 018), so tag-driving it would wrongly empty its listing.
- **Alternatives considered**: (a) Garbage-collect the untagged manifest so a manifest-driven list drops it —
  rejected: GC is explicitly out of scope and destroys digest-addressable content. (b) Leave the image
  showing with 0 tags — rejected: contradicts the spec's desired UX.

## D5. Frontend — knowing the repo kind on the tag view

- **Decision**: Add a `kind` field to the tags response (`GET …/containers/tags`) so the tag view can show
  the delete affordance for **hosted** repos only, without a second request. The affordance is confirm-guarded
  and reuses the existing 401→login / 403→forbidden / 404→refresh handling already present in the container
  routes.
- **Rationale**: The tag view currently fetches only tags; it needs the repo kind to gate the affordance
  (FR-008). Returning it on the tags response is one small additive field, cheaper than a separate
  repositories lookup, and mirrors how the images response already carries `kind`.
- **Alternatives considered**: Fetch `listRepositories()` on the tag view to derive kind — extra request and
  duplicated logic; rejected.
