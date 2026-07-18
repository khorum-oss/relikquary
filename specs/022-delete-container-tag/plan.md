# Implementation Plan: Delete a Container Image Tag from the Web UI

**Branch**: `022-delete-container-tag` | **Date**: 2026-07-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/022-delete-container-tag/spec.md`

## Summary

Let a permitted user delete a tag of a hosted container image from the tag view. A new browse-API endpoint —
`DELETE /api/repositories/{repo}/containers/tags?image={image}&tag={tag}` — delegates to the existing hosted
delete-by-tag logic (`ManifestService.delete`, which removes only the `ContainerTag` pointer and retains the
digest-addressed manifest and blobs). Authorization is inherited: any `DELETE` under
`/api/repositories/{repo}/**` already maps to the repo's DELETE action in `RepositoryAuthorizationManager`,
so a principal who cannot delete in the repo is refused (401 anonymous → login, 403 authenticated). The
controller additionally rejects delete on a non-hosted (proxy) container repo with 405. To make an image
"drop out" when its last tag is deleted **without** garbage-collecting the retained manifest, the browse
image list becomes kind-aware: a **hosted** repo lists images that have ≥1 tag; a **proxy** repo keeps its
current cached-manifest listing (it has no stored tags). The frontend adds a confirm-guarded delete
affordance per tag row (hosted only — the tags response now carries the repo kind), reloading the tag list on
success and surfacing 401→login / 403→not-permitted / 404→already-gone. No change to the `/v2` wire protocol.

## Technical Context

**Language/Version**: Backend — Kotlin on the JDK 21 toolchain (unchanged). Frontend — SvelteKit 5 (runes) +
TypeScript (unchanged).

**Primary Dependencies**: Backend — Spring Boot Web + the existing container services (`ManifestService`,
`TagService`) and browse layer (`ContainerBrowseService`/`ContainerBrowseController`) from features 018/020.
Frontend — the existing SvelteKit app. **No new dependency** (backend or frontend).

**Storage**: No schema change. Deletion removes a `ContainerTag` row via the existing service; the
`ContainerManifest` descriptor and stored blob/manifest bytes are retained (no GC). Storage backends are
untouched beyond the tag-row delete the OCI path already performs.

**Testing**: A `@SpringBootTest` HTTP round-trip against real storage: push an image with two tags, delete
one via the new endpoint, assert 204 + the tag gone + the manifest still retrievable by digest + the other
tag intact; delete the last tag and assert the image drops out of the hosted image list; delete a missing
tag → 404; delete on a proxy repo → 405. Plus a unit assertion that the DELETE authz mapping covers the
container path, and a real Playwright round-trip: an authorized user deletes a tag from the UI and it
disappears.

**Target Platform**: The existing Spring Boot backend + the SvelteKit static UI served at `/ui`.

**Project Type**: Additive read/write change across the existing `backend` and `frontend` modules — no new
module.

**Performance Goals**: N/A — a delete is a single tag-row removal; the image-list query is unchanged in
shape.

**Constraints**: Reuses feature 007 per-repo DELETE authorization with no new policy; changes no `/v2` wire
behavior or configuration key. The only behavior change to an existing surface is the kind-aware image list
(hosted = tag-driven), which is required so a last-tag delete removes the image without GC; it preserves the
proxy listing. Additive capability ⇒ MINOR `VERSION` bump (1.4.0 → 1.5.0).

**Scale/Scope**: One backend endpoint + a service delete method + a kind-aware tweak to the image list + a
`kind` field on the tags response; one frontend API call, a delete affordance on the tag view, and the
existing login/forbidden handling. One backend integration test, one authz-mapping assertion, one Playwright
test.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS (additive). No change to the container `/v2` or
  Maven wire protocol, repository layout, or any configuration key. The new `DELETE …/containers/tags`
  browse endpoint is additive; the kind-aware image list refines an additive browse surface (018/020) without
  touching resolution/publish. Adding a capability ⇒ **MINOR** bump (1.4.0 → 1.5.0).
- **II. Test-First & Integration-Verified Discipline** — PASS. The change touches container serving/manage,
  so it ships a real `@SpringBootTest` HTTP round-trip against real storage (delete → tag gone, manifest
  retained, image drops out, 404/405), plus a real Playwright round-trip for the UI. No mocked store, no
  mocked client.
- **III. Quality Gates Are Non-Negotiable** — PASS. New Kotlin satisfies detekt (zero violations); the
  frontend passes `svelte-check` and the production build. No dependency added, so
  `gradle/verification-metadata.xml` is unchanged.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. Deleting a tag removes only the mutable pointer;
  the digest-addressed manifest and blobs are preserved byte-for-byte (no re-checksum, no GC), matching the
  OCI delete-by-tag contract. No stored artifact bytes are altered; no secret introduced.

**Result**: PASS. No deviations required (see Complexity Tracking — empty).

## Project Structure

### Documentation (this feature)

```text
specs/022-delete-container-tag/
├── plan.md              # This file
├── research.md          # Phase 0 — endpoint, authz reuse, kind-aware image-list decisions
├── data-model.md        # Phase 1 — the tag delete + kind-aware listing (no schema change)
├── quickstart.md        # Phase 1 — runnable validation guide
├── contracts/
│   └── tag-delete-api.md            # the DELETE …/containers/tags contract + tags-response `kind`
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/container/
├── ContainerBrowseService.kt           # + deleteTag(repo, image, tag); images(repo, kind) is kind-aware
└── ContainerBrowseController.kt         # + DELETE /containers/tags; images() passes kind; tags response carries kind

backend/src/test/kotlin/org/khorum/oss/relikquary/integration/
└── ContainerTagDeleteApiTest.kt         # push 2 tags → delete one (204) → tag gone, manifest kept, other tag intact;
                                         # last-tag delete drops the image; missing → 404; proxy → 405

backend/src/test/kotlin/org/khorum/oss/relikquary/unit/
└── RepositoryAuthzRequestMappingTest.kt # + a case: DELETE /api/repositories/{repo}/containers/tags ⇒ DELETE action

frontend/src/
├── lib/api.ts                          # + deleteContainerTag(repo, image, tag); `kind` on ContainerTagsResponse
└── routes/c/[repo]/[...image]/+page.svelte  # confirm-guarded delete affordance per tag (hosted only); 401/403/404 handling

frontend/tests/
├── container.spec.ts                   # + an authorized user deletes a tag; it disappears; a kept tag remains
└── ... (scripts/e2e.sh)                # seed a dedicated deletable image so the delete test is isolated

VERSION                                 # 1.4.0 → 1.5.0 (additive capability)
```

**Structure Decision**: Additive change to the existing `backend` and `frontend` modules — no new module.
The delete endpoint and the kind-aware image list live in the feature-018/020 browse layer
(`ContainerBrowseService`/`Controller`); the delete itself reuses `ManifestService.delete`. The frontend
adds a delete affordance to the existing container tag route and one API call, reusing the login/forbidden
patterns already used across the browse UI.

## Complexity Tracking

Constitution Check passed with no violations; no deviation to justify. (Table intentionally empty.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 design (data-model, contract, quickstart): unchanged — PASS on all four
principles, no new violations. The design adds one additive DELETE endpoint reusing existing authorization
and delete logic, refines only the additive browse image list (kind-aware, so a last-tag delete drops the
image without GC while the proxy listing is preserved), retains all stored manifest/blob bytes (Principle
IV), and proves itself with a real HTTP round-trip against real storage plus a real browser round-trip.
