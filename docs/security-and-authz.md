# Security & Authorization

Relikquary supports **anonymous** use plus **Basic auth** (managed or configured users) and **API tokens**.
Authorization is enforced **per repository** for the actions READ, PUBLISH, and DELETE.

## Authentication + authorization on a request

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant F as Security filter chain
  participant AP as Authentication providers<br/>(Basic / ApiToken)
  participant AZ as RepositoryAuthorizationManager
  participant H as Controller

  C->>F: request (optional Authorization header)
  F->>AP: authenticate
  alt Basic user:pass
    AP->>AP: ManagedUserService / configured users
  else Bearer / token
    AP->>AP: ApiTokenService verify secret + scope
  else no header
    AP-->>F: anonymous principal
  end
  AP-->>F: Authentication (roles / scope) or anonymous

  F->>AZ: authorize(action, repo, principal)
  AZ->>AZ: RepositoryAuthorizer checks per-repo access lists
  alt permitted
    AZ-->>F: permit
    F->>H: forward
    H-->>C: 200 / 201 response
  else denied
    AZ-->>F: deny
    F-->>C: 401 (unauthenticated) / 403 (forbidden)
  end
```

## Action model

```mermaid
flowchart LR
  subgraph actions["Action"]
    read["READ"]
    publish["PUBLISH"]
    delete["DELETE"]
  end

  op["Requested operation"] --> map{"maps to"}
  map -->|GET / resolve / browse| read
  map -->|PUT / push / ingest| publish
  map -->|DELETE tag / artifact| delete

  read --> chk["RepositoryAuthorizationManager"]
  publish --> chk
  delete --> chk
  chk --> lists["Per-repo access lists<br/>read / publish / delete<br/>(RepositoryAccess)"]
  chk --> roles["Global roles<br/>(e.g. PUBLISH) + token scope"]
```

## Notes

- **Anonymous is a first-class principal**: an open (public-read) repository serves GETs without credentials;
  signing in is optional and only required where an access list demands it.
- **API tokens** carry a `TokenScope` and are verified against a stored `secretHash`; a revoked token
  (`revokedAt` set) is rejected.
- **Per-repo access** (`RepositoryAccess`: `read` / `publish` / `delete` username lists) narrows a repository
  to specific users; absence means the repository's default (e.g. open read, role-gated publish) applies.
- The same authorization gate protects both the Maven and the `/v2` surfaces, and the browse API only shows a
  repository's contents to a permitted reader.
