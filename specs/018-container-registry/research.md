# Phase 0 Research: Container (OCI / Docker) Registry

Design decisions resolving the Technical Context, each as Decision / Rationale / Alternatives.

## 1. New `format` dimension vs. new `kind` values

**Decision**: Add a `format` field to `RepositoryProperties.Repo` — `RepositoryFormat.MAVEN` (default) or
`CONTAINER` — orthogonal to the existing `kind` (HOSTED/PROXY/GROUP). Container support uses
`format: CONTAINER` with `kind: HOSTED` or `kind: PROXY`.

**Rationale**: `kind` already means "how it resolves" (local / upstream-cache / aggregate) and is reused
verbatim for containers. Format is a separate axis ("what protocol/layout"). Keeping them orthogonal
means the HOSTED/PROXY resolution semantics, validation, and authorization concepts carry over unchanged,
and existing configs stay valid (`format` defaults to `MAVEN`).

**Alternatives**: (a) Add `CONTAINER_HOSTED`/`CONTAINER_PROXY` kinds — conflates two axes and duplicates
every kind. (b) A separate top-level `containerRepositories[]` config block — diverges the config
surface, duplicates name/access/retention. Rejected for redundancy.

## 2. Routing: dedicated `/v2/**` controller

**Decision**: A new `ContainerRegistryController` mapped at `@RequestMapping("/v2")` owns the whole V2
surface. The **first path segment after `/v2/` is the Relikquary repository name**; the remainder is the
OCI image name plus the operation, e.g. `GET /v2/dockerhub/library/alpine/manifests/3.20` → repo
`dockerhub`, image `library/alpine`, manifest ref `3.20`.

**Rationale**: Consistent with the Maven side (first segment = repo). Spring matches the most specific
pattern, so `/v2/**` wins over the Maven controller's `/**` for these paths with no interference. The
operation is found by locating the last `/manifests/`, `/blobs/uploads/`, `/blobs/`, or `/tags/list`
boundary — the standard registry parse (image names may contain slashes).

**Alternatives**: Extending `RepositoryController` — rejected; the V2 verbs (POST/PATCH/DELETE, upload
sessions, digest refs) and error body shape differ enough that co-locating them would tangle the Maven
handler.

## 3. Serving-side authentication: reuse HTTP Basic (no serving-side token server)

**Decision**: `docker login <relikquary-host>` authenticates with **HTTP Basic**, reusing the existing
Spring Security chain (config users, managed users, and `rlq_…` API tokens as the Basic password). The
`/v2/` version check returns `200` when permitted; a pull/push of a role-gated repo returns `401` with
`WWW-Authenticate: Basic realm="relikquary"`, which the Docker client satisfies by resending stored
credentials.

**Rationale**: The Docker/OCI clients support Basic-auth registries directly, so no bearer/token endpoint
is needed on the serving side. This reuses features 002/007/016 (auth + per-repo authz + API tokens)
with zero new auth machinery. The existing `BasicAuthenticationEntryPoint` already emits the challenge;
the only change is teaching `RepositoryAuthorizationManager` to map `/v2/{repo}/…` verbs to READ/PUBLISH/
DELETE.

**Alternatives**: Implement a serving-side Bearer token service (`/v2/token`) like Docker Hub — rejected
as unnecessary complexity for this feature; Basic is sufficient and already wired. (A token service can
be a later spec if a client requires it.)

**Note**: the Bearer-token dance IS required on the **proxy→Docker Hub** leg (Docker Hub only issues
Bearer tokens). That is decision 6, separate from serving-side auth.

## 4. Storage layout: content-addressable over `ArtifactStorage`

**Decision**: Reuse `ArtifactStorage` unchanged. Container objects live under a reserved per-repo
sub-namespace so they never collide with Maven keys:

- Blob: `"{repo}/_container/blobs/sha256/{hex}"`
- Manifest (by digest): `"{repo}/_container/manifests/sha256/{hex}"`

Both are immutable once written. Writes use `openWrite()` → stream → verify the computed `sha256` equals
the claimed digest → `commit()` (abort on mismatch), so a partial or mismatched upload never becomes
visible. Cross-image/-push blob reuse is a simple `exists()` check on the blob key (enables the
`HEAD`/mount short-circuit).

**Rationale**: The store already preserves bytes exactly (Principle IV) and already has an atomic
pending-write/commit (feature 015) perfect for digest-verified finalize. `_container/` keeps container
and Maven trees disjoint within one repo namespace and one storage backend — no schema/bucket change,
filesystem and S3 both work with no code change.

**Alternatives**: A separate storage abstraction for blobs — rejected; the path-keyed store already fits
content-addressable keys. Global (cross-repo) blob dedup — deferred; per-repo namespacing keeps
authorization and eviction simple (a repo owns its blobs).

## 5. Relational state: tags, manifest descriptors, upload sessions in Postgres/JPA

**Decision**: Add three JPA entities + a Liquibase changeset (`002-container-registry.xml`):

- `container_tag` — `(repository, image_name, tag)` → `manifest_digest`; the mutable pointer. Unique on
  `(repository, image_name, tag)`; a re-push updates the row.
- `container_manifest` — `(repository, manifest_digest)` → `media_type`, `size_bytes`; lets a GET return
  the exact `Content-Type` and `Docker-Content-Digest` for a stored manifest.
- `blob_upload` — an in-progress chunked upload session: `upload_id`, `repository`, `image_name`,
  `bytes_received`, timestamps; deleted on finalize/abort.

**Rationale**: The bytes are content-addressable and go to `ArtifactStorage`, but three things are
relational, not byte-addressable: a tag is a mutable name→digest mapping; a manifest's media type must be
returned exactly (it is not derivable from bytes reliably across Docker/OCI types); and a chunked upload
must be resumable across requests (`STATELESS` sessions). The app already runs Postgres + JPA + Liquibase
(features 016), so this reuses the established pattern (`ManagedUser`, `ApiToken`, `Setting`).

**Alternatives**: (a) Sidecar files in storage for media type + a "tags" index file — rejected; racy
tag updates and no transactional integrity. (b) In-memory upload sessions + tags — rejected for
durability across restarts and correctness under concurrent pushes, though acceptable as a single-
instance fallback. DB matches the repo's persistence conventions.

## 6. Proxy → Docker Hub: Bearer-token handshake + `library/` normalization

**Decision**: A new `ContainerUpstreamClient` (separate from the Maven `UpstreamClient`) performs the OCI
pull handshake against the upstream (default `https://registry-1.docker.io`): request the resource; on
`401` with `WWW-Authenticate: Bearer realm=…,service=…,scope=…`, GET a token from the realm
(`?service=…&scope=repository:{image}:pull`, with optional configured Basic creds for higher limits/
private images), retry with `Authorization: Bearer <token>`; re-negotiate on expiry. Official images
whose name has no `/` are normalized to `library/{name}`. Requests send the correct `Accept` for both
Docker v2 schema-2 and OCI image/index media types.

**Rationale**: Docker Hub only serves via Bearer tokens obtained from `auth.docker.io`; the client must
speak that flow. Normalization makes `docker pull host/dockerhub/alpine` resolve to `library/alpine`
upstream. The token/creds never reach the resolving client (Principle IV / FR-015). The existing
`UpstreamClient` stays Maven-only (Basic, single GET); the OCI client is its container analog.

**Alternatives**: Reuse `UpstreamClient` — rejected; it has no challenge/token logic and a different
error model. A generic OCI proxy for arbitrary upstreams — the client is written generically but only
Docker Hub is validated in this feature (per spec Assumptions).

## 7. Proxy caching semantics: cache digests, resolve tags live

**Decision**: On a proxy pull, resolve the **tag** against the upstream on each request (tags move), but
serve immutable **digests** from cache. Flow: resolve `image:tag` → manifest digest upstream (a
`HEAD`/`GET manifests/{tag}` returning `Docker-Content-Digest`); if that manifest digest is cached, serve
it and its already-cached blobs; otherwise fetch and cache the manifest (and, on blob GET, each layer/
config blob) keyed by digest. Reuse feature 015's tee so a blob streams to the client while it caches;
abort on truncation/disconnect. `manifest` and `index` objects are cached as opaque bytes; the registry
never rewrites platform digests inside an index.

**Rationale**: Digests are immutable and safe to cache permanently; tags are the only moving part, so
resolving them live keeps `:latest` correct while avoiding re-fetching unchanged layers. This mirrors the
Maven proxy's "metadata pass-through, artifacts cached" split (feature 006) in OCI terms
(tag-resolution live, digest-addressed bytes cached). Not-found upstream → `404` (cache nothing);
upstream/token error → `502` (cache nothing).

**Alternatives**: Cache tag→digest with a TTL — deferred (adds a clock/staleness policy; live tag
resolution is simpler and always correct). Pre-fetch all platforms of an index — rejected; fetch on
demand (the client pulls the platform manifest it needs).

## 8. Manifest validation on hosted PUT

**Decision**: On `PUT manifests/{ref}`, parse the manifest JSON, and for an image manifest verify its
config blob and every layer blob already exist in the repo (`exists()` on each digest key); for a
manifest list/index, verify each referenced sub-manifest digest exists. Reject with `MANIFEST_BLOB_UNKNOWN`
(and do not record the tag) if any is missing; verify the manifest's own bytes against the request digest
(if the ref is a digest) or compute+store its digest (if the ref is a tag). Store bytes verbatim.

**Rationale**: This is the standard V2 push ordering (blobs first, manifest last) and prevents recording a
tag that points at an incomplete image. Bytes are stored verbatim so the manifest digest stays stable.

**Alternatives**: Trust the client and skip existence checks — rejected; a dangling manifest would 500 on
pull. Deep-validate media types beyond references — out of scope (store opaque, validate references).

## 9. Testing strategy (Principle II, adapted)

**Decision**: Mirror the established "deterministic stub + guarded real" pattern:
- **Proxy**: a `registry:2` Testcontainer (`GenericContainer`, from `testcontainers-junit`) seeded with a
  small image is the default upstream — offline, CI-safe, exercised by a real client through the proxy;
  plus `ContainerProxyDockerHubIT` doing a real `library/alpine` pull, guarded to auto-skip when offline.
- **Hosted**: `ContainerHostedRoundTripIT` pushes a minimal image (a config blob + one layer + a manifest)
  over the raw V2 wire and pulls it back, asserting digest equality; run against filesystem and MinIO. A
  guarded `docker`/`skopeo` CLI round-trip provides the true end-to-end client proof, auto-skipping when
  no daemon/CLI is present (like the guarded real-Maven-Central and MinIO tests).
- **Auth/errors**: `@SpringBootTest` with auth enabled — anonymous push to a role-gated repo challenged,
  authenticated push succeeds; digest-mismatch, unknown-blob, proxy 404-vs-502, and push-to-proxy-405.

**Rationale**: Keeps CI fully offline and deterministic while still proving real client/real storage
round-trips, exactly as specs 003 (s3mock + MinIO) and 006 (stub + guarded Maven Central) established.

**Alternatives**: Only mock the upstream — rejected (Principle II bans mocking the boundary away). Only
test with a real Docker daemon — rejected (not CI-portable); it is the guarded add-on, not the baseline.

## 10. No new production dependency; version bump

**Decision**: Implement with existing libraries — Spring Web/Security, Jackson (manifest JSON), JDK
`HttpClient` (upstream), Spring Data JPA + Liquibase (state). Bump `VERSION` 1.0.0 → **1.1.0** (additive
capability, backward compatible; `format` defaults to `MAVEN`).

**Rationale**: The V2 protocol is HTTP + JSON + sha256 — all covered by the current stack, so no new
production dependency and no `verification-metadata.xml` change for production. Any test-only addition
(none currently anticipated beyond the `registry:2` image pulled by Testcontainers at runtime) would
extend the verification metadata, never disable it. Additive change ⇒ MINOR per Principle I.
