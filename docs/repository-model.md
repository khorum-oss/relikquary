# Repository Model

Every configured repository has a **kind**, a **format**, and (for Maven) a **type**, plus optional proxy,
group, access, and cosign settings. These come from `RepositoryProperties.Repo`.

```mermaid
classDiagram
  class Repository {
    +String name
    +RepositoryKind kind
    +RepositoryFormat format
    +RepositoryType type
    +String~nullable~ remoteUrl
    +List~String~ members
    +RepositoryAccess~nullable~ access
    +String~nullable~ cosignPublicKey
  }

  class RepositoryKind {
    <<enumeration>>
    HOSTED
    PROXY
    GROUP
  }

  class RepositoryFormat {
    <<enumeration>>
    MAVEN
    CONTAINER
  }

  class RepositoryType {
    <<enumeration>>
    RELEASE
    SNAPSHOT
    MIXED
  }

  class RepositoryAccess {
    +List~String~ read
    +List~String~ publish
    +List~String~ delete
  }

  Repository --> RepositoryKind
  Repository --> RepositoryFormat
  Repository --> RepositoryType
  Repository --> RepositoryAccess : access
```

## What each kind means

```mermaid
flowchart LR
  req["Request for a repository"] --> kind{kind?}

  kind -->|HOSTED| hosted["Store & serve bytes locally<br/>(publish target)"]
  kind -->|PROXY| proxy["Fetch from remoteUrl,<br/>cache, then serve"]
  kind -->|GROUP| group["Try each member in order,<br/>first hit wins"]

  proxy --> up["Upstream registry"]
  group --> m1["member 1"]
  group --> m2["member 2 ... n"]
```

## Field applicability

| Field | HOSTED | PROXY | GROUP |
|-------|:------:|:-----:|:-----:|
| `type` (RELEASE/SNAPSHOT/MIXED) — Maven immutability | ✅ | — | — |
| `remoteUrl` — upstream to proxy | — | ✅ | — |
| `members` — repositories to aggregate | — | — | ✅ |
| `access` — per-repo read/publish/delete lists | ✅ | ✅ | ✅ |
| `cosignPublicKey` — advisory trust (CONTAINER only) | ✅ | — | — |

- **Format** decides which protocol serves the repo: `MAVEN` → `/repositories/{repo}/**`, `CONTAINER` →
  `/v2/{repo}/**`.
- **Type** applies to Maven only: a `RELEASE` repo rejects overwrites (immutable), a `SNAPSHOT` repo allows
  re-publishing, `MIXED` accepts both coordinate styles.
