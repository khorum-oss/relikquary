# Implementation Plan: Container (OCI / Docker) Registry — Hosted Storage & Docker Hub Pull-Through

**Branch**: `018-container-registry` | **Date**: 2026-07-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/018-container-registry/spec.md`

## Summary

Add a container (OCI / Docker Registry V2) surface to Relikquary so that unmodified `docker` / `podman`
/ `nerdctl` clients can pull and push images through it, in the same two kinds the Maven side already
supports: **HOSTED** (accepts `docker push`, serves `docker pull`) and **PROXY** (a read-only
pull-through cache of Docker Hub). A repository gains a new **`format`** dimension (`MAVEN` default |
`CONTAINER`) alongside its existing `kind`; container repos reuse the existing `ArtifactStorage`
(filesystem / S3), per-repository authorization (feature 007), request logging + metrics (feature 010),
and the `relikquary.repositories[]` config surface. A dedicated `/v2/**` controller implements the V2
protocol (version check, blob GET/HEAD, monolithic + chunked blob uploads, manifest GET/HEAD/PUT/DELETE
by tag or digest, tag listing); blobs and manifests are stored **content-addressably by `sha256`
digest** with bytes preserved exactly; tag→digest pointers, manifest descriptors (media type/size), and
in-progress upload sessions live in the existing Postgres/JPA persistence layer via a new Liquibase
changeset. Serving-side auth reuses the existing **HTTP Basic** path (`docker login` → Basic); only the
**proxy→Docker Hub** leg performs the Bearer-token handshake (`401` + `WWW-Authenticate: Bearer` → scoped
pull token from `auth.docker.io` → retry with `Authorization: Bearer`), with `library/` normalization
for official images.

## Technical Context

**Language/Version**: Kotlin on the JDK 21 toolchain (unchanged). Build logic in Gradle Kotlin DSL.

**Primary Dependencies**: Spring Boot Web + Security (existing), Spring Data JPA + Liquibase +
PostgreSQL (existing persistence), Jackson Kotlin module (existing — manifest JSON parsing), the JDK
`java.net.http.HttpClient` (existing pattern in `UpstreamClient` — reused for the OCI upstream client).
**No new production dependency anticipated.** Test-only: a `registry:2` image driven through
Testcontainers `GenericContainer` (from the already-present `testcontainers-junit`) as the deterministic
stub upstream, plus the existing MinIO/Postgres Testcontainers.

**Storage**: Existing `ArtifactStorage` (filesystem + S3), namespaced per repo. Container objects use a
reserved sub-namespace so they never collide with Maven paths:
`"{repo}/_container/blobs/sha256/{hex}"` and `"{repo}/_container/manifests/sha256/{hex}"`. Relational
state (tag→digest, manifest descriptors, blob upload sessions) uses the existing Postgres/JPA layer via a
new Liquibase changeset.

**Testing**: `@SpringBootTest` + Testcontainers with real client round-trips (Principle II), adapted from
Maven clients to OCI clients:
- **Hosted push/pull**: drive the raw V2 wire with a real HTTP test client (push blobs+manifest, pull
  back, verify digests) against a real storage backend; plus a guarded end-to-end `docker`/`skopeo` CLI
  round-trip that auto-skips when the CLI/daemon is unavailable.
- **Proxy pull-through**: a local `registry:2` Testcontainer seeded with a small image as the stub
  upstream (offline, CI-safe, real client through the proxy), plus a guarded real Docker Hub pull that
  auto-skips when offline — mirroring the stub + guarded split in spec 006 and the s3mock + MinIO split
  in spec 003.
- **Storage parity**: the hosted round-trip runs against both filesystem and MinIO (S3) backends.

**Target Platform**: Linux server (Spring Boot), same as the existing app. Clients: Docker/OCI CLIs.

**Project Type**: Web service (backend module) — no new Gradle module; container code is a new package
set inside `backend`, exactly as the proxy/group feature was added inside `backend`.

**Performance Goals**: N/A beyond "a real `docker pull`/`push` completes and streams". Blob transfer
streams (no full-buffering of layers); the tee-cache pattern from feature 015 is reused for proxy blob
streaming so a large layer is served while it caches.

**Constraints**: Digest bytes MUST be preserved exactly (Principle IV) — no re-encoding of manifests or
blobs, since any byte change breaks the `sha256` digest the client verifies. Manifest media type MUST be
returned exactly as stored (clients match on `Content-Type`). The `/v2/**` routes MUST NOT disturb the
Maven `/**` routes (Spring's most-specific-match makes `/v2/**` win). Upstream credentials and tokens
MUST never reach resolving clients.

**Scale/Scope**: One backend package set (`container/…`), one new controller, one OCI upstream client,
one storage-key scheme, ~3 new JPA entities + one Liquibase changeset, config `format` field + registry
validation, small edits to `RepositoryAuthorizationManager` and `RequestLoggingFilter` (recognize the
`/v2/{repo}` shape), README + deploy docs. Single self-hosted server; multi-arch images supported.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). This adds a NEW public contract
  surface (the OCI Distribution `/v2/` API) without changing or removing the Maven layout, resolution/
  publish protocol, or any existing configuration key. The new `format` config field defaults to `MAVEN`,
  so existing configs are unchanged. Adding a capability (not breaking one) implies a **MINOR** `VERSION`
  bump (1.0.0 → 1.1.0), not MAJOR.
- **II. Test-First & Integration-Verified Discipline** — PASS (adapted). Correctness is proven by REAL
  round-trips: a real container client pushes and pulls against a hosted repo (bytes/digests verified),
  and a real client pulls through the proxy against a real upstream (a `registry:2` stub by default, a
  guarded real Docker Hub pull additionally). Storage is exercised against both real backends (filesystem
  + MinIO) via `@SpringBootTest` + `DynamicPropertySource`, never mocked. Tests are written Red → Green.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin is covered by Kover (round-trip + unit
  tests) and satisfies detekt; nothing exempt without an annotated justification. No gate weakened. If a
  test-only dependency is added it extends `gradle/verification-metadata.xml` rather than disabling
  verification.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. Container blobs/manifests are stored and
  served byte-for-byte, addressed by their own `sha256` digest, which the feature verifies on ingest and
  on cache; nothing is re-checksummed or re-encoded. Upstream (Docker Hub) credentials/tokens are
  supplied via config/env, never committed, never exposed to clients.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/018-container-registry/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — entities, storage-key scheme, JPA/Liquibase tables
├── quickstart.md        # Phase 1 — runnable validation guide (docker pull/push round-trips)
├── contracts/
│   └── container-registry-api.md   # the /v2 endpoint contract + Docker Hub proxy contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── container/
│   ├── ContainerRegistryController.kt   # @RequestMapping("/v2") — version check, blobs, uploads,
│   │                                     #   manifests, tags; dispatches hosted vs proxy by repo format+kind
│   ├── ImageReference.kt                 # parse/validate `/v2/{repo}/{name}/{op}/{ref}`; name+tag/digest grammar
│   ├── Digest.kt                         # sha256 digest value type; compute + verify against bytes
│   ├── ContainerStorage.kt               # blob/manifest key scheme over ArtifactStorage; digest-verified writes
│   ├── ManifestService.kt                # hosted manifest PUT/GET/DELETE; blob-reference existence checks
│   ├── BlobUploadService.kt              # monolithic + chunked upload sessions; finalize with digest verify
│   ├── TagService.kt                     # tag→digest pointers + tag listing (JPA)
│   ├── proxy/
│   │   ├── ContainerUpstreamClient.kt    # OCI upstream fetch + Bearer-token handshake; library/ normalization
│   │   └── ContainerProxyService.kt      # pull-through: resolve tag upstream, cache blobs/manifests by digest
│   └── persistence/
│       ├── ContainerTag.kt / ContainerTagRepository.kt          # (repo, image, tag) -> manifest digest
│       ├── ContainerManifest.kt / ...Repository.kt              # (repo, digest) -> media type, size
│       └── BlobUpload.kt / BlobUploadRepository.kt              # in-progress upload session state
├── config/RepositoryProperties.kt        # + `format: RepositoryFormat` field (MAVEN default | CONTAINER)
├── repository/RepositoryFormat.kt        # new enum: MAVEN | CONTAINER
├── repository/RepositoryRegistry.kt      # validate container repos (proxy needs upstream; group+container rejected)
├── security/RepositoryAuthorizationManager.kt  # recognize `/v2/{repo}/…` → READ (GET/HEAD) / PUBLISH (PUT/POST/PATCH) / DELETE
└── observability/logging/RequestLoggingFilter.kt  # reserve `v2`; report the container repo name (segment after /v2)

backend/src/main/resources/db/changelog/
├── 002-container-registry.xml            # container_tag, container_manifest, blob_upload tables
└── db.changelog-master.yaml              # include the new changeset

backend/src/test/kotlin/org/khorum/oss/relikquary/container/
├── ContainerHostedRoundTripIT.kt         # push→pull an image; digests match; fs + MinIO
├── ContainerProxyIT.kt                   # pull through proxy against a registry:2 stub upstream; cache hit
├── ContainerProxyDockerHubIT.kt          # guarded real Docker Hub pull (auto-skip offline)
├── ContainerAuthIT.kt                    # role-gated push rejected anonymously, succeeds authenticated
├── ImageReferenceTest.kt / DigestTest.kt # unit: parsing/grammar, digest compute+verify
└── ContainerErrorsIT.kt                  # digest mismatch, unknown blob, proxy 404 vs 502, push-to-proxy 405

VERSION                                   # 1.0.0 → 1.1.0 (additive capability)
README.md / deploy/README.md              # "Container repositories" section: config, docker login, pull/push
```

**Structure Decision**: Pure backend addition — no new Gradle module, no `settings.gradle.kts` edit. All
new code lives under a `container/` package in `backend`, with small, surgical edits to the config,
registry validation, authorization manager, and request-logging filter to recognize the `/v2/{repo}`
shape. This mirrors how proxy/group (feature 006) were added inside `backend` rather than as new modules.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contracts, quickstart): unchanged — PASS on all four
principles, no new violations. The design introduces no gate bypass, preserves stored bytes/digests
exactly, keeps the Maven contract untouched (new surface is additive and default-off via `format:
MAVEN`), and proves itself with real client + real storage round-trips.
