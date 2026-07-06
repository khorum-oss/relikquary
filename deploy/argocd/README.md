# Continuous delivery with ArgoCD

GitOps for Relikquary's stage + prod environments. **ArgoCD never builds anything** — it watches this
git repo and reconciles the cluster to match. Your local pipeline (`deploy/pipeline/`) makes git reflect
the new version; ArgoCD does the rest.

```
you trigger locally                                  ArgoCD (in-cluster)
────────────────────────────                         ────────────────────────
deploy/pipeline/release.sh
  1. build backend + frontend images
  2. push to $REGISTRY under an immutable tag   ─┐
  3. kustomize edit set image (stage overlay)   │   watches deploy/k8s/overlays/stage
  4. git commit + push  ─────────────────────────┘─▶ auto-syncs → rolls the Deployments

deploy/pipeline/promote.sh
  copy the stage tag → prod overlay, commit    ─────▶ prod app OutOfSync (gated)
argocd app sync relikquary-prod  ──────────────────▶ prod rolls out
```

Stage **auto-syncs**; prod is **gated** (manual sync). Rollback either env by `git revert`-ing the deploy
commit — ArgoCD reconciles back.

## Prerequisites

- A Kubernetes cluster and a `kubectl` context pointing at it.
- The **1Password Kubernetes Operator** installed and the stage/prod vault items created — see
  [`../k8s/onepassword/README.md`](../k8s/onepassword/README.md). ArgoCD applies the `OnePasswordItem`;
  the operator creates the `relikquary-secrets` Secret. That Secret is **not** in git, so ArgoCD never
  tracks or prunes it — if a pod starts before the sync lands it waits in `CreateContainerConfigError`
  and recovers.
- A container registry you can push to (your self-hosted host).

## 1. Install ArgoCD (once per cluster)

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd rollout status deploy/argocd-server

# UI/CLI access + initial admin password:
kubectl -n argocd port-forward svc/argocd-server 8083:443 &     # https://localhost:8083
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d ; echo
argocd login localhost:8083 --username admin                    # optional: install the argocd CLI
```

## 2. Give ArgoCD access to this repo

- **Public repo:** nothing to do.
- **Private repo:** add read credentials (a GitHub PAT or deploy key):

  ```bash
  argocd repo add https://github.com/khorum-oss/relikquary.git \
    --username <github-user> --password <token>
  ```

## 3. Registry pull secret (only if your registry needs auth)

The base Deployments declare no `imagePullSecrets`. If your self-hosted registry is private, create a
pull secret in each namespace and attach it to the namespace's default ServiceAccount:

```bash
for ns in relikquary-stage relikquary-prod; do
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$ns" create secret docker-registry regcred \
    --docker-server=registry.example.com --docker-username=<user> --docker-password=<token>
  kubectl -n "$ns" patch serviceaccount default \
    -p '{"imagePullSecrets":[{"name":"regcred"}]}'
done
```

(An unauthenticated in-cluster registry needs none of this.)

## 4. Point the manifests at your setup

Edit before applying — in `project.yaml`, `apps/stage.yaml`, `apps/prod.yaml` (and `root.yaml`):

- `repoURL` / `sourceRepos` — your repo URL (defaults to `khorum-oss/relikquary`).
- `targetRevision` — the branch ArgoCD watches (default `main`; a dedicated `deploy` branch also works).

The **registry** is not set here — the pipeline writes it into the overlays on first release (step 6).

## 5. Create the ArgoCD project + apps

```bash
kubectl apply -n argocd -f deploy/argocd/project.yaml     # AppProject: allowed repo + namespaces
kubectl apply -n argocd -f deploy/argocd/root.yaml        # app-of-apps → manages stage + prod from git
```

Prefer to manage the two apps directly (no app-of-apps)? Apply them instead:

```bash
kubectl apply -n argocd -f deploy/argocd/apps/
```

Verify:

```bash
argocd app list                                          # relikquary-stage (Synced), relikquary-prod
kubectl -n argocd get applications
```

> On first sync the apps may be **OutOfSync** because the overlays still hold placeholder images — the
> first `release.sh` (next step) pins real ones and stage goes green.

## 6. The delivery loop

**Ship to stage** (build → push → pin → commit; ArgoCD auto-syncs):

```bash
REGISTRY=registry.example.com deploy/pipeline/release.sh
# TAG defaults to the short git SHA; override with TAG=... . PUSH=false for a dry run.
```

**Promote the tested image to prod** (gated):

```bash
deploy/pipeline/promote.sh          # copies stage's image tag into the prod overlay + commits
argocd app sync relikquary-prod     # release it (or SYNC=true deploy/pipeline/promote.sh)
```

**Roll back** either environment:

```bash
git revert <deploy-commit> && git push     # stage auto-reconciles; re-sync prod if it was the prod commit
```

## How this fits the pieces you already have

- **Images:** `release.sh` wraps the same Dockerfiles the Gradle `dockerBuild*` tasks use, but tags them
  for your registry and pushes (the Gradle tasks only build `:local`).
- **Kustomize overlays:** unchanged — ArgoCD renders `deploy/k8s/overlays/{stage,prod}` exactly as
  `kubectl apply -k` would. Preview any time with `kubectl kustomize deploy/k8s/overlays/prod`.
- **1Password:** unchanged. ArgoCD applies the `OnePasswordItem`; the operator owns the Secret.

## Notes

- **Why immutable tags?** A moving tag (`:stage`) makes "what's actually running" unknowable and breaks
  rollback. The SHA/version tag in git is the record of truth.
- **selfHeal + prune** are on for stage: hand-edits get reverted and deleted-from-git resources removed.
  Prod applies them only when you sync.
- **Committing straight to `main`** keeps it simple; for review/CI gates on config changes, point
  `targetRevision` at a `deploy` branch and open PRs into it instead.
