# Feature Specification: Mobile-Friendly Responsive Web UI

**Feature Branch**: `025-mobile-responsive-ui`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "Mobile-friendly responsive web UI. Make the entire Relikquary web UI usable on phones and small tablets, not just desktop — today the layout is fixed-width with a permanently visible 216px navigation rail and wide tables that overflow narrow screens, and there is not a single responsive breakpoint in the app. On narrow viewports the navigation rail collapses off-screen and is opened on demand as a slide-in overlay drawer via a hamburger (☰) control in the section topbar; the drawer dismisses when a destination is chosen, when its backdrop is tapped, and on Escape, and the sign-in/out session affordance remains reachable inside it. Wide content — the repository list, container image/tag tables, the artifact catalog table, the file listing, and the users/tokens panels — must never force the whole page to scroll sideways: each reflows to the available width or scrolls horizontally within its own contained region while the page itself stays within the viewport. Fixed-width elements (the login card, the topbar artifact search, detail panels) adapt down to small-phone widths (~320px). Interactive targets (nav links, buttons, tag/delete affordances, tab controls) are comfortably tappable on touch screens. The desktop experience is unchanged at wide widths — the rail stays permanently visible and nothing reflows — so this is additive and presentation-only: it changes no API, no data, no backend behavior, and keeps the vault-themed look consistent across sizes. Out of scope: a native mobile app, offline/PWA support, touch gestures beyond ordinary taps/scrolls, and any redesign of the desktop layout or information architecture."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Navigate the whole app from a phone (Priority: P1)

A person opens Relikquary on a phone-sized screen. Instead of a navigation rail permanently eating half
the width, the rail is hidden and the section header shows a menu (☰) control. Tapping it slides the full
navigation in as an overlay over the content, including the sign-in / sign-out affordance. Choosing a
destination navigates and closes the drawer; tapping the dimmed backdrop or pressing Escape closes it
without navigating. At desktop widths there is no menu control — the rail is simply always visible, exactly
as today.

**Why this priority**: Navigation is the gateway to every other screen. If a phone user cannot reach the
sections at all — or the rail permanently occupies the screen — nothing else about mobile support matters.
This is the minimum viable slice that makes the app navigable on a phone.

**Independent Test**: Load the app at a narrow viewport, confirm the rail is off-screen and a menu control is
present; open the drawer, confirm all sections and the session control are reachable; select a section and
confirm it navigates and the drawer closes; reopen and dismiss it via backdrop and via Escape. Then load at a
wide viewport and confirm the rail is permanently visible with no menu control.

**Acceptance Scenarios**:

1. **Given** the app at a narrow (phone) viewport, **When** it loads, **Then** the navigation rail is not
   occupying content width and a menu (☰) control is visible in the section header.
2. **Given** the app at a narrow viewport, **When** the menu control is activated, **Then** the navigation
   slides in over the content as an overlay, showing every section and the sign-in / sign-out affordance.
3. **Given** the navigation drawer is open, **When** a destination is chosen, **Then** the app navigates to
   that section and the drawer closes.
4. **Given** the navigation drawer is open, **When** the backdrop is tapped or Escape is pressed, **Then** the
   drawer closes and no navigation occurs.
5. **Given** the app at a wide (desktop) viewport, **When** it loads, **Then** the navigation rail is
   permanently visible and no menu control is shown.

---

### User Story 2 - Read wide content without sideways page scrolling (Priority: P2)

A person browses the repository list, a container image's tags, the artifact catalog, a repository's file
listing, or the users/tokens admin panels on a narrow screen. The whole page never scrolls sideways: each
wide region either reflows to fit the width or scrolls horizontally only within its own bounded area, so the
surrounding page — header, navigation affordance, and other content — stays put and fully readable.

**Why this priority**: Once navigation works, the content screens are the substance of the app. Wide tables
that push the entire page sideways make columns and controls unreachable and are the most visible symptom of
a desktop-only layout. This makes the primary read paths usable on a phone.

**Independent Test**: At a narrow viewport, visit each wide-content screen and confirm the document does not
scroll horizontally (the page's own horizontal scroll extent equals its visible width); where a data table is
too wide to reflow, confirm it scrolls within its own container while the page stays fixed.

**Acceptance Scenarios**:

1. **Given** any wide-content screen at a narrow viewport, **When** it renders, **Then** the page does not
   scroll horizontally as a whole.
2. **Given** a data table wider than the screen, **When** viewed at a narrow viewport, **Then** the table
   scrolls sideways only within its own region and the rest of the page remains stationary.
3. **Given** a repository or catalog listing at a narrow viewport, **When** it renders, **Then** each entry's
   primary information (name, key metadata, and its action) is reachable without horizontal page scrolling.

---

### User Story 3 - Fixed panels and touch targets fit a small phone (Priority: P3)

A person uses the sign-in card, the artifact search, detail panels, and action buttons on a small phone
(around 320px wide). Nothing is clipped off-screen or overflowing the viewport, form fields and the search
box span the available width, and interactive controls are large enough to tap reliably rather than sized for
a mouse pointer.

**Why this priority**: These are the finishing touches that make the app feel usable rather than merely
reachable. They matter, but a user can still navigate and read (P1, P2) before every panel and target is
tuned, so this rounds out the experience last.

**Independent Test**: At a ~320px viewport, open the sign-in card and confirm it fits within the viewport with
usable fields; confirm the search input and detail panels span the width without overflow; confirm primary
tap targets meet a comfortable minimum touch size.

**Acceptance Scenarios**:

1. **Given** the sign-in card at a ~320px viewport, **When** it is shown, **Then** it fits within the viewport
   with no clipped content and its fields are usable.
2. **Given** the artifact search and detail panels at a narrow viewport, **When** shown, **Then** they span the
   available width without causing overflow.
3. **Given** the primary interactive controls (navigation entries, buttons, tag open/delete, tab controls) on a
   touch screen, **When** a user taps them, **Then** each target is large enough to activate comfortably.

---

### Edge Cases

- **Rotation / resize across the breakpoint**: When the viewport crosses between narrow and wide (device
  rotation or window resize), the layout switches modes cleanly — an open drawer does not linger as a stuck
  overlay once the rail becomes permanent, and the permanent rail does not leave a phantom menu control.
- **Drawer open during navigation via browser back/forward**: The drawer does not remain open over a newly
  loaded section after a history navigation.
- **Very long single-token content** (a long image name, digest, or coordinate): wraps or is contained within
  its region rather than forcing the page wider than the viewport.
- **A data table with many columns** (e.g. layers or catalog rows): the in-region horizontal scroll is
  discoverable and does not hide the last column behind the viewport edge.
- **Landscape phone / small tablet** widths between phone and desktop: resolve to one mode without a broken
  in-between state (no half-shown rail overlapping content).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: At narrow viewports the navigation rail MUST be hidden from the content flow (not occupying
  horizontal space), and the app MUST present a menu (☰) control in the section header to reveal navigation.
- **FR-002**: Activating the menu control MUST reveal the full navigation as an overlay drawer above the page
  content, including every section link and the current session affordance (sign in, or the signed-in user
  with sign out).
- **FR-003**: The navigation drawer MUST close when a destination is selected (after navigating), when the
  backdrop is activated, and when Escape is pressed; only selecting a destination changes the current section.
- **FR-004**: At wide (desktop) viewports the navigation rail MUST remain permanently visible with no menu
  control, and no content MUST reflow relative to today's desktop layout.
- **FR-005**: No screen MUST cause the page as a whole to scroll horizontally at any supported viewport width;
  the document's horizontal extent MUST stay within the viewport.
- **FR-006**: Wide data regions (repository list, container image and tag tables, artifact catalog table,
  repository file listing, users and tokens panels) MUST either reflow to the available width or scroll
  horizontally within their own bounded region without moving the surrounding page.
- **FR-007**: Fixed-width elements (the sign-in card, the topbar artifact search, and detail panels) MUST
  adapt down to a small-phone width of approximately 320px without clipping or overflow.
- **FR-008**: Primary interactive targets (navigation entries, buttons, tag open/delete affordances, and tab
  controls) MUST present a comfortably tappable touch size on touch screens.
- **FR-009**: The change MUST be presentation-only: it MUST NOT alter any API, stored data, or backend
  behavior, and every existing capability MUST remain functionally available at both narrow and wide widths.
- **FR-010**: The vault-themed visual language (palette, type treatments, badges) MUST remain consistent
  across narrow and wide viewports; responsiveness MUST NOT introduce an off-theme appearance.
- **FR-011**: All existing browse, publish, admin, and container flows MUST continue to pass their existing
  end-to-end checks unchanged, confirming no regression at the default (desktop) viewport.

### Key Entities

Not applicable — this feature adds no data or entities. It changes only the presentation and layout of the
existing web UI.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a phone-sized screen, a user can reach every one of the app's navigation sections using only
  the on-screen menu control — 100% of sections are reachable without a desktop-width layout.
- **SC-002**: Across every primary screen (repositories, a repository's files, a container image's tags, the
  catalog, the dashboard, publish, users & tokens, settings, and the sign-in card) at phone width, zero
  screens cause horizontal scrolling of the page as a whole.
- **SC-003**: The sign-in card and all detail panels fit within a 320px-wide viewport with no clipped or
  off-screen content on 100% of the covered screens.
- **SC-004**: Primary interactive controls meet a comfortable minimum touch-target size (at least ~40px in the
  smaller dimension) on touch screens.
- **SC-005**: 100% of the existing UI end-to-end scenarios continue to pass at the default desktop viewport,
  demonstrating no functional regression.
- **SC-006**: The desktop layout at wide widths is visually unchanged from before the feature (same rail,
  spacing, and content arrangement), confirmed by the desktop scenarios still passing without modification.

## Assumptions

- **Breakpoint**: A single width threshold distinguishes "narrow" (phone / small tablet, drawer navigation)
  from "wide" (desktop, permanent rail). A conventional threshold around 768px is assumed unless design review
  indicates otherwise; the exact value is an implementation detail chosen during planning.
- **Supported minimum width**: ~320px is treated as the smallest phone width to support; narrower is not a
  goal.
- **Existing viewport configuration**: The app already declares a mobile viewport (device-width scaling), so
  no additional page-level scaling configuration is assumed necessary.
- **No new information architecture**: The same sections, screens, and content hierarchy are kept; only their
  layout adapts. No feature is hidden or added for mobile.
- **Touch and pointer coexist**: The same markup serves both; "comfortably tappable" is achieved via sizing
  and spacing rather than separate mobile-only controls or gesture handlers.
- **Theming**: The existing theme token system is reused as-is; responsiveness does not require new palette or
  theme capabilities.
- **Verification**: Responsive behavior is validated by driving the real UI at representative narrow and wide
  viewports (in addition to the existing desktop end-to-end suite), consistent with the project's
  integration-verified testing discipline.
