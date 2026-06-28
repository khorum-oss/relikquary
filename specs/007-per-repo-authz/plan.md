# Implementation Plan: Per-Repository Authorization

**Branch**: `claude/spec-007-per-repo-authz` | **Date**: 2026-06-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-per-repo-authz/spec.md`

## Summary

Add per-repository READ/PUBLISH/DELETE authorization on top of the existing HTTP Basic auth. Each
repository may declare an `access` block of per-action principal lists (username or `@role`); absent
lists keep today's behaviour (open reads, global-`PUBLISH`-gated writes). A single
`RepositoryAuthorizer` policy function is enforced by a custom Spring Security `AuthorizationManager`
for single, path-known repos (hosted/proxy READ/PUBLISH/DELETE — yielding correct `401`/`403`), and by
the `RepositoryResolver` for group reads (permissive union: a read-denied member is skipped). No new
dependencies.

## Technical Context

**Language/Version**: Kotlin 2.3.21 on JDK 21.

**Primary Dependencies**: Spring Boot 4.1.0, **Spring Security 7.1.0** (already present, feature 002) —
custom `AuthorizationManager<RequestAuthorizationContext>`; no new dependency.

**Storage**: Unaffected — authorization gates access, never touches stored bytes (Principle IV).

**Testing**: JUnit 5 + `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient` integration matrices; unit
tests for `RepositoryAuthorizer` and the request→(repo, action) parser.

**Target Platform**: Linux server (Spring Boot fat jar). `frontend/` unaffected by this feature.

**Project Type**: Web service (`backend/` module).

**Performance Goals**: Authorization is an in-memory map/list check per request; no I/O added on the
decision path. A denied proxy read never triggers an upstream fetch.

**Constraints**: Strict dependency verification stays enabled and **untouched** (no new deps); detekt
zero, Kover holds. HTTP Basic + `401`/`403` semantics preserved (Principle I).

**Scale/Scope**: One backend module; ~4 new classes (`Action`, `RepositoryAuthorizer`,
`RepositoryAuthorizationManager`, plus the `RepositoryAccess` config type), edits to `SecurityConfig`,
`RepositoryProperties`, `RepositoryResolver`, and `application.yml`/docs.

## Constitution Check

*GATE: re-checked after Phase 1 design — PASS.*

- **I. Repository Contract & Client Compatibility** — PASS. Additive config (`access` block); no layout
  or resolution change. Authentication scheme unchanged (HTTP Basic); `401` (with challenge) vs `403`
  preserved so Maven/Gradle clients keep working. Backward-compatible defaults mean existing configs are
  unaffected — no breaking change.
- **II. Test-First & Integration-Verified** — PASS. Security-critical behaviour proven by integration
  matrices across both the Maven and browse surfaces (publish/read/delete, group union, disabled bypass,
  regression), plus unit tests for the policy and parser. No mocking-away of the security boundary.
- **III. Quality Gates** — PASS. detekt/Kover unchanged; no gate weakened. No new dependency → no
  verification-metadata edit.
- **IV. Supply-Chain Integrity & Faithful Storage** — PASS. Authorization never mutates bytes; zero new
  dependencies keeps `gradle/verification-metadata.xml` and the trust surface unchanged. Credentials
  remain config/env-supplied.

No violations → Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/007-per-repo-authz/
├── plan.md              # This file
├── research.md          # Phase 0 decisions (D1–D9)
├── data-model.md        # Access types, authorizer, manager, request mapping
├── quickstart.md        # End-to-end validation scenarios
├── contracts/
│   └── authz.md         # Authorization contract (config + decisions + status codes)
├── checklists/
│   └── requirements.md  # Spec quality checklist (passing)
└── tasks.md             # Phase 2 output (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/org/khorum/oss/relikquary/
├── config/
│   ├── RepositoryProperties.kt        # MODIFIED: add RepositoryAccess + Repo.access
│   └── SecurityConfig.kt              # MODIFIED: wire RepositoryAuthorizationManager into the chain
├── security/
│   ├── Action.kt                      # NEW: READ | PUBLISH | DELETE
│   ├── RepositoryAuthorizer.kt        # NEW: permits(repo, action, authentication) policy function
│   └── RepositoryAuthorizationManager.kt  # NEW: AuthorizationManager<RequestAuthorizationContext>
├── repository/
│   └── RepositoryResolver.kt          # MODIFIED: group() applies per-member READ (permissive union)
└── resources/application.yml          # MODIFIED: document the access block

backend/src/test/kotlin/org/khorum/oss/relikquary/
├── unit/
│   ├── RepositoryAuthorizerTest.kt    # NEW: policy matrix (defaults/override, username vs @role, disabled)
│   └── RepositoryAuthzRequestMappingTest.kt  # NEW: path → (repo, action) parsing
└── integration/
    ├── PerRepoPublishAuthzTest.kt     # NEW: publish matrix (201/403/401, default vs grant)
    ├── PrivateRepoReadTest.kt         # NEW: read matrix incl. browse-API parity (200/401/403; open repo)
    ├── PerRepoDeleteAuthzTest.kt      # NEW: delete matrix (204/403/401)
    ├── GroupAuthzTest.kt              # NEW: permissive-union read through a group
    └── BackwardCompatAuthzTest.kt     # NEW: no access blocks ⇒ unchanged; disabled ⇒ open
```

**Structure Decision**: A new `security/` package holds the authorization domain (policy + manager),
separate from the existing `config/SecurityConfig` wiring. The policy function is the single source of
truth, shared by the Spring Security `AuthorizationManager` (single known repo) and the
`RepositoryResolver` (group union) — see research D1. `frontend/` is untouched (a private repo may now
return `401` to the UI's read calls; richer UI handling is a documented follow-on).

## Implementation phases (high level)

1. **Config & policy** — `RepositoryAccess` + `Repo.access`; `Action`; `RepositoryAuthorizer` with the
   default/override matrix and username/`@role` matching (D2, D4). Unit-test the matrix.
2. **Filter wiring** — `RepositoryAuthorizationManager` (request→(repo, action) parsing; unknown-repo /
   group / proxy-or-group-PUT pass-through) wired via `anyRequest().access(...)` when security is
   enabled; keep the `permitAll` branch when disabled (D1, D3, D6, D7). Unit-test the parser.
3. **Group reads** — `RepositoryResolver.group()` skips members failing `permits(member, READ, auth)`
   (D5); `RepositoryAuthorizer` short-circuits when security is disabled.
4. **Tests & docs** — integration matrices (publish/read/delete/group/backward-compat/disabled), browse
   parity; `application.yml` + README documenting the `access` block.
5. **Verify** — `./gradlew build` green (detekt + Kover + all suites; `verification-metadata.xml`
   untouched); commit & push.

## Complexity Tracking

No constitution violations; section intentionally empty.
