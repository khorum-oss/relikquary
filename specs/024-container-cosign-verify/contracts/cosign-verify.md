# Contract: Container Image Signature Verification (advisory)

Additive changes to existing internal browse responses (features 018/020) plus two new optional config keys.
No OCI `/v2` wire change; no new endpoint; no pull/push effect.

## Configuration (new, optional)

| Key | Scope | Meaning |
|-----|-------|---------|
| `relikquary.repositories[].cosign-public-key` | per repository | cosign public key for verifying that repo's images — an inline PEM (`-----BEGIN PUBLIC KEY-----…`) or a path to a PEM file |
| `relikquary.cosign.default-public-key` | global | the public key used for repositories that set no key of their own — same inline-PEM-or-path form |

Both are absent by default (→ trust status `unknown`). Keys are operator-supplied via env/file and never
committed.

## `GET /api/repositories/{repo}/containers/tags` — each tag gains `trust`

```json
{
  "repository": "apps", "image": "team/service", "kind": "HOSTED",
  "tags": [
    { "tag": "1.0.0", "digest": "sha256:aaaa…", "mediaType": "…", "size": 528, "pushedAt": "…",
      "trust": "verified" }
  ]
}
```

## `GET /api/repositories/{repo}/containers/manifest?digest={digest}` — response gains `trust`

```json
{ "kind": "image", "repository": "apps", "digest": "sha256:aaaa…", "mediaType": "…", "size": 528,
  "config": { … }, "layers": [ … ], "totalSize": 61, "trust": "verified" }
```

`trust` ∈ `verified` | `signed-but-unverified` | `unsigned` | `unknown`, per
[data-model.md](../data-model.md).

## Verification behavior

For a hosted container image with digest `D` in repo `R`:

1. Resolve `R`'s public key (per-repo `cosign-public-key`, else the global default). None ⇒ **unknown**.
2. Look up the signature tag `sha256-<hex(D)>` under the same image name. Absent ⇒ **unsigned**.
3. Read the `.sig` manifest; for each layer with mediaType
   `application/vnd.dev.cosign.simplesigning.v1+json`, read the payload blob and the
   `dev.cosignproject.cosign/signature` annotation (base64 signature). Verify the signature over the payload
   bytes with the key (ECDSA/RSA/Ed25519 by key type) and check the payload's `docker-manifest-digest` = `D`.
4. Any layer that both verifies and matches ⇒ **verified**; otherwise (a `.sig` exists but none qualify) ⇒
   **signed-but-unverified**.
5. A malformed/unreadable key ⇒ fail closed (never **verified**); a malformed layer/payload is skipped; the
   response never errors.

## UI contract

- The tag view shows a **trust badge** per tag row; the manifest detail panel shows a trust badge for the
  drilled digest — for **hosted container** repos only.
- The badge is advisory: it changes nothing about pulling the image.

## Non-goals

Keyless (Fulcio cert + Rekor transparency-log + OIDC identity) verification, enforcement/blocking of pulls,
signing by Relikquary, Maven-artifact signatures, proxy repositories, and the OCI 1.1 Referrers API are out
of scope.
