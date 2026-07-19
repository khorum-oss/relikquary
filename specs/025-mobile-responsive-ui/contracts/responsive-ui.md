# UI Contract: Responsive Behavior

The observable contract the responsive UI must satisfy. This is a **presentation** contract (no HTTP/API
surface): each clause is expressed as a testable behavior at a given viewport. Verified by
`frontend/tests/mobile.spec.ts` (narrow) and the existing e2e suite (wide/desktop, unchanged).

## Breakpoint

- **Narrow** = viewport width ≤ **768px**. **Wide** = width ≥ **769px**.
- All responsive rules apply only in the narrow range. In the wide range the rendered layout is identical to
  the pre-feature desktop layout.

## Navigation (US1)

| # | Given (viewport) | When | Then |
|---|------------------|------|------|
| N1 | Narrow | Page loads | The navigation rail occupies **0** content width; a menu control `[data-testid="nav-toggle"]` is visible in the topbar. |
| N2 | Narrow | `nav-toggle` activated | The navigation drawer `[data-testid="nav-drawer"]` is shown as an overlay above content, containing every section link **and** the session affordance (`login-button`, or `current-user` + `logout-button`). |
| N3 | Narrow, drawer open | A section link is chosen | The app navigates to that section **and** the drawer is no longer shown. |
| N4 | Narrow, drawer open | The backdrop `[data-testid="nav-backdrop"]` is tapped | The drawer is no longer shown; the current route is unchanged. |
| N5 | Narrow, drawer open | `Escape` is pressed | The drawer is no longer shown; the current route is unchanged. |
| N6 | Wide | Page loads | The rail is permanently visible; **no** `nav-toggle` is shown. |
| N7 | Narrow→Wide | Viewport grows across the breakpoint while the drawer is open | No overlay/backdrop remains; the rail is the permanent rail. |

## Content overflow (US2)

| # | Given (viewport) | When | Then |
|---|------------------|------|------|
| C1 | Narrow | Any primary screen renders | `document.scrollingElement.scrollWidth ≤ clientWidth` (the page does not scroll horizontally as a whole). |
| C2 | Narrow | A table wider than the screen renders | The table's wrapper `[data-testid$="-scroll"]` (or the table region) has `scrollWidth > clientWidth` (scrolls within itself) while C1 still holds for the page. |
| C3 | Narrow | A repository/catalog/file/user row renders | The row's primary name, key metadata, and its action control are all reachable without horizontal **page** scroll. |

Primary screens covered by C1: `/` (repositories), `/r/[repo]/…` (files), `/c/[repo]` (images),
`/c/[repo]/[…image]` (tags), `/dashboard`, `/publish`, `/users`, `/settings`, and the sign-in overlay.

## Fixed panels & touch targets (US3)

| # | Given (viewport) | When | Then |
|---|------------------|------|------|
| F1 | 320px wide | The sign-in card is shown | The card fits within the viewport (its right edge ≤ viewport width); fields are usable. |
| F2 | Narrow | The topbar search / a detail panel is shown | It spans the available width without causing page overflow (C1 holds). |
| F3 | Touch/narrow | A primary control (nav entry, button, `tag-open`, `tag-delete`, tab) is measured | Its smaller dimension is ≥ ~40px. |

## Invariants

- **I1 (no API change)**: No network request shape, header, or endpoint differs from before the feature.
- **I2 (desktop unchanged)**: Every existing e2e scenario passes unmodified at the Desktop Chrome viewport
  (SC-005, SC-006).
- **I3 (theme consistency)**: All responsive styles use existing `--rq-*` tokens; no new palette/type values.
- **I4 (no new dependency)**: No addition to `package.json` runtime deps or `verification-metadata.xml`.

## Test data-testids introduced

- `nav-toggle` — the ☰ control in the topbar (narrow only).
- `nav-drawer` — the drawer container shown when open (narrow only).
- `nav-backdrop` — the dimmed dismiss surface behind the open drawer.
- `*-scroll` — the bounded horizontal-scroll wrapper around a wide table (e.g. `catalog-scroll`).

Existing testids (`repo-link`, `image-link`, `tag-open`, `tag-delete`, `login-button`, `current-user`,
`logout-button`, section links, etc.) are reused unchanged so the desktop suite is unaffected.
