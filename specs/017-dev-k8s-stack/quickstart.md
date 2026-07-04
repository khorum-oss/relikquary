# Quickstart: Local Development Kubernetes Stack

Validation guide. Details in [contracts/dev-k8s-interface.md](./contracts/dev-k8s-interface.md) and
[data-model.md](./data-model.md).

## Prerequisites

- A local Kubernetes cluster (Docker Desktop recommended; kind/minikube/k3d supported), `kubectl`
  pointed at it, and the Docker CLI.

## Bring it up

```bash
deploy/dev-k8s.sh deploy
```

Expected: backend, frontend, and postgres reach Ready; the command prints the fixed API URL
(`http://localhost:8081`) and the current (random) UI URL.

## Validate

```bash
# fixed API endpoint
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/repositories        # -> 200

# UI + its proxy to the backend
deploy/dev-k8s.sh status                                                                # prints the UI port
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:<ui-port>/api/repositories    # -> 200

# request logging is on: every request shows up server-side
kubectl -n relikquary-dev logs -f deploy/relikquary-backend | grep relikquary.access
```

Expected: `200` for the API and UI-proxied calls; a JSON `relikquary.access` line per request
(`{"method":"GET","repository":"public","path":"...","status":200,...}`).

## Fixed-address check (SC-002)

```bash
deploy/dev-k8s.sh down
deploy/dev-k8s.sh deploy
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/repositories        # -> 200 at the SAME port
```

Expected: the API is reachable at the **same** `localhost:8081` with no config change.

## Rebuild → roll (the `:local` caveat, SC-006)

```bash
deploy/dev-k8s.sh build       # rebuild images
deploy/dev-k8s.sh up          # apply alone does NOT roll pods (unchanged spec)
deploy/dev-k8s.sh restart     # THIS rolls the new image in
```

Expected: after `restart`, the pods are replaced with ones running the freshly built image.

## Manifest schema (no cluster mutation)

```bash
kubectl create namespace relikquary-dev
kubectl apply --dry-run=server -n relikquary-dev -f deploy/k8s/relikquary-dev.yaml
kubectl delete namespace relikquary-dev
```

Expected: every resource reports `created (server dry run)` with no schema errors.

## Tear down

```bash
deploy/dev-k8s.sh down        # or: ./gradlew k8sDeleteDev
```

## Done When

- One-command up reaches Ready; API `:8081` and the UI both serve 200.
- The API port is identical across a teardown+redeploy.
- Requests appear in the `relikquary.access` log.
- A rebuild only rolls after `restart`; `up` alone is idempotent.
