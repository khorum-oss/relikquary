# Secrets from 1Password (Kubernetes Operator)

Relikquary's stage and prod overlays carry **no secret values**. Passwords are generated in and managed
by **1Password**, and the **1Password Kubernetes Operator** syncs them into the cluster as native
Secrets. You edit passwords in 1Password; the operator keeps the `relikquary-secrets` Secret in each
namespace up to date; the backend and PostgreSQL read from it. Nothing sensitive is ever committed.

```
1Password vault item ──(Connect API)──▶ 1Password Operator ──creates──▶ Secret/relikquary-secrets
  relikquary-stage / relikquary-prod                                     (per namespace)
        │                                                                        │
        └── fields: RELIKQUARY_SECURITY_USERS_0_PASSWORD, RELIKQUARY_DB_PASSWORD ┘
                                                                                 ▼
                                          backend (envFrom secretRef) + postgres (secretKeyRef)
```

The `OnePasswordItem` custom resources that drive this live in the overlays
(`deploy/k8s/overlays/{stage,prod}/secrets.onepassword.yaml`). Each one's `metadata.name` is the name of
the Secret the operator creates, and each **field of the vault item becomes a key** in that Secret.

---

## 1. What each environment's vault item must contain

Create one item per environment. This repo's overlays expect:

| Vault (suggested) | Item             | `itemPath` used by the overlay                       |
|-------------------|------------------|------------------------------------------------------|
| `relikquary-stage`| `relikquary-secrets` | `vaults/relikquary-stage/items/relikquary-secrets` |
| `relikquary-prod` | `relikquary-secrets` | `vaults/relikquary-prod/items/relikquary-secrets`  |

Each item needs these **fields** (the field *label* becomes the Secret key, so match them exactly):

| Field label                            | Value                                                              |
|----------------------------------------|--------------------------------------------------------------------|
| `RELIKQUARY_SECURITY_USERS_0_PASSWORD` | Publisher password **with an encoder prefix** — `{bcrypt}$2a$...` (use bcrypt for prod; `{noop}<plain>` only for throwaway stage) |
| `RELIKQUARY_DB_PASSWORD`               | PostgreSQL password (shared by the postgres container and the backend) |
| `RELIKQUARY_S3_ACCESS_KEY`             | *(only if you switch storage to S3)*                               |
| `RELIKQUARY_S3_SECRET_KEY`             | *(only if you switch storage to S3)*                               |

> **The `{bcrypt}` prefix.** Spring Security needs the stored password to declare its encoder. 1Password
> generates a *raw* password, so the publisher field must hold the prefix + value. The helper script
> below generates a strong password, bcrypt-hashes it, and stores `{bcrypt}$2a$...` for you — the plain
> value is never needed by the server. Use `{noop}` **only** for a throwaway stage.

You can create these items in the 1Password app (use the built-in generator for each password field), or
run the helper — see step 3.

## 2. Install the operator + 1Password Connect (once per cluster)

The operator talks to a **1Password Connect** server that you host in the cluster. Connect needs two
bootstrap credentials from your 1Password account:

- a **`1password-credentials.json`** file, and
- a **Connect token**.

Create both by adding a Connect server to your account — in the 1Password web UI under
**Developer → Connect → New Server**, or with the CLI:

```bash
op connect server create relikquary-k8s --vaults relikquary-stage,relikquary-prod
# writes ./1password-credentials.json
op connect token create relikquary-operator --server relikquary-k8s --vault relikquary-stage,relikquary-prod
# prints the token (also grant it access to both vaults)
```

Install with the official Helm chart, which deploys **both** Connect and the operator and can create the
bootstrap Secrets for you:

```bash
helm repo add 1password https://1password.github.io/connect-helm-charts
helm repo update

helm install onepassword-connect 1password/connect \
  --namespace onepassword --create-namespace \
  --set-file connect.credentials=./1password-credentials.json \
  --set operator.create=true \
  --set operator.token.value="<CONNECT_TOKEN_FROM_ABOVE>" \
  --set operator.autoRestart=true          # roll pods automatically when a synced secret changes
```

> `operator.autoRestart=true` makes the operator restart the backend/postgres pods when their secret
> changes, so a password rotation in 1Password rolls out on its own. Leave it off to roll manually.

Verify:

```bash
kubectl -n onepassword get pods                      # connect + operator Running
kubectl get crd onepassworditems.onepassword.com     # CRD installed
```

Delete `./1password-credentials.json` from disk once Connect is running — it now lives in the cluster.

## 3. Generate + store the passwords (helper script)

`create-items.sh` uses the `op` CLI (signed in to your account) to create the stage and prod items with
**freshly generated** passwords, bcrypt-hashing the publisher password:

```bash
op signin                              # if not already signed in
deploy/k8s/onepassword/create-items.sh stage     # creates vault + item for stage
deploy/k8s/onepassword/create-items.sh prod      # creates vault + item for prod
```

It is idempotent-ish: it creates the vault if missing and refuses to overwrite an existing item (pass
`--rotate` to generate new values on an existing item). See the script header for options. Prefer the
1Password app? Just create the items by hand with the fields from step 1.

## 4. Apply an environment

Once the item exists and the operator is running:

```bash
kubectl apply -k deploy/k8s/overlays/stage      # or .../prod
```

The operator reconciles the `OnePasswordItem` into a `relikquary-secrets` Secret within a few seconds. If
the backend pod starts before the Secret exists it will wait in `CreateContainerConfigError` and recover
automatically once the sync completes.

## 5. Verify

```bash
kubectl -n relikquary-stage get onepassworditem relikquary-secrets   # should be present
kubectl -n relikquary-stage get secret relikquary-secrets            # created by the operator
kubectl -n relikquary-stage get secret relikquary-secrets \
  -o jsonpath='{.data.RELIKQUARY_DB_PASSWORD}' | base64 -d ; echo    # (sanity check only)
```

## 6. Rotating a password

Edit the field in 1Password (or `create-items.sh <env> --rotate`). The operator re-syncs the Secret; with
`operator.autoRestart=true` the backend/postgres pods roll automatically to pick up the new value.
Otherwise roll them yourself:

```bash
kubectl -n relikquary-prod rollout restart deploy/relikquary-backend deploy/relikquary-postgres
```

> Rotating `RELIKQUARY_DB_PASSWORD` changes the password the app *presents*; it does not re-`ALTER` the
> existing PostgreSQL role. On an already-initialised database, update the role too
> (`ALTER USER relikquary WITH PASSWORD '...';`) or the backend will fail to connect.

## Notes

- **Isolation.** Stage and prod use separate vaults, so access and rotation are independent. Grant the
  Connect token only the vaults it needs.
- **Nothing secret in git.** These manifests reference vault *paths*, never values. Keep it that way.
- **Alternative.** If you standardise on the External Secrets Operator instead, the same vault items map
  cleanly to `ExternalSecret` resources — swap `secrets.onepassword.yaml` in each overlay.
