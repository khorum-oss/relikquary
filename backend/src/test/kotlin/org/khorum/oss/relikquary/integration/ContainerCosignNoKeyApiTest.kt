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
import java.security.MessageDigest

/**
 * Cosign verification with no key configured (feature 024, US2): a hosted container repo without a per-repo
 * key or a global default must report trust `unknown` — never `unsigned` — so the UI does not imply an image
 * is untrusted merely because the operator has not configured a key. Distinct config (no `relikquary.cosign`
 * block) from the key-configured test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-cosign-nokey-it.yml"],
)
class ContainerCosignNoKeyApiTest {

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

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    @Test
    fun `trust is unknown when no cosign key is configured`() {
        val image = "cosign/nokey"
        pushImage("apps", image, "1.0.0")

        assertEquals("unknown", trustOf("apps", image, "1.0.0"))
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun pushBlob(repo: String, image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode(), "blob upload")
        return digest
    }

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
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/manifests/$tag"))
            .header("Content-Type", OCI_MANIFEST_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(manifest)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode(), "manifest PUT")
        return digestOf(manifest)
    }

    private fun trustOf(repo: String, image: String, tag: String): String {
        val response = get("/api/repositories/$repo/containers/tags?image=${URLEncoder.encode(image, StandardCharsets.UTF_8)}")
        assertEquals(HTTP_OK, response.statusCode(), "tags listing")
        val row = json.readTree(response.body())["tags"].first { it["tag"].asText() == tag }
        return row["trust"].asText()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }
}
