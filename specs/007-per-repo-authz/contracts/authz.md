# Contract: Per-Repository Authorization

Extends the auth model of feature 002. Authentication is unchanged (HTTP Basic over the configured
users); this adds per-repository authorization for READ, PUBLISH, and DELETE. Applies to the Maven wire
protocol (`/{repo}/**`), the browse/manage API (`/api/...`), and proxy/group repos.

## Configuration

Per-repository grants live on the repository, in the same `relikquary.repositories` list:

```yaml
relikquary:
  security:
    enabled: true
    users:
      - { username: alice, password: "{bcrypt}...", roles: [PUBLISH, platform] }
      - { username: bob,   password: "{bcrypt}...", roles: [PUBLISH] }
  repositories:
    - name: releases            # no access block ⇒ open reads, PUBLISH-gated writes (unchanged)
      type: release
    - name: private-libs        # private repo
      type: mixed
      access:
        read:    [alice, "@platform"]   # username or @role
        publish: [alice]
        delete:  [alice]
```

- A principal is a **username** (`alice`) or a **role** (`@platform`, matching a user's role).
- An action with no list uses its **default**: READ → open; PUBLISH/DELETE → global `PUBLISH` role.
- An explicit list **overrides** the default for that action.

## Decisions

| Action | Trigger | No grant (default) | With grant |
|--------|---------|--------------------|-----------|
| READ | `GET`/`HEAD` `/{repo}/**`; `GET /api/repositories/{repo}/contents\|file/**` | open (anonymous OK) | only listed principals |
| PUBLISH | `PUT /{repo}/**` (hosted) | global `PUBLISH` role | only listed principals |
| DELETE | `DELETE /api/repositories/{repo}/**` | global `PUBLISH` role | only listed principals |

## Status codes

- **Permitted** → normal response (`200`/`201`/`204`/…).
- **Denied, unauthenticated** → `401` with `WWW-Authenticate: Basic realm="relikquary"`.
- **Denied, authenticated without permission** → `403`.
- **Unknown repository** → `404` (existence is not secret; authz never turns a `404` into a `401`).
- **Publish to a proxy/group** → `405` (read-only kind; precedes authz).

## Groups (permissive union)

A `GET` through a group is governed by each member's READ policy: a member that has the artifact but
denies the requesting user is skipped like a non-serving member. The group returns the artifact from the
first member that both has it and permits the user, else `404`. Group reads do **not** emit a `401`
challenge.

## Browse API parity

- `GET /api/repositories` (list names) is always allowed — repository existence is not secret.
- `GET /api/repositories/{repo}/contents|file/**` obeys the same READ policy as the Maven path, applied
  uniformly to every file (artifacts, `maven-metadata.xml`, checksums).
- `DELETE /api/repositories/{repo}/**` obeys the DELETE policy.

## Invariants

- When `relikquary.security.enabled=false`, all per-repo rules are ignored — every action is open.
- A deployment with no `access` blocks behaves exactly as feature 002/004 (open reads, PUBLISH-gated
  writes).
- Faithful storage (Principle IV) is unaffected: authorization gates access, never mutates bytes.
