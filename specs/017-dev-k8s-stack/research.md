# Research: Local Development Kubernetes Stack

Design decisions behind the delivered artifacts. (Retro — these were settled during implementation,
several after live verification on Docker Desktop.)

## D1. Fixed API address: LoadBalancer :8081 + pinned NodePort :30081

- **Decision**: Expose the backend Service as `type: LoadBalancer` with `port: 8081` and an explicit
  `nodePort: 30081`.
- **Rationale**: Build/client config needs a stable, memorable local address (FR-003). A NodePort
  cannot be 8081 — Kubernetes restricts NodePorts to 30000–32767. On Docker Desktop a LoadBalancer
  binds `localhost:8081` directly; the pinned NodePort 30081 is the deterministic fallback (FR-004) for
  clusters without a LB provider (reached at the node IP).
- **Verified nuance**: On Docker Desktop a LoadBalancer only localhost-binds its **LB port**, not its
  underlying NodePort — so `:8081` works locally and `:30081` is for other clusters (at the node IP),
  not a second localhost path. Tooling/docs reflect this.
- **Alternatives**: NodePort only (loses the clean 8081); port-forward only (fragile background
  process, not declarative).

## D2. UI on a random NodePort

- **Decision**: Frontend Service is `type: NodePort` with no fixed `nodePort` (auto-assigned).
- **Rationale**: The UI address need not be memorized; auto-assignment guarantees no collision with
  other local stacks (FR-005). The tooling prints it on deploy/status.
- **Alternatives**: A second LoadBalancer/port (unnecessary; the UI is reached from the printed URL).

## D3. Non-root rollout: numeric UID + non-root init container

- **Decision**: Backend pod `securityContext` uses numeric `runAsUser/runAsGroup/fsGroup: 999`; the
  `wait-for-postgres` (busybox) init container gets its own `securityContext` running as UID 65534.
- **Rationale**: `runAsNonRoot: true` cannot verify a non-numeric image user, and busybox defaults to
  root — both block the pod from starting (FR-007). Numeric UID matching the image + a non-root init
  container let it roll out unattended. (Both were real rollout failures caught during bring-up.)
- **Alternatives**: Drop `runAsNonRoot` (weakens the security posture); run the init container as root
  (rejected by the policy).

## D4. Pinned image UID/GID 999

- **Decision**: `backend.Dockerfile` and `combined.Dockerfile` create the `relikquary` user with
  `groupadd --gid 999` / `useradd --uid 999`.
- **Rationale**: `useradd --system` auto-assigns a UID that can drift across base-image changes; the
  manifest's `runAsUser: 999` must stay correct (FR-008). Pinning makes the identity reproducible.
- **Alternatives**: Read the UID at deploy time (brittle); leave it auto-assigned (manifest could break
  silently on a base-image bump).

## D5. `:local` tag ⇒ explicit rebuild→restart

- **Decision**: Images use a fixed `:local` tag with `imagePullPolicy: IfNotPresent`; the tooling has a
  separate `restart` step (rollout restart).
- **Rationale**: Re-applying an unchanged Deployment spec does not roll pods, so a rebuild alone leaves
  the old image running — the classic "rebuilt but nothing changed" trap (FR-009/FR-011). An explicit
  `restart` (or the all-in-one `deploy`) rolls the new image in.
- **Alternatives**: Unique tags per build (more churn, image bloat locally); `Always` pull (no registry
  to pull from).

## D6. Request logging on in dev via ConfigMap env

- **Decision**: The dev ConfigMap sets `RELIKQUARY_OBSERVABILITY_REQUEST_LOG_ENABLED: "true"`; the
  shipped `application.yml` default stays `false`.
- **Rationale**: The dev stack's value is verification — seeing each request land (FR-006). The
  per-request access log (feature 010) is opt-in by design (per-request overhead in production), so the
  dev stack overrides it *for itself* without changing the shipped default (SC-004).
- **Alternatives**: Change the global default to on (rejected — alters production behavior).

## D7. Tooling: bash script primary, apply-only Gradle tasks

- **Decision**: `deploy/dev-k8s.sh` (build|up|restart|status|down|deploy) is the primary entry point;
  `k8sDeployDev`/`k8sDeleteDev` Gradle tasks are apply-only equivalents. CLI resolution uses an
  absolute-path lookup.
- **Rationale**: Deploy glue (docker+kubectl+curl) is natural in bash and near-zero startup; Gradle is
  kept for building. The apply-only Gradle tasks avoid forcing a slow image rebuild on every deploy.
  Absolute-path CLI resolution is required because the Gradle daemon's process PATH can omit
  `/usr/local/bin`, causing `error=2` when launching a bare `docker`/`kubectl`.
- **Alternatives**: Gradle-only orchestration (JVM startup overhead, awkward for shell glue);
  script-only (loses the discoverable `./gradlew` entry point).

## D8. Self-contained, namespace-scoped, throwaway creds

- **Decision**: One manifest declares the `relikquary-dev` Namespace and scopes every resource to it;
  auth off; a committed throwaway Postgres password.
- **Rationale**: One-command up and `kubectl delete namespace relikquary-dev` teardown (FR-001/FR-002).
  Mirrors `docker-compose.dev.yml`. The committed credential is non-production and clearly marked (see
  plan Complexity Tracking).
- **Alternatives**: Apply into an arbitrary namespace (less self-contained); require a real secret
  (defeats zero-setup).

## Open questions

None.
