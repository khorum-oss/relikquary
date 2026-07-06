#!/usr/bin/env bash
# Release to STAGE. Build both images, push them under an IMMUTABLE tag, pin that tag into the stage
# overlay, and commit — ArgoCD (auto-sync) rolls it out. This IS your pipeline; run it from your machine:
#
#   REGISTRY=registry.example.com deploy/pipeline/release.sh
#
# Env:
#   REGISTRY  (required) registry host[/namespace], e.g. registry.example.com or registry.example.com/relikquary
#   TAG       (optional) image tag. Default = short git SHA. Keep it IMMUTABLE (never :latest/:stage).
#   PUSH      (optional) PUSH=false → build only, no push/commit (dry run)
#   PLATFORM  (optional) docker build --platform value (e.g. linux/amd64)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$here/../.." && pwd)"
cd "$REPO_ROOT"

: "${REGISTRY:?set REGISTRY, e.g. REGISTRY=registry.example.com}"
PUSH="${PUSH:-true}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"
BACKEND_IMAGE="$REGISTRY/relikquary-backend:$TAG"
FRONTEND_IMAGE="$REGISTRY/relikquary-frontend:$TAG"

need() { command -v "$1" >/dev/null 2>&1 || { echo "release: '$1' not found — $2" >&2; exit 1; }; }
need docker "install Docker to build/push images."
need git "run this inside the git repo."
need kustomize "install kustomize (brew install kustomize) — needed to pin the image tag."

# A dirty tree means the pushed image won't correspond to the committed SHA tag. Warn loudly.
if [[ "$TAG" == "$(git rev-parse --short HEAD)" ]] && ! git diff --quiet; then
  echo "release: WARNING working tree is dirty — the '$TAG' image won't match committed source." >&2
fi

plat=(); [[ -n "${PLATFORM:-}" ]] && plat=(--platform "$PLATFORM")

echo "release: building $BACKEND_IMAGE"
docker build "${plat[@]}" -f deploy/backend.Dockerfile  -t "$BACKEND_IMAGE"  .
echo "release: building $FRONTEND_IMAGE"
docker build "${plat[@]}" -f deploy/frontend.Dockerfile -t "$FRONTEND_IMAGE" .

if [[ "$PUSH" != "true" ]]; then
  echo "release: PUSH=false — built only, nothing pushed or committed."; exit 0
fi

echo "release: pushing images"
docker push "$BACKEND_IMAGE"
docker push "$FRONTEND_IMAGE"

echo "release: pinning stage overlay to $TAG"
( cd deploy/k8s/overlays/stage
  kustomize edit set image \
    "relikquary-backend=$BACKEND_IMAGE" \
    "relikquary-frontend=$FRONTEND_IMAGE" )

if git diff --quiet -- deploy/k8s/overlays/stage/kustomization.yaml; then
  echo "release: stage overlay already at $TAG — nothing to commit."; exit 0
fi

git add deploy/k8s/overlays/stage/kustomization.yaml
git commit -m "deploy(stage): release $TAG"
git push

cat <<EOF

Released $TAG to STAGE. ArgoCD (auto-sync) will roll it out shortly — watch it:
  argocd app get relikquary-stage        # or the ArgoCD UI
  kubectl -n relikquary-stage rollout status deploy/relikquary-backend

Happy with stage? Promote the SAME image to prod:
  deploy/pipeline/promote.sh
EOF
