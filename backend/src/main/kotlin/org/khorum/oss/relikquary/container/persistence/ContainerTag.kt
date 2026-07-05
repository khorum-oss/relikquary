package org.khorum.oss.relikquary.container.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A mutable tag pointer from `(repository, imageName, tag)` to a manifest digest (feature 018). Unlike
 * Maven release coordinates, a container tag is mutable: a later push re-points [manifestDigest] while
 * the previously referenced manifest remains retrievable by its digest. Unique per
 * `(repository, imageName, tag)`.
 */
@Entity
@Table(name = "container_tag")
class ContainerTag {

    @Id
    @Column(name = "id", length = ID_LENGTH)
    var id: String = ""

    @Column(name = "repository", length = REPO_LENGTH)
    var repository: String = ""

    @Column(name = "image_name", length = NAME_LENGTH)
    var imageName: String = ""

    @Column(name = "tag", length = TAG_LENGTH)
    var tag: String = ""

    /** `sha256:<hex>` of the manifest this tag currently points at. */
    @Column(name = "manifest_digest", length = DIGEST_LENGTH)
    var manifestDigest: String = ""

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.EPOCH

    private companion object {
        const val ID_LENGTH = 40
        const val REPO_LENGTH = 200
        const val NAME_LENGTH = 512
        const val TAG_LENGTH = 200
        const val DIGEST_LENGTH = 80
    }
}
