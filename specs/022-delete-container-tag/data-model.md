# Phase 1 Data Model: Delete a Container Image Tag from the Web UI

**No schema change.** This feature deletes an existing `ContainerTag` row via the existing service and
refines two read projections. No table, entity, or changeset is added.

## Affected persistence (existing, feature 018)

- **`ContainerTag`** `(repository, imageName, tag) → manifestDigest`: the mutable pointer. **Deleting a tag
  removes exactly one such row.**
- **`ContainerManifest`** `(repository, digest, mediaType, sizeBytes, …)`: the immutable descriptor. **Retained
  after a tag delete** (no GC).
- Stored manifest/blob **bytes** in `ContainerStorage`: **retained** after a tag delete.

## State transition — delete a tag

```
(repo, image, tag) → digest D        [before]
        │  DELETE …/containers/tags?image&tag   (hosted; permitted)
        ▼
tag pointer removed; manifest D and its blobs remain, still retrievable by digest   [after]
```

- Removing the last tag of an image leaves an **untagged manifest** (dangling) — retained, not shown as an
  image on a hosted repo (see the kind-aware listing below).
- Two tags pointing at the same digest: deleting one leaves the other and the digest intact.

## Read projections (refined)

### Tags response — `GET …/containers/tags`

Adds one field:

| Field | Meaning |
|-------|---------|
| `kind` | the repository's kind (HOSTED / PROXY) — lets the UI show the delete affordance for hosted only |

(`tags[]` entries are unchanged: `tag`, `digest`, `mediaType`, `size`, `pushedAt`.)

### Images response — `GET …/containers` (kind-aware image set)

The per-image fields are unchanged (`name`, `tagCount`, `manifestCount`, `lastPushed`); only **which images
are listed** changes:

| Repo kind | Images listed |
|-----------|---------------|
| HOSTED | image names that have **≥1 tag** (tag-driven) — so a fully-untagged image drops out after its last tag is deleted, with no GC |
| PROXY | image names with **≥1 cached manifest** (unchanged) — a proxy has no stored tags |

## Validation & authorization rules

- Delete applies only to a **container** repository (else 400) and only to a **HOSTED** one (a proxy → 405).
- Delete reuses the per-repository **DELETE** authorization (feature 007): anonymous → 401, authenticated
  without permission → 403.
- Deleting a **non-existent** tag → 404 (the service reports "not deleted").
- A successful delete → **204 No Content**; the manifest remains retrievable by its digest.
