package org.khorum.oss.relikquary.container.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Descriptor of a stored container manifest or index (feature 018). The manifest BYTES live in
 * `ArtifactStorage` keyed by [digest]; this row records what cannot be derived from the bytes reliably —
 * the exact [mediaType] to return as `Content-Type`, plus its [sizeBytes] — so a pull returns the manifest
 * with the same media type it was stored with. Immutable once written (a digest addresses fixed bytes).
 */
@Entity
@Table(name = "container_manifest")
class ContainerManifest {

    @Id
    @Column(name = "id", length = ID_LENGTH)
    var id: String = ""

    @Column(name = "repository", length = REPO_LENGTH)
    var repository: String = ""

    @Column(name = "image_name", length = NAME_LENGTH)
    var imageName: String = ""

    /** `sha256:<hex>` of the manifest bytes. Unique within a repository. */
    @Column(name = "digest", length = DIGEST_LENGTH)
    var digest: String = ""

    @Column(name = "media_type", length = MEDIA_TYPE_LENGTH)
    var mediaType: String = ""

    @Column(name = "size_bytes")
    var sizeBytes: Long = 0

    @Column(name = "created_at")
    var createdAt: Instant = Instant.EPOCH

    private companion object {
        const val ID_LENGTH = 40
        const val REPO_LENGTH = 200
        const val NAME_LENGTH = 512
        const val DIGEST_LENGTH = 80
        const val MEDIA_TYPE_LENGTH = 200
    }
}
