package org.khorum.oss.relikquary.container

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
 * Hosted container error handling (feature 018, US2, SC-004): a blob finalized against the wrong digest is
 * rejected (400 DIGEST_INVALID, nothing stored), a manifest referencing an un-uploaded blob is rejected
 * (400 MANIFEST_BLOB_UNKNOWN, no tag recorded), and absent manifests/blobs return 404.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("container")
class ContainerErrorsIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    companion object {
        const val IMAGE = "team/broken"
        const val REPO = "containers"

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
    fun `finalizing a blob against the wrong digest is rejected`() {
        val location = startUpload()
        val wrongDigest = sha256("not these bytes".toByteArray())
        val put = send("PUT", "$location?digest=$wrongDigest", "actual bytes".toByteArray())
        assertEquals(HTTP_BAD_REQUEST, put.statusCode())
        assertTrue(String(put.body()).contains("DIGEST_INVALID")) { "expected DIGEST_INVALID, got ${String(put.body())}" }
        // Nothing was stored under the claimed digest.
        assertEquals(HTTP_NOT_FOUND, getBytes("/v2/$REPO/$IMAGE/blobs/$wrongDigest").statusCode())
    }

    @Test
    fun `a manifest referencing a missing blob is rejected`() {
        val missing = sha256("a layer never uploaded".toByteArray())
        val manifest = """
            {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
             "config":{"digest":"$missing","size":1},"layers":[]}
        """.trimIndent().toByteArray()
        val put = send("PUT", "/v2/$REPO/$IMAGE/manifests/1.0", manifest, "Content-Type", "application/vnd.oci.image.manifest.v1+json")
        assertEquals(HTTP_BAD_REQUEST, put.statusCode())
        assertTrue(String(put.body()).contains("MANIFEST_BLOB_UNKNOWN")) {
            "expected MANIFEST_BLOB_UNKNOWN, got ${String(put.body())}"
        }
        // No tag was recorded.
        assertEquals(HTTP_NOT_FOUND, getBytes("/v2/$REPO/$IMAGE/manifests/1.0").statusCode())
    }

    @Test
    fun `absent manifest and blob return 404`() {
        assertEquals(HTTP_NOT_FOUND, getBytes("/v2/$REPO/$IMAGE/manifests/does-not-exist").statusCode())
        assertEquals(HTTP_NOT_FOUND, getBytes("/v2/$REPO/$IMAGE/blobs/${sha256("nope".toByteArray())}").statusCode())
    }

    private fun startUpload(): String {
        val post = send("POST", "/v2/$REPO/$IMAGE/blobs/uploads/", ByteArray(0))
        assertEquals(HTTP_ACCEPTED, post.statusCode())
        return post.headers().firstValue("Location").orElseThrow { AssertionError("no upload Location") }
    }

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

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_ACCEPTED = 202
    }
}
