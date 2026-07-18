package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.container.persistence.ContainerManifestRepository
import org.khorum.oss.relikquary.container.persistence.ContainerTagRepository
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.springframework.stereotype.Service
import java.time.Instant

/** One image name in a container repository, summarised for the browse UI (feature 018). */
data class ContainerImageSummary(
    val name: String,
    val tagCount: Int,
    val manifestCount: Int,
    val lastPushed: Instant?,
)

/** One tag of a container image: the mutable pointer plus the manifest it currently resolves to. */
data class ContainerTagSummary(
    val tag: String,
    val digest: String,
    val mediaType: String,
    val size: Long,
    val pushedAt: Instant?,
)

/**
 * Read-only projections over the container persistence tables for the browse/manage UI (feature 018).
 * A HOSTED repository has the full picture — every pushed manifest ([ContainerManifest]) and every tag
 * pointer ([ContainerTag]). A PROXY repository is a pull-through cache: it records the manifests it has
 * cached (by digest) but resolves tags live upstream, so its tag listing is empty here and image rows
 * reflect only what has been cached so far.
 */
@Service
class ContainerBrowseService(
    private val tags: ContainerTagRepository,
    private val manifests: ContainerManifestRepository,
    private val reader: ContainerManifestReader,
    private val manifestService: ManifestService,
) {

    /** The parsed detail of a stored manifest digest (feature 020), or null if the digest is malformed or
     * no manifest is stored for it. */
    fun manifestDetail(repository: String, digest: String): ManifestDetail? =
        if (Digest.isDigest(digest)) reader.read(repository, Digest.parse(digest)) else null

    /**
     * Deletes a tag pointer of a hosted image (feature 022), reusing the OCI delete-by-tag path — the
     * mutable `(repository, imageName, tag)` pointer is removed while the digest-addressed manifest and its
     * blobs are retained (no GC). Returns false when no such tag exists.
     */
    fun deleteTag(repository: String, imageName: String, tag: String): Boolean =
        manifestService.delete(repository, imageName, tag)

    /**
     * The image names in [repository], each with its tag/manifest counts and most-recent push. The listing
     * is kind-aware (feature 022): a HOSTED repo lists images that have at least one tag (so a fully-untagged
     * image drops out once its last tag is deleted, with no GC); a PROXY repo lists distinct cached-manifest
     * image names (it has no stored tags).
     */
    fun images(repository: String, kind: RepositoryKind): List<ContainerImageSummary> {
        val tagRows = tags.findByRepository(repository)
        val manifestRows = manifests.findByRepository(repository)
        val tagsByImage = tagRows.groupBy { it.imageName }
        val manifestsByImage = manifestRows.groupBy { it.imageName }
        val names = if (kind == RepositoryKind.HOSTED) tagsByImage.keys else manifestsByImage.keys
        return names.sorted().map { name ->
            val imageTags = tagsByImage[name].orEmpty()
            val imageManifests = manifestsByImage[name].orEmpty()
            val lastPushed = (imageTags.map { it.updatedAt } + imageManifests.map { it.createdAt }).maxOrNull()
            ContainerImageSummary(name, imageTags.size, imageManifests.size, lastPushed)
        }
    }

    /** The tags of a single image, resolved to their manifests, newest first. Empty for a proxy repo. */
    fun tags(repository: String, imageName: String): List<ContainerTagSummary> =
        tags.findByRepositoryAndImageName(repository, imageName)
            .map { tag ->
                val manifest = manifests.findByRepositoryAndDigest(repository, tag.manifestDigest)
                ContainerTagSummary(
                    tag = tag.tag,
                    digest = tag.manifestDigest,
                    mediaType = manifest?.mediaType ?: "",
                    size = manifest?.sizeBytes ?: 0,
                    pushedAt = tag.updatedAt,
                )
            }
            .sortedByDescending { it.pushedAt }
}
