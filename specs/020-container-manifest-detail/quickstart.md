# Quickstart: Container Image Manifest & Layer Detail View

A runnable guide to validate the feature end to end. It reuses the feature-018 hosted container repo `apps`.

## Prerequisites

- Backend built and running (`gradle :backend:bootRun`, or the e2e harness below), with the default config
  (which ships the hosted `apps` container repo) or `frontend/scripts/e2e-config.yml`.
- A way to push an image: a real `docker`/`podman` client, or the `curl`-based push the e2e harness uses
  (`frontend/scripts/e2e.sh` → `seed_container`).

## 1. Push a single-platform image

With a real client:

```sh
docker pull alpine:3.20
docker tag alpine:3.20 localhost:8080/apps/team/service:1.0.0
docker push localhost:8080/apps/team/service:1.0.0
```

Or seed a minimal image (config + one layer + manifest) with `curl` as in `seed_container` — a monolithic
blob upload per blob, then a manifest `PUT` referencing them.

## 2. Read the tag's digest, then its manifest detail

```sh
# The digest a tag points at (feature 018 browse):
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service' | jq '.tags[0].digest'

# The parsed manifest detail (this feature):
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/manifest?digest=<DIGEST>' | jq
```

**Expected**: `kind: "image"`, a `config` descriptor, an ordered `layers` array (each with `digest`,
`mediaType`, `size`, `present: true`), and `totalSize` equal to `config.size` + the sum of layer sizes. Every
digest matches what `docker`/`crane manifest` reports for the same tag.

## 3. Push and inspect a multi-arch image

```sh
docker buildx imagetools create -t localhost:8080/apps/team/multi:1.0.0 alpine:3.20   # or push a manifest list
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/multi' | jq '.tags[0].digest'
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/manifest?digest=<INDEX_DIGEST>' | jq
```

**Expected**: `kind: "index"` with a `manifests` array, one entry per platform carrying `platform`
(`os`/`architecture`/optional `variant`), sub-manifest `digest`, `size`, and `present`. Re-run the
`manifest` call with a platform entry's `digest` to get that platform's `kind: "image"` layer breakdown.

## 4. Degradation checks

- **Unknown shape**: request `…/manifest?digest=<a stored non-image/index manifest>` → `kind: "unknown"`
  with digest/mediaType/size and no breakdown (HTTP 200, not an error).
- **Absent digest**: request a digest with no stored manifest → **404**.
- **Wrong format**: `GET /api/repositories/releases/containers/manifest?digest=…` (a Maven repo) → **400**.
- **Missing reference**: after deleting a layer/sub-manifest by digest, its descriptor still appears with
  `present: false`.

## 5. UI walkthrough

1. Open the web UI, **Repositories** tab → the `apps` container repo → an image → its tags.
2. Click a tag: the manifest detail panel shows the config digest, total size, and the ordered layer list.
3. For a multi-arch tag, the panel lists platforms; click one to see that platform's layers, then return to
   the platform list. The panel matches the vault theme (gold accents, mono digests).

## 6. Automated validation

- Backend: `gradle :backend:test --tests '*ContainerManifestDetailApiTest'` — pushes an image and a manifest
  list via `/v2`, then asserts the projection, drill-in, degradation, and 400/404.
- Frontend: `bash frontend/scripts/e2e.sh` (seeds an image + a multi-arch manifest list) runs
  `frontend/tests/container.spec.ts`, which drills a tag into its layers and a multi-arch tag into a
  platform's layers in real Chromium.
- Quality gates: `gradle :backend:detekt` and `npm --prefix frontend run check` + `npm --prefix frontend run build`.
