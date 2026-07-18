package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Cosign signature verification (feature 024): advisory trust status surfaced by the browse API. Two EC
 * P-256 key pairs are generated in-JVM — one wired as the global default key, one as the `appskey` repo's
 * own key — and their public keys are injected through `${TEST_COSIGN_*}` placeholders. For each case the
 * test pushes an image over the real `/v2` surface and, where signing, builds a faithful cosign `.sig`
 * artifact (a simple-signing payload blob for the image digest plus a `SHA256withECDSA` detached signature
 * in the `dev.cosignproject.cosign/signature` annotation) tagged `sha256-<hex>.sig`, then asserts the
 * `trust` the tags endpoint reports. Verification never blocks a pull — it only reads stored bytes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-cosign-it.yml"],
)
class ContainerCosignVerifyApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val OCI_CONFIG_TYPE = "application/vnd.oci.image.config.v1+json"
        const val OCI_LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"
        const val SIMPLESIGNING_TYPE = "application/vnd.dev.cosign.simplesigning.v1+json"
        const val COSIGN_SIGNATURE_ANNOTATION = "dev.cosignproject.cosign/signature"

        /** keyA = the global default key; keyB = the `appskey` repo's own key. Generated at class load so
         *  the public keys are known when @DynamicPropertySource runs. */
        @JvmStatic
        val keyA: KeyPair = generateEc()

        @JvmStatic
        val keyB: KeyPair = generateEc()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            // Multi-line PEMs bind fine as property values; the yml resolves the `${TEST_COSIGN_*}` placeholders.
            registry.add("TEST_COSIGN_DEFAULT") { pemOf(keyA.public) }
            registry.add("TEST_COSIGN_REPO") { pemOf(keyB.public) }
        }

        private fun generateEc(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        private fun pemOf(key: PublicKey): String {
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
            return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----\n"
        }
    }

    // --- Test cases ---------------------------------------------------------------------------------------

    @Test
    fun `image signed by the configured default key is verified`() {
        val image = "cosign/verified"
        val digest = pushImage("apps", image, "1.0.0")
        signImage("apps", image, digest, keyA.private)

        assertEquals("verified", trustOf("apps", image, "1.0.0"))
    }

    @Test
    fun `image with no signature artifact is unsigned`() {
        val image = "cosign/unsigned"
        pushImage("apps", image, "1.0.0")

        assertEquals("unsigned", trustOf("apps", image, "1.0.0"))
    }

    @Test
    fun `signature by a different key is signed-but-unverified`() {
        val image = "cosign/wrongkey"
        val digest = pushImage("apps", image, "1.0.0")
        signImage("apps", image, digest, keyB.private) // signed by the repo key, but `apps` trusts the default

        assertEquals("signed-but-unverified", trustOf("apps", image, "1.0.0"))
    }

    @Test
    fun `signature over a mismatched digest is signed-but-unverified`() {
        val image = "cosign/mismatch"
        val digest = pushImage("apps", image, "1.0.0")
        val otherDigest = digestOf("a-different-image".toByteArray())
        signImage("apps", image, digest, keyA.private, signedDigest = otherDigest) // valid sig, wrong subject

        assertEquals("signed-but-unverified", trustOf("apps", image, "1.0.0"))
    }

    @Test
    fun `per-repo key takes precedence over the global default`() {
        val image = "cosign/repokey"
        val digest = pushImage("appskey", image, "1.0.0")
        signImage("appskey", image, digest, keyB.private) // matches appskey's own key

        assertEquals("verified", trustOf("appskey", image, "1.0.0"))
    }

    @Test
    fun `default-key signature does not verify under a repo with its own key`() {
        val image = "cosign/repokey-wrong"
        val digest = pushImage("appskey", image, "1.0.0")
        signImage("appskey", image, digest, keyA.private) // the global default key, not appskey's key

        assertEquals("signed-but-unverified", trustOf("appskey", image, "1.0.0"))
    }

    @Test
    fun `a malformed configured key fails closed for a signed image`() {
        val image = "cosign/badkey"
        val digest = pushImage("appsbad", image, "1.0.0")
        signImage("appsbad", image, digest, keyA.private)

        // The key cannot be parsed, so verification never returns `verified`; a signature exists ⇒ unverified.
        assertEquals("signed-but-unverified", trustOf("appsbad", image, "1.0.0"))
    }

    // --- OCI push helpers ---------------------------------------------------------------------------------

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Monolithic blob upload (the single-request docker push shape); returns the blob's digest. */
    private fun pushBlob(repo: String, image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode(), "blob upload")
        return digest
    }

    private fun putManifest(repo: String, image: String, ref: String, body: ByteArray, mediaType: String): Int {
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/manifests/$ref"))
            .header("Content-Type", mediaType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** Push a config + one layer + image manifest under [tag]; returns the image manifest digest. */
    private fun pushImage(repo: String, image: String, tag: String): String {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "layer-$repo-$image-$tag".toByteArray()
        val configDigest = pushBlob(repo, image, config)
        val layerDigest = pushBlob(repo, image, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"$OCI_CONFIG_TYPE","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"$OCI_LAYER_TYPE","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        assertEquals(HTTP_CREATED, putManifest(repo, image, tag, manifest, OCI_MANIFEST_TYPE), "manifest PUT")
        return digestOf(manifest)
    }

    /**
     * Build and push a faithful cosign `.sig` artifact for [imageDigest]: a simple-signing payload blob
     * (referencing [signedDigest], defaulting to [imageDigest]) plus a detached `SHA256withECDSA` signature
     * over the payload bytes carried in the layer's cosign annotation. Tagged `sha256-<hex>.sig` so the
     * verifier locates it exactly as cosign's convention dictates.
     */
    private fun signImage(
        repo: String,
        image: String,
        imageDigest: String,
        signingKey: PrivateKey,
        signedDigest: String = imageDigest,
    ) {
        val payload = simpleSigningPayload(repo, image, signedDigest).toByteArray()
        val payloadDigest = pushBlob(repo, image, payload)
        val signature = signEcdsa(signingKey, payload)
        val config = "{}".toByteArray()
        val configDigest = pushBlob(repo, image, config)
        val sigManifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"$OCI_CONFIG_TYPE","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"$SIMPLESIGNING_TYPE","digest":"$payloadDigest","size":${payload.size},""" +
                """"annotations":{"$COSIGN_SIGNATURE_ANNOTATION":"${base64(signature)}"}}]}"""
            ).toByteArray()
        val sigTag = imageDigest.replaceFirst(':', '-') + ".sig"
        assertEquals(HTTP_CREATED, putManifest(repo, image, sigTag, sigManifest, OCI_MANIFEST_TYPE), "signature PUT")
    }

    private fun simpleSigningPayload(repo: String, image: String, digest: String): String =
        """{"critical":{"identity":{"docker-reference":"$repo/$image"},""" +
            """"image":{"docker-manifest-digest":"$digest"},""" +
            """"type":"cosign container image signature"},"optional":null}"""

    // --- Assertion helper ---------------------------------------------------------------------------------

    /** The `trust` value the tags browse endpoint reports for [tag] of [image] in [repo]. */
    private fun trustOf(repo: String, image: String, tag: String): String {
        val response = get("/api/repositories/$repo/containers/tags?image=${enc(image)}")
        assertEquals(HTTP_OK, response.statusCode(), "tags listing")
        val body = json.readTree(response.body())
        val row = body["tags"].first { it["tag"].asText() == tag }
        return row["trust"].asText()
    }

    // --- Crypto helpers -----------------------------------------------------------------------------------

    private fun signEcdsa(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }
}
