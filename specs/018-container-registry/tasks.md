---
description: "Task list for the container (OCI/Docker) registry feature"
---

# Tasks: Container (OCI / Docker) Registry — Hosted Storage & Docker Hub Pull-Through

**Input**: Design documents from `specs/018-container-registry/`

**Prerequisites**: spec.md, plan.md, research.md, data-model.md, contracts/container-registry-api.md

**Tests**: REQUIRED. Principle II mandates test-first (Red → Green) and REAL client + REAL storage
round-trips for any change to repository serving, auth, or a storage backend. Container serving is
exactly such a change, so every user story ships with failing tests written before implementation.

## Path Conventions

- Backend main: `backend/src/main/kotlin/org/khorum/oss/relikquary/`; tests:
  `backend/src/test/kotlin/org/khorum/oss/relikquary/`; resources: `backend/src/main/resources/`.
- Container code lives under a new `container/` package; no new Gradle module (per plan.md).
- Dependency pins: `gradle/libs.versions.toml` + `gradle/verification-metadata.xml`.

Abbrev: `.../` = `backend/src/main/kotlin/org/khorum/oss/relikquary/`; `test/.../` =
`backend/src/test/kotlin/org/khorum/oss/relikquary/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The orthogonal `format` axis and version bump that everything else builds on.

- [ ] T001 [P] Add `RepositoryFormat` enum (`MAVEN` | `CONTAINER`) in `.../repository/RepositoryFormat.kt`
- [ ] T002 [P] Add `format: RepositoryFormat = RepositoryFormat.MAVEN` to `RepositoryProperties.Repo` in `.../config/RepositoryProperties.kt` (KDoc: selects Maven layout vs OCI/Docker V2; container reuses `kind`, `remoteUrl`, `remoteUsername/Password`, `access`; `type` ignored for CONTAINER)
- [ ] T003 [P] Bump `VERSION` from `1.0.0` to `1.1.0` (additive capability)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The value types, digest-verified storage, relational data layer, `/v2` controller skeleton,
and cross-cutting recognition shared by BOTH the proxy (US1) and hosted (US2) stories.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Foundational unit tests (write first, must fail)

- [ ] T004 [P] `DigestTest` — compute `sha256` over a stream, verify match/mismatch, parse/reject `sha256:<hex>` grammar, in `test/.../container/DigestTest.kt`
- [ ] T005 [P] `ImageReferenceTest` — parse `/v2/{repo}/{name}/{op}/{ref}` (names with slashes; tag vs digest ref), reject bad name grammar + path traversal, in `test/.../container/ImageReferenceTest.kt`

### Foundational implementation

- [ ] T006 [P] `Digest` value type (compute over `InputStream`, verify against claimed digest, parse/format `sha256:<hex>`) in `.../container/Digest.kt`
- [ ] T007 [P] `ImageReference` parser/validator (repo, imageName, operation, ref) in `.../container/ImageReference.kt`
- [ ] T008 Create Liquibase changeset `backend/src/main/resources/db/changelog/002-container-registry.xml` with tables `container_manifest`, `container_tag`, `blob_upload` (columns + unique/index per data-model.md) and register it in `db/changelog/db.changelog-master.yaml`
- [ ] T009 [P] `ContainerManifest` entity + `ContainerManifestRepository` (unique `(repository,digest)`; index `(repository,imageName)`) in `.../container/persistence/`
- [ ] T010 [P] `ContainerTag` entity + `ContainerTagRepository` (unique `(repository,imageName,tag)`; list by `(repository,imageName)`) in `.../container/persistence/`
- [ ] T011 [P] `BlobUpload` entity + `BlobUploadRepository` (PK `uploadId`; `bytesReceived`, `pendingKey`) in `.../container/persistence/`
- [ ] T012 `ContainerStorage` — blob/manifest key scheme (`{repo}/_container/blobs|manifests/sha256/{hex}`) over `ArtifactStorage`; `exists`, `openRead`, and digest-verified `openWrite`→verify→`commit`/`abort`; in `.../container/ContainerStorage.kt` (depends T006)
- [ ] T013 Extend `RepositoryRegistry` validation: CONTAINER+PROXY defaults `remoteUrl` to Docker Hub when blank; CONTAINER+GROUP rejected as invalid config; in `.../repository/RepositoryRegistry.kt` (depends T001, T002)
- [ ] T014 `ContainerRegistryController` skeleton `@RequestMapping("/v2")`: parse via `ImageReference`, `GET /v2/` version check (200 + `Docker-Distribution-API-Version`), OCI JSON error body helper `{"errors":[…]}` + `@ExceptionHandler`s (NAME_UNKNOWN/NAME_INVALID/UNSUPPORTED/…), dispatch stubs by `(format, kind)`, in `.../container/ContainerRegistryController.kt` (depends T007)
- [ ] T015 Teach `RepositoryAuthorizationManager` the `/v2/{repo}/…` shape → GET/HEAD=READ, POST/PATCH/PUT=PUBLISH, DELETE=DELETE; grant bare `GET /v2/`; in `.../security/RepositoryAuthorizationManager.kt`
- [ ] T016 Teach `RequestLoggingFilter` to reserve `v2` and report the container repo name (segment after `/v2/`) in `.../observability/logging/RequestLoggingFilter.kt`

**Checkpoint**: Foundation ready — the `/v2/` version check answers, storage/data layers exist, auth +
logging recognize `/v2`. US1 and US2 can now proceed (in parallel if staffed).

---

## Phase 3: User Story 1 — Pull public images through a Docker Hub proxy (Priority: P1) 🎯 MVP

**Goal**: `docker pull <host>/dockerhub/library/alpine:3.20` fetches from Docker Hub (bearer handshake,
`library/` normalization), caches by digest, and serves subsequent pulls from cache.

**Independent Test**: With a `kind: PROXY, format: CONTAINER` repo, cold-pull an official image (fetched
upstream, digests verify), then re-pull with the upstream unavailable (served from cache).

### Tests for User Story 1 (write first, must fail) ⚠️

- [ ] T017 [P] [US1] `ContainerUpstreamClientTest` — parse `WWW-Authenticate: Bearer` challenge, request the scoped token, retry with `Bearer`; `library/` normalization; map 200/404/5xx → Found/NotFound/Error (stub HTTP server), in `test/.../container/ContainerUpstreamClientTest.kt`
- [ ] T018 [P] [US1] `ContainerProxyIT` — `registry:2` Testcontainer seeded with a small image as the stub upstream; pull manifest + blob through the proxy; assert digest-identity; second pull is a cache hit (no upstream blob fetch), in `test/.../container/ContainerProxyIT.kt`
- [ ] T019 [P] [US1] `ContainerProxyDockerHubIT` — real `library/alpine` pull through the proxy, `@EnabledIf`-guarded to auto-skip when offline, in `test/.../container/ContainerProxyDockerHubIT.kt`

### Implementation for User Story 1

- [ ] T020 [US1] `ContainerUpstreamClient` — OCI upstream fetch with the Bearer-token handshake, correct `Accept` (Docker v2 + OCI image/index), `library/` normalization, redirect-follow for blob CDNs, optional configured Basic creds; `Found/NotFound/Error`; in `.../container/proxy/ContainerUpstreamClient.kt` (depends T006)
- [ ] T021 [US1] `ContainerProxyService` — resolve tag→digest live upstream; serve cached-by-digest else fetch+cache the manifest/index and blobs (feature-015 tee stream); persist `ContainerManifest` media type from upstream `Content-Type`; 404 (absent) vs 502 (upstream/token error); in `.../container/proxy/ContainerProxyService.kt` (depends T012, T020, T009)
- [ ] T022 [US1] Wire proxy dispatch in `ContainerRegistryController`: GET/HEAD `manifests/{ref}`, GET/HEAD `blobs/{digest}`, GET `tags/list` (live upstream); reject push verbs to a proxy with 405 `UNSUPPORTED`; in `.../container/ContainerRegistryController.kt` (depends T014, T021)

**Checkpoint**: A real `docker pull` through the proxy works end-to-end and caches — US1 is independently
shippable (MVP candidate).

---

## Phase 4: User Story 2 — Push and pull your own images to a hosted repo (Priority: P1)

**Goal**: `docker login` + `docker push <host>/containers/team/app:1.4.0` stores digest-verified
blobs/manifest and records the tag; a clean-daemon `docker pull` returns the identical image.

**Independent Test**: Push a locally built image to a `kind: HOSTED, format: CONTAINER` repo, then pull
it back from a daemon with no local layers; pushed and pulled digests match.

### Tests for User Story 2 (write first, must fail) ⚠️

- [ ] T023 [P] [US2] `ContainerHostedRoundTripIT` — push (both a chunked and a monolithic blob) then pull an image; assert digest equality and byte-identity; parameterized over the **filesystem AND MinIO (S3)** backends via `@DynamicPropertySource`; in `test/.../container/ContainerHostedRoundTripIT.kt`
- [ ] T024 [P] [US2] `ContainerErrorsIT` — finalize with a wrong digest → 400 `DIGEST_INVALID` (nothing stored); manifest referencing an un-uploaded blob → 400 `MANIFEST_BLOB_UNKNOWN` (no tag recorded); GET unknown manifest/blob → 404; in `test/.../container/ContainerErrorsIT.kt`

### Implementation for User Story 2

- [ ] T025 [P] [US2] `BlobUploadService` — `POST /blobs/uploads/` start (+ `mount=`/`from=` short-circuit when the blob exists), `PATCH` chunk (advance `Range`, 416 on bad range), `PUT?digest=` finalize (append, verify digest, promote pending write to blob key, delete session); in `.../container/BlobUploadService.kt` (depends T011, T012)
- [ ] T026 [P] [US2] `TagService` — upsert/list/delete `ContainerTag` rows; `tags/list` payload; in `.../container/TagService.kt` (depends T010)
- [ ] T027 [US2] `ManifestService` — `PUT`: parse image manifest/index, verify referenced config+layer blobs (or sub-manifests) exist else 400 `MANIFEST_BLOB_UNKNOWN`, verify body digest when ref is a digest, store bytes verbatim, upsert `ContainerManifest` + (tag ref) `ContainerTag`; `GET/HEAD` by tag/digest returning exact `mediaType` + `Docker-Content-Digest`; `DELETE`; in `.../container/ManifestService.kt` (depends T012, T009, T026)
- [ ] T028 [US2] Wire hosted dispatch in `ContainerRegistryController`: blob upload endpoints (POST/PATCH/PUT), manifest PUT/GET/HEAD/DELETE, blob GET/HEAD, `tags/list` from DB; in `.../container/ContainerRegistryController.kt` (depends T014, T025, T027, T026)

**Checkpoint**: A real `docker push`→`docker pull` round-trips on both storage backends — US1 and US2 both
work independently.

---

## Phase 5: User Story 3 — Auth / storage / observability parity; Maven unaffected (Priority: P2)

**Goal**: Container pull/push obey the existing per-repo authorization, persist across both storage
backends, appear in request logs/metrics, and don't regress Maven.

**Independent Test**: Auth-enabled, role-gated container repo rejects anonymous push and accepts an
authenticated one; the same image round-trips on filesystem and S3; existing Maven suites still pass.

### Tests for User Story 3 (write first, must fail) ⚠️

- [ ] T029 [P] [US3] `ContainerAuthIT` — `relikquary.security.enabled=true`, repo `access.publish=@PUBLISH`: anonymous `docker push` → 401 with `WWW-Authenticate: Basic`; authenticated publisher AND an `rlq_…` API-token-as-password → success; open-read pull without login; in `test/.../container/ContainerAuthIT.kt`
- [ ] T030 [P] [US3] `ContainerObservabilityIT` (or assert within existing) — a `/v2` pull/push emits a request-log line with the container repo name and increments the container metrics; in `test/.../container/ContainerObservabilityIT.kt`

### Implementation for User Story 3

- [ ] T031 [US3] Verify/adjust end-to-end authorization for container verbs (401 anonymous via the existing Basic entry point, 403 authenticated-but-insufficient) and emit the OCI error body on denies where the controller owns the response; in `.../container/ContainerRegistryController.kt` + `.../security/` as needed (depends T015, T022, T028)
- [ ] T032 [P] [US3] Container metrics — record resolve/publish/cache/upstream outcomes for container repos via `RepositoryMetrics` (extend if a new counter/label is needed) in `.../observability/metrics/RepositoryMetrics.kt` and call sites in `ContainerProxyService`/`ManifestService`/`BlobUploadService`
- [ ] T033 [US3] Confirm S3 parity: `ContainerStorage` keys/list/stream behave identically on MinIO (fix any S3-specific prefix/streaming issue surfaced by T023's MinIO run) in `.../container/ContainerStorage.kt`

**Checkpoint**: All three stories independently functional; Maven behavior unchanged.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T034 [P] README.md — add a "Container repositories" section (config `format: CONTAINER`, `docker login`, hosted push/pull, Docker Hub proxy) 
- [ ] T035 [P] deploy/README.md — container usage + the plain-HTTP `insecure-registries`/TLS caveat
- [ ] T036 Run `specs/018-container-registry/quickstart.md` scenarios A–D against a local `bootRun`; capture outcomes
- [ ] T037 Ensure `./gradlew build` is green: detekt zero violations, Kover thresholds met (annotate any unavoidable exclusion `@ExcludeFromCoverage` with justification — do not lower thresholds)
- [ ] T038 [P] If any test-only dependency was added (e.g. pinning the `registry:2` image), extend `gradle/verification-metadata.xml`; otherwise confirm no verification change is needed

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)** → no deps.
- **Foundational (P2)** → depends on Setup; **BLOCKS all user stories**.
- **US1 (P3)** and **US2 (P4)** → both depend only on Foundational; independent of each other (they wire
  disjoint handlers into the shared controller — coordinate the two edits to `ContainerRegistryController`
  if run truly in parallel).
- **US3 (P5)** → depends on Foundational; its assertions exercise US1/US2 handlers, so run after at least
  one of them (ideally both).
- **Polish (P6)** → after the stories being shipped are done.

### Within a story

Tests first (Red) → services → controller wiring (Green). Models/value types (Foundational) precede the
services that use them.

### Parallel opportunities

- Setup: T001–T003 all [P].
- Foundational: T004/T005 (tests) [P]; T006/T007 [P]; T009/T010/T011 [P] after T008; T015/T016 [P].
- US1 tests T017–T019 [P]; US2 tests T023/T024 [P]; US2 services T025/T026 [P] before T027.
- US1 and US2 can be built by two developers concurrently once Foundational is done.

## Parallel Example: Foundational value types + data layer

```bash
# After T008 (changeset) lands, the three repositories are independent files:
Task: "T009 ContainerManifest entity + repository in .../container/persistence/"
Task: "T010 ContainerTag entity + repository in .../container/persistence/"
Task: "T011 BlobUpload entity + repository in .../container/persistence/"
# Value types are independent too:
Task: "T006 Digest value type in .../container/Digest.kt"
Task: "T007 ImageReference parser in .../container/ImageReference.kt"
```

---

## Implementation Strategy

### MVP first

Both P1 stories are independently shippable after Foundational. Either is a valid MVP:

- **US1 (proxy Docker Hub pull-through)** — the headline "passthrough" value; ship first for a pull-through
  cache.
- **US2 (hosted push/pull)** — the "storing things" value; ship first if a private push target is the
  priority.

Recommended: **Setup → Foundational → US1 → US2 → US3 → Polish** (spec priority order), stopping to
validate at each checkpoint.

### Incremental delivery

1. Setup + Foundational → `/v2/` answers, storage/data/auth wired.
2. US1 → `docker pull` through the Docker Hub proxy (demo: pull `alpine`). 
3. US2 → `docker push`/`pull` your own image on fs + S3.
4. US3 → auth gate + observability parity; confirm Maven suites green.
5. Polish → docs + `./gradlew build` green + quickstart run.

## Notes

- [P] = different files, no incomplete-task dependency. The two `ContainerRegistryController` wiring tasks
  (T022, T028) touch the same file — sequence them or merge if one developer.
- Preserve bytes/digests exactly (Principle IV): never re-encode a manifest or blob; return the stored
  media type verbatim.
- Commit after each task or logical group; verify each story's tests fail before implementing (Red→Green).
- Total: 38 tasks — Setup 3, Foundational 13, US1 6, US2 6, US3 5, Polish 5.
