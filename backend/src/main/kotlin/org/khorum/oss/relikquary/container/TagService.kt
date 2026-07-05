package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.container.persistence.ContainerTag
import org.khorum.oss.relikquary.container.persistence.ContainerTagRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Tag pointers for hosted container repositories (feature 018): resolve, list, upsert, and delete. */
@Service
class TagService(private val tags: ContainerTagRepository) {

    /** The manifest digest a tag currently points at, or null if the tag is unknown. */
    fun resolve(repository: String, imageName: String, tag: String): Digest? =
        tags.findByRepositoryAndImageNameAndTag(repository, imageName, tag)?.let { Digest.parse(it.manifestDigest) }

    /** All tags for an image name, sorted, for `GET …/tags/list`. */
    fun list(repository: String, imageName: String): List<String> =
        tags.findByRepositoryAndImageName(repository, imageName).map { it.tag }.sorted()

    /** Points [tag] at [digest], creating or re-pointing the tag (tags are mutable). */
    fun upsert(repository: String, imageName: String, tag: String, digest: Digest) {
        val row = tags.findByRepositoryAndImageNameAndTag(repository, imageName, tag)
            ?: ContainerTag().also {
                it.id = UUID.randomUUID().toString()
                it.repository = repository
                it.imageName = imageName
                it.tag = tag
            }
        row.manifestDigest = digest.value
        row.updatedAt = Instant.now()
        tags.save(row)
    }

    /** Removes a single tag. Returns true if it existed. */
    fun deleteTag(repository: String, imageName: String, tag: String): Boolean {
        val row = tags.findByRepositoryAndImageNameAndTag(repository, imageName, tag) ?: return false
        tags.delete(row)
        return true
    }

    /** Removes every tag pointing at [digest] (when that manifest is deleted by digest). */
    fun deleteByDigest(repository: String, imageName: String, digest: Digest) {
        tags.findByRepositoryAndImageNameAndManifestDigest(repository, imageName, digest.value).forEach { tags.delete(it) }
    }
}
