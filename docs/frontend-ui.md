# Frontend UI

A SvelteKit 5 (runes) single-page app, vault-themed, served by the backend in production. It is responsive as
of feature 025 (see the drawer state machine below).

## Route map

```mermaid
flowchart TD
  root["/ — Repositories + Catalog<br/>(tabs: Catalog / Repositories)"]
  dash["/dashboard — stats"]
  pub["/publish — upload artifacts"]
  users["/users — Users & Tokens"]
  settings["/settings — theme, preferences"]

  rbrowse["/r/{repo}/{path}<br/>Maven folder / file browser"]
  cimages["/c/{repo}<br/>Container image list"]
  ctags["/c/{repo}/{image}<br/>Tags + manifest detail + trust badge"]

  root -->|maven repo| rbrowse
  root -->|container repo| cimages
  cimages --> ctags
```

## Component shell

```mermaid
flowchart TD
  layout["+layout.svelte<br/>theme, auth, login overlay"] --> shell["AppShell<br/>owns drawerOpen state"]
  shell --> sidebar["Sidebar<br/>rail (wide) / drawer (narrow)"]
  shell --> topbar["Topbar<br/>title · search · ☰ (narrow)"]
  shell --> content["Route page (content region)"]

  content --> catalog["CatalogTable"]
  content --> repolist["RepositoryRow list"]
  content --> filelist["FileListing + FileDetailsPanel"]
  content --> module["ModuleDetail"]
  content --> manifest["ManifestDetail + TrustBadge"]
  content --> admin["UsersPanel / TokensPanel"]
  content --> stats["StatCards"]
```

## Responsive navigation (feature 025)

A single 768px breakpoint separates the permanent desktop rail from a mobile overlay drawer. `AppShell` owns
the open/closed state.

```mermaid
stateDiagram-v2
  [*] --> Wide

  Wide --> Narrow: viewport <= 768px
  Narrow --> Wide: viewport > 768px (drawer force-closed)

  state Wide {
    [*] --> RailVisible
    RailVisible: Permanent 216px rail, no hamburger
  }

  state Narrow {
    [*] --> Closed
    Closed --> Open: tap hamburger (nav-toggle)
    Open --> Closed: choose a destination
    Open --> Closed: tap backdrop
    Open --> Closed: press Escape
  }
```

## Notes

- **Content that is too wide** (catalog, file listing, container tag/image tables, users/tokens tables) scrolls
  inside its own `.rq-scroll-x` region so the page never scrolls sideways as a whole.
- **The drawer reuses the same `Sidebar` markup** as the desktop rail, so the section list and the
  sign-in/out affordance are identical in both modes.
- **In development** the app runs under `vite dev`, which proxies `/api`, `/v2`, and repository paths to the
  backend on `:8080`. **In production** the static build is served by the backend's `UiController`.
