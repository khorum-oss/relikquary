# Phase 1 Data Model: Container Image Manifest & Layer Detail View

**No persistence change.** This feature introduces no table, entity, or Liquibase changeset. The model below
is a **read projection** computed on demand from the manifest bytes already stored by feature 018 (keyed by
digest) plus the presence of referenced objects in `ContainerStorage`. It is the response shape of the new
browse endpoint (see [contracts/manifest-detail-api.md](./contracts/manifest-detail-api.md)).

## Source of truth

- **Manifest bytes**: `ContainerStorage.readManifestBytes(repository, digest)` — verbatim JSON stored at
  push time. Never modified by this feature.
- **Manifest descriptor**: `ContainerManifest` row (repository, digest, `mediaType`, `sizeBytes`,
  `createdAt`) — the top-level manifest's own media type and byte size.
- **Presence**: `ContainerStorage.hasBlob(repository, digest)` / `hasManifest(repository, digest)` — whether
  a referenced config/layer/sub-manifest is stored locally.

## Projection types (derived; not stored)

### `Descriptor`

A reference to stored content, as declared by a manifest.

| Field | Meaning |
|-------|---------|
| `digest` | `sha256:<hex>` of the referenced object |
| `mediaType` | the descriptor's declared media type |
| `size` | the descriptor's declared size in bytes |
| `present` | whether the referenced object is stored locally (blob for config/layer, manifest for a platform sub-manifest) |
| `platform` | (index entries only) the target `Platform`, when the index annotates one |

### `Platform`

| Field | Meaning |
|-------|---------|
| `os` | operating system (e.g. `linux`) |
| `architecture` | CPU architecture (e.g. `amd64`, `arm64`) |
| `variant` | optional variant (e.g. `v8`); omitted when absent |

### `ManifestDetail` (discriminated by `kind`)

One of three shapes; `repository`, `digest`, `mediaType`, and `size` (the top-level manifest's own values)
are always present.

- **`kind = "image"`** — a single-platform image manifest:
  - `config`: `Descriptor` — the image config object.
  - `layers`: ordered `Descriptor[]` — the filesystem layers, in manifest order.
  - `totalSize`: `config.size` + Σ `layers[].size` (the declared compressed pull size).
- **`kind = "index"`** — a manifest list / image index:
  - `manifests`: `Descriptor[]` — one per platform sub-manifest, each with its `platform` when declared.
- **`kind = "unknown"`** — bytes present but not a recognized image/index shape:
  - no `config`/`layers`/`manifests`; the caller shows digest + media type + size and an "unavailable" note.

## Classification rule (matches `ManifestService.referencedDigests`)

1. If the parsed JSON has a non-empty `manifests` array → **index**; each element is a `Descriptor` with its
   `platform` (from the element's `platform` object, when present).
2. Else if it has a `config` object (with a `digest`) → **image**; `layers` is the ordered `layers` array
   (possibly empty).
3. Else → **unknown**.

## Validation & edge rules

- The endpoint requires the repository to exist (else 404) and to be `format = CONTAINER` (else 400) —
  identical to the feature-018 images/tags endpoints.
- If the top-level manifest bytes are not stored for `digest` → 404.
- `present` is computed per referenced descriptor; a `false` value never fails the request (FR-008).
- A malformed/unrecognized top-level manifest yields `kind = "unknown"`, not an error (FR-007, SC-005).
- Layer order is preserved exactly as in the manifest (FR-002).
- Read authorization is inherited from the `containers` sub-path READ mapping (feature 018); no new rule.
