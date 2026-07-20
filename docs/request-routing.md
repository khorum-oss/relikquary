# Request Routing

How an incoming HTTP request is dispatched to a controller, after passing the security filter chain.

```mermaid
flowchart TD
  in["Incoming HTTP request"] --> sec["Security filter chain<br/>authenticate + authorize"]
  sec --> path{"path prefix"}

  path -->|"/v2/**"| oci["ContainerRegistryController<br/>OCI push / pull / tags / delete"]
  path -->|"/repositories/{repo}/**"| mvn["RepositoryController<br/>Maven publish / resolve / browse"]
  path -->|"/api/repositories/{repo}/containers"| cbrowse["ContainerBrowseController<br/>images · tags · manifest detail · trust"]
  path -->|"/catalog"| cat["CatalogController<br/>cross-repo artifact catalog"]
  path -->|"/repositories"| repos["RepositoryController<br/>configured-repo list"]
  path -->|"/stats"| stats["StatsController<br/>dashboard metrics"]
  path -->|"/api/admin/users"| users["UserController"]
  path -->|"/api/admin/tokens"| tokens["TokenController"]
  path -->|"/preferences · /api/me"| pref["PreferenceController"]
  path -->|"/cleanup"| clean["CleanupController<br/>retention"]
  path -->|"/ · /ui/**"| uic["UiController<br/>SPA static host + fallback"]
```

## Surfaces at a glance

| Prefix | Controller | Purpose |
|--------|-----------|---------|
| `/v2/**` | `ContainerRegistryController` | OCI/Docker registry protocol |
| `/repositories/{repo}/**` | `BrowseController` / `RepositoryController` | Maven publish, resolve, folder browse |
| `/api/repositories/{repo}/containers` | `ContainerBrowseController` | Container browse JSON for the web UI |
| `/catalog` | `CatalogController` | Cross-repository searchable artifact catalog |
| `/stats` | `StatsController` | Dashboard counts |
| `/api/admin/users`, `/api/admin/tokens` | `UserController`, `TokenController` | Managed users and API tokens |
| `/preferences`, `/api/me` | `PreferenceController` | Per-user theme and identity |
| `/cleanup` | `CleanupController` | Snapshot retention operations |
| `/`, `/ui/**` | `UiController` | Serves the built SvelteKit SPA |
