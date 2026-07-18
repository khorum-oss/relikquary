# Quickstart: Container-Aware Catalog & Dashboard

Validate that container images are discoverable in the catalog and counted on the dashboard.

## Prerequisites

- Backend running with a hosted container repo (`apps`) and at least one Maven repo (`releases`).
- A container image pushed (e.g. `docker push <host>/apps/team/service:1.0.0`, or the e2e `curl` seed) and a
  Maven artifact published.

## 1. Container images appear in the catalog

```sh
curl -s 'http://127.0.0.1:8080/api/catalog?pageSize=500' \
  | jq '.entries[] | {type, repository, artifact, latestVersion, versionCount, sizeBytes}'
```

**Expected**: entries include both `"type":"maven"` rows (unchanged `group`/`artifact`/`latestVersion`) and
`"type":"container"` rows for images (`artifact` = image name, `latestVersion` = latest tag, `versionCount` =
tag count). A proxy repo's cached images appear with `latestVersion:""`.

## 2. Authorization scoping (with auth enabled)

```sh
# A user without read access to a private container repo does not see its images:
curl -s -u bob:pw 'http://127.0.0.1:8080/api/catalog?pageSize=500' | jq '[.entries[].repository] | unique'
# A permitted user does:
curl -s -u alice:pw 'http://127.0.0.1:8080/api/catalog?pageSize=500' | jq '[.entries[].repository] | unique'
```

**Expected**: the private repo is absent for `bob`, present for `alice` — identical to Maven catalog scoping.

## 3. Dashboard images figure

```sh
curl -s 'http://127.0.0.1:8080/api/stats' | jq '{repositories, artifacts, storageBytes, images}'
```

**Expected**: `images` equals the number of distinct container images (0 when there are none); the other
figures are unchanged.

## 4. UI walkthrough

1. Open the web UI (default **Catalog** view). Container images appear as rows with a **container** type
   badge, alongside Maven artifacts.
2. Type part of an image name into the topbar search — matching container rows narrow just like Maven rows.
3. Click a container row → it opens that image's tag view (`/c/{repo}/{image}`). A Maven row still opens its
   folder view.
4. Open the **Dashboard** — the **images** figure shows the distinct container-image count beside
   repositories / artifacts / storage.

## 5. Automated validation

- Backend: `gradle :backend:test --tests '*CatalogApiTest' --tests '*StatsApiTest'`
- Frontend: `bash frontend/scripts/e2e.sh` runs `frontend/tests/catalog.spec.ts` (a type-badged container row
  links to the tag view; the dashboard images figure).
- Gates: `gradle :backend:detekt` and `npm --prefix frontend run check` + `run build`.
