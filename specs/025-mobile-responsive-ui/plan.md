# Implementation Plan: Mobile-Friendly Responsive Web UI

**Branch**: `025-mobile-responsive-ui` | **Date**: 2026-07-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/025-mobile-responsive-ui/spec.md`

## Summary

Make the existing SvelteKit web UI usable on phones and small tablets without changing the desktop
experience. Introduce a single responsive breakpoint: below it the fixed 216px navigation rail is hidden and
opened on demand as a slide-in overlay drawer via a hamburger (☰) control in the topbar; above it the layout
is byte-for-byte today's desktop. Wide data regions (repository list, container image/tag tables, catalog
table, file listing, users/tokens panels) get bounded horizontal scroll or reflow so the page never scrolls
sideways as a whole, and fixed-width elements (login card, topbar search, detail panels) shrink to ~320px.
Presentation-only: no API, data, or backend change. Verified by a new Playwright spec that drives the real
app at a narrow viewport, alongside the unchanged desktop e2e suite.

## Technical Context

**Language/Version**: TypeScript 5, Svelte 5 (runes), SvelteKit 2

**Primary Dependencies**: SvelteKit, Vite; existing `$lib/theme/tokens.css` design tokens; no new runtime
dependency

**Storage**: N/A (frontend presentation only; no persistence touched)

**Testing**: Playwright (`@playwright/test`) driving real Chromium against `vite dev` + the running backend
(`scripts/e2e.sh`); `svelte-check` for type/markup validation

**Target Platform**: Modern mobile and desktop browsers (Chromium-based verified in CI); phones ~320px and up

**Project Type**: Web application — SvelteKit frontend (`frontend/`) over the existing Kotlin/Spring backend
(untouched)

**Performance Goals**: No functional perf target; the drawer open/close is a CSS transform transition and must
feel immediate (no layout jank); page must not introduce horizontal overflow at any supported width

**Constraints**: Presentation-only — no API/data/backend change (FR-009); desktop layout unchanged at wide
widths (FR-004, SC-006); single minimum supported width ~320px; keep the vault theme tokens as the only source
of palette/type (FR-010)

**Scale/Scope**: ~10 route screens + ~14 layout-bearing components (the shell, page tables, admin panels, and
detail panels enumerated in research.md); one new breakpoint, one drawer behavior, one new test spec

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility**: No change to the served repository layout, `/v2` surface,
  or any HTTP resolution/publish protocol. Frontend-only. `VERSION` gets a MINOR bump (additive UI capability),
  consistent with prior UI features. **PASS**
- **II. Test-First & Integration-Verified Discipline**: Responsive behavior is proven by a REAL Playwright
  round-trip at a narrow viewport (drawer open/dismiss, section navigation, no horizontal page scroll) against
  the running app — not mocks — added before/with the implementation. The existing desktop e2e suite must stay
  green unchanged (no-regression evidence). No backend behavior changes, so no `@SpringBootTest` round-trip is
  applicable. **PASS**
- **III. Quality Gates Are Non-Negotiable**: `svelte-check` clean, `npm run build` succeeds, Playwright green.
  No backend sources change, so detekt/Kover are unaffected (still run to confirm no regression). No gate is
  weakened. **PASS**
- **IV. Supply-Chain Integrity & Faithful Storage**: No stored bytes, checksums, or signatures touched; no new
  dependency added (no `verification-metadata.xml` change); no secrets. **PASS**

No violations — Complexity Tracking left empty.

## Project Structure

### Documentation (this feature)

```text
specs/025-mobile-responsive-ui/
├── plan.md              # This file
├── research.md          # Phase 0 output — breakpoint, drawer pattern, scroll strategy, test approach
├── data-model.md        # Phase 1 output — N/A rationale (no data)
├── quickstart.md        # Phase 1 output — how to validate responsive behavior
├── contracts/
│   └── responsive-ui.md # Phase 1 output — the responsive UI contract (breakpoint + drawer states + targets)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── app.html                                   # viewport meta already present (no change expected)
│   ├── lib/
│   │   ├── theme/tokens.css                        # add a shared breakpoint note + any responsive helpers
│   │   └── components/
│   │       ├── shell/
│   │       │   ├── AppShell.svelte                 # drawer state, backdrop, breakpoint orchestration
│   │       │   ├── Sidebar.svelte                  # rail ↔ drawer presentation; close-on-navigate
│   │       │   └── Topbar.svelte                   # hamburger control (narrow only); responsive search
│   │       ├── catalog/CatalogTable.svelte         # bounded horizontal scroll
│   │       ├── FileListing.svelte                  # bounded horizontal scroll / reflow
│   │       ├── admin/UsersPanel.svelte             # bounded horizontal scroll
│   │       ├── admin/TokensPanel.svelte            # bounded horizontal scroll
│   │       ├── ManifestDetail.svelte               # panel + layer table fit to width
│   │       ├── ModuleDetail.svelte                 # panel fit to width
│   │       ├── FileDetailsPanel.svelte             # panel fit to width
│   │       └── LoginForm.svelte                    # card fits ~320px (via layout wrapper)
│   └── routes/
│       ├── +layout.svelte                          # login-screen card max-width
│       ├── +page.svelte                            # repository list table scroll
│       ├── c/[repo]/+page.svelte                   # image list scroll
│       ├── c/[repo]/[...image]/+page.svelte        # tag table scroll
│       ├── r/[repo]/[...path]/+page.svelte         # file tree/listing scroll
│       └── (dashboard, publish, users, settings)   # verify no overflow; adjust paddings as needed
└── tests/
    └── mobile.spec.ts                              # NEW — narrow-viewport Playwright coverage
```

**Structure Decision**: Web application. All work is in `frontend/`. The shell trio (`AppShell`, `Sidebar`,
`Topbar`) carries the navigation/drawer behavior; each wide-content component gains a bounded-scroll wrapper;
a single new `tests/mobile.spec.ts` provides the narrow-viewport verification. The backend and all shared
CSS tokens are reused unchanged.

## Complexity Tracking

No constitution violations — not applicable.
