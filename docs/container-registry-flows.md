# Container Registry (`/v2`) Flows

Relikquary implements the OCI/Docker registry protocol so `docker push` / `docker pull` work against a hosted
or proxy container repository.

## Push (`docker push`)

```mermaid
sequenceDiagram
  autonumber
  participant D as Docker / OCI client
  participant S as Security filter chain
  participant Reg as ContainerRegistryController
  participant Bs as ContainerStorage
  participant DB as ContainerTag / ContainerManifest

  D->>Reg: POST /v2/{repo}/{name}/blobs/uploads/?digest=sha256-...
  Note over D,Reg: config + each layer, monolithic or chunked (BlobUpload)
  Reg->>Bs: store blob by digest
  Reg-->>D: 201 Created

  D->>Reg: PUT /v2/{repo}/{name}/manifests/{tag}
  S->>S: authorize PUBLISH on repo
  Reg->>Bs: store manifest bytes by digest
  Reg->>DB: upsert ContainerManifest + ContainerTag
  Reg-->>D: 201 Created + Docker-Content-Digest
```

## Pull (`docker pull`) — hosted or proxy

```mermaid
sequenceDiagram
  autonumber
  participant D as Docker / OCI client
  participant Reg as ContainerRegistryController
  participant Bs as ContainerStorage
  participant Px as ContainerProxyService
  participant U as Upstream registry

  D->>Reg: GET /v2/{repo}/{name}/manifests/{ref}
  alt repo kind = HOSTED
    Reg->>Bs: read manifest by tag or digest
    Bs-->>Reg: manifest bytes or 404
  else repo kind = PROXY
    Reg->>Px: fetch(ref) cache-through
    Px->>U: GET manifest
    U-->>Px: manifest bytes
    Px->>Bs: cache manifest + referenced blobs
    Px-->>Reg: manifest bytes
  end
  Reg-->>D: 200 OK + manifest

  D->>Reg: GET /v2/{repo}/{name}/blobs/{digest}
  Reg->>Bs: read blob (or proxy-fetch + cache)
  Reg-->>D: 200 OK + layer bytes
```

## Notes

- **Blob uploads** may be monolithic (`?digest=` on the POST) or chunked; chunked uploads track progress in a
  `BlobUpload` row until finalized.
- **The tag/manifest index** (`ContainerTag`, `ContainerManifest`) lets the web UI list images, tags, digests,
  and sizes without re-reading every manifest from storage.
- **Cosign signatures** are pushed like any other image — as a companion `sha256-<digest>.sig` artifact — so
  verification (see [cosign-verification](./cosign-verification.md)) only reads already-stored bytes.
- **Advisory only:** signature verification never blocks or alters a push/pull and changes no `/v2` wire
  behavior.
