---
description: "Task list for Container Registry Integration & Round-Trip Verification"
---

# Tasks: Container Registry Integration & Round-Trip Verification

**Input**: Design documents from `specs/021-container-registry-roundtrip/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/roundtrip-harness.md

**Tests**: This feature *is* tests — every implementation task produces integration coverage.

**Organization**: Grouped by user story. US1 (hosted push→pull→delete round-trip) is the MVP and the missing
coverage that matters most; US2 (proxy pull-through) reuses the same harness style.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 (setup, foundational, polish carry no story label)

## Path Conventions

Backend module: tests at `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/`, test config at
`backend/src/test/resources/` (per plan.md Structure Decision). No `main` source change is expected.

---

## Phase 1: Setup

- [x] T001 Add the test config profile `backend/src/test/resources/application-container-it.yml` declaring a hosted container repo `apps`, a second hosted container repo `vault` (for cross-repo isolation), and a proxy container repo `mirror` with `remoteUrl: ${TEST_OCI_UPSTREAM:}` and `relikquary.security.enabled=false`

## Phase 2: Foundational (blocking prerequisites for both stories)

- [x] T002 [P] Create `OciStubUpstream` (JDK `com.sun.net.httpserver.HttpServer` on an ephemeral 127.0.0.1 port, mirroring `StubUpstream`) serving the Docker Registry V2 read surface — `GET /v2/` (200 + `Docker-Distribution-API-Version`), `GET /v2/{name}/manifests/{ref}`, `GET /v2/{name}/blobs/{digest}`, `GET /v2/{name}/tags/list` — with `seed`/`seedBlob`/`start`/`stop`/`baseUrl` helpers, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/OciStubUpstream.kt`
- [x] T003 [P] Add a small shared image-fixture + push/pull helper set (build config + two layer blobs + an OCI image manifest, compute SHA-256 digests, monolithic blob upload via `POST …/blobs/uploads/?digest=`, manifest `PUT`, manifest/blob/tags `GET`, `DELETE`) — either as private helpers in the US1 test or a shared `ContainerRoundTripSupport` object in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/`

**Checkpoint**: the stub and the client helpers exist; the round-trip tests can be written against them.

## Phase 3: User Story 1 — Hosted push→pull→delete round-trip (Priority: P1) 🎯 MVP

**Goal**: Prove a hosted container repo accepts a docker-style push, serves byte-and-digest-identical
content back, lists tags, re-points a mutable tag while retaining the old digest, deletes a tag and a
manifest by digest, isolates across repos, and rejects push to a proxy.

**Independent Test**: `gradle :backend:test --tests '*ContainerRegistryRoundTripTest'` passes with no
network, asserting byte/digest identity and the tag/delete/isolation/proxy-reject outcomes.

- [x] T004 [US1] Create `ContainerRegistryRoundTripTest` (`@SpringBootTest` RANDOM_PORT, `application-container-it` profile, `@TempDir` filesystem storage via `@DynamicPropertySource`) that pushes image #1 to hosted `apps` (`team/service:1.0.0`) and asserts the manifest + every blob pull back byte-identical with matching `Docker-Content-Digest`, including a by-digest manifest pull equalling the by-tag pull, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerRegistryRoundTripTest.kt`
- [x] T005 [US1] In `ContainerRegistryRoundTripTest`, assert `GET …/tags/list` includes `1.0.0`; then push image #2 to the same tag and assert the tag resolves to `<manifestDigest2>` while `GET …/manifests/<manifestDigest1>` still returns image #1 (mutable tag, immutable digest)
- [x] T006 [US1] In `ContainerRegistryRoundTripTest`, assert `DELETE …/manifests/1.0.0` → 202 then `GET` → 404, and `DELETE …/manifests/<digest>` → 202 then `GET` → 404; assert cross-repo isolation (`GET /v2/vault/team/service/manifests/<manifestDigest1>` → 404); assert `POST /v2/mirror/team/service/blobs/uploads/?digest=…` → 405 (proxy is read-only)

**Checkpoint**: US1 is the shippable MVP — the hosted serving/publish path is proven end to end.

## Phase 4: User Story 2 — Proxy pull-through round-trip (Priority: P2)

**Goal**: Prove a proxy container repo resolves an uncached pull from the upstream and caches it, then
serves the same digest from local cache with the upstream stopped — byte-for-byte.

**Independent Test**: `gradle :backend:test --tests '*ContainerProxyRoundTripTest'` passes offline, driving
the proxy against the in-JVM `OciStubUpstream`.

- [x] T007 [US2] Create `ContainerProxyRoundTripTest` (`@SpringBootTest` RANDOM_PORT, `application-container-it` profile; start `OciStubUpstream` and publish its `baseUrl` as `TEST_OCI_UPSTREAM` via `@DynamicPropertySource`; seed it with a `library/demo:1.0` image) that pulls the uncached tag through proxy `mirror` and asserts the manifest + blobs are served with the stub's digests, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerProxyRoundTripTest.kt`
- [x] T008 [US2] In `ContainerProxyRoundTripTest`, `stop()` the stub, then pull the same manifest and blob digests again through `mirror` and assert byte-identical content served from the local cache (no upstream contact)

**Checkpoint**: both serving paths — hosted and proxy — are proven end to end.

## Phase 5: Optional real-client gold standard

- [x] T009 [P] Create `ContainerDockerClientIT` gated with `assumeTrue` on Docker-daemon availability (`docker info` succeeds): `docker pull` a small image, `docker tag` → `127.0.0.1:<serverPort>/apps/<name>:<tag>`, `docker push`, `docker rmi`, `docker pull` back, and assert the digest matches; SKIPS cleanly when no daemon is present, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerDockerClientIT.kt`

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T010 [P] Run `gradle :backend:detekt` and resolve any violations in the new test code (Principle III)
- [x] T011 Run `gradle :backend:test --tests '*ContainerRegistryRoundTripTest' --tests '*ContainerProxyRoundTripTest' --tests '*ContainerDockerClientIT'` and confirm the two core tests pass and the docker IT skips (no daemon here)
- [x] T012 [P] Confirm no `main` source or `VERSION` change was needed (verification-only); if a genuine defect was uncovered, document the fix in the commit and keep the suite green

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T003)** must complete before any story phase.
- **US1 (T004–T006)** depends on Foundational (T003 helpers). This is the MVP — stop here for the headline
  coverage.
- **US2 (T007–T008)** depends on Foundational (T002 stub + T003 helpers); independent of US1.
- **Optional (T009)** depends only on the running server pattern; independent of US1/US2.
- **Polish (T010–T012)** runs last.
- Parallelizable: T002 ∥ T003 (different files); T009 is independent of the core tests; T010 detekt ∥ other
  polish.

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — the missing hosted round-trip, hermetic and gate-enforced.
- **Increment 2**: Phase 4 (US2) — proxy pull-through.
- **Gold standard**: Phase 5 (US2 optional) — real docker client where a daemon exists.
- **Harden**: Phase 6 — detekt green, confirm verification-only (no `VERSION`/`main` change).
