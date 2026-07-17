# Contract: Container Manifest Detail (Browse API)

An additive, read-only JSON endpoint for the web UI, a sibling of the feature-018 container browse
endpoints (`GET /api/repositories/{repo}/containers` and `…/containers/tags`). It parses the stored
manifest bytes for a digest and returns a classified projection. It does **not** touch the OCI `/v2` wire
protocol. Authorization is inherited: the `containers` sub-segment is already mapped to per-repo **READ** in
`RepositoryAuthorizationManager`, so no security-layer change is needed.

## Endpoint

### `GET /api/repositories/{repo}/containers/manifest?digest={digest}`

Return the parsed detail of the stored manifest addressed by `{digest}` in repository `{repo}`.

- `{repo}` — a Relikquary repository name; MUST resolve and MUST be `format = CONTAINER`.
- `digest` (query, required) — a `sha256:<hex>` reference. Typically taken from a tag row
  (`…/containers/tags`) or from a platform entry of an index detail (to drill into that platform).

**200 OK** — one of the three shapes below (see [data-model.md](../data-model.md)).

Image manifest:

```json
{
  "kind": "image",
  "repository": "apps",
  "digest": "sha256:aaaa…",
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "size": 528,
  "config": { "digest": "sha256:cccc…", "mediaType": "application/vnd.oci.image.config.v1+json", "size": 37, "present": true },
  "layers": [
    { "digest": "sha256:1111…", "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip", "size": 24, "present": true }
  ],
  "totalSize": 61
}
```

Image index / manifest list:

```json
{
  "kind": "index",
  "repository": "apps",
  "digest": "sha256:bbbb…",
  "mediaType": "application/vnd.oci.image.index.v1+json",
  "size": 842,
  "manifests": [
    { "digest": "sha256:aaaa…", "mediaType": "application/vnd.oci.image.manifest.v1+json", "size": 528,
      "present": true, "platform": { "os": "linux", "architecture": "amd64" } },
    { "digest": "sha256:dddd…", "mediaType": "application/vnd.oci.image.manifest.v1+json", "size": 528,
      "present": false, "platform": { "os": "linux", "architecture": "arm64", "variant": "v8" } }
  ]
}
```

Unknown/unparseable (bytes present but not a recognized shape):

```json
{
  "kind": "unknown",
  "repository": "apps",
  "digest": "sha256:eeee…",
  "mediaType": "application/octet-stream",
  "size": 12
}
```

## Status codes

| Situation | Status |
|-----------|--------|
| Manifest found and parsed (image / index / unknown) | 200 |
| Repository is not a container repo (`format = MAVEN`) | 400 |
| Repository does not exist | 404 |
| No manifest stored for `digest` in this repo | 404 |
| Caller lacks READ on a role-gated repo (auth enabled) | 401 (anonymous → Basic challenge) / 403 (authenticated) |

## Field notes

- `digest`, `mediaType`, `size` at the top level are the **stored manifest's own** values (its descriptor
  row) — the digest equals what a client pulls for this manifest.
- `present` reflects local storage of the referenced object (config/layer blob, or platform sub-manifest);
  `false` is informational, never an error.
- `totalSize` (image only) = `config.size` + Σ `layers[].size`, the declared compressed pull size.
- `layers` order is the manifest's order, preserved exactly.
- `platform` is present on an index entry only when the index declares one.

## Non-goals

Serving manifest/blob bytes (that is the `/v2` pull surface), any write/delete, schema-1 manifests, and
proxy-repository drill-in (a proxy has no stored tag list) are out of scope for this contract.
