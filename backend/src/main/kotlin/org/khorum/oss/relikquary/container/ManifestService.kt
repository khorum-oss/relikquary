package org.khorum.oss.relikquary.container

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.khorum.oss.relikquary.container.persistence.ContainerManifest
import org.khorum.oss.relikquary.container.persistence.ContainerManifestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Thrown when a pushed manifest references a blob or sub-manifest that is not stored (⇒ 400 MANIFEST_BLOB_UNKNOWN). */
class ManifestBlobUnknownException(message: String) : RuntimeException(message)

/**
 * Hosted container manifest handling (feature 018): store a pushed manifest/index after verifying every
 * referenced blob (or sub-manifest) exists, record its descriptor and (for a tag ref) its tag, and serve
 * or delete it by tag or digest. Manifest bytes are preserved verbatim (Principle IV).
 */
@Service
class ManifestService(
    private val storage: ContainerStorage,
    private val manifests: ContainerManifestRepository,
    private val tags: TagService,
) {

    private val objectMapper = ObjectMapper()

    fun get(repository: String, imageName: String, reference: String): ManifestOutcome {
        val digest = resolveDigest(repository, imageName, reference) ?: return ManifestOutcome.NotFound
        val descriptor = manifests.findByRepositoryAndDigest(repository, digest.value) ?: return ManifestOutcome.NotFound
        val bytes = storage.readManifestBytes(repository, digest) ?: return ManifestOutcome.NotFound
        return ManifestOutcome.Found(bytes, descriptor.mediaType, digest)
    }

    /**
     * Stores [bytes] as a manifest and returns its digest. If [reference] is a digest it must match the
     * content ([InvalidDigestException] otherwise); if a tag, the tag is (re)pointed at the manifest. All
     * referenced blobs/sub-manifests must already be stored ([ManifestBlobUnknownException] otherwise).
     */
    fun put(repository: String, imageName: String, reference: String, bytes: ByteArray, mediaType: String): Digest {
        val digest = Digest.of(bytes)
        if (Digest.isDigest(reference) && Digest.parse(reference) != digest) {
            throw InvalidDigestException("manifest digest mismatch: reference $reference, computed ${digest.value}")
        }
        verifyReferences(repository, bytes)
        if (!storage.hasManifest(repository, digest)) storage.writeManifestBytes(repository, digest, bytes)
        upsertDescriptor(repository, imageName, digest, mediaType, bytes.size.toLong())
        if (!Digest.isDigest(reference)) tags.upsert(repository, imageName, reference, digest)
        return digest
    }

    @Transactional
    fun delete(repository: String, imageName: String, reference: String): Boolean {
        if (!Digest.isDigest(reference)) return tags.deleteTag(repository, imageName, reference)
        val digest = Digest.parse(reference)
        tags.deleteByDigest(repository, imageName, digest)
        storage.deleteObject(storage.manifestKey(repository, digest))
        val descriptor = manifests.findByRepositoryAndDigest(repository, digest.value) ?: return false
        manifests.delete(descriptor)
        return true
    }

    private fun resolveDigest(repository: String, imageName: String, reference: String): Digest? =
        if (Digest.isDigest(reference)) Digest.parse(reference) else tags.resolve(repository, imageName, reference)

    private fun verifyReferences(repository: String, bytes: ByteArray) {
        val node = objectMapper.readTree(bytes)
        val (blobDigests, subManifestDigests) = referencedDigests(node)
        blobDigests.forEach { if (!storage.hasBlob(repository, it)) throw ManifestBlobUnknownException("blob unknown: ${it.value}") }
        subManifestDigests.forEach {
            if (!storage.hasManifest(repository, it)) throw ManifestBlobUnknownException("manifest unknown: ${it.value}")
        }
    }

    /** (referenced blob digests, referenced sub-manifest digests): an index references sub-manifests; an
     * image manifest references its config + layer blobs. */
    private fun referencedDigests(node: JsonNode): Pair<List<Digest>, List<Digest>> {
        val subManifests = node.get("manifests")?.mapNotNull { digestOf(it) }.orEmpty()
        if (subManifests.isNotEmpty()) return emptyList<Digest>() to subManifests
        val blobs = buildList {
            digestOf(node.get("config"))?.let(::add)
            node.get("layers")?.forEach { digestOf(it)?.let(::add) }
        }
        return blobs to emptyList()
    }

    private fun digestOf(node: JsonNode?): Digest? =
        node?.get("digest")?.asText()?.takeIf { Digest.isDigest(it) }?.let { Digest.parse(it) }

    private fun upsertDescriptor(repository: String, imageName: String, digest: Digest, mediaType: String, size: Long) {
        if (manifests.existsByRepositoryAndDigest(repository, digest.value)) return
        val row = ContainerManifest()
        row.id = UUID.randomUUID().toString()
        row.repository = repository
        row.imageName = imageName
        row.digest = digest.value
        row.mediaType = mediaType
        row.sizeBytes = size
        row.createdAt = Instant.now()
        manifests.save(row)
    }
}
