package org.khorum.oss.relikquary.container.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [ContainerTag] pointers (feature 018). */
interface ContainerTagRepository : JpaRepository<ContainerTag, String> {

    fun findByRepositoryAndImageNameAndTag(repository: String, imageName: String, tag: String): ContainerTag?

    /** All tags recorded for an image name, for `GET …/tags/list`. */
    fun findByRepositoryAndImageName(repository: String, imageName: String): List<ContainerTag>

    /** Tags pointing at a manifest digest — removed when that manifest is deleted by digest. */
    fun findByRepositoryAndImageNameAndManifestDigest(
        repository: String,
        imageName: String,
        manifestDigest: String,
    ): List<ContainerTag>
}
