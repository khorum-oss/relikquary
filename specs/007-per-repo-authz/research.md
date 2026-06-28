# Research: Per-Repository Authorization

Phase 0 decisions for feature 007. The grant model, group-read semantics, and admin-role question were
settled in the spec's `## Clarifications` (2026-06-28); this records the technical choices that follow.

## D1 — Enforcement mechanism

**Decision**: A single shared policy function `RepositoryAuthorizer.permits(repo, action, authentication)`
is the source of truth, enforced in two places:
- A custom Spring Security `AuthorizationManager<RequestAuthorizationContext>` (wired via
  `authorizeHttpRequests { anyRequest().access(manager) }`) enforces actions on a **known single repo**
  parsed from the request path — hosted/proxy READ, PUBLISH, and DELETE — so Spring's standard
  exception handling produces the correct `401`/`403`.
- The **`RepositoryResolver`** (feature 006) enforces per-member READ for **group** requests during
  resolution, because the group's permissive-union semantics (a read-denied member is skipped) can only
  be evaluated while iterating members.

**Rationale**: Per-repo decisions are dynamic (they depend on the repo name in the path and its
configured policy), which static path-pattern matchers can't express; an `AuthorizationManager` gets the
request + authentication and can decide. Group union semantics are inherently resolution-time, so that
slice lives in the resolver. Both call the same `RepositoryAuthorizer`, so the policy is defined once.

**Alternatives considered**:
- *All authorization in controllers/resolver (method-style)* — rejected: controller-thrown
  `AccessDeniedException`s don't pass through Spring Security's `ExceptionTranslationFilter`, so emitting
  a proper `401` + `WWW-Authenticate` challenge would mean re-implementing the entry point.
- *Static `authorizeHttpRequests` matchers per repo* — rejected: can't express "repo X requires read role"
  without regenerating the chain on config, and can't do group union at all.

## D2 — Configuration schema

**Decision**: Extend `RepositoryProperties.Repo` with an optional `access` block of per-action principal
lists:

```yaml
- name: private-libs
  access:
    read:    [alice, "@platform"]
    publish: [alice]
    delete:  [alice]
```

A **principal** is a configured username, or a role written `@role` (matched against the user's
authorities). An action with no list falls back to its default (D4). The block is additive — existing
`{name, type}`/proxy/group config is unchanged.

**Rationale**: The clarified grant model — permissions declared *on the repository*, principals are
usernames or `@role`s. Co-locates a repo's access with its other config; easy to audit per repo.

## D3 — `401` vs `403`

**Decision**: Let Spring Security's `ExceptionTranslationFilter` decide: when the
`AuthorizationManager` denies and the authentication is anonymous → the `BasicAuthenticationEntryPoint`
returns `401` with `WWW-Authenticate: Basic realm="relikquary"`; when denied and authenticated → `403`.
The `AuthorizationManager` therefore only needs to return granted/denied; it does not compute the status.

**Rationale**: Reuses the framework's correct, client-compatible challenge behaviour (Principle I); no
manual status logic to get wrong.

## D4 — Default semantics (backward compatibility)

**Decision**: With no `access` block (or a missing action list):
- **READ** → open (anonymous permitted) — today's behaviour.
- **PUBLISH** → requires the existing global `PUBLISH` role (today's behaviour).
- **DELETE** → requires the global `PUBLISH` role (today's behaviour).
An explicit list for an action **overrides** that default for that action on that repo (e.g. a repo with
`publish: [alice]` accepts alice even if she lacks the global `PUBLISH` role, and rejects a global
`PUBLISH` holder who isn't listed).

**Rationale**: FR-002/FR-003 and the clarified "explicit grant overrides default"; a config with no
`access` anywhere behaves exactly as feature 002/004 do today (regression-preserving).

## D5 — Group read (permissive union)

**Decision**: In `RepositoryResolver.group(...)`, before delegating to a member, check
`permits(member, READ, auth)`; if denied, skip that member (treat as non-serving) and continue. A member
serves only if it both has the artifact and permits the user. If none qualify → `Miss` (404). Group
reads therefore never emit a `401` challenge — an unreadable/absent artifact is simply `404` for that
user.

**Rationale**: The clarified permissive-union semantics; keeps a private member from masking a public
copy and avoids leaking which member holds a coordinate.

## D6 — Proxy/group writes & proxy reads

**Decision**:
- **READ on a proxy** is a single known repo → enforced by the `AuthorizationManager` (proper 401/403),
  like a hosted repo. A denied user never triggers an upstream fetch.
- **PUBLISH to a proxy/group** stays `405` regardless of authorization: the manager permits the `PUT` to
  reach the controller, which returns `405` (matches this spec's edge case and keeps method semantics
  ahead of authz for read-only kinds).
- **DELETE on a proxy** (cache eviction, feature 006) is governed by the proxy's delete policy (default:
  global `PUBLISH`); group delete is a no-op/`404` (no backing storage).

**Rationale**: Aligns read-only kind semantics (405) with the existing 006 behaviour while still gating
proxy reads/cache-evictions.

## D7 — Security disabled

**Decision**: When `relikquary.security.enabled=false`, the filter chain keeps its existing
`permitAll` branch (the `AuthorizationManager` is not wired), and `RepositoryAuthorizer.permits`
short-circuits to `true` (so the resolver's group checks are also no-ops). Everything is open.

**Rationale**: FR-009 — the local-dev bypass is preserved unchanged.

## D8 — Dependencies

**Decision**: No new dependencies — Spring Security (7.x) is already on the classpath (feature 002).
`gradle/verification-metadata.xml` is untouched.

**Rationale**: Honors Principle IV; nothing to regenerate.

## D9 — Testing

**Decision**: Integration tests (`@SpringBootTest` + JDK `HttpClient`) configuring users and per-repo
`access` grants, covering: the publish matrix (permitted 201 / forbidden 403 / unauth 401), private read
(anonymous 401, non-permitted 403, permitted 200, open repo 200), delete matrix, group permissive-union
read, browse-API parity with the Maven path, the disabled bypass, and a backward-compatibility regression
(no `access` anywhere ⇒ unchanged). Unit tests for `RepositoryAuthorizer` (the policy matrix, role vs
username matching, defaults/override) and the request→(repo, action) parser.

**Rationale**: Principle II — authorization is security-critical behaviour and must be proven across both
the Maven and browse surfaces, not mocked.

## Frontend note

The browse UI may now receive `401` for a private repo's read calls. Enhancing the SPA to prompt for
credentials on read is **out of scope** for this backend-authorization feature (the API enforces
correctly; the UI already has a Basic-auth helper used for delete). Tracked as a follow-on.
