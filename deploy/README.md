# Deploying Relikquary

Everything you need to run Relikquary — from a one-command local try-out to a Kubernetes deployment.
Relikquary ships **two images** (a backend/API and a frontend/UI) plus a **combined** single-image
option. Nothing is published to a registry; you build the images locally (or push them to your own).

**Two kinds of storage, kept separate:**

- **Artifact storage** — the jars/poms themselves: filesystem volume (default) or S3.
- **Application state** — API tokens, users, settings, publish history: embedded **SQLite** (default) or
  external **PostgreSQL**.

---

## Quick start — try it locally (one command)

The fastest way to see it running. Needs **Docker** (Docker Desktop, or a running daemon) and free ports
**8080 / 8081 / 5432**. Auth is disabled and credentials are throwaway — nothing to configure.

```bash
docker compose -f deploy/docker-compose.dev.yml up --build
```

The first run builds the images (a few minutes: the backend jar + the UI). It starts PostgreSQL, waits
for it, then the backend, then the UI. You're up when the backend logs `Started RelikquaryApplication`.

| Open this | Where |
|-----------|-------|
| **Web UI** | http://localhost:8081 |
| **API + Maven repositories** | http://localhost:8080 |
| **PostgreSQL** | `localhost:5432` — db `relikquary`, user `relikquary`, password `relikquary` |

**Is it working?** In another terminal:

```bash
docker compose -f deploy/docker-compose.dev.yml ps        # all services "running"/"healthy"

curl -s http://localhost:8080/api/stats                   # -> {"repositories":...}

# publish an artifact (auth is off here) and read it back
printf 'hello' | curl -s -H 'Content-Type: application/octet-stream' \
  -X PUT --data-binary @- http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
curl -s http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar   # -> hello

# see the tables Hibernate created in PostgreSQL
docker compose -f deploy/docker-compose.dev.yml exec postgres \
  psql -U relikquary -d relikquary -c '\dt'                # -> api_token, setting
```

Then open **http://localhost:8081**, click around the sidebar, and try refreshing on a page like
`/dashboard` — it should reload the app, not error.

**Stop it:**

```bash
docker compose -f deploy/docker-compose.dev.yml down       # stop, keep data
```

```bash
docker compose -f deploy/docker-compose.dev.yml down -v     # stop and wipe the volumes (fresh start)
```

> ⚠️ The dev stack disables authentication for convenience — never use `docker-compose.dev.yml` to
> expose a real server. For an auth-on setup, see **Run with Docker Compose** below.

### Reading the logs / "is the frontend broken?"

The frontend is **nginx**, which logs at `notice`/`info` level — so a healthy startup looks noisy but is
fine. These lines are all **normal**, not errors:

```
/docker-entrypoint.sh: /docker-entrypoint.d/ is not empty, will attempt to perform configuration
10-listen-on-ipv6-by-default.sh: info: /etc/nginx/conf.d/default.conf differs from the packaged version
20-envsubst-on-templates.sh: Running envsubst on /etc/nginx/templates/default.conf.template ...
/docker-entrypoint.sh: Configuration complete; ready for start up
... start worker process ...
```

Once you see **`Configuration complete; ready for start up`** and worker processes, the UI is serving. A
*real* problem shows up as the image failing to **build** or a container that keeps restarting — check:

```bash
docker compose -f deploy/docker-compose.dev.yml logs -f frontend   # or backend / postgres
```

```bash
docker compose -f deploy/docker-compose.dev.yml build frontend     # surfaces build errors on their own
```

### Faster iteration (native, no image rebuilds)

Run only PostgreSQL in Docker and the apps natively for instant reloads:

```bash
docker compose -f deploy/docker-compose.dev.yml up -d postgres      # just the database

# backend (new terminal)
RELIKQUARY_PERSISTENCE_BACKEND=postgres \
RELIKQUARY_DB_URL=jdbc:postgresql://localhost:5432/relikquary \
RELIKQUARY_DB_USER=relikquary RELIKQUARY_DB_PASSWORD=relikquary \
  ./gradlew :backend:bootRun

# frontend with hot reload (new terminal) — vite proxies /api + repo paths to :8080
cd frontend && npm install && npm run dev                           # http://localhost:5173
```

---

## Artifacts

| File | What it is |
|------|------------|
| `docker-compose.dev.yml` | **Local dev stack** — Postgres + backend/frontend from source, auth off |
| `docker-compose.yml` | Split backend + frontend, persistent volume, **auth on** (embedded SQLite app-state) |
| `docker-compose.postgres.yml` | Overlay for the above: application state in PostgreSQL instead of SQLite |
| `.env.example` | Environment placeholders (copy to `.env`; never commit `.env`) |
| `backend.Dockerfile` | API server image (JRE 21, non-root, readiness healthcheck) |
| `frontend.Dockerfile` | UI image (SvelteKit SPA on non-root nginx; proxies API/repo paths to the backend) |
| `combined.Dockerfile` | Single image serving API + UI (UI under `/ui`) |
| `nginx/default.conf.template` | Frontend reverse-proxy config (`${RELIKQUARY_BACKEND}`) |
| `k8s/relikquary.yaml` | Single-file Kubernetes manifest (backend + frontend + PostgreSQL) starting point, **auth on** — hand-edit the Secret. For real environments, prefer the overlays below. |
| `k8s/base/` | Kustomize **base** shared by every environment (ConfigMap + Deployments + Services + PVCs). No Secret — secrets come from 1Password. |
| `k8s/overlays/stage/`, `k8s/overlays/prod/` | Per-environment **Kustomize overlays** — namespace, image tags, ingress host, sizing, and the 1Password secret sync. Apply with `kubectl apply -k`. |
| `k8s/onepassword/` | 1Password Kubernetes Operator setup: `README.md` (install + item layout + rotation) and `create-items.sh` (generate the passwords into your vault). |
| `k8s/relikquary-dev.yaml` | **Local dev** Kubernetes manifest — the k8s counterpart of `docker-compose.dev.yml`: auth off, throwaway creds, NodePort access, self-contained in the `relikquary-dev` namespace |
| `dev-k8s.sh` | Helper for the dev k8s stack: `build` / `up` / `restart` / `status` / `down` / `deploy` |
| `smoke.sh` | Docker-guarded build + publish/resolve smoke test |

## Build the images

The dev/compose commands above build automatically (`--build`). To build the images by name yourself,
from the repository root:

```bash
./gradlew dockerBuildSplit      # backend + frontend (relikquary-backend:local, relikquary-frontend:local)
```

```bash
./gradlew dockerBuildCombined   # combined API+UI (relikquary:local)
```

```bash
./gradlew dockerBuildBackend    # just one
```

These require the Docker CLI; they fail with a clear message if it is absent. For arm64 (or another arch),
build directly, e.g. `docker build --platform linux/arm64 -f deploy/backend.Dockerfile .` (amd64 by default).

## Run with Docker Compose (auth on)

The production-shaped stack: authentication enabled, a real publisher account, storage on a named volume.

```bash
cp deploy/.env.example deploy/.env       # set RELIKQUARY_PUBLISHER_PASSWORD (keep the {noop}/{bcrypt} prefix)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

- API + Maven repository protocol: `http://localhost:8080/<repo>/...` (point Maven/Gradle clients here).
- UI: `http://localhost:8081`.
- Artifacts persist in the `relikquary-store` volume; application state (SQLite) in `relikquary-db`.

Publish/resolve example (auth is on):

```bash
printf 'bytes' | curl -u publisher:<pw> -H 'Content-Type: application/octet-stream' \
  -X PUT --data-binary @- http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
curl http://localhost:8080/releases/com/example/app/1.0.0/app-1.0.0.jar
```

### With PostgreSQL (application state)

By default, application state — API tokens, users, settings, publish history — lives in an embedded SQLite
database on its own volume (`relikquary-db`). To store it in PostgreSQL instead, layer the overlay (set
`RELIKQUARY_DB_PASSWORD` in `.env` first):

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.postgres.yml \
               --env-file deploy/.env up -d --build
```

This adds a `postgres` service; the backend waits for it to be healthy and Liquibase creates the schema on
first boot. Artifact storage is unaffected — choosing PostgreSQL here does not change where artifacts live.

## Deploy to Kubernetes

```bash
# Build the images and make them available to your cluster (load into the node, or push to a registry
# and edit the image: fields in k8s/relikquary.yaml). Then set real Secret values and apply:
kubectl apply -n <namespace> -f deploy/k8s/relikquary.yaml
kubectl rollout status deploy/relikquary-backend -n <namespace>
```

The manifest wires liveness/readiness probes to `/actuator/health/liveness` and `/actuator/health/readiness`,
separates non-secret config (ConfigMap) from credentials (Secret — **placeholders only**, replace before
use), and persists storage on a `ReadWriteOnce` PVC (single backend replica). An `Ingress` example is
included (commented) to route the UI and the API/repository paths.

It also deploys **PostgreSQL** (Deployment + Service + PVC) for application state, wired to the backend via
the ConfigMap/Secret; an init container makes the backend wait for the database before starting. Set the
`RELIKQUARY_DB_PASSWORD` Secret (shared by Postgres and the backend) before applying. To use embedded
SQLite instead (single replica), set `RELIKQUARY_PERSISTENCE_BACKEND=sqlite` and `RELIKQUARY_DB_PATH` to a
path on the data PVC in the ConfigMap, and delete the three `relikquary-postgres*` resources.

The single-file manifest above is a quick starting point where you hand-edit the Secret. For **stage and
prod**, use the Kustomize overlays below instead — they separate environments and pull secrets from
1Password rather than committing placeholders.

### Stage & prod (Kustomize overlays + 1Password)

`k8s/base/` holds the shared resources; `k8s/overlays/stage/` and `k8s/overlays/prod/` layer on the
per-environment differences. Each overlay lands in its own namespace (`relikquary-stage` /
`relikquary-prod`), sets its own image tags and ingress host, and — crucially — carries **no secret
values**. Passwords are generated in and managed by **1Password**, and the **1Password Kubernetes
Operator** syncs them into a `relikquary-secrets` Secret in each namespace. Full walkthrough (operator
install, item layout, rotation): [`k8s/onepassword/README.md`](k8s/onepassword/README.md).

| Overlay | Namespace | Notes |
|---------|-----------|-------|
| `stage` | `relikquary-stage` | base sizing, `:stage` image tag, `relikquary.stage.example.com` |
| `prod`  | `relikquary-prod`  | larger CPU/memory, 2 frontend replicas, bigger PVCs, `:stable` image tag, `relikquary.example.com` |

**One-time cluster setup** — install the operator + 1Password Connect (see the onepassword README), then
generate the passwords into your vaults:

```bash
op signin
deploy/k8s/onepassword/create-items.sh stage      # generates + stores stage passwords in 1Password
deploy/k8s/onepassword/create-items.sh prod       # …and prod, in a separate vault
```

**Deploy an environment.** Point the `images:` in the overlay's `kustomization.yaml` at your registry, set
the ingress host, then:

```bash
kubectl apply -k deploy/k8s/overlays/stage        # or .../prod
kubectl -n relikquary-stage rollout status deploy/relikquary-backend
```

Preview exactly what will be applied — no cluster needed — with `kubectl kustomize`:

```bash
kubectl kustomize deploy/k8s/overlays/prod        # renders the full manifest to stdout
```

The operator reconciles each `OnePasswordItem` into the `relikquary-secrets` Secret within a few seconds;
if the backend pod starts first it waits in `CreateContainerConfigError` and recovers once the sync lands.
To roll out a rotated password, edit it in 1Password (or `create-items.sh <env> --rotate`) — with the
operator's `autoRestart` on, the pods roll automatically.

> **Images:** this project ships artifacts only (no registry push). Build the two images, push them to
> your registry, and set the overlay `images:` `newName`/`newTag` accordingly (prod should use an
> immutable, promoted tag — never a moving one).

### Local dev cluster (auth off)

For a throwaway stack on a local cluster (Docker Desktop, kind, minikube, k3d) — the k8s equivalent of
`docker-compose.dev.yml` — use `k8s/relikquary-dev.yaml`. It declares and scopes everything to the
`relikquary-dev` namespace, disables auth, and ships a throwaway Postgres password. External access:

- **API / Maven repos → fixed `http://localhost:8081`** (a `LoadBalancer` service — on Docker Desktop
  it binds `localhost:8081`, so build/client config can rely on it). A NodePort can't be 8081 (k8s
  restricts NodePorts to 30000–32767), hence LoadBalancer.
- **UI → a random NodePort** (auto-assigned, so nothing collides). `dev-k8s.sh` prints it on every run,
  or check it with `status`.

> On plain **kind/minikube** the LoadBalancer stays `<pending>`, but the API is also on a **fixed
> NodePort `30081`** — reach it at `http://<node-ip>:30081` (`kubectl get nodes -o wide` for the IP;
> kind can map it to localhost via `extraPortMappings`). Or, for a clean `localhost:8081` on any
> cluster: `kubectl -n relikquary-dev port-forward svc/relikquary-backend 8081:8081`.
>
> **k3d**: `dev-k8s.sh build`/`deploy` auto-imports the images into the cluster (`k3d image import`) —
> k3d nodes have their own image store, so host-built `:local` images aren't visible otherwise. For a
> host-bound `localhost:8081`, create the cluster with the LoadBalancer port mapped, e.g.
> `k3d cluster create dev -p '8081:8081@loadbalancer'`; otherwise use the port-forward above.

**Easiest — the `dev-k8s.sh` helper.** Build + apply + roll + status in one shot:

```bash
deploy/dev-k8s.sh deploy
```

Or the individual subcommands:

```bash
deploy/dev-k8s.sh status
```

```bash
deploy/dev-k8s.sh restart
```

```bash
deploy/dev-k8s.sh up
```

```bash
deploy/dev-k8s.sh down
```

`status` prints pods + URLs; `restart` rolls the deployments to pick up rebuilt images; `up` applies
without rebuilding; `down` deletes the namespace.

> **The `:local`-tag gotcha:** `kubectl apply` after a rebuild does **not** restart pods — the image
> tag is unchanged, so k8s sees no diff. After rebuilding, run `deploy/dev-k8s.sh restart` (or the
> all-in-one `deploy`) to actually roll the new image in. This is the usual "I rebuilt but nothing
> changed" surprise.

**Gradle equivalents** (apply-only — they do *not* build or roll):

```bash
./gradlew k8sDeployDev
```

```bash
./gradlew k8sDeleteDev
```

**Or run the steps yourself** — build both images, apply, and show the node ports:

```bash
docker build -f deploy/backend.Dockerfile  -t relikquary-backend:local  .
docker build -f deploy/frontend.Dockerfile -t relikquary-frontend:local .
kubectl apply -f deploy/k8s/relikquary-dev.yaml
kubectl -n relikquary-dev get svc      # API on :8081 (LoadBalancer); UI on 8080:<random-nodePort>/TCP
```

Not on Docker Desktop (which shares the local image store)? Load the images into the cluster first —
`kind load docker-image relikquary-backend:local relikquary-frontend:local`, or
`minikube image load relikquary-backend:local relikquary-frontend:local`.

The **UI** is the only random port. For a stable UI URL, port-forward it (pick any free local port):

```bash
kubectl -n relikquary-dev port-forward svc/relikquary-frontend 8082:8080
```

The API is already fixed at `localhost:8081` via the LoadBalancer. Only port-forward it if that service
shows `<pending>` (plain kind/minikube without a LB provider):

```bash
kubectl -n relikquary-dev port-forward svc/relikquary-backend 8081:8081
```

> ⚠️ Auth is **disabled** and credentials are throwaway — for local development only, never expose it.

## Storage backends (filesystem ↔ S3)

The same images run against either **artifact** backend — no rebuild, just configuration:

- **Filesystem (default)**: artifacts live on the volume (`/data`) / PVC.
- **S3-compatible**: set `RELIKQUARY_STORAGE_BACKEND=s3` and the `RELIKQUARY_S3_*` values (via `.env` /
  Secret). In compose, drop the volume; in Kubernetes, remove the PVC and raise `replicas` (S3 is shared,
  the RWO PVC is not). See the commented blocks in `docker-compose.yml` and `k8s/relikquary.yaml`.

## Troubleshooting

- **`port is already allocated` / address in use** — something else is on 8080/8081/5432. Stop it, or
  change the left-hand side of the `ports:` mapping (e.g. `"18080:8080"`).
- **Frontend logs look like errors** — nginx logs at `notice`/`info`; see *Reading the logs* above. If it
  truly won't come up, run `docker compose ... build frontend` to see build errors on their own.
- **Backend keeps restarting on the Postgres stack** — it waits for the database; give it a few seconds.
  If it persists, check `docker compose ... logs postgres` (wrong/blank `RELIKQUARY_DB_PASSWORD`?).
- **Changed code isn't reflected** — rebuild: add `--build` (compose) or re-run the `dockerBuild*` task.
- **Start completely fresh** — `docker compose ... down -v` wipes the named volumes (artifacts + database).

## Notes

- **Non-root volumes**: the backend runs as a non-root user. For host-bind mounts, ensure the path is
  writable by that user; the compose named volumes and the k8s `fsGroup` handle this automatically.
- **Secrets**: only placeholders are committed here. Never commit a real `.env`, Secret value, or password.
- **Verify locally** (where Docker exists): `bash deploy/smoke.sh` builds the backend image and round-trips
  a publish/resolve through it; it skips cleanly when no Docker runtime is present.
