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
 * Observability parity for container repositories (feature 018, US3): a hosted push + pull increments the
 * same `relikquary_publish_total` / `relikquary_resolve_total` meters as Maven — tagged with the container
 * repository — visible on `/actuator/prometheus` (feature 010, FR-004).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("container")
class ContainerObservabilityIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    companion object {
        const val REPO = "containers"
        const val IMAGE = "team/svc"

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
    fun `container push and pull show up in the prometheus meters`() {
        val configBytes = """{"os":"linux"}""".toByteArray()
        val configDigest = sha256(configBytes)
        // Monolithic blob push (POST ...?digest=), then a manifest referencing it.
        assertEquals(HTTP_CREATED, send("POST", "/v2/$REPO/$IMAGE/blobs/uploads/?digest=$configDigest", configBytes).statusCode())
        val manifest = """
            {"schemaVersion":2,"mediaType":"$MANIFEST_TYPE",
             "config":{"digest":"$configDigest","size":${configBytes.size}},"layers":[]}
        """.trimIndent().toByteArray()
        assertEquals(HTTP_CREATED, send("PUT", "/v2/$REPO/$IMAGE/manifests/1.0", manifest, "Content-Type", MANIFEST_TYPE).statusCode())
        // Pull the manifest (a resolve hit).
        assertEquals(HTTP_OK, send("GET", "/v2/$REPO/$IMAGE/manifests/1.0", ByteArray(0)).statusCode())

        val scrape = send("GET", "/actuator/prometheus", ByteArray(0))
        val body = String(scrape.body())
        assertTrue(body.contains("relikquary_publish_total")) { "publish counter missing" }
        assertTrue(body.contains("relikquary_resolve_total")) { "resolve counter missing" }
        assertTrue(body.contains("repository=\"$REPO\"")) { "container repository tag missing from meters" }
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

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
    }
}
