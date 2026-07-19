# Research: Mobile-Friendly Responsive Web UI

Phase 0 decisions that resolve the plan's Technical Context. Everything here is frontend-only.

## Decision 1 — Single breakpoint at 768px

**Decision**: One width threshold, `768px`. `max-width: 768px` = "narrow" (drawer navigation, reflow/scroll
content). `min-width: 769px` = "wide" (today's desktop, permanent rail, unchanged).

**Rationale**: 768px is the conventional tablet/phone-vs-desktop line and comfortably keeps common phones
(320–430px) and small tablets in portrait on the narrow side while every real desktop stays wide. A single
breakpoint keeps the mental model and the CSS simple and makes the "desktop unchanged" guarantee (FR-004,
SC-006) trivial to reason about: all responsive rules live inside `@media (max-width: 768px)` blocks, so at
wide widths nothing new applies.

**Alternatives considered**:
- Multiple breakpoints (phone / tablet / desktop): rejected — the spec only distinguishes "narrow" vs "wide";
  extra tiers add CSS surface with no requirement behind them.
- A container-query approach: rejected — overkill for a single app-shell decision; viewport media queries are
  universally supported and match "phone vs desktop" directly.

## Decision 2 — Navigation: CSS-driven off-canvas overlay drawer, state in AppShell

**Decision**: The existing `Sidebar` markup is reused verbatim; responsiveness is a presentation concern.
`AppShell` owns a single `drawerOpen` boolean (`$state`). At wide widths the rail is `position: static` and
always shown and the flag is inert. At narrow widths the rail becomes a fixed off-canvas panel
(`transform: translateX(-100%)`), slid in (`translateX(0)`) when `drawerOpen`, above a dimmed backdrop.
`Topbar` renders the ☰ button only at narrow widths (CSS `display`), calling an `onMenu` callback to toggle.
Selecting a nav link, tapping the backdrop, or pressing Escape sets `drawerOpen = false`.

**Rationale**: Keeping one source of truth for open/closed in `AppShell` (which already composes `Sidebar` +
`Topbar` + content) avoids cross-component state plumbing. Using a CSS transform for the slide keeps it GPU-
cheap and jank-free and means the drawer needs no JS animation. Reusing the same `Sidebar` markup for both
modes guarantees the section list and the sign-in/out footer are identical in the drawer and the rail
(FR-002) with no duplicate nav to keep in sync.

**Close-on-navigate**: SvelteKit client navigation is detected via `afterNavigate` (or an `onclick` on the nav
links) to reset `drawerOpen`, which also covers the browser back/forward edge case (drawer never lingers over
a newly loaded section).

**Breakpoint-cross reset**: An open drawer must not persist as a stuck overlay when the viewport grows to
wide. Because the wide-width CSS forces the rail back to `position: static` regardless of `drawerOpen`, the
overlay visually disappears at wide widths automatically; the boolean is additionally reset when crossing to
wide (a `matchMedia` change listener) so reopening at narrow starts closed and the backdrop is not left
mounted.

**Alternatives considered**:
- A separate mobile-only nav component: rejected — duplicates the section list and the session affordance,
  risking drift; the spec explicitly keeps the same information architecture.
- A bottom tab bar: rejected — the user chose the drawer; 5 sections plus a session control is tight for
  bottom tabs and would rehome the footer.
- `<dialog>` element for the drawer: viable for focus-trapping but heavier than needed and harder to style as
  an off-canvas slide consistent with the rail; a plain fixed panel + backdrop + Escape handler meets the
  requirements with less surface.

## Decision 3 — Wide content: bounded horizontal scroll wrapper, reflow where cheap

**Decision**: Every wide data region is wrapped so it can scroll horizontally **within its own bounds** while
the page cannot. Concretely: the app content region and page roots get `max-width: 100%` / `min-width: 0` and
`overflow-x: hidden` at the page level; each wide table (`CatalogTable`, `FileListing`, `UsersPanel` and
`TokensPanel` tables, the container tag/image tables, the repository list) is placed in a wrapper with
`overflow-x: auto` and the table keeps a sensible `min-width` so columns stay legible and scroll rather than
crush. Long single tokens (digests, image names, coordinates) use `overflow-wrap: anywhere` / truncation so
they never dictate page width.

**Rationale**: This is the standard, low-risk way to honor "the page never scrolls sideways, but a genuinely
wide table still shows all its columns" (FR-005, FR-006). Wrapping rather than restructuring each table into
cards keeps the desktop rendering identical and minimizes the change footprint across ~6 tables. Reflow (e.g.
letting the repository list rows stack) is used only where a component already uses flex/list markup and can
narrow for free.

**The flex `min-width: 0` gotcha**: The shell uses `display: flex` with a `.main` column; flex children
default to `min-width: auto`, which lets wide content push the flparent wider than the viewport. The fix
(already partly present as `min-width: 0` on `.main`) is applied consistently to the content column and page
roots so `overflow-x: auto` wrappers actually clip instead of expanding the page.

**Alternatives considered**:
- Card/stacked reflow for every table (label:value per row): rejected as the default — it is a larger visual
  redesign per table, risks desktop regressions, and the spec permits in-region horizontal scroll. May be
  applied selectively only if a table is unusable even with scroll.
- CSS `zoom`/scaling the whole page down: rejected — degrades legibility and tap targets, fights the viewport
  meta, and is not theme-consistent.

## Decision 4 — Fixed-width elements shrink via max-width, not fixed width

**Decision**: Replace hard `width` with `width: min(<target>, 100%)` (or `max-width` + full width) on the
login card (`+layout.svelte` `.card` is 390px → `min(390px, 100%)` with viewport padding), the topbar search
(240px → shrinks/hides label on narrow), and detail panels. Add horizontal padding on the content region so a
full-width card still has breathing room at 320px.

**Rationale**: 390px exceeds a 320–360px phone; `min(target, 100%)` keeps the desktop size while letting it
shrink to fit small phones (FR-007, SC-003) with a one-line change per element and no media query needed for
the width itself.

## Decision 5 — Touch targets ≥ ~44px

**Decision**: At narrow widths, bump the min height/padding of primary controls (nav entries, buttons, tag
open/delete, tab controls) so their smaller dimension is ≥ ~44px (Apple HIG / Material comfortable minimum;
spec asks ~40px, SC-004). Applied via the narrow media block so desktop density is untouched.

**Rationale**: The current controls are sized for a pointer (e.g. nav links `padding: 10px 16px`, small
uppercase buttons). A media-scoped min-size satisfies SC-004 without changing desktop look.

## Decision 6 — Verification: a narrow-viewport Playwright spec, per-test viewport override

**Decision**: Add `frontend/tests/mobile.spec.ts`. Each test sets a phone viewport via
`test.use({ viewport: { width: 375, height: 812 } })` (iPhone-class) and a 320px case for the fit check. It
reuses the existing `scripts/e2e.sh` backend + seed (no new project in `playwright.config.ts`, so the desktop
suite's run time and behavior are unchanged). Assertions:
- rail hidden + ☰ visible at narrow; drawer opens, shows all sections + session control; selecting a section
  navigates and closes; backdrop tap and Escape close without navigating (US1);
- `document.scrollingElement.scrollWidth <= clientWidth` (no horizontal page scroll) on representative
  screens; a wide table's own wrapper is scrollable (US2);
- the sign-in card fits within a 320px viewport (US3).

**Rationale**: Per-test `test.use({ viewport })` is the lightest way to add mobile coverage to a suite whose
single project is Desktop Chrome; it keeps SC-005/SC-006 (desktop unchanged) literally true because the
existing specs keep their Desktop Chrome viewport. Driving the real app satisfies Principle II.

**Alternatives considered**:
- A second Playwright project with a mobile device descriptor: rejected — doubles the whole suite across two
  viewports and slows CI for no added coverage of the desktop paths; per-test override targets exactly the new
  scenarios.
- Unit-testing CSS/media queries: rejected — media-query behavior is only meaningfully verifiable by driving a
  real rendered viewport, which Playwright already provides.

## Resolved unknowns

- Breakpoint value → 768px (Decision 1).
- Minimum width → 320px supported; login card and panels validated there (Decision 4).
- Drawer dismissal semantics → navigate / backdrop / Escape, state in `AppShell` (Decision 2).
- Content overflow strategy → bounded `overflow-x: auto` wrappers + `min-width: 0` on flex columns
  (Decision 3).
- Test strategy → per-test viewport override in a new `mobile.spec.ts` (Decision 6).

No open NEEDS CLARIFICATION items remain.
