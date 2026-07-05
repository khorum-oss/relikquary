# Feature Specification: Container (OCI / Docker) Registry — Hosted Storage & Docker Hub Pull-Through

**Feature Branch**: `018-container-registry`

**Created**: 2026-07-04

**Status**: Draft

**Input**: User description: "Container (OCI / Docker) registry support with both hosted storage and
Docker Hub pull-through proxy. Add a new repository kind/format so Relikquary can serve container
images alongside Maven artifacts, reachable by an unmodified `docker` / `podman` / `nerdctl` client.
Two modes, mirroring the existing Maven HOSTED and PROXY parity: (1) HOSTED container repositories
that accept `docker push` and serve `docker pull`; (2) PROXY container repositories that are a
read-only pull-through cache of Docker Hub. Reuse the existing pluggable storage backends
(filesystem + S3), per-repository authorization, request logging/observability, and named-repository
configuration surface. Preserve stored bytes and digests exactly. Out of scope for this first
feature: GROUP aggregation of container repos, image signing/cosign verification, and registry
replication."

## Clarifications

### Session 2026-07-04

- Q: Multi-arch images — must the proxy handle manifest lists / OCI image indexes? → A: Yes. Official
  Docker Hub images resolve to a manifest list by default; the registry MUST store and serve manifest
  lists and OCI image indexes byte-for-byte as content-addressable objects, exactly like image
  manifests, so `docker pull` of a multi-arch image works end-to-end.
- Q: Pull authorization for container repos? → A: Reuse the existing per-repository authorization
  (feature 007). A container repo participates in the same read/write policy as any other repo:
  where reads are open they stay open; where a repo requires a role, container pull/push enforce it
  through the `docker login` → bearer/basic credential path. No new authorization model.
- Q: Docker Hub credentials for the proxy? → A: Anonymous pull by default (Docker Hub issues anonymous
  pull tokens). Optional upstream credentials reuse the existing proxy `remoteUsername`/`remotePassword`
  config so a deployment can raise its Docker Hub rate limit or pull permitted private images; those
  credentials are never exposed to resolving clients.
- Q: Are pushed tags immutable like release coordinates? → A: No. Tags are mutable pointers (a re-push
  of `:latest` re-points the tag); the immutable, content-addressable objects are the digests
  (`sha256:…`). A blob or manifest addressed by digest is immutable once stored.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pull public images through a Docker Hub proxy repository (Priority: P1)

An operator configures a proxy container repository (e.g. `/dockerhub`) whose upstream is Docker Hub.
A developer runs `docker pull <relikquary-host>/dockerhub/library/alpine:3.20` (or `.../nginx:latest`).
On a cache miss, Relikquary performs Docker Hub's anonymous bearer-token handshake, fetches the
manifest and every referenced blob, stores the exact bytes keyed by their digests, and serves the
image to the client. Subsequent pulls of the same digests are served from the local cache without
contacting Docker Hub.

**Why this priority**: A pull-through cache for Docker Hub is the headline value — one trusted,
cacheable, rate-limit-insulated entry point for public base images, mirroring the Maven Central proxy.

**Independent Test**: With a proxy container repo pointed at Docker Hub, `docker pull` an official
image on a cold cache (fetched upstream, digests verify); then re-pull the same image with the
upstream unavailable and confirm it still serves from cache.

**Acceptance Scenarios**:

1. **Given** a proxy container repo with Docker Hub upstream, **When** a client pulls an image tag not
   yet cached, **Then** Relikquary resolves the tag to a manifest via the upstream token handshake,
   fetches and stores the manifest and all referenced blobs byte-for-byte, and the client receives a
   working image.
2. **Given** an image whose blobs and manifest are already cached, **When** a client pulls it again,
   **Then** every blob and the manifest are served from the local cache without contacting Docker Hub.
3. **Given** a multi-arch image (manifest list / image index), **When** a client pulls it, **Then** the
   index and the platform-specific manifest it selects, plus their blobs, are fetched, cached, and
   served so the pull succeeds on the client's platform.
4. **Given** an image reference that does not exist upstream, **When** a client pulls it, **Then** the
   registry returns the standard "not found" error and caches nothing.
5. **Given** the upstream is unreachable on a cache miss, **When** a client pulls an uncached image,
   **Then** the registry returns a gateway/unavailable error (not "not found") and caches nothing.
6. **Given** an official image referenced without a namespace (e.g. `alpine`), **When** a client pulls
   it through the proxy, **Then** the name is normalized to the `library/` namespace against the
   upstream and the pull succeeds.

---

### User Story 2 - Push and pull your own images to a hosted container repository (Priority: P1)

A developer configured a hosted container repository (e.g. `/containers`), runs `docker login`
against Relikquary, `docker tag`s a locally built image to
`<relikquary-host>/containers/team/app:1.4.0`, and `docker push`es it. Relikquary accepts the blob
uploads and the manifest, verifying each digest, stores them content-addressably, and records the
tag. Another developer (or a CI job / Kubernetes node) then `docker pull`s the same reference and
receives the identical image.

**Why this priority**: Hosting first-party images is the other half of parity with Maven hosted repos
— a private place to publish and resolve your own container artifacts.

**Independent Test**: `docker push` a locally built image to a hosted container repo, then
`docker pull` it back from a clean Docker daemon (no local layers) and confirm the pulled image runs
and its digest matches what was pushed.

**Acceptance Scenarios**:

1. **Given** a hosted container repo, **When** a client pushes an image (monolithic or chunked blob
   uploads followed by a manifest PUT), **Then** every blob and the manifest are stored, each digest is
   verified against its bytes, and the pushed tag resolves to the manifest.
2. **Given** an image already pushed, **When** a client pulls it by tag or by digest, **Then** the
   registry serves the stored bytes unchanged and the client obtains the identical image.
3. **Given** a client uploads a blob whose content does not match the claimed digest, **When** it
   attempts to finalize the upload, **Then** the registry rejects it with a digest-invalid error and
   stores nothing.
4. **Given** a manifest referencing a blob that was never uploaded, **When** a client PUTs the
   manifest, **Then** the registry rejects it (blob unknown) and does not record the tag.
5. **Given** an existing tag, **When** a client pushes a new image to the same tag, **Then** the tag
   re-points to the new manifest while the previously referenced digests remain retrievable by digest.
6. **Given** a pushed repository, **When** a client lists its tags, **Then** the registry returns the
   recorded tags for that image name.

---

### User Story 3 - Container repos honor existing auth, storage, and observability; Maven unaffected (Priority: P2)

Container repositories reuse Relikquary's existing cross-cutting behavior: per-repository
authorization gates pull/push the same way it gates Maven read/publish, artifacts persist through the
same configurable storage backends (filesystem or S3), and every container request is logged and
observed through the existing request-logging and metrics. Adding container support does not change
or regress Maven publish/resolve, existing auth, or the browse UI.

**Why this priority**: A new repository format must slot into the platform's contracts without
weakening authorization, storage faithfulness, or any existing capability.

**Independent Test**: With auth enabled, an anonymous push to a hosted container repo that requires
the publish role is rejected while an authenticated push succeeds; the same image round-trips against
both the filesystem and the S3-compatible storage backend; and the existing Maven publish/resolve and
DELETE auth suites still pass unchanged.

**Acceptance Scenarios**:

1. **Given** auth is enabled and a container repo requires the publish role, **When** an
   unauthenticated client attempts `docker push`, **Then** the registry responds with the standard
   unauthorized challenge and stores nothing; **When** a client with the role pushes, **Then** it
   succeeds.
2. **Given** the S3-compatible storage backend is configured, **When** an image is pushed and pulled,
   **Then** it round-trips byte-for-byte identically to the filesystem backend with no code change.
3. **Given** container pulls and pushes occur, **When** requests are served, **Then** they appear in
   the existing request log and metrics like any other request.
4. **Given** the existing Maven hosted/proxy/group repositories, **When** their publish/resolve and
   DELETE flows run, **Then** their behavior is unchanged by the addition of container repos.

### Edge Cases

- **Version check**: `GET /v2/` MUST advertise Docker Registry V2 support (success when the client is
  authorized, or the standard unauthorized challenge when auth is required) so clients proceed.
- **Digest immutability**: a blob or manifest addressed by `sha256:<hex>` is immutable; re-uploading
  identical content is idempotent, and content addressed by a digest never changes.
- **Digest mismatch on push**: bytes not matching the claimed digest are rejected (digest-invalid);
  nothing is stored.
- **Chunked upload correctness**: interrupted/out-of-order chunked blob uploads are handled per the V2
  upload protocol; a partial upload that is never finalized leaves no visible blob.
- **Cross-repository blob reuse**: a layer already present (same digest) need not be re-uploaded; the
  push can complete by referencing the existing blob (mount/HEAD short-circuit).
- **Manifest list / index**: multi-arch indexes are stored and served as opaque content-addressable
  objects; the registry does not rewrite platform digests inside them.
- **Proxy tag freshness**: a proxy resolves a *tag* against the upstream (a moving pointer) while
  serving immutable *digests* from cache — so a tag that moves upstream (e.g. `:latest`) resolves to the
  new manifest, but already-cached digests are never re-fetched.
- **Proxy not-found vs error**: image/blob absent upstream → "not found" (nothing cached); upstream
  unreachable, 5xx, or token endpoint failure → gateway/unavailable error (nothing cached), so a
  transient outage is not cached as a miss.
- **Upstream token expiry**: an expired/insufficient upstream bearer token is transparently
  re-negotiated for the required pull scope; the client never sees the upstream challenge.
- **Push to a proxy**: `docker push` to a proxy container repo is rejected (read-only), like the Maven
  proxy.
- **Name / reference validation**: image names and references that violate the registry grammar, or
  attempt path traversal, are rejected without touching storage.
- **HEAD requests**: `HEAD` for a blob or manifest returns the same digest/size/type headers as `GET`
  without a body, for both hosted and (cache- or upstream-resolved) proxy repos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Relikquary MUST support declaring a repository as a **container (OCI/Docker) repository**
  in the same named-repository configuration surface as Maven repositories, in both **hosted** and
  **proxy** kinds; adding one MUST require no code change.
- **FR-002**: A container repository MUST expose the Docker Registry HTTP API V2 surface required for
  `docker`/`podman`/`nerdctl` pull and push: the `GET /v2/` version check; blob `GET`/`HEAD` by digest;
  blob uploads (`POST` to initiate, `PATCH` for chunks, `PUT` to finalize — supporting both monolithic
  and chunked uploads); manifest `GET`/`HEAD`/`PUT`/`DELETE` by tag or digest; and tag listing.
- **FR-003**: A hosted container repository MUST accept `docker push`: it MUST store uploaded blobs and
  manifests content-addressably by their `sha256` digest, verify every uploaded blob and manifest
  against its claimed digest before making it visible, and reject a mismatch (digest-invalid) without
  storing anything.
- **FR-004**: On a manifest `PUT`, the registry MUST reject a manifest that references a blob (layer or
  config) or a sub-manifest not present in the repository (blob/manifest unknown) and MUST NOT record
  the tag in that case.
- **FR-005**: A hosted container repository MUST record tag→digest mappings, resolve a pull by tag or by
  digest to the stored manifest, allow a tag to be re-pointed by a subsequent push (tags are mutable),
  and list the tags for an image name.
- **FR-006**: The registry MUST store and serve image manifests, **manifest lists / OCI image indexes**,
  configs, and layer blobs byte-for-byte, preserving their digests exactly (Principle IV); it MUST NEVER
  alter, re-encode, or re-checksum stored container content.
- **FR-007**: Relikquary MUST support a **proxy** container repository that is a read-only pull-through
  cache of an upstream OCI registry (Docker Hub by default): on a cache miss it MUST resolve the
  reference against the upstream, fetch the manifest/index and referenced blobs, store the exact bytes
  keyed by digest, and serve them; a subsequent request for a cached digest MUST be served without
  contacting the upstream.
- **FR-008**: The proxy MUST perform the upstream (Docker Hub) authentication handshake: on an upstream
  `401` with a `WWW-Authenticate: Bearer` challenge it MUST obtain a scoped pull token from the
  indicated token service and retry with `Authorization: Bearer`, transparently re-negotiating an
  expired/insufficient token; the client MUST never see the upstream challenge or token.
- **FR-009**: The proxy MUST normalize official-image references that omit a namespace (e.g. `alpine`)
  to the upstream's `library/` namespace so short official-image names resolve.
- **FR-010**: A proxy request for an image/blob/manifest absent upstream MUST return the standard
  not-found error and cache nothing; a proxy request that fails because the upstream (registry or token
  service) is unreachable or errors MUST return a gateway/unavailable error and cache nothing.
- **FR-011**: A proxy resolves a **tag** against the upstream on each request (a moving pointer) while
  serving immutable **digests** from cache; `docker push` to a proxy container repository MUST be
  rejected (read-only).
- **FR-012**: Container repositories MUST reuse the existing per-repository authorization (feature 007):
  pull/push MUST be gated by the same role policy that gates Maven read/publish for that repository,
  enforced through the registry's `docker login` credential path (bearer or basic), and MUST issue the
  standard unauthorized challenge when credentials are missing or insufficient.
- **FR-013**: Container content MUST persist through the existing configurable storage backends
  (filesystem and S3-compatible) with no code change to repoint, namespaced under the container repo's
  name, and MUST round-trip byte-for-byte identically across backends.
- **FR-014**: Every container request MUST be captured by the existing request logging and metrics
  (feature 010) like any other request.
- **FR-015**: Upstream credentials for a proxy container repo (when configured for higher rate limits or
  permitted private images) MUST be supplied via configuration/environment, MUST NOT be committed, and
  MUST NEVER be exposed to resolving clients.
- **FR-016**: Adding container repositories MUST NOT change or regress existing Maven hosted/proxy/group
  publish/resolve, authentication, storage behavior, or the browse/manage API and UI.
- **FR-017**: A request whose target repository name is not a configured repository, or that violates
  the image-name/reference grammar or attempts path traversal, MUST be rejected (not-found or
  bad-request as appropriate) without touching storage.

### Key Entities

- **Container Repository**: a named repository whose format is container (OCI/Docker), in `hosted`
  (accepts push, serves pull) or `proxy` (read-only pull-through cache) kind, sharing the configuration,
  storage, authorization, and observability of Maven repositories.
- **Blob**: an immutable, content-addressable object (image layer or config) identified by its
  `sha256` digest; the unit of `docker` upload/download and of cross-image reuse.
- **Manifest**: the document describing an image (its config + ordered layers) or, as a **manifest
  list / image index**, a set of platform-specific manifests; content-addressable by digest and also
  reachable by tag.
- **Tag**: a mutable, human-readable pointer (e.g. `latest`, `1.4.0`) to a manifest digest within an
  image name.
- **Image Name / Reference**: the repository-scoped path identifying an image (e.g. `team/app`,
  `library/alpine`) plus a reference that is either a tag or a digest.
- **Upstream Registry**: the external OCI registry a proxy fetches from (Docker Hub —
  `registry-1.docker.io` with its `auth.docker.io` token service — by default).
- **Blob Upload Session**: the transient, resumable state of an in-progress chunked blob upload that
  becomes a visible blob only when finalized with a verified digest.
- **Cache**: the locally stored copy of digests a proxy has fetched, namespaced under the proxy repo's
  name in the configured storage backend.

## Success Criteria *(mandatory)*

- **SC-001**: An unmodified `docker pull` of an official multi-arch image (e.g. `alpine:3.20`) through a
  Docker Hub proxy repo succeeds on a cold cache; the manifest and layer digests match Docker Hub's, and
  a second pull succeeds with the upstream unavailable.
- **SC-002**: Cached proxy bytes are byte-for-byte identical to the upstream's — every stored blob and
  manifest digest verifies against its content.
- **SC-003**: An unmodified `docker push` of a locally built image to a hosted container repo, followed
  by a `docker pull` from a daemon with no local layers, yields an identical image (pushed and pulled
  digests match).
- **SC-004**: A blob or manifest whose bytes do not match its claimed digest is rejected on push and
  nothing is stored; a manifest referencing a missing blob is rejected and no tag is recorded.
- **SC-005**: The same hosted image round-trips byte-for-byte identically across the filesystem and the
  S3-compatible storage backend with no code change.
- **SC-006**: With auth enabled, an unauthenticated `docker push` to a role-gated container repo is
  rejected with the standard challenge while an authenticated push succeeds; the existing Maven
  publish/resolve and DELETE auth suites still pass unchanged.
- **SC-007**: A proxy pull of an image absent upstream returns not-found (nothing cached), and an
  upstream outage on a cache miss returns a gateway/unavailable error (nothing cached).

## Assumptions

- **Protocol baseline**: clients speak the Docker Registry HTTP API V2 (the OCI Distribution
  Specification). The registry accepts the Docker v2 schema-2 and OCI image/index media types; legacy
  schema-1 (deprecated) is out of scope.
- **Multi-arch**: manifest lists / OCI image indexes are stored and served as opaque content-addressable
  objects; the registry selects nothing on the client's behalf beyond serving what the client requests
  by digest/tag (the client's daemon picks the platform).
- **Tag mutability**: tags are mutable pointers; digests are immutable. There is no per-image
  "release/snapshot" immutability toggle for containers (that Maven concept does not apply); garbage
  collection of unreferenced blobs is out of scope for this feature.
- **Proxy scope**: the default and validated upstream is Docker Hub (anonymous pull tokens). The proxy
  design is not Docker-Hub-specific in principle, but only Docker Hub is validated in this feature;
  optional upstream credentials reuse the existing proxy credential config.
- **Storage**: container content caches/persists into the same configured storage backend (filesystem
  or S3) as Maven repos, namespaced by repository name; per-repo storage targets remain out of scope.
- **Authorization**: container pull/push reuse the existing per-repository authorization model
  (feature 007) via the registry credential path; no new roles or authorization model is introduced.
- **Out of scope (this feature)**: GROUP aggregation of container repos, image signing / cosign
  verification, cross-registry replication, blob garbage collection/quota, and a container-aware browse
  UI. These are candidate follow-up specs.
- **Testing**: hosted push/pull round-trips run against a real container client (the `docker` CLI or an
  OCI client library) exercising a real storage backend; the Docker Hub proxy path is validated by a
  real pull of a small official image, guarded to auto-skip when offline — mirroring the stub-plus-guarded
  split used for the Maven Central proxy (spec 006) and the s3mock + MinIO split (spec 003).
