---
description: "Task list for Local Development Kubernetes Stack"
---

# Tasks: Local Development Kubernetes Stack

**Input**: Design documents from `specs/017-dev-k8s-stack/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/dev-k8s-interface.md

**Tests**: Validation is **operational** (dry-run, rollout-to-Ready, curl, log observation) — this
feature ships deployment config, not application behavior, so there are no unit/integration test tasks.

**Status**: Retro — all tasks are already delivered and marked `[X]`. Paths point at the shipped files.

## Path Conventions

Deploy artifacts under `deploy/`; Gradle tasks in the root `build.gradle.kts`. No application source.

---

## Phase 1: Setup

- [X] T001 Confirm this is a deploy/tooling-only change: no new dependency, no `gradle/verification-metadata.xml` change, and no `backend/`/`frontend/` application source touched (constitution IV).

---

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T002 [P] Pin the app user to a reproducible non-root UID/GID (999) in `deploy/backend.Dockerfile` (`groupadd --gid 999` / `useradd --uid 999`).
- [X] T003 [P] Pin the same UID/GID (999) in `deploy/combined.Dockerfile`.
- [X] T004 Create the dev manifest base in `deploy/k8s/relikquary-dev.yaml`: `Namespace relikquary-dev`, `ConfigMap relikquary-config`, `Secret relikquary-secrets` (throwaway DB password), and the two RWO 2Gi PVCs (`relikquary-data`, `relikquary-postgres-data`), all namespaced.
- [X] T005 Add the PostgreSQL `Deployment` + ClusterIP `Service` (application state) to `deploy/k8s/relikquary-dev.yaml`, wired to the ConfigMap/Secret with a readiness probe.

**Checkpoint**: images build a stable non-root user; the manifest has its namespace, config, storage, and database.

---

## Phase 3: User Story 1 — One-command dev stack (Priority: P1) 🎯 MVP

**Goal**: Bring up DB + API + UI with one command, auth off, reaching Ready unattended.

**Independent Test**: run the deploy; all components reach Ready; API and UI both serve 200; teardown removes everything.

- [X] T006 [US1] Add the backend `Deployment` to `deploy/k8s/relikquary-dev.yaml`: `envFrom` the ConfigMap+Secret, mount the `/data` PVC, liveness/readiness on `/actuator/health/*`, and a `wait-for-postgres` init container.
- [X] T007 [US1] Non-root rollout in `deploy/k8s/relikquary-dev.yaml`: backend pod `securityContext` numeric `runAsUser/runAsGroup/fsGroup: 999`, and a per-container `securityContext` on the busybox init container running as UID 65534.
- [X] T008 [US1] Add the frontend `Deployment` (env `RELIKQUARY_BACKEND=http://relikquary-backend:8081`) and set `RELIKQUARY_SECURITY_ENABLED: "false"` in the ConfigMap in `deploy/k8s/relikquary-dev.yaml`.
- [X] T009 [US1] Implement the deploy/teardown entry points: `deploy/dev-k8s.sh` `up`/`down` (and `deploy`) plus the `k8sDeployDev` (apply-only) and `k8sDeleteDev` Gradle tasks in `build.gradle.kts` (with the `cliPath` absolute-path CLI resolution + friendly missing-CLI guards).
- [X] T010 [US1] Validate US1: `deploy/dev-k8s.sh deploy` reaches Ready for all three deployments; `curl :8081/api/repositories` and the UI both return 200; `deploy/dev-k8s.sh down` deletes the namespace.

**Checkpoint**: the MVP — the full stack comes up and serves with one command.

---

## Phase 4: User Story 2 — Fixed, reliable API address (Priority: P1)

**Goal**: A stable API/Maven address across teardown+redeploy, with a deterministic fallback; UI auto-assigned + printed.

**Independent Test**: note the API address, teardown+redeploy, confirm the same address serves; confirm the tooling prints the UI URL.

- [X] T011 [US2] Backend `Service` in `deploy/k8s/relikquary-dev.yaml`: `type: LoadBalancer`, `port: 8081`, `targetPort: 8080`, pinned `nodePort: 30081` (fixed API on Docker Desktop localhost + a deterministic fallback elsewhere).
- [X] T012 [US2] Frontend `Service`: `type: NodePort` with no fixed nodePort (auto-assigned); add `print_urls` to `deploy/dev-k8s.sh` so `up`/`deploy`/`status` print the fixed API URL and the current UI URL.
- [X] T013 [US2] Validate US2: `curl :8081/api/repositories` → 200 before and after `down`+`deploy` (unchanged); confirm the pinned `nodePort: 30081` is present for the fallback.

**Checkpoint**: build/client config can hard-code `localhost:8081` and rely on it.

---

## Phase 5: User Story 3 — Watch traffic hit the server (Priority: P2)

**Goal**: Per-request access logging on by default in dev, without changing the shipped default.

**Independent Test**: make a request; see a matching access-log line; confirm the shipped default stays off.

- [X] T014 [US3] Enable the request log in the dev ConfigMap in `deploy/k8s/relikquary-dev.yaml`: `RELIKQUARY_OBSERVABILITY_REQUEST_LOG_ENABLED: "true"`.
- [X] T015 [US3] Validate US3: a request to `:8081` produces a `relikquary.access` line (method/repository/path/status/bytes/duration); confirm `backend/src/main/resources/application.yml` keeps `request-log.enabled: false` (shipped default unchanged, SC-004).

**Checkpoint**: every resolve/publish is observable server-side in the dev stack.

---

## Phase 6: User Story 4 — Predictable rebuild-and-roll (Priority: P2)

**Goal**: Rebuild images and roll the running stack onto them via an explicit, discoverable step.

**Independent Test**: rebuild, run the roll step, confirm pods are replaced; a manifest-only change applies without a rebuild.

- [X] T016 [US4] Implement `build` and `restart` (`rollout restart` + `rollout status` wait) in `deploy/dev-k8s.sh`, and make `deploy` = build → up → restart → status (the `:local`-tag roll workflow).
- [X] T017 [US4] Implement `status` in `deploy/dev-k8s.sh`: pod health + the fixed API URL and the current UI URL.
- [X] T018 [US4] Validate US4: after `build` + `restart`, pods run the new `:local` image; a second `up` on an unchanged stack reports only `unchanged` (idempotent, FR-010).

**Checkpoint**: the rebuild→restart iteration loop works and is documented.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T019 [P] Document the workflow in `deploy/README.md`: the "Local dev cluster" section (fixed-vs-random port model, rebuild→restart caveat, one-command `deploy`), the `dev-k8s.sh` Artifacts-table row, and the `k8s/relikquary-dev.yaml` row.
- [X] T020 [P] Server-side dry-run validation of `deploy/k8s/relikquary-dev.yaml` (`kubectl apply --dry-run=server` into a temp namespace) — all resources valid.
- [X] T021 Confirm `./gradlew build` stays green with the new `deployment`-group tasks (constitution III; no Kotlin app source changed).

---

## Dependencies & Execution Order

- **Setup (T001)** → **Foundational (T002–T005)** → user stories.
- Foundational: T002/T003 (`[P]`, different Dockerfiles) → T004 → T005.
- **US1 (T006–T010)** is the MVP and depends on Foundational; T007 (non-root) is required for T010 to reach Ready.
- **US2 (T011–T013)** refines the backend/frontend Services from US1; can follow US1.
- **US3 (T014–T015)** is a ConfigMap toggle — independent, small.
- **US4 (T016–T018)** extends the tooling from T009.
- **Polish (T019–T021)** after the stories.

## Parallel Opportunities

- Foundational: T002 and T003 (`[P]`) — separate Dockerfiles.
- Polish: T019 and T020 (`[P]`).

## Implementation Strategy

- **MVP = User Story 1** (T001–T010): the stack comes up and serves with one command.
- **Increment 2 = US2**: lock the API address (the reason the stack is usable for build/client testing).
- **Increment 3 = US3 + US4**: observability-on-in-dev and the rebuild→roll loop.
- Polish: docs, dry-run validation, and keeping the Gradle build green.

## Independent Test Criteria

- **US1**: one command → all Ready → API + UI serve 200 → teardown removes everything.
- **US2**: API at `:8081` identical across teardown+redeploy; pinned `30081` fallback present; tooling prints URLs.
- **US3**: a request yields a `relikquary.access` line; shipped default stays off.
- **US4**: rebuild+restart replaces pods with the new image; `up` alone is idempotent.
