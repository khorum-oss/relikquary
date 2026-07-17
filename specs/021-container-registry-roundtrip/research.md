# Phase 0 Research: Container Registry Integration & Round-Trip Verification

Decisions were resolved from the existing feature-018 code, the existing Maven proxy test harness
(`StubUpstream`), and a probe of this environment's tooling. No open NEEDS CLARIFICATION remained.

## D1. What drives the round-trip — real client vs. protocol-faithful stand-in

- **Decision**: The **core, gate-enforced** round-trips use a protocol-faithful in-JVM client (JDK
  `HttpClient`) performing the exact OCI `/v2` request sequence. A **separate, optional** IT
  (`ContainerDockerClientIT`) drives a **real `docker`** push/pull and is gated with `assumeTrue` on Docker
  daemon availability, so it runs where a daemon exists and skips cleanly where it does not.
- **Rationale**: A probe found the `docker` binary present but **no daemon** (`/var/run/docker.sock`
  absent), and the Maven suite's real-client tests already show that hard-requiring an external toolchain is
  fragile in constrained environments. The protocol-faithful client exercises the identical byte sequence a
  real client would, keeping the guarantee hermetic and always-on (SC-004/FR-009); the gated docker IT adds
  the real-client gold standard without ever blocking the gate.
- **Alternatives considered**: (a) Require a real docker daemon for the core test — rejected: non-hermetic,
  skips entirely where no daemon (this environment), violating "never silently degrade to no coverage".
  (b) Testcontainers running a docker-client image — rejected: needs a working Docker environment anyway and
  adds a heavy dependency for no extra fidelity over the gated IT.

## D2. The proxy upstream — in-JVM stub vs. live Docker Hub

- **Decision**: A new `OciStubUpstream` built on the JDK `com.sun.net.httpserver.HttpServer` (exactly as the
  existing Maven `StubUpstream`), serving the Docker Registry V2 endpoints the proxy calls: `GET /v2/`
  (200), `GET /v2/{name}/manifests/{ref}`, `GET /v2/{name}/blobs/{digest}`, `GET /v2/{name}/tags/list`. It
  returns content directly with no `401`, so no bearer-token dance is needed.
- **Rationale**: `ContainerUpstreamClient` requests `"{base}/v2/{name}/manifests|blobs/…"` and only performs
  the token handshake when the upstream answers `401` with a `WWW-Authenticate: Bearer` challenge; a stub
  that answers `200` directly is served verbatim. An in-JVM stub makes the proxy round-trip deterministic and
  offline (FR-010/SC-004), mirroring how the Maven proxy tests already work — and it can be **stopped** to
  prove the cache-hit path is served without the upstream.
- **Alternatives considered**: Point the proxy at real `registry-1.docker.io` — rejected for the core suite
  (network-dependent, rate-limited, non-deterministic); a live-Hub variant, if ever added, would be a
  separately gated IT per the spec.

## D3. Wiring the proxy's upstream URL into the test

- **Decision**: A dedicated `application-container-it.yml` test profile declares a hosted container repo
  (`apps`) and a proxy container repo (`mirror`) whose `remoteUrl` is a placeholder
  (`${TEST_OCI_UPSTREAM:}`) filled at runtime by `@DynamicPropertySource` with the stub's random base URL.
  Storage root is likewise a `@TempDir` set via `DynamicPropertySource`; security is disabled to focus on the
  wire round-trip (authorization is already covered by feature 007's tests).
- **Rationale**: The stub binds an ephemeral port, so its URL is only known at runtime — `DynamicPropertySource`
  is the established mechanism in this suite for exactly this. A profile keeps the two container repos out of
  the default application config used by other tests.
- **Alternatives considered**: Setting an indexed `relikquary.repositories[N].remoteUrl` property directly —
  rejected as brittle (couples the test to list ordering); a named profile is clearer and self-contained.

## D4. Exercising the push, pull, re-tag, and delete sequence

- **Decision**: The hosted client uses **monolithic blob uploads** (`POST …/blobs/uploads/?digest=<d>` with
  the blob body) then a manifest `PUT …/manifests/<tag>`; it pulls via `GET …/manifests/<ref>` and
  `GET …/blobs/<digest>`, lists tags via `GET …/tags/list`, re-tags by pushing a second image to the same
  tag, and deletes via `DELETE …/manifests/<tag>` and `DELETE …/manifests/<digest>`. Digests are computed in
  the test with `MessageDigest` (SHA-256) and compared to the server's `Docker-Content-Digest` header and the
  returned bytes.
- **Rationale**: Monolithic upload is the simplest complete push and is already supported by
  `ContainerHostedEndpoints.startUpload` (`?digest=` finalizes immediately); it needs no chunk bookkeeping.
  The sequence maps one-to-one onto the acceptance scenarios (FR-002…FR-006) and asserts faithful bytes and
  digests at each pull (FR-008).
- **Alternatives considered**: Chunked uploads (`POST` → `PATCH` → `PUT`) — deferred; monolithic proves the
  store/serve fidelity that is the point, and chunked bookkeeping is a separate concern already unit-tested.

## D5. Fixtures — realistic multi-blob image, both single-platform and referenced-by-digest

- **Decision**: Build a small but realistic image fixture in-test: a JSON config blob and **two** distinct
  layer blobs, plus an OCI image manifest referencing them, all keyed by their real SHA-256 digests. Assert a
  by-digest manifest pull returns the same bytes as the by-tag pull (edge case), and that a second hosted
  repo cannot retrieve the first repo's image (cross-repo isolation).
- **Rationale**: More than one layer guards against single-blob happy-path bias (spec edge case); by-digest
  and cross-repo checks pin the immutability and namespacing guarantees cheaply.
- **Alternatives considered**: A single trivial blob — rejected as too weak to prove multi-blob fidelity.
