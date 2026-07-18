# Implementation Plan: Container-Aware Catalog & Dashboard

**Branch**: `023-container-catalog` | **Date**: 2026-07-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/023-container-catalog/spec.md`

## Summary

Make container images first-class in the cross-repo catalog (feature 016) and add an images figure to the
dashboard. The `CatalogService` already aggregates READ-authorized repositories into `CatalogEntry` rows for
Maven `group:artifact` coordinates; this feature adds container-image rows from the same auth-scoped pass.
`CatalogEntry` gains one additive field, `type` (`maven` | `container`, default `maven`); a container row
reuses the existing fields with container meaning — `artifact` = image name, `latestVersion` = latest tag,
`versionCount` = tag count, `sizeBytes` = summed manifest size, `group` = "" — so the response shape and the
Maven rows are unchanged. Container rows come from the container tables via a small `ContainerBrowseService`
projection: a **hosted** repo contributes its tagged images (latest tag, tag count, size); a **proxy** repo
contributes only its cached images (no live upstream enumeration, no tags). The frontend `CatalogTable`
renders a type badge, links a container row to its tag view (`/c/{repo}/{image}`) while Maven rows keep their
folder-view link, and filters by the display name uniformly. The dashboard gains an `images` figure — the
count of distinct container images — beside the existing repository/artifact/storage figures. Additive: no
`/v2` or Maven wire change, no config key, no resolution/publish change.

## Technical Context

**Language/Version**: Backend — Kotlin on the JDK 21 toolchain (unchanged). Frontend — SvelteKit 5 (runes) +
TypeScript (unchanged).

**Primary Dependencies**: Backend — Spring Boot Web, the existing `CatalogService`/`StatsController` (feature
016) and the container browse/persistence layer (features 018/020/022). Frontend — the existing SvelteKit
app. **No new dependency** (backend or frontend).

**Storage**: No schema change. Container catalog rows and the images figure are read projections over the
container tables (`ContainerTag`, `ContainerManifest`) already populated by feature 018 — no new stored
state, no walk of blob storage.

**Testing**: A `@SpringBootTest` HTTP round-trip against real storage: push a container image and a Maven
artifact, GET `/api/catalog` and assert both appear with correct `type` and fields; assert a private
container repo's images are hidden from an unpermitted caller and visible to a permitted one (authz matrix);
assert `/api/stats` reports the distinct image count. Plus a real Playwright round-trip: the catalog shows a
type-badged container row that links to the tag view, and the dashboard shows the images figure.

**Target Platform**: The existing Spring Boot backend + the SvelteKit static UI served at `/ui`.

**Project Type**: Additive change across the existing `backend` and `frontend` modules — no new module.

**Performance Goals**: N/A. Container rows come from indexed table reads (per readable repo), not a storage
walk; the images count is a cheap distinct-count over the same tables. The Maven catalog path is unchanged.

**Constraints**: Additive and authorization-preserving — container rows join the catalog's existing per-repo
READ scoping (feature 007), so no new leakage. The `CatalogEntry.type` and `StatsResponse.images` fields are
additive (existing consumers ignore them; Maven rows default to `type = "maven"`). No `/v2`/Maven wire,
resolution/publish, or config change. Additive capability ⇒ MINOR `VERSION` bump (1.5.0 → 1.6.0).

**Scale/Scope**: One additive DTO field + a container catalog projection + wiring in `CatalogService`, and a
distinct-image count on the dashboard stats; frontend adds a type badge, a container link branch, and the
images figure. One backend integration test (catalog + stats, incl. authz), one Playwright test.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). No change to the container `/v2` or
  Maven wire protocol, repository layout, resolution/publish, or any configuration key. `CatalogEntry.type`
  and `StatsResponse.images` are additive fields on internal browse/overview endpoints; Maven catalog rows
  are unchanged in meaning. Adding a capability ⇒ **MINOR** bump (1.5.0 → 1.6.0).
- **II. Test-First & Integration-Verified Discipline** — PASS. The change touches the browse/overview
  surface, so it ships a real `@SpringBootTest` HTTP round-trip against real storage (catalog contents +
  type + authz scoping + stats count) and a real Playwright round-trip for the UI. No mocked store, no mocked
  client.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin satisfies detekt (zero violations); the
  frontend passes `svelte-check` and the production build. No dependency added, so
  `gradle/verification-metadata.xml` is unchanged.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS (not implicated). The feature reads existing
  container descriptors to summarize them for discovery; it writes nothing and alters no stored artifact
  bytes, checksums, or signatures. No secret introduced.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/023-container-catalog/
├── plan.md              # This file
├── research.md          # Phase 0 — DTO-shape, proxy handling, dashboard-scope decisions
├── data-model.md        # Phase 1 — the unified catalog entry + images figure (no schema change)
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/
│   └── catalog-dashboard-api.md    # the /api/catalog (typed entries) + /api/stats (images) contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── protocol/dto/StatsCatalogDtos.kt        # + CatalogEntry.type (default "maven"); + StatsResponse.images
├── catalog/CatalogService.kt               # + container-image rows for CONTAINER repos (auth-scoped, as today)
├── container/ContainerBrowseService.kt      # + catalogImages(repo, kind) and distinctImageCount(repo, kind)
└── protocol/StatsController.kt             # + images = distinct container images across repos

backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
├── CatalogApiTest.kt                       # + container rows appear (type=container, fields), authz-scoped
└── StatsApiTest.kt                         # + images figure equals the distinct image count

frontend/src/
├── lib/api.ts                              # + CatalogEntry.type; + Stats.images
├── lib/components/catalog/CatalogTable.svelte  # type badge; container row → /c/{repo}/{image}; name filter
└── routes/dashboard/+page.svelte (or the stats component)  # + images figure beside the existing ones

frontend/tests/
└── catalog.spec.ts                         # + a container row is type-badged and links to the tag view; images figure

VERSION                                     # 1.5.0 → 1.6.0 (additive capability)
```

**Structure Decision**: Additive change to the existing `backend` and `frontend` modules — no new module.
Container catalog rows are produced in the existing `CatalogService` (which already owns the auth-scoped
aggregation) by delegating to a small projection on `ContainerBrowseService` (features 018/020/022), keeping
container logic in the container package. The frontend change is confined to the existing `CatalogTable` and
the dashboard stats view.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds one additive DTO field and one dashboard figure, produces
container rows from the same READ-authorized pass the Maven catalog already uses (so no new leakage), writes
nothing and alters no stored bytes, and proves itself with a real HTTP round-trip against real storage plus a
real browser round-trip.
