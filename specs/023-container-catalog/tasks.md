---
description: "Task list for Container-Aware Catalog & Dashboard"
---

# Tasks: Container-Aware Catalog & Dashboard

**Input**: Design documents from `specs/023-container-catalog/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/catalog-dashboard-api.md

**Tests**: Included — the constitution (Principle II) requires a real `@SpringBootTest` round-trip for the
browse/overview change and a real Playwright round-trip for the UI.

**Organization**: Grouped by user story. US1 (images in the catalog) is the MVP; US2 (privacy/kind
correctness) hardens it; US3 (dashboard figure) completes it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (setup, foundational, polish carry no story label)

## Path Conventions

Web app: backend at `backend/src/…`, frontend at `frontend/src/…` (per plan.md Structure Decision).

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` 1.5.0 → 1.6.0 (additive capability) in `VERSION`

## Phase 2: Foundational (blocking prerequisites for all stories)

- [x] T002 Add `type: String = "maven"` to `CatalogEntry` and `images: Long` to `StatsResponse` in `backend/src/main/kotlin/org/khorum/oss/relikquary/protocol/dto/StatsCatalogDtos.kt`
- [x] T003 Add `ContainerCatalogImage(name, latestTag, tagCount, sizeBytes)` plus `catalogImages(repository, kind): List<ContainerCatalogImage>` (hosted: per tagged image → latest tag by `updatedAt`, tag count, Σ distinct referenced manifest sizes; proxy: per cached image → latestTag "", count = cached manifests, Σ sizes) and `distinctImageCount(repository, kind): Int`, in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt`
- [x] T004 [P] Add `type` to the frontend `CatalogEntry` type and `images` to the `Stats` type in `frontend/src/lib/api.ts`

**Checkpoint**: the DTOs carry type/images and the container catalog projection exists; wiring lands next.

## Phase 3: User Story 1 — Container images in the catalog (Priority: P1) 🎯 MVP

**Goal**: Container images are first-class, searchable, type-badged catalog rows that link to their tag view;
Maven rows are unchanged.

**Independent Test**: With a hosted container image and a Maven artifact present, `GET /api/catalog` returns
both (container typed with image/tag/size fields) and the UI shows both, type-distinguished, the container
row linking to `/c/{repo}/{image}`.

- [x] T005 [US1] In `CatalogService`, inject `ContainerBrowseService` and, for each READ-authorized `format = CONTAINER` repo, append container `CatalogEntry` rows (`type="container"`, `group=""`, `artifact=name`, `latestVersion=latestTag`, `versionCount=tagCount`, `sizeBytes`) from `catalogImages(repo, kind)`; leave the Maven storage-walk path unchanged, in `backend/src/main/kotlin/org/khorum/oss/relikquary/catalog/CatalogService.kt`
- [x] T006 [P] [US1] Backend integration test: push a container image (two tags) to hosted `apps` and a Maven artifact to `releases`, `GET /api/catalog`, assert a `type="container"` entry for the image (artifact=image name, latestVersion=latest tag, versionCount=2, sizeBytes>0) and the Maven entry still `type="maven"`, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/CatalogApiTest.kt`
- [x] T007 [US1] In `CatalogTable.svelte`, render a per-row type badge (container/maven), compute a display name (`artifact` for container, `group:artifact` for Maven) used for the filter and row key, and link a container row to `/c/{repository}/{artifact}` while Maven rows keep `artifactHref`, in `frontend/src/lib/components/catalog/CatalogTable.svelte`
- [x] T008 [P] [US1] Playwright: on the catalog, assert a type-badged container row for the seeded image links to `/c/apps/…`, and the name filter narrows to it, in `frontend/tests/catalog.spec.ts`

**Checkpoint**: US1 is the shippable MVP — images are discoverable in the catalog.

## Phase 4: User Story 2 — Privacy & repository-kind correctness (Priority: P2)

**Goal**: Container rows appear only for repositories the user may READ; a proxy contributes cached images
only (no upstream enumeration); untagged hosted images are omitted.

**Independent Test**: A user without read on a private container repo sees none of its images; a permitted
user does; a proxy shows only cached images.

- [x] T009 [US2] Verify the container rows are produced inside `CatalogService`'s existing `authorizer.permits(repo, READ, auth)` loop (no separate unfiltered path), and that `catalogImages` for a PROXY repo reads only cached manifests (never calls the upstream) and a HOSTED repo omits untagged images, in `backend/src/main/kotlin/org/khorum/oss/relikquary/catalog/CatalogService.kt` and `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt`
- [x] T010 [P] [US2] Backend integration test (security enabled) using a standalone `application-catalog-it.yml` (open container repo + a private container repo readable only by alice + a Maven repo, loaded via `spring.config.location`): assert bob's catalog omits the private repo's images and alice's includes them, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/CatalogContainerAuthzTest.kt`

**Checkpoint**: discovery is privacy-correct and repo-kind-correct.

## Phase 5: User Story 3 — Dashboard images figure (Priority: P3)

**Goal**: The dashboard shows an images figure (distinct container images) beside the existing figures.

**Independent Test**: With images present, `GET /api/stats` reports the distinct count and the dashboard
shows it; with none, it reads zero.

- [x] T011 [US3] In `StatsController`, inject the registry + `ContainerBrowseService` and set `images` = sum of `distinctImageCount(repo, kind)` over the `CONTAINER` repos, in `backend/src/main/kotlin/org/khorum/oss/relikquary/protocol/StatsController.kt`
- [x] T012 [P] [US3] Extend `StatsApiTest`: after pushing a container image, assert `/api/stats` `images` equals the distinct image count (and update the existing assertions for the added field), in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/StatsApiTest.kt`
- [x] T013 [US3] Render the `images` figure beside repositories/artifacts/storage in `frontend/src/lib/components/dashboard/StatCards.svelte`
- [x] T014 [P] [US3] Playwright: the dashboard shows the images figure with the expected count, in `frontend/tests/catalog.spec.ts`

**Checkpoint**: the overview acknowledges container content.

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T015 [P] Run `gradle :backend:detekt` and resolve any violations (Principle III)
- [x] T016 [P] Run `npm --prefix frontend run check` and `npm --prefix frontend run build`; resolve any issues
- [x] T017 Run `gradle :backend:test --tests '*CatalogApiTest' --tests '*CatalogContainerAuthzTest' --tests '*StatsApiTest'` and `bash frontend/scripts/e2e.sh`; confirm green (no regression in the existing catalog/stats tests)
- [x] T018 [P] Walk `quickstart.md`: catalog shows typed container rows linking to the tag view, authz scoping holds, and the dashboard images figure is correct

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T004)** before any story phase.
- **US1 (T005–T008)** depends on Foundational. This is the MVP — images discoverable in the catalog.
- **US2 (T009–T010)** depends on US1's container-row production; it verifies/tests the scoping and proxy
  behavior.
- **US3 (T011–T014)** depends on Foundational (T003 count) and is otherwise independent of US1/US2.
- **Polish (T015–T018)** runs last.
- Parallelizable: T004 (frontend) ∥ T002/T003 (backend); T006 test ∥ T007 UI; T012 test ∥ T013 UI; T015 ∥ T016.

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — images in the searchable catalog, tested.
- **Increment 2**: Phase 4 (US2) — privacy/kind correctness with an authz test.
- **Increment 3**: Phase 5 (US3) — dashboard images figure.
- **Harden**: Phase 6 — gates green, no regression in the existing catalog/stats tests.
- `VERSION` 1.5.0 → 1.6.0 (additive capability).
