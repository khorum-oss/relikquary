---
description: "Task list for Container Image Manifest & Layer Detail View"
---

# Tasks: Container Image Manifest & Layer Detail View

**Input**: Design documents from `specs/020-container-manifest-detail/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/manifest-detail-api.md

**Tests**: Included — the constitution (Principle II) requires a real `@SpringBootTest` round-trip for
container serving/browse changes and a real Playwright round-trip for the UI.

**Organization**: Grouped by user story. US1 (single-platform image layers) is the MVP and is independently
shippable; US2 (multi-arch index + platform drill-in) builds on the shared endpoint.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 (setup, foundational, polish carry no story label)

## Path Conventions

Web app: backend at `backend/src/…`, frontend at `frontend/src/…` (per plan.md Structure Decision).

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` 1.3.0 → 1.4.0 (additive capability) in `VERSION`

## Phase 2: Foundational (blocking prerequisites for both stories)

- [x] T002 Add the manifest-detail response DTOs — a discriminated `ManifestDetailResponse` (`kind` = image | index | unknown) with `Descriptor` (digest, mediaType, size, present, optional platform) and `Platform` (os, architecture, variant?) — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerManifestReader.kt`
- [x] T003 Create `ContainerManifestReader` that loads stored bytes by digest (`ContainerStorage.readManifestBytes`), reads the descriptor (`ContainerManifestRepository.findByRepositoryAndDigest`), parses the JSON with Jackson, and returns the top-level shell (repository, digest, mediaType, size) — classification filled by US1/US2 — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerManifestReader.kt`
- [x] T004 Add `manifestDetail(repository, digest)` to `ContainerBrowseService` delegating to `ContainerManifestReader` (returns null when no manifest bytes are stored for the digest) in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt`
- [x] T005 Add `GET /containers/manifest?digest=` to `ContainerBrowseController` (reuse `requireContainerRepo`; 404 when `manifestDetail` is null) returning `ManifestDetailResponse` in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseController.kt`
- [x] T006 [P] Add frontend `ManifestDetail`/`ManifestDescriptor`/`ManifestPlatform` types and `getContainerManifest(repo, digest)` in `frontend/src/lib/api.ts`

**Checkpoint**: the endpoint responds for any stored digest with the top-level shell; classification lands next.

## Phase 3: User Story 1 — Inspect a single-platform image's layers (Priority: P1) 🎯 MVP

**Goal**: Drill from a tag into its image manifest and see the config digest, total pull size, and the
ordered layer list (digest, media type, size), with graceful degradation for unknown/missing content.

**Independent Test**: Push a single-platform image (config + two layers) to hosted `apps`, open its tag, and
confirm the detail shows the config digest, total size = config + both layers, and both layers in order.

- [x] T007 [US1] Implement the **image** classification in `ContainerManifestReader` — a node with a `config` object (and no `manifests` array) → config `Descriptor`, ordered `layers` `Descriptor[]`, `totalSize` = config.size + Σ layer sizes; compute each `present` via `ContainerStorage.hasBlob`; classify an unrecognized shape as `kind = "unknown"` — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerManifestReader.kt`
- [x] T008 [P] [US1] Backend integration test: push a single-platform image via `/v2` (monolithic blob uploads + manifest PUT), then assert the `image` projection (config, ordered layers with sizes, totalSize), plus the `unknown`-shape, absent-digest (404), and maven-repo (400) cases, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerManifestDetailApiTest.kt`
- [x] T009 [US1] Create `ManifestDetail.svelte` rendering the image view — config digest, human-readable total size, and the ordered layer table (short digest, media type, size, a "not stored" mark when `present` is false) in the vault theme — in `frontend/src/lib/components/ManifestDetail.svelte`
- [x] T010 [US1] Wire the panel into the tag view: clicking a tag row loads `getContainerManifest(repo, tag.digest)` and shows `ManifestDetail`; reuse the existing size/short-digest helpers — in `frontend/src/routes/c/[repo]/[...image]/+page.svelte`
- [x] T011 [P] [US1] Playwright: drill the seeded `team/service:1.0.0` tag into its manifest and assert the config digest, total size, and layer rows in `frontend/tests/container.spec.ts`

**Checkpoint**: US1 is a complete, shippable MVP — single-platform images are fully inspectable.

## Phase 4: User Story 2 — Multi-arch index and platform drill-in (Priority: P2)

**Goal**: For a tag resolving to a manifest list / image index, list the platforms (os/architecture/variant,
sub-manifest digest, size) and drill into one platform to see its layers.

**Independent Test**: Push a manifest list referencing two platform image manifests to hosted `apps`, open
its tag, confirm both platforms are listed, drill into one, and see that platform's layers (as US1).

- [x] T012 [US2] Implement the **index** classification in `ContainerManifestReader` — a node with a non-empty `manifests` array → one `Descriptor` per entry carrying its `Platform` (os/architecture/variant when declared) and `present` via `ContainerStorage.hasManifest` — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerManifestReader.kt`
- [x] T013 [P] [US2] Extend the backend test: push a two-platform manifest list, assert the `index` projection (both platform entries with digest/size/platform), then request `manifest?digest=<platform sub-manifest>` and assert that platform's `image` layer breakdown, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerManifestDetailApiTest.kt`
- [x] T014 [US2] Extend `ManifestDetail.svelte` to render the index view — a platform list (os/arch/variant, short digest, size, "not stored" mark) whose rows are clickable to re-drive the panel with the sub-manifest digest, plus a "back to platforms" affordance — in `frontend/src/lib/components/ManifestDetail.svelte`
- [x] T015 [US2] Seed a multi-arch manifest list (`team/multi:1.0.0`, two platform image manifests) into hosted `apps` in `frontend/scripts/e2e.sh`
- [x] T016 [P] [US2] Playwright: drill the `team/multi:1.0.0` tag into its platform list and into one platform's layers in `frontend/tests/container.spec.ts`

**Checkpoint**: both stories done — single-platform and multi-arch images are fully inspectable.

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T017 [P] Run `gradle :backend:detekt` and resolve any violations (Principle III)
- [x] T018 [P] Run `npm --prefix frontend run check` and `npm --prefix frontend run build`; resolve any issues
- [x] T019 Run `gradle :backend:test --tests '*ContainerManifestDetailApiTest'` and `bash frontend/scripts/e2e.sh`; confirm green
- [x] T020 [P] Walk `quickstart.md` and confirm every displayed digest matches a real client's for the same tag/platform (Principle IV faithful-storage check)

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T006)** must complete before any story phase.
- **US1 (T007–T011)** depends only on Foundational. This is the MVP — stop here for a shippable slice.
- **US2 (T012–T016)** depends on Foundational; its reader change (T012) shares `ContainerManifestReader`
  with T007, so land T007 first. Otherwise US2 is independent of US1's UI.
- **Polish (T017–T020)** runs after the stories being shipped are complete.
- Within a phase, `[P]` tasks touch different files and may run together (e.g. T008 backend test ∥ T009/T010
  frontend; T017 detekt ∥ T018 frontend checks).

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — single-platform image layer detail, fully tested.
- **Increment 2**: Phase 4 (US2) — multi-arch index and platform drill-in.
- **Harden**: Phase 5 — gates green, real-client digest fidelity confirmed.
- `VERSION` 1.3.0 → 1.4.0 (additive capability).
