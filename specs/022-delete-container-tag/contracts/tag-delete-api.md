# Contract: Delete a Container Tag (Browse API)

An additive read/write endpoint for the web UI, a sibling of the feature-018/020 container browse endpoints.
It changes no OCI `/v2` wire behavior. Authorization is inherited: any `DELETE` under
`/api/repositories/{repo}/**` maps to the repo's **DELETE** action in `RepositoryAuthorizationManager`.

## Endpoint

### `DELETE /api/repositories/{repo}/containers/tags?image={image}&tag={tag}`

Delete one tag pointer of a hosted container image. Removes only the mutable `(repo, image, tag)→digest`
pointer; the digest-addressed manifest and its blobs are retained (no garbage collection).

- `{repo}` — a Relikquary repository name; MUST resolve, MUST be `format = CONTAINER`, MUST be `kind = HOSTED`.
- `image` (query, required) — the image name (may contain slashes).
- `tag` (query, required) — the tag to delete.

| Situation | Status |
|-----------|--------|
| Tag deleted | **204 No Content** |
| No such tag on the image | **404 Not Found** |
| Repository is not a container repo (`format = MAVEN`) | **400 Bad Request** |
| Repository is a container **proxy** (read-only pull-through) | **405 Method Not Allowed** |
| Repository does not exist | **404 Not Found** |
| Caller is anonymous on a delete-gated repo | **401 Unauthorized** (browser shows the login form) |
| Caller is authenticated but lacks DELETE permission | **403 Forbidden** |

**Effect**: after 204, `GET …/containers/tags?image={image}` no longer lists `{tag}`; the manifest the tag
pointed at is still retrievable by its digest; any other tag on the image is unaffected. If `{tag}` was the
image's last tag, `GET …/containers` (hosted) no longer lists the image.

## Modified response — `GET /api/repositories/{repo}/containers/tags`

Adds a top-level `kind` field so the UI can show the delete affordance for hosted repos only:

```json
{
  "repository": "apps",
  "image": "team/service",
  "kind": "HOSTED",
  "tags": [ { "tag": "1.0.0", "digest": "sha256:…", "mediaType": "…", "size": 528, "pushedAt": "…" } ]
}
```

## Modified behavior — `GET /api/repositories/{repo}/containers` (image list)

Unchanged response shape. The set of images listed is now kind-aware: a **hosted** repo lists images with
≥1 tag (so a fully-untagged image drops out after its last tag is deleted, without GC); a **proxy** repo
continues to list distinct cached-manifest image names.

## Non-goals

Deleting a manifest by digest from the UI, garbage-collecting untagged manifests/blobs, and any delete on a
proxy repository are out of scope. The OCI `/v2` delete path is unchanged.
