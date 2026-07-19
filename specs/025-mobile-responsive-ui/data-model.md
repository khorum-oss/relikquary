# Data Model: Mobile-Friendly Responsive Web UI

**Not applicable.** This feature introduces no data entities, no persistence, and no API payload changes. It
adapts the presentation and layout of the existing web UI only (FR-009).

The only client-side state introduced is transient UI state, not a domain entity:

| UI state | Owner | Type | Meaning | Lifecycle |
|----------|-------|------|---------|-----------|
| `drawerOpen` | `AppShell.svelte` | boolean (`$state`) | Whether the navigation drawer overlay is shown at narrow widths | Ephemeral; defaults `false`; set `true` by the ☰ control; reset `false` on navigate, backdrop tap, Escape, and when crossing to a wide viewport |
| `isNarrow` (optional) | `AppShell.svelte` | boolean (derived from `matchMedia`) | Whether the viewport is at/below the breakpoint | Ephemeral; read-only; drives the breakpoint-cross reset |

Both are view-local and never persisted or transmitted. No existing store, API type, or backend model is
modified.
