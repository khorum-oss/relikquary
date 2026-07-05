# Contract: Container Registry (OCI Distribution / Docker Registry V2) Surface

The client-facing contract for container repositories. Clients are unmodified `docker` / `podman` /
`nerdctl` (and `skopeo`/`crane`). All endpoints are rooted at `/v2`. The **first path segment after
`/v2/` is the Relikquary repository name**; the rest is the OCI image name plus the operation.

Addressing example — with a repo named `dockerhub`:
`docker pull <host>/dockerhub/library/alpine:3.20`
→ `GET /v2/dockerhub/library/alpine/manifests/3.20`, repo=`dockerhub`, image=`library/alpine`, ref=`3.20`.

`<name>` below is the image name (may contain slashes). `<ref>` is a tag or a `sha256:<hex>` digest.
`<digest>` is always `sha256:<hex>`.

## Endpoints (per repository, dispatched by the repo's format+kind)

| # | Method | Path | Purpose | Hosted | Proxy |
|---|--------|------|---------|--------|-------|
| 1 | GET | `/v2/` | Version check | ✓ | ✓ |
| 2 | HEAD | `/v2/{repo}/{name}/manifests/{ref}` | Manifest exists? (digest+type headers) | ✓ | ✓ |
| 3 | GET | `/v2/{repo}/{name}/manifests/{ref}` | Fetch manifest / index | ✓ | ✓ |
| 4 | PUT | `/v2/{repo}/{name}/manifests/{ref}` | Store manifest, set tag | ✓ | ✗ (405) |
| 5 | DELETE | `/v2/{repo}/{name}/manifests/{ref}` | Delete manifest/tag | ✓ | ✗ (405) |
| 6 | HEAD | `/v2/{repo}/{name}/blobs/{digest}` | Blob exists? | ✓ | ✓ |
| 7 | GET | `/v2/{repo}/{name}/blobs/{digest}` | Fetch blob | ✓ | ✓ |
| 8 | POST | `/v2/{repo}/{name}/blobs/uploads/` | Start blob upload (or mount) | ✓ | ✗ (405) |
| 9 | PATCH | `/v2/{repo}/{name}/blobs/uploads/{uuid}` | Upload a chunk | ✓ | ✗ (405) |
| 10 | PUT | `/v2/{repo}/{name}/blobs/uploads/{uuid}?digest=` | Finalize upload | ✓ | ✗ (405) |
| 11 | GET | `/v2/{repo}/{name}/tags/list` | List tags | ✓ | ✓ (live upstream) |

### 1. Version check — `GET /v2/`

- **200** `{}` with header `Docker-Distribution-API-Version: registry/2.0` when the caller is permitted.
- **401** with `WWW-Authenticate: Basic realm="relikquary"` when auth is enabled and the caller is
  anonymous but a subsequent operation needs a role. `docker login` treats **200** as success.

### 2–3. Manifest GET/HEAD

- **Request**: `Accept` listing supported manifest media types (Docker v2 schema-2, OCI image + index).
- **200** (GET, body = exact stored manifest bytes) / **200** (HEAD, no body) with:
  - `Content-Type`: the stored `mediaType` (returned exactly).
  - `Docker-Content-Digest`: `sha256:<hex>` of the manifest bytes.
  - `Content-Length`: manifest size.
- **Hosted**: `{ref}` tag → resolve `ContainerTag`→digest, else `{ref}` is a digest. **404**
  `MANIFEST_UNKNOWN` if absent.
- **Proxy**: resolve the tag→digest live upstream; serve from cache by digest or fetch+cache. **404** if
  absent upstream; **502** if the upstream/token endpoint errors.

### 4. Manifest PUT (hosted)

- **Request**: `Content-Type` = the manifest media type; body = manifest/index JSON bytes.
- Validate every referenced blob (image: config + layers) or sub-manifest (index) exists in the repo →
  else **400** `MANIFEST_BLOB_UNKNOWN` (nothing recorded).
- Store bytes verbatim under `manifests/sha256/{hex}`; upsert `ContainerManifest`; if `{ref}` is a tag,
  upsert `ContainerTag` (mutable re-point). If `{ref}` is a digest, verify body digest == `{ref}` →
  **400** `DIGEST_INVALID` on mismatch.
- **201 Created**, `Location: /v2/{repo}/{name}/manifests/{digest}`, `Docker-Content-Digest: {digest}`.

### 5. Manifest DELETE (hosted)

- By digest → remove the manifest (and any tags pointing at it); by tag → remove the tag only.
- **202 Accepted**; **404** `MANIFEST_UNKNOWN` if absent.

### 6–7. Blob HEAD/GET

- **200** with `Docker-Content-Digest: {digest}`, `Content-Length`; GET body = exact blob bytes (streamed).
- **Hosted**: **404** `BLOB_UNKNOWN` if absent.
- **Proxy**: serve cached blob by digest, else fetch+cache from upstream (tee-stream). **404** absent
  upstream; **502** upstream error.

### 8. Start blob upload (hosted) — `POST /blobs/uploads/`

- **Monolithic**: `POST …?digest={digest}` with the full body → finalize immediately (see 10 semantics).
- **Chunked**: empty `POST` → **202 Accepted**, `Location: /v2/{repo}/{name}/blobs/uploads/{uuid}`,
  `Range: 0-0`, `Docker-Upload-UUID: {uuid}`; a `BlobUpload` row is created.
- **Cross-repo mount**: `POST …?mount={digest}&from={other}` → if the blob exists, **201 Created**
  (no upload); else fall back to a normal upload session.

### 9. Upload chunk (hosted) — `PATCH /blobs/uploads/{uuid}`

- Append the chunk to the pending write; advance `bytesReceived`.
- **202 Accepted**, `Range: 0-{n-1}`, `Docker-Upload-UUID: {uuid}`, `Location: …/{uuid}`.
- Out-of-order/overlapping range → **416 Requested Range Not Satisfiable**.

### 10. Finalize upload (hosted) — `PUT /blobs/uploads/{uuid}?digest={digest}`

- Append any final body bytes, then verify computed `sha256` == `{digest}` → **400** `DIGEST_INVALID` on
  mismatch (abort; nothing stored). On match, promote the pending write to `blobs/sha256/{hex}`; delete
  the `BlobUpload`.
- **201 Created**, `Location: /v2/{repo}/{name}/blobs/{digest}`, `Docker-Content-Digest: {digest}`.

### 11. Tag list — `GET /v2/{repo}/{name}/tags/list`

- **200** `{"name":"{name}","tags":[…]}` — hosted: from `ContainerTag`; proxy: resolved live upstream.

## Error body (all errors)

OCI-standard JSON: `{"errors":[{"code":"<CODE>","message":"<text>","detail":<any>}]}` with codes
`BLOB_UNKNOWN`, `MANIFEST_UNKNOWN`, `MANIFEST_BLOB_UNKNOWN`, `DIGEST_INVALID`, `NAME_INVALID`,
`NAME_UNKNOWN`, `UNAUTHORIZED`, `DENIED`, `UNSUPPORTED`, `TOOMANYREQUESTS`.

| Situation | Status | Code |
|-----------|--------|------|
| Unknown Relikquary repo (first segment) | 404 | `NAME_UNKNOWN` |
| Invalid image name / path traversal | 400 | `NAME_INVALID` |
| Push (4/5/8/9/10) to a proxy repo | 405 | `UNSUPPORTED` |
| Digest mismatch on finalize/manifest | 400 | `DIGEST_INVALID` |
| Manifest references a missing blob | 400 | `MANIFEST_BLOB_UNKNOWN` |
| Absent (blob/manifest, hosted or proxy-upstream) | 404 | `BLOB_UNKNOWN` / `MANIFEST_UNKNOWN` |
| Proxy upstream unreachable / 5xx / token failure | 502 | `UNSUPPORTED` (gateway) |
| Anonymous op on a role-gated repo (auth enabled) | 401 | `UNAUTHORIZED` (+ `WWW-Authenticate: Basic`) |
| Authenticated but insufficient role | 403 | `DENIED` |

## Authorization mapping (reuses feature 007)

`RepositoryAuthorizationManager` maps a `/v2/{repo}/…` request to `(repo, action)`:

| Method | Action |
|--------|--------|
| GET, HEAD | READ |
| POST, PATCH, PUT | PUBLISH |
| DELETE | DELETE |

Bare `GET /v2/` is granted (the controller returns 200). Where a repo's READ is open, pulls are open;
where a repo requires a role, the standard Basic challenge is issued and satisfied by `docker login`
credentials (config user, managed user, or `rlq_…` API token as the Basic password).

## Docker Hub proxy (upstream contract — `ContainerUpstreamClient`)

For `kind: PROXY, format: CONTAINER` with upstream `https://registry-1.docker.io` (default):

1. Normalize the image name: if it has no `/`, prefix `library/` (official images).
2. Request the resource (`manifests/{ref}` or `blobs/{digest}`) with the appropriate `Accept`.
3. On **401** with `WWW-Authenticate: Bearer realm="…",service="…"[,scope="…"]`, GET
   `realm?service={service}&scope=repository:{image}:pull` (with optional configured Basic creds) to
   obtain `{ "token"|"access_token": "…" }`; retry the resource with `Authorization: Bearer <token>`.
   Re-negotiate on a later 401 (token expiry).
4. Follow redirects (blob GETs commonly 307 to a CDN).
5. Map upstream **200** → Found (stream + digest/type), **404/410** → NotFound, everything else /
   unreachable / token-endpoint failure → Error. The upstream token/credentials are never surfaced to
   the resolving client.

## Non-goals (this contract)

Referrers API (`/v2/{name}/referrers/{digest}`), a serving-side Bearer token service, cross-registry
replication, and blob garbage collection are out of scope for feature 018.
