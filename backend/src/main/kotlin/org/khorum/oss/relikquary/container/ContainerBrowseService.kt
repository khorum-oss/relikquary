package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.container.cosign.CosignVerifier
import org.khorum.oss.relikquary.container.persistence.ContainerManifestRepository
import org.khorum.oss.relikquary.container.persistence.ContainerTagRepository
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.springframework.stereotype.Service
import java.time.Instant

/** Cosign's own artifacts (`sha256-<hex>.sig`/`.att`/`.sbom`) are stored as tags but are not user-facing image
 * tags; they are hidden from tag listings and counts (feature 024). */
internal fun isCosignArtifactTag(tag: String): Boolean =
    tag.startsWith("sha256-") && COSIGN_ARTIFACT_SUFFIXES.any { tag.endsWith(it) }

private val COSIGN_ARTIFACT_SUFFIXES = listOf(".sig", ".att", ".sbom")

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
    /** Advisory cosign trust status of the manifest this tag points at (feature 024). */
    val trust: String = "unknown",
)

/** One container image summarised for the cross-repo catalog (feature 023). */
data class ContainerCatalogImage(
    val name: String,
    val latestTag: String,
    val tagCount: Int,
    val sizeBytes: Long,
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
    private val cosign: CosignVerifier,
) {

    /**
     * The parsed detail of a stored manifest digest (feature 020) with its advisory cosign trust status
     * (feature 024), or null if the digest is malformed or no manifest is stored for it.
     */
    fun manifestDetail(repository: String, digest: String): ManifestDetail? {
        if (!Digest.isDigest(digest)) return null
        val detail = reader.read(repository, Digest.parse(digest)) ?: return null
        val imageName = manifests.findByRepositoryAndDigest(repository, digest)?.imageName ?: return detail
        return detail.copy(trust = cosign.verify(repository, imageName, digest).wire)
    }

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
        val tagRows = tags.findByRepository(repository).filterNot { isCosignArtifactTag(it.tag) }
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

    /**
     * The container images of [repository] projected for the cross-repo catalog (feature 023). A HOSTED repo
     * yields one entry per tagged image — latest tag (by `updatedAt`), tag count, and the summed size of the
     * distinct manifests its tags point at. A PROXY repo has no stored tags, so it yields one entry per
     * distinct cached image with an empty latest tag, the cached-manifest count, and their summed size.
     */
    fun catalogImages(repository: String, kind: RepositoryKind): List<ContainerCatalogImage> {
        val sizeByDigest = manifests.findByRepository(repository).associate { it.digest to it.sizeBytes }
        return if (kind == RepositoryKind.HOSTED) {
            tags.findByRepository(repository).filterNot { isCosignArtifactTag(it.tag) }
                .groupBy { it.imageName }.map { (name, imageTags) ->
                val latest = imageTags.maxByOrNull { it.updatedAt }
                val size = imageTags.map { it.manifestDigest }.distinct().sumOf { sizeByDigest[it] ?: 0L }
                ContainerCatalogImage(name, latest?.tag ?: "", imageTags.size, size)
            }.sortedBy { it.name }
        } else {
            manifests.findByRepository(repository).groupBy { it.imageName }.map { (name, imageManifests) ->
                ContainerCatalogImage(name, "", imageManifests.size, imageManifests.sumOf { it.sizeBytes })
            }.sortedBy { it.name }
        }
    }

    /** The number of distinct container images in [repository] (feature 023): tagged images for a hosted repo,
     * cached-manifest image names for a proxy repo. */
    fun distinctImageCount(repository: String, kind: RepositoryKind): Int =
        if (kind == RepositoryKind.HOSTED) {
            tags.findByRepository(repository).filterNot { isCosignArtifactTag(it.tag) }
                .map { it.imageName }.distinct().size
        } else {
            manifests.findByRepository(repository).map { it.imageName }.distinct().size
        }

    /**
     * The tags of a single image, resolved to their manifests, newest first, each with its cosign trust
     * status (feature 024). Cosign's own `.sig`/`.att`/`.sbom` artifact tags are hidden. Empty for a proxy.
     */
    fun tags(repository: String, imageName: String): List<ContainerTagSummary> =
        tags.findByRepositoryAndImageName(repository, imageName)
            .filterNot { isCosignArtifactTag(it.tag) }
            .map { tag ->
                val manifest = manifests.findByRepositoryAndDigest(repository, tag.manifestDigest)
                ContainerTagSummary(
                    tag = tag.tag,
                    digest = tag.manifestDigest,
                    mediaType = manifest?.mediaType ?: "",
                    size = manifest?.sizeBytes ?: 0,
                    pushedAt = tag.updatedAt,
                    trust = cosign.verify(repository, imageName, tag.manifestDigest).wire,
                )
            }
            .sortedByDescending { it.pushedAt }
}
