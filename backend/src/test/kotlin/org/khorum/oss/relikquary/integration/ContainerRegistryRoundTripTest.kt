package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Feature 021 (US1): a real hosted push→pull→tags→re-tag→delete round-trip against the running server with
 * real filesystem storage, proving the container registry stores and serves byte-and-digest-identical
 * content (Principle IV) — the integration coverage feature 018 shipped without. A protocol-faithful client
 * (JDK HttpClient) performs the exact OCI `/v2` sequence a `docker` client would.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-container-it.yml"],
)
class ContainerRegistryRoundTripTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_ACCEPTED = 202
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val IMAGE_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val CONFIG_TYPE = "application/vnd.oci.image.config.v1+json"
        const val LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"
        const val IMAGE = "team/service"
        const val DIGEST_HEADER = "Docker-Content-Digest"
        const val SHA256_HEX_LENGTH = 64

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    @Test
    fun `hosted push, pull, tags, re-tag, delete round-trip preserves bytes and digests`() {
        // --- Push image #1 (config + two layers + manifest under tag 1.0.0) ---
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layerA = "layer-a-contents".toByteArray()
        val layerB = "layer-b-different-contents".toByteArray()
        val configDigest = pushBlob("apps", config)
        val layerADigest = pushBlob("apps", layerA)
        val layerBDigest = pushBlob("apps", layerB)
        val manifest1 = imageManifest(configDigest, config.size, listOf(layerADigest to layerA.size, layerBDigest to layerB.size))
        val put1 = putManifest("apps", "1.0.0", manifest1)
        assertEquals(HTTP_CREATED, put1.statusCode())
        val digest1 = digestOf(manifest1)
        assertEquals(digest1, put1.headers().firstValue(DIGEST_HEADER).orElse(""))

        // --- Pull the manifest back: byte-identical, same digest and media type ---
        val pulled = getManifest("apps", "1.0.0")
        assertEquals(HTTP_OK, pulled.statusCode())
        assertArrayEquals(manifest1, pulled.body(), "pulled manifest bytes must equal what was pushed")
        assertEquals(digest1, pulled.headers().firstValue(DIGEST_HEADER).orElse(""))
        assertEquals(IMAGE_TYPE, pulled.headers().firstValue("Content-Type").orElse(""))

        // --- Pull each blob back byte-for-byte ---
        assertBlob("apps", configDigest, config)
        assertBlob("apps", layerADigest, layerA)
        assertBlob("apps", layerBDigest, layerB)

        // --- Pulling the manifest by digest returns the same bytes as by tag ---
        assertArrayEquals(manifest1, getManifestByDigest("apps", digest1).body())

        // --- Tag list includes the pushed tag ---
        assertTrue(getTags("apps").contains("\"1.0.0\""), "tags/list should include 1.0.0")

        // --- Re-tag: push a different image to the same tag; tag re-points, old digest still retrievable ---
        val config2 = """{"architecture":"arm64","os":"linux"}""".toByteArray()
        val layerC = "layer-c-second-image".toByteArray()
        val config2Digest = pushBlob("apps", config2)
        val layerCDigest = pushBlob("apps", layerC)
        val manifest2 = imageManifest(config2Digest, config2.size, listOf(layerCDigest to layerC.size))
        assertEquals(HTTP_CREATED, putManifest("apps", "1.0.0", manifest2).statusCode())
        val digest2 = digestOf(manifest2)
        assertArrayEquals(manifest2, getManifest("apps", "1.0.0").body(), "tag must resolve to the new image")
        assertArrayEquals(manifest1, getManifestByDigest("apps", digest1).body(), "old digest must remain retrievable")

        // --- Delete the tag, then a manifest by digest; both become unretrievable ---
        assertEquals(HTTP_ACCEPTED, deleteManifest("apps", "1.0.0"))
        assertEquals(HTTP_NOT_FOUND, getManifest("apps", "1.0.0").statusCode())
        assertEquals(HTTP_ACCEPTED, deleteManifest("apps", digest2))
        assertEquals(HTTP_NOT_FOUND, getManifestByDigest("apps", digest2).statusCode())

        // --- Cross-repo isolation: image #1's digest is not retrievable through a different repo ---
        assertEquals(HTTP_NOT_FOUND, getManifestByDigest("vault", digest1).statusCode())

        // --- A proxy repository rejects a push (read-only pull-through cache) ---
        assertEquals(HTTP_METHOD_NOT_ALLOWED, startUpload("mirror", "sha256:${"0".repeat(SHA256_HEX_LENGTH)}"))
    }

    // ---- OCI client helpers ----

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun pushBlob(repo: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode(), "blob upload")
        return digest
    }

    private fun startUpload(repo: String, digest: String): Int {
        val req = HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray("x".toByteArray())).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun putManifest(repo: String, ref: String, body: ByteArray): HttpResponse<Void> {
        val req = HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/manifests/$ref"))
            .header("Content-Type", IMAGE_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding())
    }

    private fun getManifest(repo: String, ref: String): HttpResponse<ByteArray> {
        val req = HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/manifests/$ref"))
            .header("Accept", IMAGE_TYPE).GET().build()
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun getManifestByDigest(repo: String, digest: String): HttpResponse<ByteArray> = getManifest(repo, digest)

    private fun assertBlob(repo: String, digest: String, expected: ByteArray) {
        val res = http.send(
            HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/blobs/$digest")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(HTTP_OK, res.statusCode(), "blob pull")
        assertArrayEquals(expected, res.body(), "blob bytes must equal what was pushed")
        assertEquals(digest, res.headers().firstValue(DIGEST_HEADER).orElse(""))
    }

    private fun getTags(repo: String): String =
        http.send(HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/tags/list")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()

    private fun deleteManifest(repo: String, ref: String): Int {
        val req = HttpRequest.newBuilder(url("/v2/$repo/$IMAGE/manifests/$ref")).DELETE().build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun imageManifest(configDigest: String, configSize: Int, layers: List<Pair<String, Int>>): ByteArray {
        val layerJson = layers.joinToString(",") { (digest, size) ->
            """{"mediaType":"$LAYER_TYPE","digest":"$digest","size":$size}"""
        }
        return (
            """{"schemaVersion":2,"mediaType":"$IMAGE_TYPE",""" +
                """"config":{"mediaType":"$CONFIG_TYPE","digest":"$configDigest","size":$configSize},""" +
                """"layers":[$layerJson]}"""
            ).toByteArray()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }
}
