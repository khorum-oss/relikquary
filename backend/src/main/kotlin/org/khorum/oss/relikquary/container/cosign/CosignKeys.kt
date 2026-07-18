package org.khorum.oss.relikquary.container.cosign

import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.CosignProperties
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/** The outcome of resolving a repository's cosign public key (feature 024). */
sealed interface KeyResolution {
    /** No key configured for the repository and no global default ⇒ trust `unknown`. */
    data object None : KeyResolution

    /** A configured key that parsed successfully. */
    data class Key(val publicKey: PublicKey) : KeyResolution

    /** A key was configured but could not be read/parsed ⇒ verification fails closed (never `verified`). */
    data object Invalid : KeyResolution
}

/**
 * Resolves the cosign public key to verify a container repository's image signatures (feature 024): the
 * repository's own [RepositoryProperties.Repo.cosignPublicKey] if set, else the global
 * [CosignProperties.defaultPublicKey]. The configured value is either an inline PEM
 * (`-----BEGIN PUBLIC KEY-----…`) or a path to a PEM file. Parsed keys are cached per repository name.
 */
@Component
class CosignKeys(private val cosign: CosignProperties) {

    private val cache = ConcurrentHashMap<String, KeyResolution>()

    /** Resolve [repo]'s key. Cached by repo name; the configuration is static for the process lifetime. */
    fun resolve(repo: RepositoryProperties.Repo): KeyResolution =
        cache.getOrPut(repo.name) {
            val configured = repo.cosignPublicKey ?: cosign.defaultPublicKey ?: return@getOrPut KeyResolution.None
            parse(configured, repo.name)
        }

    private fun parse(configured: String, repoName: String): KeyResolution {
        val pem = if (configured.trimStart().startsWith(PEM_HEADER)) configured else readFile(configured, repoName)
            ?: return KeyResolution.Invalid
        val der = decodePem(pem) ?: return KeyResolution.Invalid
        val key = KEY_ALGORITHMS.firstNotNullOfOrNull { algorithm -> tryParse(algorithm, der) }
        return if (key != null) {
            KeyResolution.Key(key)
        } else {
            logger.warn { "Configured cosign public key for '$repoName' is not a supported EC/RSA/Ed25519 key" }
            KeyResolution.Invalid
        }
    }

    private fun readFile(pathValue: String, repoName: String): String? =
        try {
            Files.readString(Path.of(pathValue.trim()))
        } catch (e: java.io.IOException) {
            logger.warn { "Cannot read cosign public key file for '$repoName' at '$pathValue': ${e.message}" }
            null
        }

    private fun decodePem(pem: String): ByteArray? {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("") { it.trim() }
        return try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun tryParse(algorithm: String, der: ByteArray): PublicKey? =
        try {
            KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(der))
        } catch (_: GeneralSecurityException) {
            null
        }

    private companion object {
        const val PEM_HEADER = "-----BEGIN"

        /** Try each in turn: the DER SubjectPublicKeyInfo names the algorithm, so the wrong factory just fails. */
        val KEY_ALGORITHMS = listOf("EC", "RSA", "Ed25519")
    }
}
