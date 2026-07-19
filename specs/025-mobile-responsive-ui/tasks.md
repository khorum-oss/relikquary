---
description: "Task list for Mobile-Friendly Responsive Web UI"
---

# Tasks: Mobile-Friendly Responsive Web UI

**Input**: Design documents from `specs/025-mobile-responsive-ui/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/responsive-ui.md

**Tests**: Included — the constitution (Principle II) requires responsive behavior to be proven by a real
Playwright round-trip at a narrow viewport; the existing desktop suite must stay green unchanged.

**Organization**: Grouped by user story. US1 (navigate from a phone) is the MVP; US2 (no sideways page
scroll) and US3 (small-phone panels & touch targets) harden it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 / US2 / US3 (setup, foundational, polish carry no story label)

## Path Conventions

Web app: all work in `frontend/` (per plan.md Structure Decision). The backend is untouched.

---

## Phase 1: Setup

- [x] T001 Bump `VERSION` 1.7.0 → 1.8.0 (additive UI capability) in `VERSION`

## Phase 2: Foundational (blocking prerequisites for all stories)

- [x] T002 Establish the responsive baseline in `frontend/src/lib/theme/tokens.css`: document the single
  breakpoint (`768px`) in a comment, ensure `html, body { max-width: 100%; overflow-x: hidden; }` does not
  clip legitimately-bounded scroll regions, and add any shared helper (e.g. a `.rq-scroll-x` utility with
  `overflow-x: auto` + `-webkit-overflow-scrolling: touch`) used by the wide-table wrappers
- [x] T003 Fix the flexbox overflow foundation in `frontend/src/lib/components/shell/AppShell.svelte`: add
  `min-width: 0` / `max-width: 100%` to `.main` and `.content` so descendant `overflow-x: auto` wrappers clip
  instead of widening the page (Decision 3); keep the wide-width layout visually identical

**Checkpoint**: the shell no longer lets wide content push the page sideways; the breakpoint is defined.

## Phase 3: User Story 1 — Navigate the whole app from a phone (Priority: P1) 🎯 MVP

**Goal**: At narrow widths the rail is hidden and a ☰ control opens it as an overlay drawer that dismisses on
navigate / backdrop / Escape; at wide widths nothing changes.

**Independent Test**: At 375px, rail hidden + ☰ shown; open drawer → all sections + session control; select a
section → navigates and closes; backdrop and Escape close without navigating; at desktop width the rail is
permanent with no ☰.

- [x] T004 [US1] Add drawer state + orchestration to `frontend/src/lib/components/shell/AppShell.svelte`: a
  `drawerOpen` `$state` boolean, an `isNarrow` derived from `window.matchMedia('(max-width: 768px)')`, an
  `onMenu` toggle passed to `Topbar`, a backdrop element (`data-testid="nav-backdrop"`) shown only when open,
  a global `Escape` keydown handler and a `matchMedia` change listener that both reset `drawerOpen = false`,
  and pass `drawerOpen` + an `onClose` callback into `Sidebar`
- [x] T005 [US1] Present the rail as an off-canvas drawer at narrow widths in
  `frontend/src/lib/components/shell/Sidebar.svelte`: accept `open`/`onClose` props, wrap the rail as
  `data-testid="nav-drawer"`, and under `@media (max-width: 768px)` make it `position: fixed` with
  `transform: translateX(-100%)` → `translateX(0)` when `open` (transform transition); wide-width CSS keeps
  the current static 216px rail exactly; call `onClose` from each nav link's `onclick` (close-on-navigate,
  covering back/forward via reset in T004)
- [x] T006 [US1] Add the hamburger control to `frontend/src/lib/components/shell/Topbar.svelte`: a
  `data-testid="nav-toggle"` ☰ button wired to an `onMenu` prop, shown only under `@media (max-width: 768px)`
  (hidden at wide widths, FR-004/N6); keep the title + version chip layout intact
- [x] T007 [P] [US1] Style the drawer backdrop + drawer surface consistently with the vault theme (dimmed
  `--rq-*` backdrop, drawer above it) within `AppShell.svelte` / `Sidebar.svelte`; ensure the backdrop is
  tappable to close and sits below the drawer but above content (z-index)
- [x] T008 [P] [US1] Create `frontend/tests/mobile.spec.ts` with the US1 scenarios at
  `test.use({ viewport: { width: 375, height: 812 } })`: rail hidden + `nav-toggle` visible (N1); open →
  `nav-drawer` shows all sections + session control (N2); select a section → navigates and drawer hidden
  (N3); backdrop tap closes without navigating (N4); Escape closes without navigating (N5); and a
  desktop-viewport check that `nav-toggle` is absent and the rail is visible (N6)

**Checkpoint**: US1 is the shippable MVP — the app is fully navigable on a phone; desktop unchanged.

## Phase 4: User Story 2 — Read wide content without sideways page scrolling (Priority: P2)

**Goal**: No screen scrolls the page sideways; wide tables scroll within their own bounded region.

**Independent Test**: At 375px, each primary screen has `scrollWidth ≤ clientWidth` for the page; a wide table
scrolls within its wrapper while the page stays put.

- [x] T009 [P] [US2] Wrap the repository list in a bounded scroll region and prevent page overflow in
  `frontend/src/routes/+page.svelte` (tabs + `.repos` list); long repo names/paths wrap or truncate
- [x] T010 [P] [US2] Wrap the catalog table in a `data-testid="catalog-scroll"` `overflow-x: auto` region with
  a sensible `min-width` in `frontend/src/lib/components/catalog/CatalogTable.svelte`
- [x] T011 [P] [US2] Wrap the file listing table in a bounded scroll region in
  `frontend/src/lib/components/FileListing.svelte`; the name/size/modified/action columns scroll within it
- [x] T012 [P] [US2] Wrap the users and tokens tables in bounded scroll regions in
  `frontend/src/lib/components/admin/UsersPanel.svelte` and
  `frontend/src/lib/components/admin/TokensPanel.svelte`; the create/edit forms reflow to full width
- [x] T013 [P] [US2] Bound the container tables: the image list in `frontend/src/routes/c/[repo]/+page.svelte`
  and the tag table in `frontend/src/routes/c/[repo]/[...image]/+page.svelte` scroll within their own region;
  long image names/digests wrap or truncate rather than widen the page
- [x] T014 [US2] Extend `frontend/tests/mobile.spec.ts` with US2 assertions at 375px: for each primary screen
  (`/`, `/c/apps`, a tag view, `/dashboard`, a repo file view, `/users`) assert
  `document.scrollingElement.scrollWidth <= document.scrollingElement.clientWidth` (C1); assert a wide table's
  wrapper is itself horizontally scrollable while the page is not (C2)

**Checkpoint**: every primary screen is readable on a phone with no page-level sideways scroll.

## Phase 5: User Story 3 — Fixed panels and touch targets fit a small phone (Priority: P3)

**Goal**: The login card and detail panels fit ~320px; the search spans width; primary controls are tappable.

**Independent Test**: At 320px the sign-in card fits within the viewport; search and detail panels span width
without overflow; primary controls meet ~40px minimum.

- [x] T015 [P] [US3] Make the sign-in card fit small phones in `frontend/src/routes/+layout.svelte`: change the
  `.card` `width: 390px` to `width: min(390px, 100%)` and add viewport padding to `.login-screen` so it clears
  the edges at 320px (F1)
- [x] T016 [P] [US3] Make the topbar artifact search responsive in
  `frontend/src/lib/components/shell/Topbar.svelte`: the fixed `width: 240px` search shrinks to the available
  width under the breakpoint without pushing the header wider (F2)
- [x] T017 [P] [US3] Fit detail panels to width in `frontend/src/lib/components/ManifestDetail.svelte`,
  `frontend/src/lib/components/ModuleDetail.svelte`, and `frontend/src/lib/components/FileDetailsPanel.svelte`:
  `max-width: 100%`, wrap long mono/digest values, and let inner tables use the T002 scroll helper (F2)
- [x] T018 [US3] Add comfortable touch sizing under `@media (max-width: 768px)` for primary controls (nav
  entries in `Sidebar.svelte`; buttons/tabs; `tag-open`/`tag-delete` in the container routes) so the smaller
  dimension is ≥ ~44px, without changing desktop density (F3)
- [x] T019 [US3] Extend `frontend/tests/mobile.spec.ts` with US3 assertions: at
  `viewport: { width: 320, height: 720 }` the sign-in card's right edge ≤ viewport width (F1); the search/detail
  panel does not cause page overflow (F2); a primary control's measured box is ≥ ~40px in its smaller dimension
  (F3)

**Checkpoint**: the app is comfortable, not merely reachable, on a small phone.

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T020 [P] Run `npm --prefix frontend run check` and `npm --prefix frontend run build`; resolve any issues
- [x] T021 [P] Run `gradle :backend:detekt` to confirm no backend regression (no backend sources changed)
- [x] T022 Build the backend jar (`gradle :backend:bootJar`) and run `bash frontend/scripts/e2e.sh`; confirm
  the full Playwright suite is green — the new `mobile.spec.ts` passes and every pre-existing desktop spec
  passes unmodified (SC-005/SC-006 no-regression evidence)
- [x] T023 [P] Walk `quickstart.md` at 375px and 320px: drawer open/dismiss, no page sideways scroll on each
  screen, login card fits, tap targets comfortable, and a desktop-width pass confirming the layout is visually
  unchanged

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T003)** before any story phase.
- **US1 (T004–T008)** depends on Foundational. This is the MVP — phone navigation. T004→T005/T006 (shell state
  before presentation); T007/T008 parallelizable after T004–T006.
- **US2 (T009–T014)** depends on Foundational (T002/T003). T009–T013 touch different files → parallel; T014
  after them.
- **US3 (T015–T019)** depends on Foundational; T015–T017 parallel (different files); T018 then T019.
- **Polish (T020–T023)** runs last.
- Independent-test boundaries: US1, US2, US3 can each be verified on their own once Foundational is done.

## Implementation Strategy

- **MVP**: Phases 1–3 (Setup + Foundational + US1) — the app is navigable on a phone with the desktop layout
  untouched.
- **Increment 2**: Phase 4 (US2) — no sideways page scroll across every primary screen.
- **Increment 3**: Phase 5 (US3) — small-phone panels and touch targets.
- **Harden**: Phase 6 — gates green, full e2e (mobile + unchanged desktop), quickstart walked.
- `VERSION` 1.7.0 → 1.8.0 (additive UI capability).
