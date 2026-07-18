# Phase 1 Data Model: Container Image Signature Verification (cosign, advisory)

**No schema change.** This feature adds config keys, a derived status, and two response fields. No table,
entity, or changeset is added; verification reads bytes features 018/020 already store.

## Trust status (derived; not stored)

`TrustStatus` — one verdict per image manifest digest:

| Value | Meaning |
|-------|---------|
| `verified` | a cosign signature validates against the resolved key **and** its payload references this image digest |
| `signed-but-unverified` | a `.sig` artifact exists but no signature both validates and matches the digest |
| `unsigned` | no `.sig` artifact for the digest |
| `unknown` | no public key resolved for the repository (no per-repo key, no global default) |

Fail-closed rule: a malformed/unreadable key ⇒ *never* `verified` (a `.sig` ⇒ `signed-but-unverified`, none ⇒
`unsigned`); a malformed manifest/payload for a layer is skipped; the page never errors.

## Inputs (existing stored state + config)

- **Signed image**: a `ContainerManifest` (digest `D`) already stored (feature 018).
- **Signature artifact**: a `ContainerTag (repo, imageName, "sha256-<hex(D)>")` → a `.sig` manifest whose
  layers carry `mediaType application/vnd.dev.cosign.simplesigning.v1+json`, a payload blob, and a
  `dev.cosignproject.cosign/signature` annotation — all stored bytes read via `ContainerStorage`.
- **Signature payload**: the layer's blob JSON; its `critical.image.docker-manifest-digest` must equal `D`.
- **Configured public key** (config, not stored by this feature):
  - `Repo.cosignPublicKey: String?` — inline PEM or a file path, per repository.
  - `relikquary.cosign.default-public-key: String?` — the global default when a repo sets none.

## Key resolution (derived)

`CosignKeys.resolve(repo)` → one of:

| Result | When | Effect on status |
|--------|------|------------------|
| `None` | no per-repo key and no global default | `unknown` |
| `Key(publicKey)` | a per-repo key (else the global default) parsed successfully | real verification |
| `Invalid` | a key was configured but cannot be parsed | fail closed (never `verified`) |

Per-repo key precedes the global default. Parsed keys are cached per repository.

## Response projections (extended, additive)

- **Tags response** (`GET …/containers/tags`): each `tag` gains `trust: TrustStatus`.
- **Manifest detail** (`GET …/containers/manifest?digest=`): gains `trust: TrustStatus` for the drilled
  digest.

## Rules

- Trust is computed only for **hosted container** images; proxy container repos, Maven repos, and non-image
  entries carry no trust badge (FR-010).
- Trust is computed/shown only for a user permitted to READ the repository (FR-011) — it rides the existing
  browse authorization.
- Computing trust never alters stored bytes and never affects the `/v2` pull/push path (FR-009).
