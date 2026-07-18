package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Dashboard stats feed (feature 016, Phase 2). The figures come from the shared storage-usage snapshot;
 * a 0s refresh makes each request recompute so the assertions are deterministic against freshly seeded
 * content. Auth disabled to focus on the aggregation.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=false",
        "relikquary.observability.storage-usage-refresh=0s",
    ],
)
class StatsApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun put(path: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(url(path)).header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `stats reports repository count, object count, bytes used, and container images`() {
        // Two files (10 bytes total) into a hosted repo.
        assertEquals(201, put("/releases/com/example/stats/1.0.0/stats-1.0.0.jar", ByteArray(5)))
        assertEquals(201, put("/releases/com/example/stats/1.0.0/stats-1.0.0.pom", ByteArray(5)))
        // A container image into the hosted 'apps' repo (feature 023).
        pushImage("team/statsimg", "1.0.0")

        val stats = json.readTree(get("/api/stats").body())
        // All configured repositories (releases, snapshots, maven-central, gradle-plugins, public, plugins,
        // plus the container repos apps + dockerhub — feature 018).
        assertEquals(8, stats["repositories"].asInt())
        assertTrue(stats["artifacts"].asLong() >= 2) { "object count should include the seeded files: $stats" }
        assertTrue(stats["storageBytes"].asLong() >= 10) { "bytes should include the seeded files: $stats" }
        // The container tables are shared across test classes in the JVM (like the storage snapshot), so
        // assert the pushed image is counted rather than an exact total (mirrors the `artifacts >= 2` style).
        assertTrue(stats["images"].asLong() >= 1) { "the distinct container image count should include the push: $stats" }
    }

    private fun pushImage(image: String, tag: String) {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "stats-layer".toByteArray()
        val configDigest = pushBlob(image, config)
        val layerDigest = pushBlob(image, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/manifests/$tag"))
            .header("Content-Type", OCI_MANIFEST_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(manifest)).build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    private fun pushBlob(image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
        return digest
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }
}
