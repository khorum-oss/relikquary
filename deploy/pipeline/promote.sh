#!/usr/bin/env bash
# Promote the exact image currently on STAGE to PROD. Reads the image tag pinned in the stage overlay,
# writes it into the prod overlay, and commits. Prod is GATED (manual sync), so this does NOT deploy on
# its own — finish with `argocd app sync relikquary-prod`.
#
#   deploy/pipeline/promote.sh
#   SYNC=true deploy/pipeline/promote.sh     # also run `argocd app sync` (needs argocd CLI logged in)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$here/../.." && pwd)"
cd "$REPO_ROOT"

need() { command -v "$1" >/dev/null 2>&1 || { echo "promote: '$1' not found — $2" >&2; exit 1; }; }
need git "run this inside the git repo."
need kubectl "install kubectl — used to read the resolved stage image."
need kustomize "install kustomize (brew install kustomize) — needed to pin the image tag."

# Read the fully-resolved images from the rendered stage overlay (source of truth for what's on stage).
render="$(kubectl kustomize deploy/k8s/overlays/stage)"
backend="$(printf '%s\n'  "$render" | awk '/image: .*relikquary-backend:/  {print $2; exit}')"
frontend="$(printf '%s\n' "$render" | awk '/image: .*relikquary-frontend:/ {print $2; exit}')"

if [[ -z "$backend" || -z "$frontend" || "$backend" == *"registry.example.com"* ]]; then
  echo "promote: stage overlay still has placeholder images — run deploy/pipeline/release.sh first." >&2
  exit 1
fi
echo "promote: stage is on"
echo "  backend:  $backend"
echo "  frontend: $frontend"

( cd deploy/k8s/overlays/prod
  kustomize edit set image "relikquary-backend=$backend" "relikquary-frontend=$frontend" )

if git diff --quiet -- deploy/k8s/overlays/prod/kustomization.yaml; then
  echo "promote: prod already on this image — nothing to commit."
else
  git add deploy/k8s/overlays/prod/kustomization.yaml
  git commit -m "deploy(prod): promote ${backend##*:}"
  git push
fi

if [[ "${SYNC:-false}" == "true" ]]; then
  need argocd "install the argocd CLI, or sync from the UI."
  echo "promote: syncing prod"
  argocd app sync relikquary-prod
else
  cat <<EOF

Prod is gated. Nothing deployed yet — release it when ready:
  argocd app sync relikquary-prod        # or click Sync in the ArgoCD UI
  kubectl -n relikquary-prod rollout status deploy/relikquary-backend

Rollback (either env): git revert the deploy commit and push — ArgoCD reconciles back.
EOF
fi
