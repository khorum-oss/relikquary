# Phase 1 Data Model: Container (OCI / Docker) Registry

Two layers: **content** (immutable bytes in `ArtifactStorage`, addressed by digest) and **relational
state** (mutable/queryable rows in Postgres via JPA + Liquibase). Nothing here re-encodes stored bytes
(Principle IV).

## Content layer — keys in `ArtifactStorage`

All keys are namespaced under the repository name and a reserved `_container/` prefix so container and
Maven trees never collide within one repo/backend.

| Object | Storage key | Written when | Immutable |
|--------|-------------|--------------|-----------|
| Blob (layer or config) | `{repo}/_container/blobs/sha256/{hex}` | finalized blob upload (hosted) or cached blob (proxy) | Yes |
| Manifest / index | `{repo}/_container/manifests/sha256/{hex}` | manifest PUT (hosted) or cached manifest (proxy) | Yes |

- `{hex}` is the lowercase hex of the object's `sha256` digest; the full digest string is `sha256:{hex}`.
- Writes go through `ArtifactStorage.openWrite(key)` → stream bytes while computing `sha256` → on EOF,
  compare to the claimed/declared digest → `commit()` (mismatch ⇒ `abort()`, nothing visible).
- Blob reuse / cross-mount: `exists(blobKey)` is the `HEAD /blobs/{digest}` and mount short-circuit.
- Proxy cached blobs reuse the feature-015 tee: stream to client while writing the pending entry; commit
  only on a complete read.

## Relational layer — JPA entities (Liquibase `002-container-registry.xml`)

### ContainerManifest

A stored manifest's descriptor — needed to return the exact `Content-Type` and `Docker-Content-Digest`
on GET/HEAD (media type is not reliably derivable from bytes across Docker/OCI variants).

| Field | Type | Notes |
|-------|------|-------|
| `id` | PK | generated |
| `repository` | string | Relikquary repo name |
| `imageName` | string | OCI name, e.g. `library/alpine`, `team/app` |
| `digest` | string | `sha256:{hex}` of the manifest bytes |
| `mediaType` | string | e.g. `application/vnd.oci.image.index.v1+json`, `…manifest.v2+json` |
| `sizeBytes` | long | manifest byte length |
| `createdAt` | instant | ingest/cache time |

- **Unique**: `(repository, digest)`.
- **Index**: `(repository, imageName)` for tag-list and cleanup queries.

### ContainerTag

The mutable pointer from a human tag to a manifest digest.

| Field | Type | Notes |
|-------|------|-------|
| `id` | PK | generated |
| `repository` | string | Relikquary repo name |
| `imageName` | string | OCI name |
| `tag` | string | e.g. `latest`, `1.4.0` |
| `manifestDigest` | string | `sha256:{hex}` → the pointed-at manifest |
| `updatedAt` | instant | last re-push |

- **Unique**: `(repository, imageName, tag)` — a re-push UPDATEs `manifestDigest` (tags are mutable).
- **Query**: list tags for `(repository, imageName)` → `GET …/tags/list`.
- Proxy repos generally do not persist tags (tags resolve live upstream); a proxy MAY record the
  last-resolved tag→digest purely for observability, but resolution never trusts it (decision 7).

### BlobUpload

Transient state of an in-progress chunked blob upload (resumable across requests under STATELESS
sessions).

| Field | Type | Notes |
|-------|------|-------|
| `uploadId` | PK (uuid) | the `{uuid}` in `/blobs/uploads/{uuid}` |
| `repository` | string | target repo |
| `imageName` | string | target image |
| `bytesReceived` | long | current offset (for `Range`/`PATCH` continuation) |
| `pendingKey` | string | storage key of the in-progress pending write |
| `startedAt` | instant | for reaping abandoned sessions |

- Created by `POST /blobs/uploads/`; advanced by each `PATCH`; deleted on `PUT` finalize (after digest
  verify + promotion to the `blobs/sha256/{hex}` key) or on `DELETE`/reap.

## Configuration entities (extended, not new tables)

### RepositoryProperties.Repo — new field

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `format` | `RepositoryFormat` | `MAVEN` | `MAVEN` \| `CONTAINER`; selects protocol/layout. |

- Container repos reuse existing `kind` (HOSTED \| PROXY), `remoteUrl` (proxy upstream; default
  `https://registry-1.docker.io` when a CONTAINER proxy omits it), `remoteUsername`/`remotePassword`
  (optional upstream creds), and `access` (per-repo authz). `type` (RELEASE/SNAPSHOT/MIXED) is ignored
  for CONTAINER (tag mutability is fixed: tags mutable, digests immutable).

### RepositoryFormat (new enum)

`MAVEN` — the existing Maven-layout behavior. `CONTAINER` — OCI/Docker Registry V2 behavior.

## Validation rules (startup, in `RepositoryRegistry`)

- A `CONTAINER` + `PROXY` repo resolves an upstream: `remoteUrl` defaults to Docker Hub if blank (a
  CONTAINER proxy is legal with no explicit upstream, meaning Docker Hub).
- A `CONTAINER` + `GROUP` repo is **rejected** (group aggregation of container repos is out of scope).
- A `CONTAINER` + `HOSTED` repo needs no upstream (same as Maven hosted).
- Existing Maven validations are unchanged.

## Request-time value types (not persisted)

- **Digest** — a `sha256:{64 hex}` value; parses/validates the algorithm+hex, computes a digest over a
  stream, and verifies bytes match a claimed digest.
- **ImageReference** — parses `/v2/{repo}/{name…}/{op}/{ref}` into `(repo, imageName, operation, ref)`
  where `ref` is a tag or a digest; validates the OCI name grammar and rejects path traversal.

## State transitions

**Hosted push** (per the V2 protocol): `POST /blobs/uploads/` → (monolithic `PUT?digest=` | one-or-more
`PATCH` then `PUT?digest=`) → blob committed to `blobs/sha256/{hex}` (digest verified) → repeat for all
blobs → `PUT /manifests/{ref}` validates referenced blobs exist, stores manifest bytes, upserts
`ContainerManifest`, and (if `ref` is a tag) upserts `ContainerTag`.

**Hosted pull**: `GET /manifests/{ref}` — if `ref` is a tag, resolve `ContainerTag`→digest; load manifest
bytes by digest; return with stored `mediaType` + `Docker-Content-Digest`. `GET /blobs/{digest}` streams
the blob bytes.

**Proxy pull**: `GET /manifests/{tag}` → resolve tag→digest upstream (live); serve cached manifest for
that digest or fetch+cache it. `GET /blobs/{digest}` → serve cached blob or fetch+cache (tee-stream).
Not-found upstream ⇒ 404 (nothing cached); upstream/token error ⇒ 502 (nothing cached).
