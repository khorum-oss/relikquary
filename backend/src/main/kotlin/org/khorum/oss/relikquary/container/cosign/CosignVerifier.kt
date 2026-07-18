package org.khorum.oss.relikquary.container.cosign

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.khorum.oss.relikquary.container.ContainerStorage
import org.khorum.oss.relikquary.container.Digest
import org.khorum.oss.relikquary.container.persistence.ContainerTagRepository
import org.khorum.oss.relikquary.repository.RepositoryFormat
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.stereotype.Service
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/** The advisory trust verdict for a container image manifest digest (feature 024). */
enum class TrustStatus(val wire: String) {
    VERIFIED("verified"),
    SIGNED_UNVERIFIED("signed-but-unverified"),
    UNSIGNED("unsigned"),
    UNKNOWN("unknown"),
}

/**
 * Advisory cosign signature verification for hosted container images (feature 024). For an image digest,
 * resolves the repository's configured public key ([CosignKeys]), locates the cosign signature artifact
 * (the `sha256-<hex>` tag under the same image name), and verifies each simple-signing layer's detached
 * signature over its payload blob with the JDK, checking the payload references the image digest. Reads only
 * already-stored bytes; never verified without a matching signature (fails closed).
 */
@Service
class CosignVerifier(
    private val registry: RepositoryRegistry,
    private val keys: CosignKeys,
    private val storage: ContainerStorage,
    private val tags: ContainerTagRepository,
) {

    private val objectMapper = ObjectMapper()

    /** The trust status of [digest] under [imageName] in [repositoryName]. UNKNOWN for non-hosted-container. */
    fun verify(repositoryName: String, imageName: String, digest: String): TrustStatus {
        val repo = registry.all().firstOrNull { it.name == repositoryName } ?: return TrustStatus.UNKNOWN
        if (repo.format != RepositoryFormat.CONTAINER || repo.kind != RepositoryKind.HOSTED) return TrustStatus.UNKNOWN
        return when (val resolution = keys.resolve(repo)) {
            KeyResolution.None -> TrustStatus.UNKNOWN
            KeyResolution.Invalid ->
                if (signatureExists(repositoryName, imageName, digest)) TrustStatus.SIGNED_UNVERIFIED else TrustStatus.UNSIGNED
            is KeyResolution.Key -> verifyAgainst(repositoryName, imageName, digest, resolution.publicKey)
        }
    }

    private fun signatureExists(repository: String, imageName: String, digest: String): Boolean =
        tags.findByRepositoryAndImageNameAndTag(repository, imageName, signatureTag(digest)) != null

    private fun verifyAgainst(repository: String, imageName: String, digest: String, key: PublicKey): TrustStatus {
        val sigTag = tags.findByRepositoryAndImageNameAndTag(repository, imageName, signatureTag(digest))
            ?: return TrustStatus.UNSIGNED
        val manifestBytes = storage.readManifestBytes(repository, Digest.parse(sigTag.manifestDigest))
            ?: return TrustStatus.SIGNED_UNVERIFIED
        val layers = parse(manifestBytes)?.get("layers") ?: return TrustStatus.SIGNED_UNVERIFIED
        val verified = layers.any { layer -> layerVerifies(repository, layer, digest, key) }
        return if (verified) TrustStatus.VERIFIED else TrustStatus.SIGNED_UNVERIFIED
    }

    private fun layerVerifies(repository: String, layer: JsonNode, digest: String, key: PublicKey): Boolean {
        if (layer.get("mediaType")?.asText() != SIMPLESIGNING_TYPE) return false
        val payloadDigest = layer.get("digest")?.asText()?.takeIf { Digest.isDigest(it) } ?: return false
        val payload = storage.readBlob(repository, Digest.parse(payloadDigest))?.stream?.use { it.readBytes() }
            ?: return false
        val signature = layer.get("annotations")?.get(COSIGN_SIGNATURE_ANNOTATION)?.asText()?.let(::decodeBase64)
            ?: return false
        if (!signatureValidates(key, payload, signature)) return false
        val signedDigest = parse(payload)?.get("critical")?.get("image")?.get("docker-manifest-digest")?.asText()
        return signedDigest == digest
    }

    private fun signatureValidates(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        val algorithm = algorithmFor(key) ?: return false
        return try {
            Signature.getInstance(algorithm).apply { initVerify(key); update(data) }.verify(signature)
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private fun algorithmFor(key: PublicKey): String? = when (key.algorithm) {
        "EC" -> "SHA256withECDSA"
        "RSA" -> "SHA256withRSA"
        "EdDSA", "Ed25519" -> "Ed25519"
        else -> null
    }

    private fun parse(bytes: ByteArray): JsonNode? =
        try {
            objectMapper.readTree(bytes)
        } catch (_: JacksonException) {
            null
        }

    private fun decodeBase64(value: String): ByteArray? =
        try {
            Base64.getDecoder().decode(value.trim())
        } catch (_: IllegalArgumentException) {
            null
        }

    /** The cosign signature tag for a manifest digest: `sha256:<hex>` → `sha256-<hex>.sig`. */
    private fun signatureTag(digest: String): String = digest.replaceFirst(':', '-') + ".sig"

    private companion object {
        const val SIMPLESIGNING_TYPE = "application/vnd.dev.cosign.simplesigning.v1+json"
        const val COSIGN_SIGNATURE_ANNOTATION = "dev.cosignproject.cosign/signature"
    }
}
