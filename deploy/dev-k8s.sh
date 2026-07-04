#!/usr/bin/env bash
# Local-dev Kubernetes helper for Relikquary — build the images and drive the dev stack
# (deploy/k8s/relikquary-dev.yaml) on the current kube-context. Applying is idempotent, and because the
# images use the fixed ':local' tag, a rebuild alone does NOT roll the pods — use `restart` (or `deploy`)
# to pick up freshly built images.
#
#   deploy/dev-k8s.sh deploy     # build + apply + restart + status (the usual one-shot)
#   deploy/dev-k8s.sh build      # just (re)build the backend + frontend images
#   deploy/dev-k8s.sh up         # apply the manifest (no rebuild) — fast, idempotent
#   deploy/dev-k8s.sh restart    # roll the deployments to pick up rebuilt :local images
#   deploy/dev-k8s.sh status     # pods + the auto-assigned NodePorts / URLs
#   deploy/dev-k8s.sh down       # delete the whole relikquary-dev namespace
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NS="relikquary-dev"
MANIFEST="$ROOT/deploy/k8s/relikquary-dev.yaml"
BACKEND_IMAGE="relikquary-backend:local"
FRONTEND_IMAGE="relikquary-frontend:local"
DEPLOYMENTS=(deploy/relikquary-backend deploy/relikquary-frontend)

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "dev-k8s: '$1' not found on PATH — $2" >&2; exit 1; }
}

# Make the freshly built images available to the current cluster. Docker Desktop shares the host image
# store (nothing to do); k3d and kind run their own image store, so import/load into the cluster or pods
# hit ImagePullBackOff (imagePullPolicy: IfNotPresent, no registry to pull the :local tag from).
load_images() {
  local ctx="${1:-$(kubectl config current-context 2>/dev/null || true)}"
  case "$ctx" in
    k3d-*)
      need k3d "install k3d, or import the images manually."
      echo "dev-k8s: importing images into k3d cluster '${ctx#k3d-}' ..."
      k3d image import "$BACKEND_IMAGE" "$FRONTEND_IMAGE" -c "${ctx#k3d-}"
      ;;
    kind-*)
      need kind "install kind, or load the images manually."
      echo "dev-k8s: loading images into kind cluster '${ctx#kind-}' ..."
      kind load docker-image "$BACKEND_IMAGE" "$FRONTEND_IMAGE" --name "${ctx#kind-}"
      ;;
    docker-desktop|"") : ;; # shared image store (or no context) — nothing to import
    *) echo "dev-k8s: context '$ctx' — ensure $BACKEND_IMAGE / $FRONTEND_IMAGE are available to the cluster." ;;
  esac
}

do_build() {
  need docker "install Docker to build the images."
  echo "dev-k8s: building $BACKEND_IMAGE ..."
  docker build -f "$ROOT/deploy/backend.Dockerfile" -t "$BACKEND_IMAGE" "$ROOT"
  echo "dev-k8s: building $FRONTEND_IMAGE ..."
  docker build -f "$ROOT/deploy/frontend.Dockerfile" -t "$FRONTEND_IMAGE" "$ROOT"
  load_images
}

do_up() {
  need kubectl "install kubectl and point it at a cluster."
  echo "dev-k8s: applying $MANIFEST ..."
  kubectl apply -f "$MANIFEST"
  echo "dev-k8s: applied (namespace '$NS'). If you just rebuilt images, run: $0 restart"
  echo
  print_urls
}

do_restart() {
  need kubectl "install kubectl and point it at a cluster."
  echo "dev-k8s: rolling ${DEPLOYMENTS[*]} to pick up rebuilt images ..."
  kubectl -n "$NS" rollout restart "${DEPLOYMENTS[@]}"
  kubectl -n "$NS" rollout status deploy/relikquary-backend --timeout=180s
}

# The API LoadBalancer binds localhost:<port> on Docker Desktop. Its pinned NodePort is the fixed
# fallback for kind/minikube (reached at the node IP, not localhost — Docker Desktop only localhost-binds
# the LB port). The UI is a plain NodePort whose external port is auto-assigned and changes each apply.
print_urls() {
  local blb bnode fport
  blb="$(kubectl   -n "$NS" get svc relikquary-backend  -o jsonpath='{.spec.ports[0].port}'     2>/dev/null || true)"
  bnode="$(kubectl -n "$NS" get svc relikquary-backend  -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)"
  fport="$(kubectl -n "$NS" get svc relikquary-frontend -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || true)"
  echo "  API / Maven repos : http://localhost:${blb:-<not-deployed>}   (fixed — LoadBalancer, Docker Desktop)"
  echo "  UI                : http://localhost:${fport:-<not-deployed>}   (random — changes on teardown)"
  echo "  (kind/minikube: API is on fixed NodePort ${bnode:-<not-deployed>} at the node IP; on Docker Desktop use :${blb:-8081}.)"
  case "$(kubectl config current-context 2>/dev/null || true)" in
    k3d-*)
      echo "  k3d: localhost:${blb:-8081} only binds if the cluster was created with '-p ${blb:-8081}:${blb:-8081}@loadbalancer'."
      echo "       Otherwise port-forward:  kubectl -n $NS port-forward svc/relikquary-backend ${blb:-8081}:${blb:-8081}"
      ;;
  esac
}

do_status() {
  need kubectl "install kubectl and point it at a cluster."
  kubectl -n "$NS" get pods
  echo
  print_urls
}

do_down() {
  need kubectl "install kubectl and point it at a cluster."
  echo "dev-k8s: deleting namespace '$NS' ..."
  kubectl delete namespace "$NS" --ignore-not-found
}

usage() {
  # Print the header comment block (skip the shebang; stop at the first non-comment line).
  awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "$0"
}

case "${1:-deploy}" in
  build)   do_build ;;
  up)      do_up ;;
  restart) do_restart ;;
  status)  do_status ;;
  down)    do_down ;;
  deploy)  do_build; do_up; do_restart; do_status ;;
  -h|--help|help) usage ;;
  *) echo "dev-k8s: unknown command '${1}'" >&2; usage; exit 2 ;;
esac
