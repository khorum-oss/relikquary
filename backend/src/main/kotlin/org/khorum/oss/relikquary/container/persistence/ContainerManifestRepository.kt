package org.khorum.oss.relikquary.container.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA repository for [ContainerManifest] descriptors (feature 018). */
interface ContainerManifestRepository : JpaRepository<ContainerManifest, String> {

    /** The descriptor for a stored manifest digest within a repository, or null if not stored. */
    fun findByRepositoryAndDigest(repository: String, digest: String): ContainerManifest?

    fun existsByRepositoryAndDigest(repository: String, digest: String): Boolean

    /** Every manifest descriptor in a repository, for the browse UI's image listing (feature 018). */
    fun findByRepository(repository: String): List<ContainerManifest>
}
