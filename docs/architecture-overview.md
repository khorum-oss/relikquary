# Architecture Overview

The major moving parts of the Relikquary server and how requests flow from clients down to storage.

```mermaid
flowchart TB
  subgraph clients["Clients"]
    mvn["Maven / Gradle client"]
    docker["Docker / OCI client"]
    browser["Web browser"]
  end

  subgraph relikquary["Relikquary — Spring Boot application"]
    direction TB
    sec["Security filter chain<br/>Basic auth · API tokens · per-repo authz"]

    subgraph http["HTTP surfaces"]
      mavenapi["Maven repository API<br/>/repositories/{repo}/**"]
      v2["OCI registry<br/>/v2/**"]
      browseapi["Browse & admin JSON API<br/>/api/** · /catalog · /stats"]
      ui["SPA host<br/>/ · /ui/**"]
    end

    subgraph core["Core services"]
      resolver["RepositoryResolver"]
      ingest["Ingestion"]
      proxysvc["Proxy + streaming cache"]
      cosign["Cosign verifier"]
      meta["Maven metadata"]
      cleanup["Retention cleanup"]
    end

    subgraph storageabs["Storage abstraction"]
      astor["ArtifactStorage"]
      cstor["ContainerStorage"]
    end

    db[("Metadata DB (JPA)<br/>users · tokens · prefs · settings<br/>container tags · manifests")]
  end

  subgraph backends["Storage backends"]
    fs["Filesystem"]
    s3["S3 / MinIO"]
  end

  upstream["Upstream registries<br/>Maven Central · Docker Hub · ..."]

  mvn --> sec
  docker --> sec
  browser --> sec
  sec --> mavenapi
  sec --> v2
  sec --> browseapi
  sec --> ui

  mavenapi --> resolver
  mavenapi --> ingest
  mavenapi --> meta
  v2 --> resolver
  v2 --> proxysvc
  v2 --> cstor
  browseapi --> resolver
  browseapi --> cosign
  browseapi --> db

  resolver --> astor
  ingest --> astor
  proxysvc --> upstream
  cleanup --> astor

  astor --> fs
  astor --> s3
  cstor --> fs
  cstor --> s3
  cosign --> cstor
  core --> db
```

## Notes

- **Two protocols, one server.** The Maven surface (`/repositories/{repo}/**`) and the OCI surface (`/v2/**`)
  are served by the same application over the same storage abstraction and security chain.
- **Storage is pluggable.** `ArtifactStorage` (Maven bytes) and `ContainerStorage` (blobs + manifests) both
  resolve to the configured backend — filesystem or S3/MinIO — chosen by configuration.
- **The DB holds metadata only**, not artifact bytes: managed users, API tokens, user preferences, settings,
  and the container tag/manifest index. Artifact and blob bytes live in the storage backend.
- **The web UI is hosted by the backend** in production (static SvelteKit build served under `/` and `/ui/**`).
