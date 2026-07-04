package org.khorum.oss.relikquary.container.proxy

import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.container.BlobOutcome
import org.khorum.oss.relikquary.container.ContainerStorage
import org.khorum.oss.relikquary.container.Digest
import org.khorum.oss.relikquary.container.ManifestOutcome
import org.khorum.oss.relikquary.container.TagsOutcome
import org.khorum.oss.relikquary.container.persistence.ContainerManifest
import org.khorum.oss.relikquary.container.persistence.ContainerManifestRepository
import org.khorum.oss.relikquary.observability.metrics.RepositoryMetrics
import org.khorum.oss.relikquary.proxy.TeeInputStream
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Pull-through cache for a proxy container repository (feature 018). A tag is resolved against the
 * upstream on each request (tags move); an immutable digest is served from the local cache when present,
 * otherwise fetched from the upstream and cached by digest — the manifest bytes verbatim, and each blob
 * teed to the cache while streaming to the client (feature-015 tee, so a truncated/aborted transfer is
 * never cached). Not-found upstream ⇒ 404 (nothing cached); an upstream/token failure ⇒ 502.
 */
@Service
class ContainerProxyService(
    private val storage: ContainerStorage,
    private val upstream: ContainerUpstreamClient,
    private val manifests: ContainerManifestRepository,
    private val metrics: RepositoryMetrics,
) {

    fun getManifest(repo: RepositoryProperties.Repo, imageName: String, reference: String): ManifestOutcome {
        if (Digest.isDigest(reference)) {
            cachedManifest(repo.name, Digest.parse(reference))?.let {
                metrics.recordCache(repo.name, "hit")
                return it
            }
        }
        metrics.recordCache(repo.name, "miss")
        return when (val response = upstream.fetchManifest(repo, imageName, reference)) {
            is UpstreamManifest.Found -> {
                metrics.recordUpstream(repo.name, "found")
                cacheManifest(repo.name, imageName, response.bytes, response.mediaType)
            }
            UpstreamManifest.NotFound -> {
                metrics.recordUpstream(repo.name, "not_found")
                ManifestOutcome.NotFound
            }
            UpstreamManifest.Error -> {
                metrics.recordUpstream(repo.name, "error")
                ManifestOutcome.UpstreamError
            }
        }
    }

    fun getBlob(repo: RepositoryProperties.Repo, imageName: String, digest: Digest): BlobOutcome {
        storage.readBlob(repo.name, digest)?.let {
            metrics.recordCache(repo.name, "hit")
            return BlobOutcome.Found(it.stream, it.sizeBytes, digest)
        }
        metrics.recordCache(repo.name, "miss")
        return when (val response = upstream.fetchBlob(repo, imageName, digest)) {
            is UpstreamBlob.Found -> {
                metrics.recordUpstream(repo.name, "found")
                val pending = storage.openBlobCacheWrite(repo.name, digest)
                val tee = TeeInputStream(response.stream, pending, response.contentLength)
                logger.debug { "Streaming ${repo.name}/$imageName blob ${digest.value} from upstream (caching on completion)" }
                BlobOutcome.Found(tee, response.contentLength, digest)
            }
            UpstreamBlob.NotFound -> {
                metrics.recordUpstream(repo.name, "not_found")
                BlobOutcome.NotFound
            }
            UpstreamBlob.Error -> {
                metrics.recordUpstream(repo.name, "error")
                BlobOutcome.UpstreamError
            }
        }
    }

    fun listTags(repo: RepositoryProperties.Repo, imageName: String): TagsOutcome =
        when (val response = upstream.fetchTags(repo, imageName)) {
            is UpstreamTags.Found -> TagsOutcome.Found(response.bytes)
            UpstreamTags.NotFound -> TagsOutcome.NotFound
            UpstreamTags.Error -> TagsOutcome.UpstreamError
        }

    private fun cachedManifest(repository: String, digest: Digest): ManifestOutcome.Found? {
        val descriptor = manifests.findByRepositoryAndDigest(repository, digest.value) ?: return null
        val bytes = storage.readManifestBytes(repository, digest) ?: return null
        return ManifestOutcome.Found(bytes, descriptor.mediaType, digest)
    }

    private fun cacheManifest(repository: String, imageName: String, bytes: ByteArray, mediaType: String): ManifestOutcome.Found {
        // Compute the digest from the bytes (authoritative), rather than trusting the upstream header.
        val computed = Digest.of(bytes)
        if (!storage.hasManifest(repository, computed)) {
            storage.writeManifestBytes(repository, computed, bytes)
        }
        if (!manifests.existsByRepositoryAndDigest(repository, computed.value)) {
            // Explicit assignment (not apply): the entity's field names collide with the params, and inside
            // an apply block the receiver's members would shadow them.
            val row = ContainerManifest()
            row.id = UUID.randomUUID().toString()
            row.repository = repository
            row.imageName = imageName
            row.digest = computed.value
            row.mediaType = mediaType
            row.sizeBytes = bytes.size.toLong()
            row.createdAt = Instant.now()
            manifests.save(row)
        }
        return ManifestOutcome.Found(bytes, mediaType, computed)
    }
}
