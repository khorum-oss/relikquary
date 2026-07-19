# Quickstart: Validate the Responsive Web UI

How to run and eyeball the mobile behavior, and how the automated checks prove it. Frontend-only; no backend
change.

## Prerequisites

- Backend jar built: `gradle :backend:bootJar` (from repo root).
- Frontend deps installed: `npm --prefix frontend ci` (or `install`).

## Automated verification (the source of truth)

```bash
# Type/markup check
npm --prefix frontend run check

# Production build
npm --prefix frontend run build

# End-to-end: desktop suite (unchanged) + the new narrow-viewport mobile suite.
# scripts/e2e.sh starts the backend, seeds data, and runs all Playwright specs (including mobile.spec.ts).
bash frontend/scripts/e2e.sh
```

Expected: `check` reports 0 errors; `build` succeeds; **all** Playwright specs pass, including the new
`tests/mobile.spec.ts`, and the pre-existing desktop specs pass **unmodified** (evidence for SC-005/SC-006).

## Manual walk-through (optional, matches the contract)

Start the app against the running backend (`bash frontend/scripts/e2e.sh` leaves `vite dev` on
`http://localhost:5173`, or run `npm --prefix frontend run dev`). Use the browser devtools device toolbar.

### US1 — Navigation (narrow, e.g. 375×812)

1. Load `/`. Confirm the left rail is gone and a ☰ control shows in the header. *(N1, N6-inverse)*
2. Tap ☰ → the drawer slides in over the content with all sections and the Sign In / Sign Out control. *(N2)*
3. Tap a section → it navigates and the drawer closes. *(N3)*
4. Reopen ☰, tap the dimmed backdrop → closes, route unchanged. *(N4)*
5. Reopen ☰, press Escape → closes, route unchanged. *(N5)*
6. Widen the window past 768px → the permanent rail returns and the ☰ disappears; no stuck overlay. *(N7)*

### US2 — No sideways page scroll (narrow)

7. Visit `/`, `/c/apps`, a container image's tags, `/dashboard` (catalog), a repo's files, and `/users`.
   Confirm the **page** never scrolls horizontally. *(C1)*
8. On a wide table (catalog / users), confirm you can scroll the **table** sideways within its own area while
   the header and surroundings stay put. *(C2)*

### US3 — Small phone (320px)

9. Set the viewport to 320px wide. Open Sign In → the card fits within the screen, fields usable. *(F1)*
10. Confirm the search box and any open detail panel span the width without overflow. *(F2)*
11. Confirm nav entries and buttons are comfortably tappable (not pointer-sized). *(F3)*

## What to check for regressions

- At a desktop width the app looks exactly as before (same rail, spacing, tables). *(SC-006, I2)*
- No new network calls or changed request shapes (I1) — the Network tab is identical to before.
- Only `--rq-*` theme tokens are used for any new styling (I3).

## Reference

- Contract: [contracts/responsive-ui.md](./contracts/responsive-ui.md)
- Plan: [plan.md](./plan.md) · Research: [research.md](./research.md)
