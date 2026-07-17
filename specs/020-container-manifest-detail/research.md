# Phase 0 Research: Container Image Manifest & Layer Detail View

All Technical Context items were resolved directly from the existing feature-018 code and the OCI/Docker
distribution spec; no open NEEDS CLARIFICATION remained. Key decisions below.

## D1. Where the manifest bytes come from

- **Decision**: Read the exact stored manifest bytes by digest via the existing
  `ContainerStorage.readManifestBytes(repository, digest)`, and read its descriptor (media type, size) via
  `ContainerManifestRepository.findByRepositoryAndDigest`.
- **Rationale**: The hosted push path (feature 018) already stores manifest/index bytes verbatim keyed by
  digest and records a `ContainerManifest` descriptor. `ManifestService.get` uses exactly this pair to
  serve a pull. Reusing it means the detail view sees byte-identical content to what a client pulls
  (Principle IV) with zero new storage.
- **Alternatives considered**: Re-fetching from a client or re-deriving from blobs — rejected; the bytes are
  already local and authoritative.

## D2. Classifying image manifest vs. image index

- **Decision**: Parse the JSON and classify by structure: a node with a `manifests` array is an **index**
  (Docker manifest list / OCI image index); otherwise a node with a `config` object and `layers` array is an
  **image manifest**; anything else is **unknown**. This mirrors `ManifestService.referencedDigests`, which
  already treats a non-empty `manifests` array as the index case and falls back to `config` + `layers`.
- **Rationale**: Media-type strings vary (Docker schema-2 vs OCI; `+json` suffixes) but the structural shape
  is stable and is exactly how the existing push-time reference check discriminates them — consistency
  avoids two divergent notions of "what is an index".
- **Alternatives considered**: Switching solely on the top-level `mediaType` — rejected as less robust
  (some tools omit it or set it inconsistently); structure is the reliable signal, media type is reported
  as-is for display.

## D3. "Total image size"

- **Decision**: Sum the `size` fields the manifest declares for its config descriptor and every layer
  descriptor; present it human-readably. Do not sum blob bytes on disk.
- **Rationale**: The manifest's declared sizes are the compressed pull size a user cares about ("how big is
  this to pull"), are always present in a valid schema-2/OCI manifest, and require no blob reads. The
  uncompressed on-disk size is not derivable from the manifest and is out of scope (per spec clarification).

## D4. Missing / unparseable degradation

- **Decision**: For each referenced descriptor (config, layer, sub-manifest), compute a `present` flag from
  `ContainerStorage.hasBlob` / `hasManifest` so a partially-deleted image still lists what the manifest
  declares, marked not-present. If the top-level manifest bytes are absent → 404; if present but not a
  recognized image/index shape → an `unknown` projection (digest + media type + size, no breakdown). The UI
  renders these states rather than erroring.
- **Rationale**: Satisfies FR-007/FR-008 and SC-005 (non-erroring 100% of the time). Digest addresses are
  immutable, but tags and by-digest deletes can leave dangling references; the view must stay useful.
- **Alternatives considered**: 500 on any parse failure — rejected (poor UX, breaks SC-005).

## D5. Endpoint shape & authorization

- **Decision**: `GET /api/repositories/{repo}/containers/manifest?digest={sha256:…}` returning a
  discriminated JSON projection. The digest is a query parameter (a `sha256:<hex>` string with no slashes).
- **Rationale**: The digest is what the tag list already carries, so the frontend drills in with a value it
  already has. Keeping the path under the existing `containers` sub-segment means the feature-018 READ
  authorization mapping (`RepositoryAuthorizationManager` → `containers` ⇒ READ) already covers it with **no
  security-layer edit** — a user who cannot read the repo cannot read a manifest (FR-006/SC-004).
- **Alternatives considered**: A path segment for the digest (`…/manifest/{digest}`) — works too, but the
  query param keeps the authz sub-segment unambiguous and avoids colon/encoding fuss; drill-into-platform
  reuses the same endpoint with the sub-manifest digest.

## D6. Frontend placement — inline panel vs. new route

- **Decision**: An inline `ManifestDetail` panel on the existing `/c/{repo}/{image}` tag view. Clicking a
  tag loads its digest into the panel; an index's platform rows are clickable and re-drive the same panel
  with the sub-manifest digest (with a "back to platforms" affordance).
- **Rationale**: Image names contain slashes and are already the `[...image]` rest parameter; threading a
  digest through the path would be awkward. An inline panel keeps drill-in/-out state local, needs no new
  route, and matches the existing pattern where the tag view already composes a `DockerPullSnippet`.
- **Alternatives considered**: A dedicated `/c/{repo}/{image}/manifest?digest=` route — more navigable/
  linkable but heavier; deferred as unnecessary for this feature's flow.
