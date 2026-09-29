package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.container.persistence.BlobUpload
import org.khorum.oss.relikquary.container.persistence.BlobUploadRepository
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.Instant
import java.util.Enumeration
import java.util.UUID

/**
 * Blob upload sessions for hosted `docker push` (feature 018). A session collects chunk bytes under a
 * pending storage prefix across `PATCH` requests; finalizing verifies the `sha256` (via
 * [ContainerStorage.writeBlobVerified]) and promotes the bytes to the content-addressable blob key.
 * Sessions are persisted so a multi-request push survives the STATELESS session boundary.
 *
 * **Each chunk is a separate key, named by its byte offset** — never appended into one shared key.
 * The original design read the pending blob, concatenated, and wrote it back, which was wrong twice over
 * (HS-29): two chunks of one session interleaved, both reading the same prior state and each writing
 * *prior + its own bytes*, so a chunk was silently dropped; and a retried chunk was appended a second
 * time. Disjoint keys make a retry idempotent by construction and need no lock. Nothing corrupt was ever
 * stored — [ContainerStorage.writeBlobVerified] caught it at finalize — but the push failed with a digest
 * mismatch that read as a client problem.
 *
 * Nothing here materialises a blob in heap: chunks stream to storage on the way in, and finalize
 * concatenates them as a lazily-opened stream on the way out. The old `readBytes()` round-trip made
 * memory track blob size, which OOM'd the backend on real layers.
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

    /**
     * Stores the chunk at [offset] and returns the session's new byte count.
     *
     * Each chunk is written to its **own** key derived from [offset], never appended into a shared one.
     * That makes a retried chunk idempotent by construction — it rewrites its own key with identical
     * bytes — and lets concurrent chunks proceed without a lock, because they touch disjoint keys.
     */
    fun append(row: BlobUpload, data: InputStream, offset: Long): Long {
        val written = storage.write(chunkKey(row.pendingKey, offset), data)
        val total = maxOf(row.bytesReceived, offset + written)
        row.bytesReceived = total
        uploads.save(row)
        return total
    }

    /**
     * Stores the final bytes at [offset], verifies the assembled content's `sha256` equals [digest]
     * ([InvalidDigestException] otherwise), promotes it to the blob key, and clears the session.
     *
     * The chunks are concatenated as a lazily-opened stream, so no more than one chunk's buffer is live
     * at a time and the blob is never materialised in heap.
     */
    fun finalize(row: BlobUpload, data: InputStream, digest: Digest, offset: Long): Long {
        storage.write(chunkKey(row.pendingKey, offset), data)
        val written = assembled(row.pendingKey).use {
            containerStorage.writeBlobVerified(row.repository, digest, it)
        }
        storage.deletePrefix(row.pendingKey)
        uploads.deleteById(row.uploadId)
        return written
    }

    /**
     * The session's chunks in offset order as one continuous stream. Offsets are parsed from the key and
     * sorted numerically rather than relying on lexical order, so the padding below is presentational
     * (and keeps an S3 listing readable) rather than load-bearing.
     */
    private fun assembled(pendingKey: String): InputStream {
        val keys = storage.walk(pendingKey)
            .sortedBy { it.key.substringAfterLast('/').toLongOrNull() ?: 0L }
            .map { it.key }
        // Opened one at a time as the stream advances: SequenceInputStream closes each before the next.
        val lazily = object : Enumeration<InputStream> {
            private val remaining = keys.iterator()
            override fun hasMoreElements() = remaining.hasNext()
            override fun nextElement(): InputStream =
                storage.openRead(remaining.next())?.stream ?: InputStream.nullInputStream()
        }
        return SequenceInputStream(lazily)
    }

    private fun uploadKey(repository: String, uuid: String): String = "$repository/_container/_uploads/$uuid"

    /** A chunk's own key within the session's prefix, named by its byte offset. */
    private fun chunkKey(pendingKey: String, offset: Long): String =
        "$pendingKey/${offset.toString().padStart(OFFSET_DIGITS, '0')}"

    companion object {
        /** Wide enough for any Long offset, so padded names stay uniform. */
        private const val OFFSET_DIGITS = 20

        /**
         * The start byte of a chunk's `Content-Range`, or null when the header is absent or unparseable.
         *
         * Accepts both spellings seen in the wild: the OCI spec's bare `<start>-<end>` and the full HTTP
         * `bytes <start>-<end>/<total>`. Null is deliberately distinct from `0` — the caller falls back to
         * the session's current offset, where defaulting to zero would silently overwrite the first chunk.
         */
        fun parseChunkOffset(header: String?): Long? =
            header?.trim()
                ?.removePrefix("bytes")?.trim()
                ?.substringBefore('-')?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
    }
}
