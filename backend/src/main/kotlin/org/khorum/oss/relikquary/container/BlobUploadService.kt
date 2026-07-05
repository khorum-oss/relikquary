package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.container.persistence.BlobUpload
import org.khorum.oss.relikquary.container.persistence.BlobUploadRepository
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * Blob upload sessions for hosted `docker push` (feature 018). A session accumulates chunk bytes at a
 * pending storage key across `PATCH` requests; finalizing verifies the `sha256` (via
 * [ContainerStorage.writeBlobVerified]) and promotes the bytes to the content-addressable blob key. The
 * accumulation is a read-modify-write per chunk — simple and correct for typical layer sizes; a future
 * optimization could stream-append. Sessions are persisted so a multi-request push survives the STATELESS
 * session boundary.
 */
@Service
class BlobUploadService(
    private val storage: ArtifactStorage,
    private val containerStorage: ContainerStorage,
    private val uploads: BlobUploadRepository,
) {

    /** Starts a session and returns its upload uuid. */
    fun start(repository: String, imageName: String): String {
        val uuid = UUID.randomUUID().toString()
        val row = BlobUpload()
        row.uploadId = uuid
        row.repository = repository
        row.imageName = imageName
        row.bytesReceived = 0
        row.pendingKey = uploadKey(repository, uuid)
        row.startedAt = Instant.now()
        uploads.save(row)
        return uuid
    }

    /** The in-progress session, or null if unknown. */
    fun session(uuid: String): BlobUpload? = uploads.findById(uuid).orElse(null)

    /** Appends a chunk to [row]'s pending bytes and returns the new total received. */
    fun append(row: BlobUpload, data: InputStream): Long {
        val total = accumulate(row.pendingKey, data)
        row.bytesReceived = total
        uploads.save(row)
        return total
    }

    /**
     * Appends the final bytes, verifies the accumulated content's `sha256` equals [digest]
     * ([InvalidDigestException] otherwise), promotes it to the blob key, and clears the session.
     */
    fun finalize(row: BlobUpload, data: InputStream, digest: Digest): Long {
        accumulate(row.pendingKey, data)
        val full = storage.openRead(row.pendingKey)?.stream?.use { it.readBytes() } ?: ByteArray(0)
        containerStorage.writeBlobVerified(row.repository, digest, ByteArrayInputStream(full))
        storage.delete(row.pendingKey)
        uploads.deleteById(row.uploadId)
        return full.size.toLong()
    }

    private fun accumulate(pendingKey: String, data: InputStream): Long {
        val existing = storage.openRead(pendingKey)?.stream?.use { it.readBytes() } ?: ByteArray(0)
        val incoming = data.readBytes()
        val combined = existing + incoming
        storage.write(pendingKey, ByteArrayInputStream(combined))
        return combined.size.toLong()
    }

    private fun uploadKey(repository: String, uuid: String): String = "$repository/_container/_uploads/$uuid"
}
