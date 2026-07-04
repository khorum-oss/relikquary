# Contract: Dev-Stack Interfaces

Three interfaces: the helper-script CLI, the Gradle tasks, and the externally-exposed addresses.

## 1. `deploy/dev-k8s.sh <command>`

| Command | Guarantee |
|---------|-----------|
| `deploy` (default) | Build images → apply manifest → roll deployments → print status. The full one-shot. |
| `build` | (Re)build the backend + frontend `:local` images. Fails with a clear message if Docker is absent. |
| `up` | `kubectl apply` the manifest only — **no rebuild**. Idempotent (reports `unchanged` when nothing differs). Prints URLs. |
| `restart` | Roll the deployments (`rollout restart`) and wait — the step that makes running pods pick up a rebuilt `:local` image. |
| `status` | Print pod readiness + the **fixed API URL** and the **current (random) UI URL**. |
| `down` | Delete the `relikquary-dev` namespace (idempotent). |

- Each command guards its required CLI (`docker` / `kubectl`) and fails with a friendly message if
  missing (never a raw stack trace).
- `up`, `deploy`, and `status` all print the API + UI addresses.

## 2. Gradle tasks (`deployment` group)

| Task | Guarantee |
|------|-----------|
| `k8sDeployDev` | `kubectl apply` the dev manifest — **apply-only** (no image build, no rollout). Points the user at `dev-k8s.sh` for build/restart/status. |
| `k8sDeleteDev` | Delete the `relikquary-dev` namespace (`--ignore-not-found`). |

- Both resolve `kubectl` to an absolute path (the Gradle daemon's spawn PATH may omit `/usr/local/bin`)
  and fail friendly if it is absent.

## 3. Externally-exposed address contract

| Aspect | Contract |
|--------|----------|
| API / Maven port | **Fixed** `localhost:8081` (Docker Desktop) or **fixed** `<node-ip>:30081` (fallback). Unchanged across teardown/redeploy. |
| UI port | Auto-assigned NodePort; **not** stable across teardown; surfaced by the tooling. |
| Auth | Disabled — all requests permitted (dev only). |
| Request log | On — each request emits a structured access line (method, repository, path, status, bytes, duration). |
| App/protocol behavior | Unchanged — identical served bytes, status codes, repository layout to any other Relikquary instance. |

## Validation obligations (operational, not unit tests)

- **Manifest schema** — `kubectl apply --dry-run=server` (into an existing namespace) reports all
  resources valid.
- **Rollout** — after `deploy`, all three deployments reach Ready with no manual intervention (FR-007).
- **Fixed address** — `curl http://localhost:8081/api/repositories` → 200 before and after a
  teardown+redeploy, unchanged (FR-003 / SC-002).
- **UI proxy** — `curl http://localhost:<ui-port>/api/repositories` → 200 (frontend → backend:8081).
- **Request log** — a request produces a matching `relikquary.access` line (FR-006 / SC-003).
- **Idempotent apply** — a second `up` on an unchanged stack reports only `unchanged` (FR-010).
- **Rebuild→restart** — `restart` after a build replaces the running pods (FR-009 / SC-006).
- **Gradle green** — `./gradlew build` stays green with the new tasks (Constitution III).
