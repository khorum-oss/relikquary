# Phase 0 Research: Container-Aware Catalog & Dashboard

Decisions resolved from the existing feature-016 catalog/stats code and the feature-018/020/022 container
layer; no open NEEDS CLARIFICATION remained.

## D1. Representing a container image in the catalog without breaking Maven rows

- **Decision**: Add one additive field to `CatalogEntry`: `type` (`maven` | `container`, default `maven`).
  A container row reuses the existing fields with container meaning — `artifact` = image name,
  `latestVersion` = latest tag, `versionCount` = tag count, `sizeBytes` = summed manifest size, `group` = ""
  — so the response shape is unchanged and every existing (Maven) row keeps its exact meaning.
- **Rationale**: The catalog table already renders name / latest / count / size columns; overloading those
  columns with container semantics plus a `type` discriminator gives a single interleaved list with zero
  change to the Maven path and a one-field-additive DTO. The frontend branches on `type` for the label,
  the badge, and the row link.
- **Alternatives considered**: (a) A separate `containerEntries` array / a second endpoint — rejected: it
  splits the one searchable list the spec wants and duplicates paging. (b) A new richer unified schema
  (rename group/artifact→name, etc.) — rejected: a breaking reshape of an established response for no user
  benefit.

## D2. Where container rows are produced

- **Decision**: Produce them in the existing `CatalogService`, which already iterates
  `registry.all().filter { authorizer.permits(it, READ, auth) }`. For a `CONTAINER`-format repo, delegate to
  a new `ContainerBrowseService.catalogImages(repo, kind)` projection; for MAVEN repos, the current
  storage-walk path is untouched.
- **Rationale**: `CatalogService` is the single owner of the auth-scoped cross-repo aggregation, so container
  rows automatically inherit the READ-scoping (FR-006/SC-005) with no new authorization code. Keeping the
  container projection in `ContainerBrowseService` keeps container knowledge in the container package.
- **Alternatives considered**: A parallel container catalog service merged in the controller — rejected:
  duplicates the auth-scoping and paging logic already in `CatalogService`.

## D3. What a container catalog row shows, per repo kind

- **Decision**:
  - **Hosted**: one row per image that has ≥1 tag. `latestVersion` = the tag with the newest `updatedAt`;
    `versionCount` = number of tags; `sizeBytes` = sum of the (distinct) manifest `sizeBytes` the image's
    tags point at. An untagged/dangling image is omitted (consistent with feature 022's tag-driven image
    list).
  - **Proxy**: one row per distinct cached image name. It has no stored tags, so `latestVersion` = ""
    (rendered as "—"), `versionCount` = number of cached manifests, `sizeBytes` = sum of cached manifest
    sizes. No live upstream enumeration (FR-008).
- **Rationale**: Reuses exactly the stored state and size notion already presented by the container tags/
  browse UI (features 018/020/022) — cheap indexed reads, no manifest re-parsing, no blob walk. The proxy
  case honors "cached only" without pretending to know the upstream's full image set.
- **Alternatives considered**: Computing a true image byte-size by parsing each manifest's config+layers —
  rejected as costly for a list view and beyond the spec's stated size notion; the manifest-detail view
  already provides the precise per-manifest total.

## D4. Selecting a row

- **Decision**: A container row links to the image's tag view `/c/{repo}/{image}` (features 018/020/022); a
  Maven row keeps its existing folder-view link `/r/{repo}/{group-path}/{artifact}`. The name filter matches
  on the display name — `image` for container, `group:artifact` for Maven — uniformly.
- **Rationale**: Reuses the destinations that already exist for each type; no new route. Filtering on the
  display name keeps container and Maven rows searchable with the same interaction (FR-004/SC-003).

## D5. The dashboard "images" figure and its scope

- **Decision**: Add `images` to `StatsResponse` = the count of **distinct container images** across the
  container repositories (hosted: distinct tagged image names; proxy: distinct cached image names), computed
  from the container tables. It follows the **existing `/api/stats` convention**: like the repository,
  artifact, and storage figures, it is an unscoped aggregate (the stats endpoint is open and already reports
  totals across all repositories), not per-user auth-scoped.
- **Rationale**: Consistency with the sibling figures avoids a confusing mixed model on one endpoint, and the
  count is a cheap distinct-count over indexed columns (no walk). The test uses a fully-readable setup, so
  "distinct images present" and "distinct images a permitted view shows" coincide (SC-006).
- **Alternatives considered**: Auth-scoping only the images figure — rejected: it would make one dashboard
  number behave differently from its neighbors on the same open endpoint. (The **catalog**, which *is*
  auth-scoped, remains the privacy-sensitive surface.)
