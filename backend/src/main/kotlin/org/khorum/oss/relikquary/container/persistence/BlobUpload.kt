package org.khorum.oss.relikquary.container.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The resumable state of an in-progress chunked blob upload (feature 018). Created by
 * `POST /blobs/uploads/`, advanced by each `PATCH`, and deleted on `PUT` finalize (after the digest is
 * verified and the pending write is promoted to the blob key) or on `DELETE`/reap. Persisted so a
 * multi-request chunked push survives across the STATELESS session boundary.
 */
@Entity
@Table(name = "blob_upload")
class BlobUpload {

    /** The `{uuid}` in `/blobs/uploads/{uuid}`. */
    @Id
    @Column(name = "upload_id", length = UPLOAD_ID_LENGTH)
    var uploadId: String = ""

    @Column(name = "repository", length = REPO_LENGTH)
    var repository: String = ""

    @Column(name = "image_name", length = NAME_LENGTH)
    var imageName: String = ""

    /** Bytes received so far — the current upload offset, reported as the `Range` upper bound. */
    @Column(name = "bytes_received")
    var bytesReceived: Long = 0

    /** Storage key of the in-progress pending write that becomes the blob on finalize. */
    @Column(name = "pending_key", length = PENDING_KEY_LENGTH)
    var pendingKey: String = ""

    @Column(name = "started_at")
    var startedAt: Instant = Instant.EPOCH

    private companion object {
        const val UPLOAD_ID_LENGTH = 60
        const val REPO_LENGTH = 200
        const val NAME_LENGTH = 512
        const val PENDING_KEY_LENGTH = 600
    }
}
