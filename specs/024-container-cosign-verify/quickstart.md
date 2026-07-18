# Quickstart: Container Image Signature Verification (cosign, advisory)

Validate that a signed image shows **verified**, an unsigned one **unsigned**, and no-key **unknown**.

## Prerequisites

- Backend running with a hosted container repo (`apps`).
- A cosign key pair. With the `cosign` CLI: `cosign generate-key-pair` (yields `cosign.key` + `cosign.pub`).
  Without it, an EC P-256 pair via `openssl` works for key-based verification:
  ```sh
  openssl ecparam -name prime256v1 -genkey -noout -out cosign.key
  openssl ec -in cosign.key -pubout -out cosign.pub
  ```

## 1. Configure the public key

Point the repo (or the global default) at the public key — inline PEM or a file path:

```yaml
relikquary:
  cosign:
    default-public-key: /path/to/cosign.pub      # global default
  repositories:
    - name: apps
      format: container
      cosign-public-key: /path/to/cosign.pub     # per-repo (takes precedence)
```

Keys are operator config — mount the file or inject the PEM via env; never commit it.

## 2. Push and sign an image

```sh
docker push <host>/apps/team/service:1.0.0
cosign sign --key cosign.key <host>/apps/team/service:1.0.0      # pushes the sha256-<hex>.sig artifact
```

(Or construct the `.sig` artifact directly — a simple-signing payload blob referencing the image digest plus
a `dev.cosignproject.cosign/signature` annotation — as the tests do.)

## 3. Read the trust status

```sh
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service' \
  | jq '.tags[] | {tag, trust}'
```

**Expected**: `"trust":"verified"` for the signed tag. An image with no `.sig` reads `"unsigned"`; a
signature made with a different key reads `"signed-but-unverified"`; with no key configured, `"unknown"`.
`curl` of the manifest detail (`…/containers/manifest?digest=<D>`) shows the same `trust`.

## 4. Confirm it is advisory

```sh
docker pull <host>/apps/team/service:1.0.0     # succeeds identically regardless of trust status
```

**Expected**: the pull is unaffected — verification never blocks or alters it.

## 5. UI walkthrough

1. Open the web UI → `apps` → `team/service` → its tags. Each tag shows a **trust badge**
   (verified / signed / unsigned / unknown).
2. Drill a tag into its manifest detail — the badge appears there too.
3. A proxy repo and Maven repos show no trust badge.

## 6. Automated validation

- Backend: `gradle :backend:test --tests '*ContainerCosignVerifyApiTest'` — an in-JVM EC key signs a faithful
  cosign artifact; asserts verified / unsigned / signed-but-unverified / unknown / malformed-degradation.
- Frontend: `bash frontend/scripts/e2e.sh` (seeds a signed image via `openssl` + a configured key) runs
  `frontend/tests/container.spec.ts` (the trust badge shows verified).
- Gates: `gradle :backend:detekt` and `npm --prefix frontend run check` + `run build`.
