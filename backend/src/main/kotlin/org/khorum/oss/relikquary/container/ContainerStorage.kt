package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.ArtifactWrite
import org.khorum.oss.relikquary.storage.StoredArtifact
import org.springframework.stereotype.Component
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-addressable storage for container blobs and manifests over the existing [ArtifactStorage]
 * (feature 018). Objects live under a reserved `{repo}/_container/…` sub-namespace so container and Maven
 * trees never collide within one repository/backend, and are keyed by their `sha256` digest so identical
 * content is stored once and served byte-for-byte (Principle IV). Filesystem and S3 both work unchanged.
 */
@Component
class ContainerStorage(private val storage: ArtifactStorage) {

    /** Storage key for a blob (layer/config) addressed by [digest] within [repository]. */
    fun blobKey(repository: String, digest: Digest): String =
        "$repository/$CONTAINER_PREFIX/blobs/${Digest.ALGORITHM}/${digest.hex}"

    /** Storage key for a manifest/index addressed by [digest] within [repository]. */
    fun manifestKey(repository: String, digest: Digest): String =
        "$repository/$CONTAINER_PREFIX/manifests/${Digest.ALGORITHM}/${digest.hex}"

    fun hasBlob(repository: String, digest: Digest): Boolean = storage.exists(blobKey(repository, digest))

    fun readBlob(repository: String, digest: Digest): StoredArtifact? = storage.openRead(blobKey(repository, digest))

    fun hasManifest(repository: String, digest: Digest): Boolean = storage.exists(manifestKey(repository, digest))

    fun readManifest(repository: String, digest: Digest): StoredArtifact? =
        storage.openRead(manifestKey(repository, digest))

    fun readManifestBytes(repository: String, digest: Digest): ByteArray? =
        readManifest(repository, digest)?.stream?.use { it.readBytes() }

    /**
     * Opens a pending cache write for a blob addressed by [digest] — used by the proxy to tee upstream
     * bytes into the cache while streaming them to the client. The entry becomes visible only on
     * [ArtifactWrite.commit]; the bytes are trusted to match [digest] because the key is derived from it
     * and the upstream is content-addressable.
     */
    fun openBlobCacheWrite(repository: String, digest: Digest): ArtifactWrite =
        storage.openWrite(blobKey(repository, digest))

    /**
     * Streams [content] into the blob store, verifying its `sha256` equals [digest] before the entry
     * becomes visible. On a mismatch nothing is stored and [InvalidDigestException] is thrown (⇒ 400
     * DIGEST_INVALID). Returns the number of bytes written. Used by hosted `docker push` finalize.
     */
    fun writeBlobVerified(repository: String, digest: Digest, content: InputStream): Long {
        val write = storage.openWrite(blobKey(repository, digest))
        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            content.use { source ->
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                    write.sink.write(buffer, 0, read)
                    written += read
                }
            }
            val computed = "${Digest.ALGORITHM}:${md.digest().toHex()}"
            if (computed != digest.value) {
                write.abort()
                throw InvalidDigestException("blob digest mismatch: claimed ${digest.value}, computed $computed")
            }
            write.sink.flush()
            write.commit()
            return written
        } finally {
            write.close()
        }
    }

    /**
     * Stores manifest/index [bytes] verbatim under [digest], verifying `sha256(bytes) == digest` first.
     * Used both for a hosted manifest PUT and for caching a proxied manifest.
     */
    fun writeManifestBytes(repository: String, digest: Digest, bytes: ByteArray) {
        val computed = Digest.of(bytes)
        if (computed != digest) {
            throw InvalidDigestException("manifest digest mismatch: claimed ${digest.value}, computed ${computed.value}")
        }
        val write = storage.openWrite(manifestKey(repository, digest))
        try {
            write.sink.write(bytes)
            write.sink.flush()
            write.commit()
        } finally {
            write.close()
        }
    }

    private companion object {
        const val CONTAINER_PREFIX = "_container"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
