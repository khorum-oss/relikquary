# Phase 1 Data Model: Container Registry Integration & Round-Trip Verification

**No persistence change.** This feature adds tests; it introduces no entity, table, or changeset. The
"model" here is the **fixture set** each round-trip pushes and the identity relationships the tests assert
over what feature 018 already stores (`ContainerManifest`, `ContainerTag`, blob/manifest bytes in
`ContainerStorage`).

## Round-trip fixture set

An image fixture built in-test, every part addressed by its real `sha256:<hex>` digest:

| Part | Content (illustrative) | Addressed by |
|------|------------------------|--------------|
| Config blob | small JSON, e.g. `{"architecture":"amd64","os":"linux"}` | `sha256(config bytes)` |
| Layer blob A | distinct bytes | `sha256(layer A)` |
| Layer blob B | distinct bytes (≠ A) | `sha256(layer B)` |
| Image manifest | OCI image manifest referencing the config + layers A,B with their sizes | `sha256(manifest bytes)` |
| Second image | a different config/layer set → a different manifest digest | its own digest |

The digest of each part is computed in the test with SHA-256 and is the identity the server must echo.

## Identity relationships asserted

- **Faithful pull**: `pull(manifest, tag) == pushed manifest bytes` and `digest == sha256(pushed bytes)`;
  likewise every `pull(blob, digest)` returns the pushed bytes with a matching `Docker-Content-Digest`.
- **Tag → digest (mutable)**: after pushing image #1 to tag `t`, `resolve(t) == digest(#1)`; after pushing
  image #2 to the same tag `t`, `resolve(t) == digest(#2)` while `pull(manifest, digest(#1))` still returns
  image #1's bytes (old digest retained).
- **Digest (immutable)**: `pull(manifest, digest)` returns the same bytes as `pull(manifest, tag)` when the
  tag points at that digest.
- **Deletion**: after `delete(tag)` → `resolve(tag)` is 404; after `delete(manifest, digest)` →
  `pull(manifest, digest)` is 404.
- **Cross-repo isolation**: an image pushed to hosted repo `apps` is not retrievable under a different repo's
  path.
- **Proxy pull-through**: `pull(proxy, tag)` on a cold cache returns the upstream stub's bytes/digests and
  populates the local cache; a subsequent `pull(proxy, digest)` with the stub stopped returns the same bytes.
- **Proxy is read-only**: any push verb against the proxy repo returns 405 (unsupported).

## Test wiring (not persistence)

- **Storage**: real filesystem backend rooted at a `@TempDir`, via `DynamicPropertySource`.
- **Repositories**: `application-container-it.yml` declares hosted `apps` (+ a second hosted repo for the
  isolation check) and proxy `mirror`; `mirror.remoteUrl` is filled from the running `OciStubUpstream`'s base
  URL via `DynamicPropertySource`.
- **Upstream**: `OciStubUpstream` (JDK `HttpServer`) seeded with the same fixture set, serving the Docker
  Registry V2 read endpoints; `stop()` proves the cache-hit path is upstream-independent.
