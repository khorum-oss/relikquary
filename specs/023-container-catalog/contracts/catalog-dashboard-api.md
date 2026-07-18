# Contract: Container-Aware Catalog & Dashboard (Browse/Overview API)

Two additive changes to existing internal endpoints (feature 016). No OCI `/v2` or Maven wire change.

## `GET /api/catalog?pageSize={n}` — entries gain a `type` and include container images

Unchanged shape (`entries`, `page`, `pageSize`, `total`, `truncated`), but each `CatalogEntry` now carries a
`type` and the list interleaves Maven artifacts and container images from the caller's **READ-authorized**
repositories.

Maven entry (unchanged, `type` defaults to `"maven"`):

```json
{ "type": "maven", "repository": "releases", "group": "com.example", "artifact": "widget",
  "latestVersion": "1.2.0", "versionCount": 3, "sizeBytes": 40960 }
```

Container entry (hosted):

```json
{ "type": "container", "repository": "apps", "group": "", "artifact": "team/service",
  "latestVersion": "1.0.0", "versionCount": 2, "sizeBytes": 1044 }
```

Container entry (proxy — cached only, no stored tags):

```json
{ "type": "container", "repository": "dockerhub", "group": "", "artifact": "library/alpine",
  "latestVersion": "", "versionCount": 1, "sizeBytes": 528 }
```

Rules:
- Entries appear only for repositories the caller may **READ** (unchanged authorization).
- A **hosted** container repo contributes one entry per image with ≥1 tag (`latestVersion` = latest tag,
  `versionCount` = tag count, `sizeBytes` = summed manifest size); an untagged image is omitted.
- A **proxy** container repo contributes one entry per distinct cached image (`latestVersion` = "",
  `versionCount` = cached-manifest count); **no live upstream enumeration**.
- The existing name filter matches container entries on the image name and Maven entries on
  `group:artifact`, uniformly; paging/`truncated` behavior is unchanged.

## `GET /api/stats` — response gains `images`

```json
{ "repositories": 8, "artifacts": 120, "storageBytes": 10485760, "images": 3 }
```

- `images` = the count of **distinct container images** across the repositories (hosted: distinct tagged
  image names; proxy: distinct cached image names). `0` when there are none.
- Consistent with the sibling figures, `images` is an unscoped aggregate on the open stats endpoint; it never
  triggers an upstream call.

## Frontend behavior (UI contract)

- The catalog renders a **type badge** per row (container vs. Maven). A container row links to
  `/c/{repository}/{artifact}` (the image's tag view); a Maven row keeps its folder-view link.
- The dashboard shows the `images` figure beside the repository, artifact, and storage figures.

## Non-goals

Enumerating a proxy's live upstream image/tag list, changing Maven catalog semantics, and search beyond the
existing name filter are out of scope.
