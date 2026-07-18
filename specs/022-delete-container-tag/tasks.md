---
description: "Task list for Delete a Container Image Tag from the Web UI"
---

# Tasks: Delete a Container Image Tag from the Web UI

**Input**: Design documents from `specs/022-delete-container-tag/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/tag-delete-api.md

**Tests**: Included — the constitution (Principle II) requires a real `@SpringBootTest` round-trip for a
container serving/manage change and a real Playwright round-trip for the UI.

**Organization**: Grouped by user story. US1 (delete a tag) is the MVP; US2 (permission & repo-kind
guardrails) hardens it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 (setup, foundational, polish carry no story label)

## Path Conventions

Web app: backend at `backend/src/…`, frontend at `frontend/src/…` (per plan.md Structure Decision).

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` 1.4.0 → 1.5.0 (additive capability) in `VERSION`

## Phase 2: Foundational (blocking prerequisites for both stories)

- [x] T002 Add `deleteTag(repository, image, tag): Boolean` to `ContainerBrowseService`, injecting `ManifestService` and delegating to `manifests.delete(repository, image, tag)` (false ⇒ no such tag), in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt`
- [x] T003 Make `ContainerBrowseService.images(...)` kind-aware — accept the repo kind and, for HOSTED, list only image names that have ≥1 tag; for PROXY, keep listing distinct cached-manifest image names — in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseService.kt`
- [x] T004 [P] Add frontend `deleteContainerTag(repo, image, tag)` (DELETE, throws `ApiError`) and add `kind` to the `ContainerTagsResponse` type, in `frontend/src/lib/api.ts`

**Checkpoint**: the service can delete a tag and the hosted image list is tag-driven; wiring lands next.

## Phase 3: User Story 1 — Delete a tag from a hosted image (Priority: P1) 🎯 MVP

**Goal**: A permitted user deletes a tag from the tag view; it disappears, the manifest is retained by
digest, other tags are unaffected, and a last-tag delete drops the image from the image list.

**Independent Test**: Push an image with two tags to hosted `apps`, delete one via the endpoint/UI, and
confirm it is gone while the other tag and the manifest-by-digest remain; delete the last tag and confirm
the image drops out of the image list.

- [x] T005 [US1] Add `DELETE /containers/tags?image=&tag=` to `ContainerBrowseController` (reuse `requireContainerRepo`; 204 on delete, 404 when `deleteTag` returns false) and pass `repo.kind` into `browse.images(...)`, in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseController.kt`
- [x] T006 [US1] Add `kind` to the `ContainerTagsResponse` DTO and populate it from the repo in the `tags(...)` handler, in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseController.kt`
- [x] T007 [P] [US1] Backend integration test `ContainerTagDeleteApiTest`: push an image with tags `1.0.0` + `latest` to hosted `apps`, `DELETE …/containers/tags?image&tag=1.0.0` → 204, assert `1.0.0` gone from the tags list, the manifest still retrievable by its digest (via `/v2 …/manifests/<digest>`), and `latest` intact; then delete `latest` and assert the image is absent from `GET …/containers`; missing tag → 404, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerTagDeleteApiTest.kt`
- [x] T008 [US1] Add a confirm-guarded delete affordance per tag row (shown only when `data.kind === 'HOSTED'`) that calls `deleteContainerTag`, and on success reloads the tag list (empty ⇒ existing empty state); reuse the route's size/short-digest helpers and vault styling, in `frontend/src/routes/c/[repo]/[...image]/+page.svelte`
- [x] T009 [P] [US1] Playwright: as an authorized user, seed then delete a tag on a dedicated image and assert it disappears while a kept tag remains — add the delete flow to `frontend/tests/container.spec.ts` and seed a dedicated deletable image (`team/deletable` with two tags) in `frontend/scripts/e2e.sh`

**Checkpoint**: US1 is the shippable MVP — a permitted user can delete a tag from the UI.

## Phase 4: User Story 2 — Permission & repository-kind guardrails (Priority: P2)

**Goal**: Delete honors per-repo DELETE authorization (401 anonymous → login, 403 without permission) and is
refused on proxy repos (405) and Maven repos (400); the affordance is hidden on proxy repos.

**Independent Test**: Anonymous delete → 401/login; signed-in without permission → 403/not-permitted; proxy
delete → 405 and no affordance shown; Maven repo → 400.

- [x] T010 [US2] In `ContainerBrowseController`, reject `DELETE …/containers/tags` on a non-HOSTED container repo with 405 (proxy is read-only) and on a Maven repo with 400 (via `requireContainerRepo`), in `backend/src/main/kotlin/org/khorum/oss/relikquary/container/ContainerBrowseController.kt`
- [x] T011 [P] [US2] Extend `ContainerTagDeleteApiTest` with the proxy → 405 and maven-repo → 400 cases, in `backend/src/test/kotlin/org/khorum/oss/relikquary/integration/ContainerTagDeleteApiTest.kt`
- [x] T012 [P] [US2] Add a unit assertion that `DELETE /api/repositories/{repo}/containers/tags` maps to the repo's DELETE action (per-repo authz), in `backend/src/test/kotlin/org/khorum/oss/relikquary/unit/RepositoryAuthzRequestMappingTest.kt`
- [x] T013 [US2] In the tag view, handle the delete response — 401 ⇒ show the login form and retry after sign-in; 403 ⇒ a clear "not permitted" message; 404 ⇒ "already gone" + reload; and render no delete affordance when `kind !== 'HOSTED'`, in `frontend/src/routes/c/[repo]/[...image]/+page.svelte`

**Checkpoint**: deletion is permissioned and repo-kind-guarded end to end.

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T014 [P] Run `gradle :backend:detekt` and resolve any violations (Principle III)
- [x] T015 [P] Run `npm --prefix frontend run check` and `npm --prefix frontend run build`; resolve any issues
- [x] T016 Run `gradle :backend:test --tests '*ContainerTagDeleteApiTest' --tests '*RepositoryAuthzRequestMappingTest' --tests '*ContainerBrowseApiTest'` and `bash frontend/scripts/e2e.sh`; confirm green (no regression in the 018/020 browse tests from the kind-aware image list)
- [x] T017 [P] Walk `quickstart.md`: confirm delete → 204, tag gone, manifest retained by digest, last-tag delete drops the image, and the 404/405/400 guardrails

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T004)** before any story phase.
- **US1 (T005–T009)** depends on Foundational. This is the MVP — a permitted user can delete a tag.
- **US2 (T010–T013)** depends on US1's endpoint (T005) and the frontend affordance (T008); it adds the
  guardrails and error handling.
- **Polish (T014–T017)** runs last.
- Parallelizable: T004 (frontend) ∥ T002/T003 (backend); T007 test ∥ T008 UI; T011/T012 tests ∥ T013 UI;
  T014 detekt ∥ T015 frontend checks.

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — delete a tag from the UI, tested.
- **Increment 2**: Phase 4 (US2) — permission & repo-kind guardrails and error handling.
- **Harden**: Phase 5 — gates green, no regression in the existing browse tests.
- `VERSION` 1.4.0 → 1.5.0 (additive capability).
