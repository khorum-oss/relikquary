---
description: "Task list for Container Image Signature Verification (cosign, advisory)"
---

# Tasks: Container Image Signature Verification (cosign, advisory)

**Input**: Design documents from `specs/024-container-cosign-verify/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/cosign-verify.md

**Tests**: Included — the constitution (Principle II) requires a real `@SpringBootTest` round-trip
(verifying a real signature) and a real Playwright round-trip for the UI.

**Organization**: Grouped by user story. US1 (see a signed image is trusted) is the MVP; US2 (config &
honest unknowns / fail-closed) hardens it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 (setup, foundational, polish carry no story label)

## Path Conventions

Web app: backend at `backend/src/…`, frontend at `frontend/src/…` (per plan.md Structure Decision).

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` 1.6.0 → 1.7.0 (additive capability) in `VERSION`

## Phase 2: Foundational (blocking prerequisites for both stories)

- [x] T002 Add `cosignPublicKey: String?` to `RepositoryProperties.Repo`, create `CosignProperties` (`@ConfigurationProperties("relikquary.cosign")` with `defaultPublicKey: String?`) in `backend/src/main/kotlin/org/khorum/oss/relikquary/config/CosignProperties.kt`, and register it in `RelikquaryApplication`'s `@EnableConfigurationProperties`
- [x] T003 Create `CosignKeys` — resolve a repo's public key (per-repo `cosignPublicKey`, else the global default), parsing an inline PEM (`-----BEGIN PUBLIC KEY-----`) or a file path via `KeyFactory` + `X509EncodedKeySpec`, returning `None` / `Key(PublicKey)` / `Invalid`, cached per repo — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/cosign/CosignKeys.kt`
- [x] T004 Create `TrustStatus` enum (VERIFIED / SIGNED_UNVERIFIED / UNSIGNED / UNKNOWN) and `CosignVerifier.verify(repository, imageName, digest): TrustStatus` — resolve the key (None ⇒ UNKNOWN; Invalid ⇒ fail closed), find the `sha256-<hex(digest)>` tag under the image (absent ⇒ UNSIGNED), read the `.sig` manifest's simple-signing layers (payload blob + `dev.cosignproject.cosign/signature` annotation), verify each with `java.security.Signature` (EC→SHA256withECDSA, RSA→SHA256withRSA, EdDSA→Ed25519) and check the payload `docker-manifest-digest` == digest; any qualifying layer ⇒ VERIFIED, else SIGNED_UNVERIFIED — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/cosign/CosignVerifier.kt`
- [x] T005 [P] Add `trust` to the frontend `ContainerTagSummary` and `ManifestDetail` types in `frontend/src/lib/api.ts`, and create `TrustBadge.svelte` (verified / signed / unsigned / unknown) in `frontend/src/lib/components/TrustBadge.svelte`

**Checkpoint**: verification and the badge component exist; wiring into the browse responses/UI lands next.

## Phase 3: User Story 1 — See that a signed image is trusted (Priority: P1) 🎯 MVP

**Goal**: A signed image shows **verified**, an unsigned one **unsigned**, and a wrong-key/mismatch one
**signed-but-unverified**, on the tag view and manifest detail — with pulls unaffected.

**Independent Test**: With a configured key, push a signed image → UI/API shows verified; unsigned → unsigned;
signature by a different key → signed-but-unverified.

- [x] T006 [US1] Attach `trust` to each tag in `ContainerBrowseService.tags(...)` (via `CosignVerifier` + `CosignKeys`, per tag digest) and to `manifestDetail(...)` (resolving the image name from the manifest row), and surface it on the tag and manifest-detail responses, in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt` (+ `ContainerBrowseController.kt`/`ContainerManifestReader.kt` DTOs as needed)
- [x] T007 [P] [US1] Backend integration test `ContainerCosignVerifyApiTest` (with `application-cosign-it.yml` configuring a hosted repo's `cosignPublicKey`): generate an EC P-256 key pair in-JVM, push an image, build + push a faithful cosign `.sig` (simple-signing payload for the image digest + a `SHA256withECDSA` signature annotation), and assert `trust=verified`; assert `unsigned` for an unsigned image and `signed-but-unverified` for a signature by a different key — in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerCosignVerifyApiTest.kt`
- [x] T008 [US1] Render `TrustBadge` per tag row in `frontend/src/routes/c/[repo]/[...image]/+page.svelte` and in the manifest panel in `frontend/src/lib/components/ManifestDetail.svelte`
- [x] T009 [P] [US1] Playwright: the trust badge renders for the seeded image (asserting `verified`) — add to `frontend/tests/container.spec.ts`; configure a cosign key for `apps` in `frontend/scripts/e2e-config.yml` and seed a signed image (openssl EC key + a constructed `.sig`) in `frontend/scripts/e2e.sh`

**Checkpoint**: US1 is the shippable MVP — trust status is computed and surfaced.

## Phase 4: User Story 2 — Configuration & honest unknowns (Priority: P2)

**Goal**: No key ⇒ **unknown** (short-circuit); a global default applies when a repo sets none; a malformed
key/signature fails closed (never **verified**); a digest mismatch is not **verified**.

**Independent Test**: No key → unknown; global default → verifies a repo without its own key; malformed key →
safe non-verified with no page error.

- [x] T010 [US2] Confirm/implement the fail-closed + short-circuit semantics in `CosignKeys`/`CosignVerifier`: UNKNOWN returns before any storage read when no key resolves; the global default is used when a repo sets no key; an `Invalid` key never yields VERIFIED; a malformed layer/payload is skipped — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/cosign/CosignKeys.kt` and `.../CosignVerifier.kt`
- [x] T011 [P] [US2] Extend `ContainerCosignVerifyApiTest`: no key ⇒ `unknown`; a repo using the global default key ⇒ `verified`; a malformed configured key ⇒ safe non-`verified` (page/response still 200); a signature whose payload references a different digest ⇒ not `verified` — in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerCosignVerifyApiTest.kt`

**Checkpoint**: the badge is honest — unknown when unjudged, never falsely verified.

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T012 [P] Run `gradle :backend:detekt` and resolve any violations (Principle III)
- [x] T013 [P] Run `npm --prefix frontend run check` and `npm --prefix frontend run build`; resolve any issues
- [x] T014 Run `gradle :backend:test --tests '*ContainerCosignVerifyApiTest' --tests '*ContainerBrowseApiTest' --tests '*ContainerManifestDetailApiTest'` and `bash frontend/scripts/e2e.sh`; confirm green (no regression in the 018/020 browse tests from the added `trust` field)
- [x] T015 [P] Walk `quickstart.md`: verified/unsigned/signed/unknown statuses, a `docker pull` is unaffected (advisory), and trust is shown only to a permitted reader

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T005)** before any story phase.
- **US1 (T006–T009)** depends on Foundational. This is the MVP — trust computed and surfaced.
- **US2 (T010–T011)** depends on the verifier (T004) and US1's wiring; it pins the unknown/fail-closed
  semantics.
- **Polish (T012–T015)** runs last.
- Parallelizable: T005 (frontend) ∥ T002–T004 (backend); T007 test ∥ T008 UI; T011 test ∥ nothing blocking;
  T012 detekt ∥ T013 frontend checks.

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — verify a signed image and surface the badge, tested.
- **Increment 2**: Phase 4 (US2) — config (global default) and honest-unknown / fail-closed semantics.
- **Harden**: Phase 5 — gates green, advisory behavior confirmed, no regression.
- `VERSION` 1.6.0 → 1.7.0 (additive capability).
