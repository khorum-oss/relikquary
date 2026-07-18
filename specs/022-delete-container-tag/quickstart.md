# Quickstart: Delete a Container Image Tag from the Web UI

Validate the tag-delete capability end to end. Reuses the hosted `apps` container repo.

## Prerequisites

- Backend running (`gradle :backend:bootRun`, or the e2e harness) with a hosted container repo and a user
  who has DELETE permission on it.
- An image pushed with two tags — e.g. `docker push <host>/apps/team/service:1.0.0` and
  `docker push <host>/apps/team/service:latest` (or the `curl`-based push the e2e harness uses).

## 1. Delete a tag via the API

```sh
# List tags before:
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service' | jq '.kind, [.tags[].tag]'

# Delete one tag (as a user with delete permission; add -u user:pass if auth is enabled):
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service&tag=1.0.0'   # → 204

# List tags after — 1.0.0 is gone, latest remains:
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service' | jq '[.tags[].tag]'
```

**Expected**: the first list shows `"HOSTED"` and both tags; the delete returns **204**; the second list
omits `1.0.0`. The manifest is still retrievable by its digest (`GET …/manifests/<digest>` via `/v2`, or the
manifest-detail endpoint) — nothing was garbage-collected.

## 2. Guardrails

```sh
# Missing tag → 404
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE 'http://127.0.0.1:8080/api/repositories/apps/containers/tags?image=team/service&tag=nope'   # → 404

# Proxy repo → 405 (read-only pull-through)
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE 'http://127.0.0.1:8080/api/repositories/dockerhub/containers/tags?image=library/alpine&tag=3.20'   # → 405

# Maven repo → 400
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE 'http://127.0.0.1:8080/api/repositories/releases/containers/tags?image=x&tag=y'   # → 400
```

With auth enabled: an anonymous delete returns **401**; an authenticated user without DELETE permission
returns **403**.

## 3. Last-tag delete drops the image

```sh
# After deleting the image's remaining tag(s), the hosted image list no longer includes it:
curl -s 'http://127.0.0.1:8080/api/repositories/apps/containers' | jq '[.images[].name]'
```

**Expected**: `team/service` is absent once it has no tags (the retained manifest is not shown as an image).

## 4. UI walkthrough

1. Sign in as a user with delete permission; open **Repositories** → `apps` → an image → its tags.
2. Each tag row shows a delete affordance (hosted repos only). Click it → confirm.
3. The tag disappears from the list without a page reload; a kept tag remains. Deleting the last tag empties
   the image (and it drops off the image list). A proxy repo's tag view shows no delete affordance.
4. As an anonymous user, clicking delete prompts login; without permission, a "not permitted" message shows.

## 5. Automated validation

- Backend: `gradle :backend:test --tests '*ContainerTagDeleteApiTest' --tests '*RepositoryAuthzRequestMappingTest'`
- Frontend: `bash frontend/scripts/e2e.sh` runs `frontend/tests/container.spec.ts` (an authorized user deletes
  a tag and it disappears).
- Gates: `gradle :backend:detekt` and `npm --prefix frontend run check` + `run build`.
