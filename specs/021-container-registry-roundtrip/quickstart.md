# Quickstart: Container Registry Integration & Round-Trip Verification

How to run and read the new container round-trip coverage.

## Prerequisites

- The backend module builds (`gradle :backend:compileTestKotlin`).
- No external services required for the core suite — it is hermetic (real filesystem `@TempDir`, in-JVM
  upstream stub). A Docker daemon is required only for the optional real-client IT.

## Run the core round-trips

```sh
# Hosted push→pull→tags→re-tag→delete + proxy-push rejection
gradle :backend:test --tests '*ContainerRegistryRoundTripTest'

# Proxy pull-through: cache miss (from the in-JVM upstream stub) → cache hit (stub stopped)
gradle :backend:test --tests '*ContainerProxyRoundTripTest'
```

**Expected**: both pass with no network access. Every pull asserts byte-and-digest identity; the re-tag test
shows the tag moving to the new digest while the old digest still resolves; the delete test shows 404 after
deletion; the proxy test serves the second pull from cache with the stub stopped.

## Run the optional real-`docker` IT

```sh
# Runs only if a Docker daemon is reachable; otherwise it is SKIPPED (not failed).
gradle :backend:test --tests '*ContainerDockerClientIT'
```

**Expected where a daemon exists**: a real `docker push` to `127.0.0.1:<port>/apps/…` followed by a
`docker pull` back returns the same digest. **In this environment** (no `/var/run/docker.sock`): reported as
skipped.

## Run the whole gate

```sh
gradle :backend:test :backend:detekt
```

**Expected**: the container round-trips run alongside the Maven round-trips in the same gate; a future
regression in the container serving/publish path now fails the build (SC-005). detekt reports zero
violations on the new test code.

## What each scenario proves

| Test | Proves |
|------|--------|
| `ContainerRegistryRoundTripTest` | hosted push→pull faithful bytes/digests; tags list; mutable re-tag with old-digest retention; tag + by-digest delete; cross-repo isolation; proxy push → 405 (FR-002…FR-006, FR-008) |
| `ContainerProxyRoundTripTest` | proxy pull-through miss→hit with faithful bytes/digests, cache serves without the upstream (FR-007, FR-010) |
| `ContainerDockerClientIT` (gated) | a real container client round-trips through the hosted registry when a daemon is present (FR-009) |

## Notes on faithful storage (Principle IV)

Every assertion compares the **exact bytes** pulled to the exact bytes pushed and the server's
`Docker-Content-Digest` to a locally computed SHA-256 — so any silent alteration or re-checksumming of stored
content fails the suite.
