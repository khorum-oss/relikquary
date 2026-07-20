# Storage & Data Model

## Storage abstraction

Artifact and blob **bytes** go through a pluggable storage interface; the concrete backend (filesystem or
S3/MinIO) is chosen by configuration.

```mermaid
classDiagram
  class ArtifactStorage {
    <<interface>>
    +exists(key) Boolean
    +openRead(key) StoredArtifact
    +write(key, content) Long
    +openWrite(key) ArtifactWrite
    +list(prefix) List
    +delete(key) Boolean
    +deletePrefix(prefix) Int
    +walk(prefix) List
    +probe() StorageProbe
  }

  class FilesystemArtifactStorage
  class S3ArtifactStorage
  class ContainerStorage {
    +readBlob(repo, digest) StoredArtifact
    +readManifestBytes(repo, digest) ByteArray
    +hasBlob(repo, digest) Boolean
    +hasManifest(repo, digest) Boolean
  }

  ArtifactStorage <|.. FilesystemArtifactStorage
  ArtifactStorage <|.. S3ArtifactStorage
  ContainerStorage ..> ArtifactStorage : same configured backend
```

## Persisted entities (metadata DB)

The database stores **metadata only** — artifact and blob bytes live in the storage backend.

```mermaid
erDiagram
  MANAGED_USER ||--o{ API_TOKEN : owns
  MANAGED_USER ||--o| USER_PREFERENCE : has
  CONTAINER_MANIFEST ||--o{ CONTAINER_TAG : "referenced by"

  MANAGED_USER {
    string id PK
    string username
    string email "nullable"
    string passwordHash
    string roles
    instant lastActiveAt "nullable"
    instant createdAt
  }

  API_TOKEN {
    string id PK
    string name
    string ownerUsername FK
    string secretHash
    TokenScope scope
    instant createdAt
    instant lastUsedAt "nullable"
    instant revokedAt "nullable"
  }

  USER_PREFERENCE {
    string username PK
    string theme
    instant updatedAt
  }

  SETTING {
    string key PK
    string value
    instant updatedAt
    string updatedBy "nullable"
  }

  CONTAINER_MANIFEST {
    string id PK
    string repository
    string imageName
    string digest
    string mediaType
    long sizeBytes
    instant createdAt
  }

  CONTAINER_TAG {
    string id PK
    string repository
    string imageName
    string tag
    string manifestDigest FK
    instant updatedAt
  }

  BLOB_UPLOAD {
    string uploadId PK
    string repository
    string imageName
    long bytesReceived
    string pendingKey
    instant startedAt
  }
```

## Notes

- **`SETTING`** is a small key/value store for server-wide settings. **`USER_PREFERENCE`** holds each user's
  chosen theme.
- **`BLOB_UPLOAD`** is transient bookkeeping for in-progress chunked container blob uploads; it is cleared once
  an upload is finalized into a stored blob.
- **`CONTAINER_TAG` → `CONTAINER_MANIFEST`** is a soft reference by `(repository, imageName, manifestDigest)`;
  many tags can point at the same manifest digest.
