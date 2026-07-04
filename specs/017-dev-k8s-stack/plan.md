# Implementation Plan: Local Development Kubernetes Stack

**Branch**: `017-dev-k8s-stack` | **Date**: 2026-07-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/017-dev-k8s-stack/spec.md`

**Note**: Retro-plan — the feature is already delivered. This documents the design of the shipped
artifacts and maps each spec requirement to them.

## Summary

Add a self-contained local-development Kubernetes stack (Postgres + backend + frontend) as the k8s
counterpart of `docker-compose.dev.yml`: a single manifest in a dedicated `relikquary-dev` namespace,
auth off, throwaway creds, request logging on. The API/Maven endpoint gets a **fixed** address via a
LoadBalancer on port 8081 (binds `localhost` on Docker Desktop) plus a **pinned NodePort 30081** as the
deterministic fallback for clusters without a LB provider; the UI gets a random NodePort. Correct
non-root pod security (numeric `runAsUser` matching the image, non-root init container) lets it roll out
unattended, and the images pin the app user's UID/GID (999) so those settings stay valid. A helper
script (`deploy/dev-k8s.sh`) and two apply-only Gradle tasks drive build/apply/roll/status/teardown; the
`:local` image tag means a rebuild needs an explicit `restart` to roll the pods.

## Technical Context

**Language/Version**: YAML (Kubernetes manifests), Bash (helper script), Kotlin DSL (Gradle tasks),
Dockerfile. No application (Kotlin/JVM) code change.

**Primary Dependencies**: Kubernetes (local: Docker Desktop / kind / minikube / k3d), `kubectl`,
Docker CLI, the existing `backend`/`frontend`/`combined` Dockerfiles, the existing app config surface
(`RELIKQUARY_*` env, relaxed-binding to `relikquary.*` properties).

**Storage**: PostgreSQL for application state (PVC); artifact storage on a filesystem PVC. Both are
dev-scoped PVCs (2Gi).

**Testing**: Validation-style — `kubectl apply --dry-run=server` for schema, rollout status for
readiness, `curl` against the fixed API port + UI port, and the server access log to confirm requests.
No unit/integration suite (this is deployment config, not application behavior).

**Target Platform**: Local single-node Kubernetes clusters.

**Project Type**: Deployment tooling / infrastructure (no module or protocol change).

**Performance Goals**: N/A (developer-experience feature; success is "one command up", "fixed address",
"visible requests").

**Constraints**: NodePorts limited to 30000–32767 (so the fixed 8081 needs a LoadBalancer); locally
built images share the `:local` tag (so apply won't roll pods — needs restart); non-root policy must be
satisfied by every container; the app's non-root UID must be numeric+pinned for the kubelet to verify.

**Scale/Scope**: One namespace, ~11 resources, one script, two Gradle tasks, two Dockerfile edits, one
ConfigMap toggle, README updates. Single developer, single node.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Repository Contract & Client Compatibility** — PASS. No change to the served repository layout,
  resolution/publish protocol, or the configuration contract. The dev stack runs the same app image;
  it only chooses *where/how* it is exposed locally. No version bump implied.
- **II. Test-First & Integration-Verified Discipline** — PASS (adapted). This feature ships no
  application behavior, so there is no publish/resolve round-trip to add; it is validated operationally
  (server-side dry-run of the manifest, rollout-to-Ready, `curl` of the fixed API + UI, access-log
  observation). The existing application test suite is unaffected.
- **III. Quality Gates Are Non-Negotiable** — PASS. The only compiled change is the Gradle Kotlin DSL
  (new `k8sDeployDev`/`k8sDeleteDev` tasks + a `cliPath` helper); it must keep `./gradlew build` green
  (detekt/Kover unaffected — no Kotlin app source changed). No gate weakened.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS with a justified, precedented deviation
  (see Complexity Tracking). Artifact bytes/checksums are untouched; dependency verification is
  unaffected (the Dockerfile UID pin adds no dependency). The one nuance is committing a **throwaway
  local-dev** Postgres password.

**Result**: PASS. One deliberate deviation recorded below.

## Complexity Tracking

| Deviation | Why needed | Simpler alternative rejected because |
|-----------|------------|--------------------------------------|
| Commit a throwaway dev DB password in `relikquary-dev.yaml` | A zero-setup local stack ("no secrets to supply") — the constitution's secret rule targets *real* secrets | Requiring the developer to inject a secret defeats the one-command goal. The value is the sanctioned `changeme` placeholder (works as-is, non-production, clearly marked) so the `DeploymentArtifactsTest` no-committed-secret gate stays satisfied — not weakened. Not a real secret. |
| Fixed API via `LoadBalancer` (not NodePort) | A NodePort cannot be 8081 (30000–32767 only), and build config needs a stable, memorable local port | A pinned NodePort alone would force clients onto a 30xxx port; a port-forward alone is a fragile background process. LB (Docker Desktop) + pinned NodePort (portable fallback) gives a stable address with no process. |

## Project Structure

### Documentation (this feature)

```text
specs/017-dev-k8s-stack/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions
├── data-model.md        # Phase 1 — the dev-stack resources
├── quickstart.md        # Phase 1 — validation guide
├── contracts/
│   └── dev-k8s-interface.md   # script CLI + Gradle tasks + external address contract
└── checklists/
    └── requirements.md  # spec quality checklist (from /speckit-specify)
```

### Source Code (repository root)

```text
deploy/
├── k8s/
│   └── relikquary-dev.yaml     # namespace + ConfigMap/Secret + PVCs + Postgres + backend + frontend
│                               #   backend Service: LoadBalancer port 8081 + pinned nodePort 30081
│                               #   frontend Service: NodePort (auto-assigned)
│                               #   backend pod securityContext: runAsUser/Group/fsGroup 999;
│                               #   wait-for-postgres init container: non-root (65534);
│                               #   RELIKQUARY_SECURITY_ENABLED=false, request-log enabled=true
├── dev-k8s.sh                  # build | up | restart | status | down | deploy
├── backend.Dockerfile          # groupadd/useradd pin gid/uid 999
├── combined.Dockerfile         # same UID/GID pin
└── README.md                   # "Local dev cluster" section: workflow, fixed-vs-random ports, rebuild→restart

build.gradle.kts                # cliPath() helper; k8sDeployDev / k8sDeleteDev (apply-only)
```

**Structure Decision**: Pure deploy/tooling change — no new Gradle module, no `settings.gradle.kts`
edit, no application source touched. All artifacts live under `deploy/` plus the root `build.gradle.kts`.

## Complexity Tracking (post-design re-check)

Constitution re-evaluated after design: unchanged — PASS with the two recorded, precedented deviations.
No new violations introduced by the design.
