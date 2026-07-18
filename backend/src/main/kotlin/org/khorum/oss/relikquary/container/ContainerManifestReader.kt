package org.khorum.oss.relikquary.container

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.khorum.oss.relikquary.container.persistence.ContainerManifestRepository
import org.springframework.stereotype.Service

/** The operating system, architecture, and optional variant a platform sub-manifest targets (feature 020). */
data class ManifestPlatform(
    val os: String,
    val architecture: String,
    val variant: String? = null,
)

/**
 * A reference to stored content as a manifest declares it (feature 020): its digest, media type, and size,
 * plus whether the referenced object is stored locally ([present]) and, for an index entry, the [platform]
 * it targets. `size` is the size the manifest declares, not a re-measurement of the bytes.
 */
data class ManifestDescriptor(
    val digest: String,
    val mediaType: String,
    val size: Long,
    val present: Boolean,
    val platform: ManifestPlatform? = null,
)

/**
 * The parsed, classified detail of a stored container manifest (feature 020), discriminated by [kind]:
 * - `image`: a single-platform image — [config] + ordered [layers] + [totalSize] (declared pull size).
 * - `index`: a manifest list / image index — its platform sub-[manifests].
 * - `unknown`: bytes present but not a recognized image/index shape — only the top-level fields are set.
 *
 * [digest], [mediaType], and [size] are the stored manifest's own descriptor values (what a client pulls).
 */
data class ManifestDetail(
    val kind: String,
    val repository: String,
    val digest: String,
    val mediaType: String,
    val size: Long,
    val config: ManifestDescriptor? = null,
    val layers: List<ManifestDescriptor>? = null,
    val totalSize: Long? = null,
    val manifests: List<ManifestDescriptor>? = null,
    /** Advisory cosign trust status of this manifest, attached by the browse service (feature 024). */
    val trust: String = "unknown",
)

/**
 * Reads a stored container manifest by digest and projects it for the browse UI (feature 020). The manifest
 * bytes are read verbatim from [ContainerStorage] (never altered — Principle IV) and classified the same way
 * [ManifestService] discriminates references at push time: a non-empty `manifests` array is an image index;
 * otherwise a `config` + `layers` shape is an image manifest; anything else is `unknown`. Each referenced
 * descriptor is flagged with whether it is stored locally so a partially-deleted image degrades gracefully.
 */
@Service
class ContainerManifestReader(
    private val storage: ContainerStorage,
    private val manifests: ContainerManifestRepository,
) {

    private val objectMapper = ObjectMapper()

    /** The detail for a stored [digest] in [repository], or null when no manifest bytes are stored for it. */
    fun read(repository: String, digest: Digest): ManifestDetail? {
        val bytes = storage.readManifestBytes(repository, digest) ?: return null
        val descriptor = manifests.findByRepositoryAndDigest(repository, digest.value)
        val mediaType = descriptor?.mediaType ?: ""
        val size = descriptor?.sizeBytes ?: bytes.size.toLong()
        val node = parse(bytes) ?: return shell("unknown", repository, digest, mediaType, size)
        return classify(repository, digest, mediaType, size, node)
    }

    private fun parse(bytes: ByteArray): JsonNode? =
        try {
            objectMapper.readTree(bytes)
        } catch (_: JacksonException) {
            null
        }

    private fun classify(repository: String, digest: Digest, mediaType: String, size: Long, node: JsonNode): ManifestDetail {
        val subManifests = node.get("manifests")
        if (subManifests != null && subManifests.isArray && !subManifests.isEmpty) {
            return shell("index", repository, digest, mediaType, size)
                .copy(manifests = subManifests.mapNotNull { descriptor(repository, it, blob = false) })
        }
        val configNode = node.get("config")
        if (configNode != null && configNode.hasNonNull("digest")) {
            val config = descriptor(repository, configNode, blob = true)
            val layers = node.get("layers")?.mapNotNull { descriptor(repository, it, blob = true) }.orEmpty()
            val total = (config?.size ?: 0) + layers.sumOf { it.size }
            return shell("image", repository, digest, mediaType, size).copy(config = config, layers = layers, totalSize = total)
        }
        return shell("unknown", repository, digest, mediaType, size)
    }

    private fun descriptor(repository: String, node: JsonNode, blob: Boolean): ManifestDescriptor? {
        val digestText = node.get("digest")?.asText()?.takeIf { Digest.isDigest(it) } ?: return null
        val referenced = Digest.parse(digestText)
        val present = if (blob) storage.hasBlob(repository, referenced) else storage.hasManifest(repository, referenced)
        return ManifestDescriptor(
            digest = digestText,
            mediaType = node.get("mediaType")?.asText() ?: "",
            size = node.get("size")?.asLong() ?: 0,
            present = present,
            platform = platform(node.get("platform")),
        )
    }

    private fun platform(node: JsonNode?): ManifestPlatform? {
        val os = node?.get("os")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val architecture = node.get("architecture")?.asText()?.takeIf { it.isNotBlank() } ?: return null
        return ManifestPlatform(os, architecture, node.get("variant")?.asText()?.takeIf { it.isNotBlank() })
    }

    private fun shell(kind: String, repository: String, digest: Digest, mediaType: String, size: Long) =
        ManifestDetail(kind = kind, repository = repository, digest = digest.value, mediaType = mediaType, size = size)
}
