# Contract: Round-Trip Verification Harness

This feature adds no product contract; it **verifies** the existing one
([feature 018's container-registry-api.md](../../018-container-registry/contracts/container-registry-api.md)).
This document fixes the request sequences the tests drive and the behavior the in-JVM OCI upstream stub
promises, so the harness is unambiguous.

## Hosted round-trip sequence (US1) — client → server `/v2`

Repo `apps` (hosted, `format = CONTAINER`), image name `team/service`.

1. **Push** image #1:
   - `POST /v2/apps/team/service/blobs/uploads/?digest=<configDigest>` (body = config bytes) → **201**,
     `Location`, `Docker-Content-Digest: <configDigest>`.
   - same for layer A and layer B.
   - `PUT /v2/apps/team/service/manifests/1.0.0` (Content-Type = OCI image manifest; body = manifest bytes)
     → **201**, `Docker-Content-Digest: <manifestDigest1>`.
2. **Pull & verify**:
   - `GET /v2/apps/team/service/manifests/1.0.0` → **200**, body == pushed manifest bytes,
     `Docker-Content-Digest == <manifestDigest1>`, `Content-Type` == the stored media type.
   - `GET /v2/apps/team/service/blobs/<configDigest>` (and each layer) → **200**, body == pushed bytes,
     `Docker-Content-Digest` matches.
   - `GET /v2/apps/team/service/manifests/<manifestDigest1>` → same bytes as the by-tag pull.
3. **Tags**: `GET /v2/apps/team/service/tags/list` → **200**, `tags` contains `1.0.0`.
4. **Re-tag**: push image #2 to `manifests/1.0.0` → **201**, `<manifestDigest2>`; then
   `GET …/manifests/1.0.0` → `<manifestDigest2>`; `GET …/manifests/<manifestDigest1>` → **200** (old digest
   still retrievable).
5. **Delete**: `DELETE …/manifests/1.0.0` → **202**; `GET …/manifests/1.0.0` → **404**.
   `DELETE …/manifests/<manifestDigest2>` → **202**; `GET …/manifests/<manifestDigest2>` → **404**.
6. **Isolation**: `GET /v2/<otherHostedRepo>/team/service/manifests/<manifestDigest1>` → **404**.
7. **Proxy is read-only**: `POST /v2/mirror/team/service/blobs/uploads/?digest=…` → **405** (unsupported).

## Proxy round-trip sequence (US2) — client → server → upstream stub

Repo `mirror` (proxy, `format = CONTAINER`, `remoteUrl` = stub base URL), image `library/demo`.

1. **Cache miss**: `GET /v2/mirror/library/demo/manifests/1.0` → server fetches from the stub, returns
   **200** with the stub's manifest bytes and digest, and caches them; each referenced blob
   `GET /v2/mirror/library/demo/blobs/<digest>` → **200**, cached.
2. **Cache hit (upstream down)**: stop the stub; `GET /v2/mirror/library/demo/manifests/<digest>` and the
   blob `GET`s → **200**, byte-identical from the local cache (no upstream contact).

## OCI upstream stub contract (`OciStubUpstream`, in-JVM)

A JDK `HttpServer` on an ephemeral `127.0.0.1` port, mirroring the Maven `StubUpstream`. Serves the Docker
Registry V2 **read** surface with no auth (answers 200 directly, so no bearer challenge):

| Method | Path | Response |
|--------|------|----------|
| GET | `/v2/` | **200** `{}`, `Docker-Distribution-API-Version: registry/2.0` |
| GET | `/v2/{name}/manifests/{ref}` | **200**, body = seeded manifest bytes, `Content-Type` = seeded media type, `Docker-Content-Digest` = its digest; **404** if unseeded |
| GET | `/v2/{name}/blobs/{digest}` | **200**, body = seeded blob bytes, `Docker-Content-Digest` = digest; **404** if unseeded |
| GET | `/v2/{name}/tags/list` | **200** `{"name":"{name}","tags":[…]}` |

Helpers: `seed(name, ref, manifestBytes, mediaType)`, `seedBlob(name, digest, bytes)`, `start()`, `stop()`,
`baseUrl`. Resolving a tag returns the seeded manifest; resolving by digest returns the seeded bytes. `stop()`
lets a test prove the proxy cache serves without the upstream.

## Optional real-client contract (`ContainerDockerClientIT`, gated)

When `docker info` succeeds, the IT runs a real `docker pull` of a small public/base image, `docker tag` to
`127.0.0.1:<serverPort>/apps/<name>:<tag>`, `docker push`, then `docker rmi` + `docker pull` back and
inspects the digest — proving a genuine client round-trips through the hosted registry. When no daemon is
available (`assumeTrue` fails), the test is **skipped**, never failed, so the core gate stays green offline.
