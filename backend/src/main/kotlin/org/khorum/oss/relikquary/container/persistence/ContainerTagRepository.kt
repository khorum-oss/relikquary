package org.khorum.oss.relikquary.container.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [ContainerTag] pointers (feature 018). */
interface ContainerTagRepository : JpaRepository<ContainerTag, String> {

    fun findByRepositoryAndImageNameAndTag(repository: String, imageName: String, tag: String): ContainerTag?

    /** All tags recorded for an image name, for `GET …/tags/list`. */
    fun findByRepositoryAndImageName(repository: String, imageName: String): List<ContainerTag>
}
