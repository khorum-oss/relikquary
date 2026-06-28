# Data Model: Per-Repository Authorization

Configuration and in-memory domain types; no database. Builds on feature 006's repository model.

## RepositoryAccess (new) — `config/RepositoryProperties.kt`

Optional per-repository access policy. Each field is a list of **principals** (a username, or `@role`).
A `null`/absent list means "use the default for that action" (see Defaults).

| Field | Type | Meaning |
|-------|------|---------|
| `read` | `List<String>?` | Principals allowed to resolve/download/browse this repo. Absent ⇒ open. |
| `publish` | `List<String>?` | Principals allowed to `PUT`. Absent ⇒ global `PUBLISH` role. |
| `delete` | `List<String>?` | Principals allowed to manage-`DELETE`. Absent ⇒ global `PUBLISH` role. |

## RepositoryProperties.Repo (extended)

Adds one optional field; everything else (name, kind, type, remote*, members) is unchanged.

| Field | Type | Notes |
|-------|------|-------|
| `access` | `RepositoryAccess?` | Per-action grants; `null` ⇒ all defaults (today's behaviour). |

## Action (new enum) — `security/`

`READ` | `PUBLISH` | `DELETE`. The operation being authorized against a repo.

## RepositoryAuthorizer (new) — `security/RepositoryAuthorizer.kt`

The single policy decision point, consulted by both the `AuthorizationManager` and the resolver.

- `permits(repo: Repo, action: Action, authentication: Authentication?): Boolean`
- Logic:
  - If `relikquary.security.enabled == false` ⇒ `true` (D7).
  - Resolve the grant list for the action from `repo.access`.
  - **READ**, no list ⇒ `true` (open). **PUBLISH/DELETE**, no list ⇒ require authority `ROLE_PUBLISH`.
  - With a list ⇒ `true` iff the authenticated principal matches it: `authentication.name` ∈ list, or
    the user holds authority `ROLE_<r>` for some `@r` in the list. Anonymous/unauthenticated ⇒ `false`.
- Pure and side-effect free (no storage/upstream access), so it is safe to call inside both the security
  filter and the resolver.

**Principal matching**: a bare token `alice` matches `authentication.name == "alice"`; a token `@team`
matches authority `ROLE_team` (Spring stores configured role `team` as `ROLE_team`).

## RepositoryAuthorizationManager (new) — `security/RepositoryAuthorizationManager.kt`

`AuthorizationManager<RequestAuthorizationContext>` wired into the filter chain when security is enabled.

- Spring invokes `check(Supplier<Authentication>, RequestAuthorizationContext)`; the manager **unwraps the
  supplier** (`.get()`) to obtain the `Authentication` before calling `RepositoryAuthorizer.permits(...)`.
- Parses the request into `(repoName, action)` (see Request mapping); looks up the repo via
  `RepositoryRegistry`.
- **Unknown repo** ⇒ grant (let the controller return `404`; existence is not secret).
- **GROUP** repo ⇒ grant (group read is enforced per-member in the resolver; publish/delete N/A).
- **PUT to a PROXY/GROUP** ⇒ grant (controller returns `405`; method semantics precede authz for
  read-only kinds).
- Otherwise ⇒ `AuthorizationDecision(authorizer.permits(repo, action, authentication))`.
- Spring's `ExceptionTranslationFilter` maps a denied decision to `401` (anonymous → Basic challenge) or
  `403` (authenticated), per D3.

### Request mapping (path → repo, action)

| Request | repo | action |
|---------|------|--------|
| `GET`/`HEAD` `/{repo}/**` (Maven) | first segment | READ |
| `PUT` `/{repo}/**` | first segment | PUBLISH |
| `GET` `/api/repositories/{repo}/contents/**`, `/file/**` | `{repo}` | READ |
| `DELETE` `/api/repositories/{repo}/**` | `{repo}` | DELETE |
| `GET` `/api/repositories` (list), `/api`, `/ui/**` | — | grant (not repo-scoped) |

## Resolution / status mapping

- Group read (resolver, D5): skip members where `permits(member, READ, auth)` is false; serve the first
  member that both has the artifact and permits the user; else `Miss` (404). No challenge.
- Hosted/proxy READ, PUBLISH, DELETE: decided by the `AuthorizationManager` ⇒ `401`/`403`/allow.

| Situation | HTTP |
|-----------|------|
| Permitted action | normal (200/201/204/…) |
| Denied, anonymous (could authenticate) | 401 + `WWW-Authenticate: Basic` |
| Denied, authenticated without permission | 403 |
| Group read with no member serving+permitting | 404 |
| Unknown repo | 404 |
| Publish to proxy/group | 405 |

## Backward compatibility

A configuration with no `access` block on any repository yields: READ open everywhere, PUBLISH/DELETE
gated by the global `PUBLISH` role — byte-for-byte the feature 002/004 behaviour.
