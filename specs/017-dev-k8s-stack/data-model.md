# Data Model: Local Development Kubernetes Stack

Not a data feature — the "entities" are the Kubernetes resources of the dev stack and the addresses it
exposes. All live in the `relikquary-dev` namespace and come up / tear down as a unit.

## Resources (deploy/k8s/relikquary-dev.yaml)

| Resource | Kind | Key attributes |
|----------|------|----------------|
| `relikquary-dev` | Namespace | Scopes and isolates the whole stack; `kubectl delete namespace relikquary-dev` removes everything. |
| `relikquary-config` | ConfigMap | `RELIKQUARY_SECURITY_ENABLED=false`, `RELIKQUARY_OBSERVABILITY_REQUEST_LOG_ENABLED=true`, `RELIKQUARY_STORAGE_ROOT=/data`, persistence=postgres + DB URL/user, upstream proxy URLs. |
| `relikquary-secrets` | Secret | Throwaway `RELIKQUARY_DB_PASSWORD` (dev-only, non-production). |
| `relikquary-data` | PVC (RWO, 2Gi) | Artifact storage for the backend (`/data`). |
| `relikquary-postgres-data` | PVC (RWO, 2Gi) | PostgreSQL data. |
| `relikquary-postgres` | Deployment + Service | Application-state DB; ClusterIP (internal only). |
| `relikquary-backend` | Deployment + Service | API; **Service = LoadBalancer port 8081 + pinned nodePort 30081**; pod `securityContext` uid/gid/fsGroup 999; non-root init container waits for Postgres. |
| `relikquary-frontend` | Deployment + Service | UI; **Service = NodePort (auto-assigned)**; env `RELIKQUARY_BACKEND=http://relikquary-backend:8081`. |

## Exposed addresses (the external "contract")

| Address | Backing | Stability | Where it works |
|---------|---------|-----------|----------------|
| **API / Maven** `http://localhost:8081` | LoadBalancer `port: 8081` | **Fixed** across teardown/redeploy | Docker Desktop (localhost) |
| **API / Maven** `http://<node-ip>:30081` | pinned `nodePort: 30081` | **Fixed** | kind/minikube (node IP) — deterministic fallback |
| **UI** `http://localhost:<random>` | NodePort (auto) | Random — changes each teardown | Local clusters; printed by the tooling |

**Invariants**:
- The backend Service's `port` (8081) is the canonical backend port **in-cluster and external** — the
  frontend proxies to `relikquary-backend:8081`, not 8080.
- The dev stack never enables auth and never carries a real secret.
- Every container satisfies `runAsNonRoot`; only fully-transferred state persists on the PVCs.

## Lifecycle (state)

```text
absent ── deploy ──▶ Ready (DB→API→UI, API waits for DB; pods roll out non-root)
Ready  ── up (apply) ──▶ Ready         (idempotent; "unchanged" when nothing differs)
Ready  ── rebuild + restart ──▶ Ready  (pods replaced with new :local image)
Ready  ── down ──▶ absent               (namespace deleted)
```
