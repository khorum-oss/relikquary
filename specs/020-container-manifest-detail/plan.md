# Implementation Plan: Container Image Manifest & Layer Detail View

**Branch**: `020-container-manifest-detail` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/020-container-manifest-detail/spec.md`

## Summary

Extend the container browse UI (feature 018) with a read-only **manifest detail** drilled from a tag. A new
browse endpoint — `GET /api/repositories/{repo}/containers/manifest?digest={digest}` — reads the exact
stored manifest bytes (keyed by digest, already persisted by the hosted push path) and returns a parsed,
classified projection: for a Docker/OCI **image manifest**, the config descriptor, the total pull size
(config + layers), and the ordered layer descriptors (digest, media type, size); for a Docker **manifest
list** / OCI **image index**, the platform entries (os/architecture/variant, sub-manifest digest, size).
Each referenced object is flagged `present` (is it stored locally?) so a partially-deleted image degrades
gracefully; an unrecognized manifest shape returns an `unknown` projection carrying just digest/mediaType/
size rather than erroring. The frontend adds an inline `ManifestDetail` panel on the existing
`/c/{repo}/{image}` tag view: clicking a tag loads its manifest; an index's platform rows are themselves
clickable to load that platform's sub-manifest (the same panel, re-driven by the sub-manifest digest).
Bytes are never altered (Principle IV); access reuses the per-repo READ authorization the feature-018
browse endpoints already enforce.

## Technical Context

**Language/Version**: Backend — Kotlin on the JDK 21 toolchain (unchanged). Frontend — SvelteKit 5 (Svelte
runes) + TypeScript, built with Vite (unchanged).

**Primary Dependencies**: Backend — Spring Boot Web, the existing container persistence + storage from
feature 018 (`ContainerManifestRepository`, `ContainerStorage`), and the already-present Jackson
`ObjectMapper` used by `ManifestService` to read manifest JSON. Frontend — the existing SvelteKit app.
**No new production dependency** (backend or frontend).

**Storage**: None added. The feature is a pure read projection over the manifest bytes already stored by
the hosted push path (feature 018), addressed by digest via `ContainerStorage.readManifestBytes`. No new
table, no Liquibase changeset, no schema change. Artifact/manifest bytes are read, never written or altered.

**Testing**: A `@SpringBootTest` HTTP round-trip against the real datastore + real filesystem storage: a
docker-push-shaped upload (monolithic blob uploads + manifest PUT via `/v2`) of (a) a single-platform image
and (b) a two-platform manifest list into a hosted container repo, then assertions on the manifest browse
endpoint — image config/total-size/ordered layers, index platform entries, drill into a platform's
sub-manifest, plus unparseable-bytes and missing-reference degradation and the maven-repo(400)/unknown(404)
rejections. Plus a real Playwright round-trip in real Chromium: drill a tag into its layers and a multi-arch
tag into a platform's layers, styled by the existing vault theme.

**Target Platform**: The existing Spring Boot backend + the SvelteKit static UI served at `/ui`.

**Project Type**: Additive change across the existing `backend` and `frontend` modules — no new module.

**Performance Goals**: N/A beyond "instant" — a manifest is a few KB of JSON read by digest and parsed
once per request; no fan-out fetch of blob bytes (sizes come from the manifest's own descriptors).

**Constraints**: Purely additive — no existing screen, endpoint, config key, or the Maven/container wire
surfaces change (Principle I; MINOR bump). The new endpoint is a sibling under the same
`/api/repositories/{repo}/containers` path already mapped to per-repo READ, so authorization is inherited
with no security-layer edit. Manifest bytes are read verbatim; every displayed digest equals what a client
pulls (Principle IV). Hosted repositories only (a proxy has no stored tag to drill from).

**Scale/Scope**: One backend parser/service + one browse endpoint + response DTOs; frontend adds two API
functions, one `ManifestDetail` panel component wired into the existing tag route, and a size/digest
formatting helper reused from the tag view. One backend integration test and one Playwright spec.
`VERSION` 1.3.0 → 1.4.0 (additive capability).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). No change to the Maven or container
  (`/v2`) wire protocol, repository layout, or any existing configuration key. The new
  `/api/repositories/{repo}/containers/manifest` browse endpoint is additive, as is the UI. Adding a
  capability (not breaking one) implies a **MINOR** `VERSION` bump (1.3.0 → 1.4.0).
- **II. Test-First & Integration-Verified Discipline** — PASS (adapted). The change touches container
  serving/browse, so it ships a real `@SpringBootTest` HTTP round-trip against the real datastore + real
  storage: a client-shaped `/v2` push of a single-platform image and a manifest list, then the browse
  endpoint's projection is asserted (including drill-in, degradation, and authz-shape rejections). The UI is
  proven by a real Playwright round-trip in a real browser. No mocked store, no mocked client. The Maven and
  container round-trips already in the suite are unaffected.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin satisfies detekt (zero violations); the
  frontend passes `svelte-check` and the production build. Nothing exempted; no gate weakened. No test-only
  or production dependency is added, so `gradle/verification-metadata.xml` needs no change.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS (directly upheld). The feature reads stored
  manifest bytes and reports their content without ever writing, re-checksumming, or altering them; the
  digests it displays are exactly what a container client resolves. No stored artifact byte is touched; no
  secret is introduced or committed.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/020-container-manifest-detail/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — the manifest-detail projection (derived, no persistence)
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/
│   └── manifest-detail-api.md      # the /api/repositories/{repo}/containers/manifest contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/container/
├── ContainerManifestReader.kt          # reads bytes by digest, parses+classifies image | index | unknown
├── ContainerBrowseService.kt           # + manifestDetail(repo, digest) delegating to the reader
└── ContainerBrowseController.kt        # + GET /containers/manifest?digest= → ManifestDetailResponse

backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
└── ContainerManifestDetailApiTest.kt   # image + index push, drill-in, degradation, 400/404

frontend/src/
├── lib/api.ts                          # + ManifestDetail types; getContainerManifest(repo, digest)
├── lib/components/ManifestDetail.svelte # inline panel: config+layers | platform list; drill into platform
└── routes/c/[repo]/[...image]/+page.svelte  # open a tag's manifest in the panel

frontend/tests/
└── container.spec.ts                   # + drill a tag into layers; drill a multi-arch tag into a platform

frontend/scripts/
├── e2e.sh                              # + seed a multi-arch manifest list into the hosted 'apps' repo
└── e2e-config.yml                      # (unchanged — the 'apps' container repo already exists)

VERSION                                 # 1.3.0 → 1.4.0 (additive capability)
```

**Structure Decision**: Additive change to the existing `backend` and `frontend` modules — no new Gradle or
Node module. Backend manifest parsing lives in a new `ContainerManifestReader` in the existing `container/`
package (beside `ManifestService`, which already parses manifest JSON to verify references), surfaced
through the feature-018 `ContainerBrowseService`/`ContainerBrowseController`. Frontend detail logic is one
self-contained `ManifestDetail` component wired into the existing container tag route.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds one additive read endpoint and one UI panel, introduces no
persistence and no wire-protocol change, reads stored manifest bytes verbatim (default-safe: an unparseable
or partially-missing manifest degrades to a digest/size view rather than an error), and proves itself with a
real HTTP round-trip against the real datastore + storage plus a real browser round-trip.
