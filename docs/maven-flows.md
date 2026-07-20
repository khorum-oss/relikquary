# Maven / Gradle Flows

## Publish (PUT to a hosted repository)

```mermaid
sequenceDiagram
  autonumber
  participant C as Maven / Gradle client
  participant S as Security filter chain
  participant R as RepositoryController
  participant I as Ingestion
  participant St as ArtifactStorage
  participant B as Storage backend

  C->>S: PUT /repositories/releases/.../artifact-1.0.0.jar
  S->>S: authenticate (Basic / token)
  S->>S: authorize PUBLISH on 'releases'
  S->>R: forward (permitted)
  R->>I: ingest(repo, path, body)
  I->>I: enforce immutability (RELEASE rejects overwrite)
  I->>St: write(key, bytes)
  St->>B: persist bytes faithfully
  B-->>St: stored size
  St-->>I: ok
  I-->>R: created
  R-->>C: 201 Created
```

## Resolve (GET) — hosted, proxy, or group

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant R as RepositoryController
  participant Rs as RepositoryResolver
  participant St as ArtifactStorage
  participant P as Proxy + streaming cache
  participant U as Upstream

  C->>R: GET /repositories/{repo}/{path}
  R->>Rs: resolve(repo, path)

  alt repo kind = HOSTED
    Rs->>St: openRead(key)
    St-->>Rs: bytes or 404
  else repo kind = PROXY
    Rs->>St: openRead(cache key)
    alt cache hit
      St-->>Rs: cached bytes
    else cache miss
      Rs->>P: fetch(remoteUrl + path)
      P->>U: GET
      U-->>P: bytes
      P->>St: tee-write to cache while streaming
      P-->>Rs: streamed bytes
    end
  else repo kind = GROUP
    loop each member in order
      Rs->>Rs: resolve(member, path)
    end
    Note over Rs: first member that has it wins
  end

  Rs-->>R: content
  R-->>C: 200 OK + bytes
```

## Notes

- **Faithful storage (Principle IV):** stored bytes, consumer checksums, and signatures are served back
  exactly — never re-checksummed or rewritten.
- **Proxy caching** streams the upstream response to the client and to the cache at the same time
  (`TeeInputStream`), so the first requester is not blocked waiting for the full download to persist.
- **Maven metadata** (`maven-metadata.xml`, snapshot timestamps) is produced by the metadata service for
  hosted repositories so standard clients resolve versions correctly.
