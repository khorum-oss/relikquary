package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Principle II round-trip for a HOSTED container repository (feature 018, US2, SC-003): a client pushes
 * an image (a config blob via a chunked PATCH+PUT, a layer blob via a monolithic POST→PUT, then the
 * manifest) over the raw Docker Registry V2 wire, and pulls it all back — asserting byte- and
 * digest-identity and that the tag lists. Security is disabled to focus on the push/pull protocol.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("container")
class ContainerHostedRoundTripIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_ACCEPTED = 202
        const val IMAGE = "team/app"
        const val REPO = "containers"
        const val TAG = "1.0"
        const val MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"

        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { tempDir.resolve("store").toString() }
            registry.add("relikquary.persistence.sqlite.path") { tempDir.resolve("test.db").toString() }
        }
    }

    @Test
    fun `pushes an image then pulls it back byte-for-byte`() {
        val configBytes = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layerBytes = "fake tar layer bytes".toByteArray()
        val configDigest = pushBlobChunked(configBytes)
        val layerDigest = pushBlobMonolithic(layerBytes)

        val manifestBytes = manifestJson(configDigest, configBytes.size, layerDigest, layerBytes.size).toByteArray()
        val manifestDigest = sha256(manifestBytes)

        // PUT the manifest by tag.
        val put = send("PUT", "/v2/$REPO/$IMAGE/manifests/$TAG", manifestBytes, "Content-Type", MANIFEST_TYPE)
        assertEquals(HTTP_CREATED, put.statusCode())
        assertEquals(manifestDigest, put.headers().firstValue("Docker-Content-Digest").orElse(""))

        // Pull the manifest by tag — bytes + digest + media type identical.
        val manifest = getBytes("/v2/$REPO/$IMAGE/manifests/$TAG")
        assertEquals(HTTP_OK, manifest.statusCode())
        assertArrayEquals(manifestBytes, manifest.body())
        assertEquals(manifestDigest, manifest.headers().firstValue("Docker-Content-Digest").orElse(""))
        assertEquals(MANIFEST_TYPE, manifest.headers().firstValue("Content-Type").orElse(""))

        // Pull the manifest by digest too.
        assertEquals(HTTP_OK, getBytes("/v2/$REPO/$IMAGE/manifests/$manifestDigest").statusCode())

        // Pull both blobs — byte-identical.
        assertArrayEquals(configBytes, getBytes("/v2/$REPO/$IMAGE/blobs/$configDigest").body())
        assertArrayEquals(layerBytes, getBytes("/v2/$REPO/$IMAGE/blobs/$layerDigest").body())

        // The tag lists.
        val tags = getBytes("/v2/$REPO/$IMAGE/tags/list")
        assertEquals(HTTP_OK, tags.statusCode())
        assertTrue(String(tags.body()).contains("\"$TAG\"")) { "tags/list missing $TAG: ${String(tags.body())}" }
    }

    /** Uploads a blob via POST → PATCH(chunk) → PUT(finalize) and returns its digest. */
    private fun pushBlobChunked(bytes: ByteArray): String {
        val digest = sha256(bytes)
        val location = startUpload()
        val patch = send("PATCH", location, bytes)
        assertEquals(HTTP_ACCEPTED, patch.statusCode())
        val put = send("PUT", "$location?digest=$digest", ByteArray(0))
        assertEquals(HTTP_CREATED, put.statusCode())
        return digest
    }

    /** Uploads a blob via POST → PUT(full body) and returns its digest. */
    private fun pushBlobMonolithic(bytes: ByteArray): String {
        val digest = sha256(bytes)
        val location = startUpload()
        val put = send("PUT", "$location?digest=$digest", bytes)
        assertEquals(HTTP_CREATED, put.statusCode())
        return digest
    }

    private fun startUpload(): String {
        val post = send("POST", "/v2/$REPO/$IMAGE/blobs/uploads/", ByteArray(0))
        assertEquals(HTTP_ACCEPTED, post.statusCode())
        return post.headers().firstValue("Location").orElseThrow { AssertionError("no upload Location") }
    }

    private fun manifestJson(configDigest: String, configSize: Int, layerDigest: String, layerSize: Int): String =
        """
        {"schemaVersion":2,"mediaType":"$MANIFEST_TYPE",
         "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":$configSize},
         "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar","digest":"$layerDigest","size":$layerSize}]}
        """.trimIndent()

    private fun base() = "http://127.0.0.1:$port"

    private fun send(method: String, path: String, body: ByteArray, vararg headers: String): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(base() + path))
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        var i = 0
        while (i + 1 < headers.size) {
            builder.header(headers[i], headers[i + 1]); i += 2
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        client.send(
            HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
