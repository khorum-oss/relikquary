# Quickstart: Per-Repository Authorization

Validates feature 007 end-to-end. See [contracts/authz.md](contracts/authz.md) and
[data-model.md](data-model.md) for the full contract.

## Prerequisites

- JDK 21; the repo checked out; `./gradlew` available.
- A config with users and a private repo, e.g. (passwords `{noop}` for the demo):

```yaml
relikquary:
  security:
    enabled: true
    users:
      - { username: alice, password: "{noop}pw", roles: [PUBLISH, platform] }
      - { username: bob,   password: "{noop}pw", roles: [PUBLISH] }
  repositories:
    - name: releases
      type: release
    - name: private-libs
      type: mixed
      access:
        read:    [alice, "@platform"]
        publish: [alice]
        delete:  [alice]
```

## Scenario 1 — Per-repo publish control

```bash
B=com/acme/x/1.0.0/x-1.0.0.jar
curl -u alice:pw -X PUT --data 'x' http://localhost:8080/private-libs/$B   # 201 (alice may publish)
curl -u bob:pw   -X PUT --data 'x' http://localhost:8080/private-libs/$B   # 403 (bob not a publisher here)
curl            -X PUT --data 'x' http://localhost:8080/private-libs/$B    # 401 + WWW-Authenticate
curl -u bob:pw  -X PUT --data 'x' http://localhost:8080/releases/$B        # 201 (default: global PUBLISH)
```

## Scenario 2 — Private read

```bash
curl -u alice:pw http://localhost:8080/private-libs/$B   # 200 (alice may read)
curl -u bob:pw   http://localhost:8080/private-libs/$B   # 403
curl            http://localhost:8080/private-libs/$B    # 401
curl            http://localhost:8080/releases/$B        # 200 (releases has no read rule → open)
```

The same READ rule governs the browse API:

```bash
curl -u bob:pw http://localhost:8080/api/repositories/private-libs/contents   # 403
curl           http://localhost:8080/api/repositories                          # 200 (names not secret)
```

## Scenario 3 — Delete & group union

```bash
# delete obeys the delete policy
curl -u bob:pw   -X DELETE http://localhost:8080/api/repositories/private-libs/$B   # 403
curl -u alice:pw -X DELETE http://localhost:8080/api/repositories/private-libs/$B   # 204

# a group resolves via the first member that both has the artifact AND permits the user;
# a member that denies the user is skipped (union) → 404 only if none qualify.
```

## Scenario 4 — Backward compatibility & disable switch

```bash
# With no `access` blocks anywhere: open reads, PUBLISH-gated writes (feature 002/004 behaviour).
# With security disabled, everything is open:
./gradlew :backend:bootRun --args='--relikquary.security.enabled=false'
curl -X PUT --data 'x' http://localhost:8080/private-libs/$B   # 201 (bypass)
```

## Automated verification

```bash
./gradlew build      # backend: detekt + Kover + unit/integration authz matrix; existing suites unchanged
```

Expected: green; the authorization unit + integration tests pass and the existing auth/routing/browse
suites still pass unchanged.
