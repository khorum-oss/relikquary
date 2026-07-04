# Feature Specification: Local Development Kubernetes Stack

**Feature Branch**: `017-dev-k8s-stack`

**Created**: 2026-07-04

**Status**: Implemented (retro-spec — documents work already delivered this iteration)

**Input**: User description: "Local-development Kubernetes deployment for Relikquary — the k8s counterpart of the existing docker-compose dev stack: a self-contained, throwaway dev deployment (Postgres + backend + frontend) a developer can bring up on a local cluster, with auth off, a fixed API/Maven port, a random UI port printed on run, request logging on, correct non-root security so it rolls out, reproducible image users, and helper tooling (a script + Gradle tasks)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bring up a working dev stack in one command (Priority: P1)

A developer runs the whole Relikquary stack (application state DB + API + UI) on their local Kubernetes
cluster with a single command, no authentication to configure and no secrets to supply, and it reaches
a Ready/serving state on its own.

**Why this priority**: This is the point of the feature — a frictionless local environment. Everything
else builds on the stack actually rolling out and serving.

**Independent Test**: On a local cluster, run the one-command deploy; confirm all components reach Ready
and the API and UI both respond successfully.

**Acceptance Scenarios**:

1. **Given** a local Kubernetes cluster and the built images, **When** the developer runs the deploy
   command, **Then** the database, API, and UI all reach a Ready state without manual intervention.
2. **Given** the stack is up, **When** the developer opens the UI and calls the API, **Then** both
   respond successfully and the UI can list repositories (no auth prompt).
3. **Given** the stack is up, **When** the developer runs the teardown command, **Then** every resource
   for the dev stack is removed.

---

### User Story 2 - A fixed, reliable address for the API / Maven endpoint (Priority: P1)

A developer points build/client configuration (e.g. a sandbox build resolving through Relikquary) at a
**stable** local address for the API / Maven repository endpoint, and that address keeps working across
teardown and redeploy without edits.

**Why this priority**: Build configuration that references the server must not break every time the
stack is recreated; a moving port makes the dev stack unusable for real resolve/publish testing.

**Independent Test**: Note the API address, tear the stack down and redeploy, and confirm the same
address still serves the API without any configuration change.

**Acceptance Scenarios**:

1. **Given** the dev stack, **When** the developer resolves the API address, **Then** it is the same
   fixed local port every deploy (it does not change on teardown/redeploy).
2. **Given** a cluster that cannot bind that fixed local port, **When** the developer resolves the API,
   **Then** a deterministic (non-random) alternate address is available so the endpoint is still
   reachable.
3. **Given** the UI, **When** the developer resolves its address, **Then** it is auto-assigned (so it
   never collides with other local stacks) and the tooling prints the current UI URL on every run.

---

### User Story 3 - Watch traffic hit the server (Priority: P2)

A developer verifies that a client (build tool, curl, the UI) actually reached the dev server by
observing a per-request log line for each request, without changing any configuration first.

**Why this priority**: The dev stack's value is verification; being able to *see* each resolve/publish
land turns "did it hit the server?" from guesswork into an observation. Lower priority than the stack
existing and being addressable.

**Independent Test**: Make a request against the API and confirm a corresponding per-request log entry
appears in the server's logs.

**Acceptance Scenarios**:

1. **Given** the dev stack, **When** any request reaches the API, **Then** a structured per-request log
   line (method, repository, path, status, size, duration) is emitted — with no extra setup.
2. **Given** the shipped (non-dev) configuration, **When** the same server runs, **Then** that
   per-request logging stays off by default (the dev stack opts in; the default is unchanged).

---

### User Story 4 - Predictable rebuild-and-roll workflow (Priority: P2)

A developer who changes application code rebuilds the images and gets the running stack to pick up the
new build with an explicit, discoverable step.

**Why this priority**: Locally-built images reuse a stable tag, so re-applying the manifest alone will
not roll the pods — without a clear "roll it" step, developers hit the "I rebuilt but nothing changed"
trap. Important for iteration, but secondary to the stack working at all.

**Independent Test**: Change code, rebuild, run the roll step, and confirm the running pods are replaced
with ones running the new build.

**Acceptance Scenarios**:

1. **Given** freshly rebuilt images, **When** the developer runs the roll/restart step, **Then** the
   deployments are replaced with pods running the new images.
2. **Given** only a manifest (non-image) change, **When** the developer applies it, **Then** it takes
   effect without a full rebuild.
3. **Given** the tooling, **When** the developer asks for status, **Then** it reports component health
   and the current API and UI addresses.

---

### Edge Cases

- **Cluster without the capability to bind the fixed local port** (e.g. no load-balancer provider): the
  deterministic alternate address (US2 #2) is used; the endpoint is still reachable.
- **UI port already taken**: because the UI port is auto-assigned, it selects a free one rather than
  failing.
- **Database not yet ready when the API starts**: the API waits for the database rather than
  crash-looping on first deploy.
- **Non-root enforcement**: every container (including short-lived helper/init containers) satisfies the
  non-root policy, so the stack is never blocked from starting by a container that would run as root.
- **Image user identity drift**: the running user identity the deployment expects stays stable across
  base-image changes (it is pinned), so the security settings keep matching.
- **Re-running deploy when already up**: applying is idempotent — it reports no changes rather than
  erroring or duplicating resources.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST provide a self-contained local-development deployment of the full stack
  (application-state database + API + UI), isolated in its own dedicated namespace, that a developer can
  apply to a local cluster.
- **FR-002**: The dev deployment MUST run with authentication disabled and only throwaway credentials,
  and MUST be clearly marked as local-development-only (never for exposure/production).
- **FR-003**: The API / Maven repository endpoint MUST be reachable at a fixed, stable local address
  that does not change across teardown and redeploy, so build/client configuration can rely on it.
- **FR-004**: Where a cluster cannot bind that fixed local address, a deterministic (non-random)
  alternate address MUST be available for the API endpoint.
- **FR-005**: The UI MUST be exposed on an auto-assigned (collision-free) address, and the tooling MUST
  report the current UI address whenever it deploys or is asked for status.
- **FR-006**: The dev deployment MUST enable structured per-request access logging by default so each
  request to the server is observable, WITHOUT changing the shipped (default) configuration, which keeps
  that logging off.
- **FR-007**: The dev deployment MUST reach a serving state unattended: the API waits for the database,
  and all containers satisfy the cluster's non-root policy (including any helper/init container).
- **FR-008**: The container images MUST run as a reproducible, pinned non-root user identity so the
  deployment's security settings remain correct regardless of base-image changes.
- **FR-009**: The project MUST provide tooling with distinct operations to: build the images, apply the
  manifest without rebuilding, roll the running deployments onto freshly built images, report status
  (health + current API/UI addresses), tear the stack down, and do the full build+apply+roll+status in
  one step.
- **FR-010**: Applying the dev deployment MUST be idempotent (re-applying an unchanged stack makes no
  changes and does not error).
- **FR-011**: Documentation MUST describe the dev-cluster workflow, the fixed-vs-auto-assigned address
  model, and the rebuild-then-roll caveat (that a rebuild alone does not roll the running pods).

### Key Entities *(include if feature involves data)*

- **Dev stack**: the isolated set of local-development resources (database, API, UI, their config and
  storage) that come up and tear down together as a unit.
- **API address (fixed)**: the stable local endpoint for the API / Maven repository that build config
  targets; unchanged across redeploys, with a deterministic fallback.
- **UI address (auto-assigned)**: the collision-free local endpoint for the UI, surfaced by the tooling.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can go from "images built" to a fully serving dev stack with a single command,
  and tear it down with a single command.
- **SC-002**: The API endpoint address is identical before and after a teardown+redeploy cycle (0
  configuration changes required to keep a client pointed at it).
- **SC-003**: 100% of requests reaching the dev API produce a corresponding per-request log line, with
  no setup beyond deploying the dev stack.
- **SC-004**: The shipped default configuration is unchanged by this feature — per-request logging and
  authentication remain at their shipped defaults outside the dev stack.
- **SC-005**: The stack reaches a Ready/serving state unattended on a first apply (no manual
  intervention to get past database-startup ordering or non-root enforcement).
- **SC-006**: After a rebuild, running the documented roll step results in the pods running the new
  build; without it, the running pods are unchanged (the caveat is documented and observable).
- **SC-007**: The tooling reports the current API and UI addresses on deploy and on status.

## Assumptions

- Target clusters are local single-node developer clusters (Docker Desktop, kind, minikube, k3d). On
  Docker Desktop the fixed local address binds directly; other clusters use the deterministic fallback
  address (or a port-forward) — both are acceptable and documented.
- Images are built locally and made available to the cluster (this project publishes no registry); the
  dev stack references locally-tagged images.
- Application state uses the external database option (as the docker-compose dev stack does); switching
  to the embedded option is a documented manual variation, not a requirement here.
- "Throwaway credentials" for the local database are acceptable to commit because the stack is
  local-only and clearly marked non-production.

## Out of Scope

- Production Kubernetes deployment (authentication on, real managed secrets, ingress/hostnames, scaling)
  — handled by the existing production manifest.
- Changing the shipped application defaults (authentication and per-request logging keep their shipped
  defaults; the dev stack only overrides them for itself).
- Any change to application or repository-protocol behavior — this is purely a local-development
  deployment and tooling addition.
- Multi-node / cloud clusters and load-balancer providers beyond what a local cluster offers.
