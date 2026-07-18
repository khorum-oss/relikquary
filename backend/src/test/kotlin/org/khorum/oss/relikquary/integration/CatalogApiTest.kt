package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * Cross-repo artifact catalog (feature 016, Phase 2): one entry per `group:artifact` with the correct
 * latest version, version count, and aggregate size; `q` filters by coordinate; paging bounds results.
 * Auth disabled to focus on the aggregation (the read-scoping is exercised by the authorizer elsewhere).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class CatalogApiTest {

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

    private fun entries(query: String): List<JsonNode> =
        json.readTree(get("/api/catalog$query").body())["entries"].toList()

    // The static storage root is shared across methods and release coordinates are immutable, so each
    // test seeds distinct artifact names.
    private fun seed(group: String, artifact: String, version: String, vararg sizes: Int) {
        val groupPath = group.replace('.', '/')
        val exts = listOf("jar", "pom", "module")
        sizes.forEachIndexed { i, size ->
            val ext = exts[i]
            val key = "/releases/$groupPath/$artifact/$version/$artifact-$version.$ext"
            assertEquals(201, put(key, ByteArray(size))) { "seeding $key" }
        }
    }

    @Test
    fun `one entry per coordinate with latest, count, and size`() {
        seed("com.example", "widgetcount", "1.0.0", 3, 2) // jar + pom = 5 bytes
        seed("com.example", "widgetcount", "2.0.0", 4) // jar = 4 bytes
        seed("org.acme", "libcount", "1.0.0", 6)

        val widget = entries("?repo=releases").first { it["artifact"].asText() == "widgetcount" }
        assertEquals("com.example", widget["group"].asText())
        assertEquals("2.0.0", widget["latestVersion"].asText())
        assertEquals(2, widget["versionCount"].asInt())
        assertEquals(9, widget["sizeBytes"].asLong()) // 3 + 2 + 4

        val lib = entries("?repo=releases").first { it["artifact"].asText() == "libcount" }
        assertEquals("org.acme", lib["group"].asText())
        assertEquals(1, lib["versionCount"].asInt())
        assertEquals(6, lib["sizeBytes"].asLong())
    }

    @Test
    fun `q filters by coordinate fragment`() {
        seed("com.example", "qwidget", "1.0.0", 3)
        seed("com.example", "qgadget", "1.0.0", 3)

        val matches = entries("?repo=releases&q=qwidget")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it["artifact"].asText() == "qwidget" }) { "q=qwidget returned $matches" }
        assertNull(matches.firstOrNull { it["artifact"].asText() == "qgadget" })
    }

    @Test
    fun `container images appear as typed catalog entries`() {
        // Push an image (config + layer) under two tags to the hosted 'apps' container repo.
        val image = "team/catalogimg"
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "catalog-layer".toByteArray()
        val configDigest = pushBlob(image, config)
        val layerDigest = pushBlob(image, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        assertEquals(201, putManifest(image, "1.0.0", manifest))
        assertEquals(201, putManifest(image, "2.0.0", manifest))

        val entry = entries("?repo=apps").first { it["artifact"].asText() == image }
        assertEquals("container", entry["type"].asText())
        assertEquals("", entry["group"].asText())
        assertEquals(2, entry["versionCount"].asInt(), "two tags")
        assertTrue(entry["latestVersion"].asText().isNotEmpty(), "a latest tag is shown")
        assertTrue(entry["sizeBytes"].asLong() > 0, "the manifest size is aggregated")

        // A Maven entry keeps type=maven (default).
        seed("com.example", "mixedmaven", "1.0.0", 3)
        assertEquals("maven", entries("?repo=releases").first { it["artifact"].asText() == "mixedmaven" }["type"].asText())
    }

    private fun pushBlob(image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
        return digest
    }

    private fun putManifest(image: String, ref: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/manifests/$ref"))
            .header("Content-Type", OCI_MANIFEST_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    @Test
    fun `paging bounds the result and discloses truncation`() {
        seed("com.example", "pageone", "1.0.0", 3)
        seed("com.example", "pagetwo", "1.0.0", 3)

        val body = json.readTree(get("/api/catalog?repo=releases&pageSize=1&page=0").body())
        assertEquals(1, body["entries"].size())
        assertEquals(1, body["pageSize"].asInt())
        assertTrue(body["total"].asLong() >= 2) { "expected at least two coordinates: $body" }
        assertTrue(body["truncated"].asBoolean()) { "first of many pages should be truncated: $body" }
    }
}
