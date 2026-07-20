# Relikquary Architecture Diagrams

A set of Mermaid diagrams describing how Relikquary is put together. GitHub renders Mermaid in Markdown
natively, so each file below shows its diagrams inline when viewed on GitHub.

Relikquary is a self-hosted artifact repository server: it speaks the **Maven/Gradle** repository protocol and
the **OCI/Docker container registry** (`/v2`) protocol, stores bytes faithfully on a **filesystem or S3**
backend, can act as a **hosted / proxy / group** repository, and ships a **SvelteKit** web UI for browsing,
publishing, and administration.

## Index

| Diagram | What it shows |
|---------|---------------|
| [Architecture overview](./architecture-overview.md) | Clients, HTTP surfaces, core services, storage, and DB at a glance |
| [Repository model](./repository-model.md) | Repository kinds (hosted/proxy/group), formats, types, and access |
| [Request routing](./request-routing.md) | How an incoming HTTP path maps to a controller |
| [Maven flows](./maven-flows.md) | Publish and resolve sequences (hosted, proxy, group) |
| [Container registry flows](./container-registry-flows.md) | OCI `/v2` push and pull (hosted and proxy) |
| [Cosign verification](./cosign-verification.md) | Advisory trust-status decision flow (feature 024) |
| [Storage & data model](./storage-and-data-model.md) | Storage abstraction and the persisted entities |
| [Security & authorization](./security-and-authz.md) | Authentication and per-repository authorization |
| [Frontend UI](./frontend-ui.md) | SPA route map, component shell, and the responsive drawer (feature 025) |

> These diagrams are hand-maintained documentation, not generated artifacts. When a subsystem changes
> materially, update the relevant file here.
