package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.AfterAll
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

/**
 * Principle II round-trip for a proxy container repository (feature 018): the OCI wire is driven against
 * a `dockerhub` proxy pointed at an in-process [StubOciRegistry] that enforces the Bearer-token handshake.
 * A manifest and blob are fetched through the proxy (bytes/digest byte-identical, SC-002); a push is
 * rejected (405); and after the upstream is stopped, a second blob request is served from the local cache
 * (SC-001). Security is disabled to keep the focus on resolution.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("container")
class ContainerProxyIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    companion object {
        private val stub = StubOciRegistry().start()

        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        @JvmStatic
        @AfterAll
        fun tearDown() {
            runCatching { stub.stop() }
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { tempDir.resolve("store").toString() }
            registry.add("relikquary.persistence.sqlite.path") { tempDir.resolve("test.db").toString() }
            registry.add("RELIKQUARY_DOCKERHUB_URL") { stub.baseUrl }
        }
    }

    @Test
    fun `pulls a manifest and blob through the proxy, rejects push, then serves the blob from cache`() {
        val manifestBytes = """{"schemaVersion":2,"mediaType":"$MANIFEST_TYPE"}""".toByteArray()
        val digest = stub.seedManifest("library/alpine", "3.20", manifestBytes, MANIFEST_TYPE)
        val blobBytes = "a fake layer blob".toByteArray()
        val blobDigest = stub.seedBlob("library/alpine", blobBytes)

        // Version check advertises V2 support.
        val version = getString("/v2/")
        assertEquals(HTTP_OK, version.statusCode())
        assertEquals("registry/2.0", version.headers().firstValue("Docker-Distribution-API-Version").orElse(""))

        // Manifest pull by tag: bytes + digest + media type match the upstream (bearer handshake internal).
        val manifest = getBytes("/v2/dockerhub/library/alpine/manifests/3.20")
        assertEquals(HTTP_OK, manifest.statusCode())
        assertArrayEquals(manifestBytes, manifest.body())
        assertEquals(digest, manifest.headers().firstValue("Docker-Content-Digest").orElse(""))
        assertEquals(MANIFEST_TYPE, manifest.headers().firstValue("Content-Type").orElse(""))

        // Blob pull by digest: bytes match the upstream.
        val blob = getBytes("/v2/dockerhub/library/alpine/blobs/$blobDigest")
        assertEquals(HTTP_OK, blob.statusCode())
        assertArrayEquals(blobBytes, blob.body())

        // A push to a proxy repository is rejected (read-only), without contacting the upstream.
        val push = client.send(
            HttpRequest.newBuilder(URI.create(base() + "/v2/dockerhub/library/alpine/blobs/uploads/"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(HTTP_METHOD_NOT_ALLOWED, push.statusCode())
        assertTrue(push.body().contains("UNSUPPORTED")) { "expected OCI UNSUPPORTED error, got: ${push.body()}" }

        // Stop the upstream; a second blob request (by digest) is served from the local cache.
        stub.stop()
        val cached = getBytes("/v2/dockerhub/library/alpine/blobs/$blobDigest")
        assertEquals(HTTP_OK, cached.statusCode())
        assertArrayEquals(blobBytes, cached.body())
    }

    private fun base() = "http://127.0.0.1:$port"

    private fun getString(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        client.send(
            HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val MANIFEST_TYPE = "application/vnd.docker.distribution.manifest.v2+json"
    }
}
