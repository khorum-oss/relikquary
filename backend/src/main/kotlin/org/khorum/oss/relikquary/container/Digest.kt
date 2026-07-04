package org.khorum.oss.relikquary.container

import java.io.InputStream
import java.security.MessageDigest

/** Thrown when a digest string is malformed or a computed digest does not match a claimed one (⇒ 400). */
class InvalidDigestException(message: String) : RuntimeException(message)

/**
 * A content digest in the OCI form `algorithm:hex` (feature 018). Only `sha256` is supported — the
 * algorithm every current Docker/OCI client uses to address blobs and manifests. The value is immutable
 * and content-addressable: the same bytes always yield the same [Digest], which is why stored container
 * objects MUST be preserved byte-for-byte (Principle IV) — any re-encoding would change the digest a
 * client verifies.
 */
@JvmInline
value class Digest private constructor(val value: String) {

    /** The lowercase hex portion (no `sha256:` prefix) — used as the storage key leaf. */
    val hex: String get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        const val ALGORITHM = "sha256"
        private const val HEX_LENGTH = 64
        private val HEX = Regex("[0-9a-f]{$HEX_LENGTH}")

        /** Parses `sha256:<64 lowercase hex>`, throwing [InvalidDigestException] on any other shape. */
        fun parse(raw: String): Digest {
            val trimmed = raw.trim()
            val (algo, hex) = trimmed.split(':', limit = 2).let {
                if (it.size != 2) throw InvalidDigestException("malformed digest: $raw") else it[0] to it[1]
            }
            if (algo != ALGORITHM) throw InvalidDigestException("unsupported digest algorithm: $algo")
            if (!HEX.matches(hex)) throw InvalidDigestException("malformed $ALGORITHM digest: $raw")
            return Digest("$ALGORITHM:$hex")
        }

        /** Whether [raw] is a well-formed `sha256:<hex>` digest (never throws). */
        fun isDigest(raw: String): Boolean =
            runCatching { parse(raw) }.isSuccess

        /** Computes the `sha256` digest of [bytes]. */
        fun of(bytes: ByteArray): Digest = Digest("$ALGORITHM:${sha256().digest(bytes).toHex()}")

        /** Computes the `sha256` digest of [stream], consuming and closing it. */
        fun of(stream: InputStream): Digest {
            val md = sha256()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            stream.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return Digest("$ALGORITHM:${md.digest().toHex()}")
        }

        private fun sha256() = MessageDigest.getInstance("SHA-256")

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
