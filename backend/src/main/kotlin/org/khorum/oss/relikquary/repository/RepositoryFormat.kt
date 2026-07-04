package org.khorum.oss.relikquary.repository

/**
 * A repository's wire format — the protocol/layout it speaks — orthogonal to its [RepositoryKind]
 * (feature 018):
 * - [MAVEN]: the Maven-compatible repository layout served over the `/{repo}/…` HTTP protocol
 *   (features 001–015). This is the default, so existing configurations are unchanged.
 * - [CONTAINER]: the OCI / Docker Registry HTTP API V2 served under `/v2/{repo}/…`, for `docker` /
 *   `podman` / `nerdctl` clients. A CONTAINER repository reuses [RepositoryKind] HOSTED (push/pull) or
 *   PROXY (a read-only pull-through cache of an upstream registry, Docker Hub by default). GROUP is not
 *   supported for CONTAINER in this feature.
 */
enum class RepositoryFormat { MAVEN, CONTAINER }
