# Phase 1 Data Model: Container-Aware Catalog & Dashboard

**No schema change.** This feature adds one DTO field and one dashboard figure, both computed from state that
features 016 and 018/020/022 already store. No table, entity, or changeset is added.

## Catalog entry (extended, additive)

`CatalogEntry` gains one field; the rest are reused with a per-type meaning:

| Field | Maven meaning | Container meaning |
|-------|---------------|-------------------|
| `type` *(new)* | `"maven"` (default) | `"container"` |
| `repository` | repository name | repository name |
| `group` | Maven group | `""` (unused) |
| `artifact` | Maven artifact id | image name |
| `latestVersion` | latest version | latest tag (hosted); `""` (proxy — no stored tags) |
| `versionCount` | version count | tag count (hosted); cached-manifest count (proxy) |
| `sizeBytes` | total artifact size | summed manifest size for the image |

- Existing consumers that ignore `type` see unchanged Maven rows (default `type = "maven"`).
- The frontend renders a **type badge** from `type`, links a container row to `/c/{repository}/{artifact}`
  and a Maven row to its folder view, and filters on the display name (`artifact` for container,
  `group:artifact` for Maven).

## Where container rows come from (read projection)

`ContainerBrowseService.catalogImages(repository, kind)` → `List<ContainerCatalogImage>`:

- **Hosted**: group `ContainerTag` rows by `imageName`; per image → `latestTag` (max `updatedAt`),
  `tagCount`, `sizeBytes` (Σ of the distinct referenced `ContainerManifest.sizeBytes`). Images with no tags
  are omitted.
- **Proxy**: group cached `ContainerManifest` rows by `imageName`; per image → `latestTag = ""`,
  `count = distinct cached manifests`, `sizeBytes` (Σ of cached manifest sizes). No upstream call.

`CatalogService` maps each `ContainerCatalogImage` to a `CatalogEntry` with `type = "container"`,
`group = ""`, `artifact = name`, `latestVersion = latestTag`, `versionCount = count`, `sizeBytes = sizeBytes`.
The same READ-authorization filter the Maven path uses applies (a repo the user cannot read contributes
nothing).

## Dashboard summary (extended, additive)

`StatsResponse` gains `images`:

| Field | Meaning |
|-------|---------|
| `repositories` | configured repository count (unchanged) |
| `artifacts` | stored object count (unchanged) |
| `storageBytes` | stored bytes (unchanged) |
| `images` *(new)* | count of distinct container images across the repositories |

- `images` is an unscoped aggregate, consistent with the sibling figures on the open `/api/stats` endpoint
  (hosted: distinct tagged image names; proxy: distinct cached image names). Zero when there are none.

## Validation & rules

- Container rows appear only for `format = CONTAINER` repositories the caller may READ; Maven rows are
  unchanged.
- A proxy repo never triggers a live upstream request for the catalog or the images figure.
- The name filter and paging/truncation behavior of the catalog (feature 016) are reused unchanged.
