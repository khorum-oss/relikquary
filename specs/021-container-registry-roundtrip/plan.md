# Implementation Plan: Container Registry Integration & Round-Trip Verification

**Branch**: `021-container-registry-roundtrip` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/021-container-registry-roundtrip/spec.md`

## Summary

Close feature 018's integration-coverage gap: prove the container registry round-trips end to end against a
real running server with real storage. Two `@SpringBootTest` round-trips do the work. **Hosted** (US1): a
protocol-faithful client (JDK `HttpClient`) performs the exact OCI `/v2` push sequence — monolithic blob
uploads then a manifest `PUT` under a tag — pulls the manifest and every blob back and asserts byte-and-
digest identity, lists tags, re-pushes a different image to the same tag and confirms the tag re-points
while the old digest stays retrievable, deletes a tag and a manifest by digest, and confirms a push to a
**proxy** repo is rejected (405). **Proxy** (US2): a new in-JVM `OciStubUpstream` (JDK `HttpServer`,
mirroring the existing `StubUpstream`) serves a fixed image; the proxy resolves an uncached pull from it and
caches the bytes, then serves the same digest from local cache with the upstream stopped — byte-for-byte. An
optional, daemon-gated real-`docker` push/pull IT (`assumeTrue(docker available)`) provides the gold-standard
real-client check where a Docker daemon exists and skips cleanly where it does not (as in this environment).
Verification-only: no product behavior, wire protocol, or configuration changes; a defect uncovered by the
new coverage would be fixed so the suite ends green.

## Technical Context

**Language/Version**: Backend test code — Kotlin on the JDK 21 toolchain (unchanged). No frontend change.

**Primary Dependencies**: Test-only, all already present — JUnit 5, `@SpringBootTest` + `DynamicPropertySource`,
the JDK `java.net.http.HttpClient` (client) and `com.sun.net.httpserver.HttpServer` (the upstream stub, as
`StubUpstream` already uses), Jackson for asserting manifest/tag JSON. **No new dependency** (production or
test), so `gradle/verification-metadata.xml` is untouched.

**Storage**: The real filesystem backend against a real `@TempDir` (never mocked), wired via
`DynamicPropertySource` — the standard round-trip pattern in this suite. The S3-compatible backend is a
secondary, environment-gated parity scenario, not part of the core.

**Testing**: This feature *is* testing. Real `@SpringBootTest` (RANDOM_PORT) round-trips against the real
server + real storage + real datastore; a real in-JVM OCI upstream for the proxy path; an optional real
`docker` client gated on daemon availability.

**Target Platform**: The existing Spring Boot backend serving the container registry under `/v2`.

**Project Type**: Test/verification addition to the existing `backend` module — no new module, no `main`
source change (barring a bug fix if the coverage uncovers one).

**Performance Goals**: N/A. Each round-trip pushes a few small blobs and a manifest; wall-clock is dominated
by Spring context startup, shared with the rest of the integration suite.

**Constraints**: Verification-only and hermetic (SC-004) — the core suite needs no live external registry;
the proxy path uses the in-JVM stub. The real-`docker` IT is opt-in via daemon detection and never blocks
the gate. No product wire/behavior/config change; `VERSION` is **unchanged** (a test addition is not a
released capability).

**Scale/Scope**: Two new integration test classes, one in-JVM OCI upstream stub, one test-resources config
profile defining a hosted + a proxy container repo, and one optional daemon-gated docker IT. No `main` code.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (no product change). No change to the container
  (`/v2`) or Maven wire protocol, repository layout, or any configuration key; the new file under
  `src/test/resources` is a test profile, not a shipped config contract. Because no capability is added or
  changed, **`VERSION` is not bumped** (a test-only change is not a release).
- **II. Test-First & Integration-Verified Discipline** — PASS (this feature is the embodiment). It adds the
  real push→pull round-trip the discipline requires of every serving/publish path, proven against a real
  server + real filesystem storage + real datastore, with a real in-JVM upstream for the proxy and an
  optional real `docker` client — no mocked store, no mocked server. It brings the container path up to the
  Maven features' standard rather than weakening any rule.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin test code satisfies detekt (zero violations);
  no gate is disabled or weakened. Coverage of the container serving path increases. No dependency added, so
  `gradle/verification-metadata.xml` is unchanged.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS (directly asserted). The round-trips assert exact
  byte-and-digest identity of manifests and blobs on pull and after a re-tag, which is precisely the faithful-
  storage guarantee this principle demands; nothing stored is altered by the tests.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/021-container-registry-roundtrip/
├── plan.md              # This file
├── research.md          # Phase 0 — client & upstream-stub decisions
├── data-model.md        # Phase 1 — the round-trip fixture set (derived; no persistence change)
├── quickstart.md        # Phase 1 — how to run the verification
├── contracts/
│   └── roundtrip-harness.md        # the request sequences under test + the OCI upstream-stub contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
├── OciStubUpstream.kt                   # in-JVM OCI /v2 upstream (JDK HttpServer): version, manifests, blobs, tags
├── ContainerRegistryRoundTripTest.kt    # US1: hosted push→pull→tags→re-tag→delete; proxy-push rejected (405)
├── ContainerProxyRoundTripTest.kt       # US2: proxy pull-through miss (from stub) → hit (from cache, stub stopped)
└── ContainerDockerClientIT.kt           # OPTIONAL: real `docker` push/pull, gated on daemon availability (skips if absent)

backend/src/test/resources/
└── application-container-it.yml         # hosted 'apps' + proxy 'mirror' container repos; proxy remoteUrl via DynamicPropertySource

# No main source change (a defect fix, if any surfaces, would touch backend/src/main and be called out).
# VERSION — unchanged (verification-only).
```

**Structure Decision**: Test-only addition to the existing `backend` module, placed beside the other
integration round-trips in `integration/`. The OCI upstream stub mirrors the existing `StubUpstream`
(JDK `HttpServer`, no dependency) so the proxy round-trip is hermetic and offline-safe, exactly like the
Maven proxy tests. Repository wiring uses a dedicated `application-container-it.yml` profile with the proxy
`remoteUrl` supplied at runtime via `DynamicPropertySource` so it points at the stub's random port.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, harness contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds only test code + a hermetic in-JVM upstream, changes no
product behavior/wire/config (so no `VERSION` bump), asserts faithful byte/digest identity throughout, and
runs in the same automated gate as the Maven round-trips so a future container regression fails the build.
