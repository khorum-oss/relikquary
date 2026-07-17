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
import java.security.MessageDigest

/**
 * Container browse API (feature 018 UI): after a `docker push`-shaped upload to the hosted `apps`
 * container repository via the `/v2` surface, the JSON browse endpoints the web UI consumes list the
 * pushed image and its tags. Auth disabled to focus on the browse behaviour. Also asserts the endpoints
 * reject a Maven repository (400) and an unknown repository (404).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ContainerBrowseApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
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

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** Monolithic blob upload (POST …/blobs/uploads/?digest=), the single-request docker push shape. */
    private fun pushBlob(image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
        assertEquals(HTTP_CREATED, status, "blob upload should be created")
        return digest
    }

    private fun putManifest(image: String, tag: String, body: ByteArray): HttpResponse<String> {
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/manifests/$tag"))
            .header("Content-Type", OCI_MANIFEST_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    private fun pushImage(image: String, tag: String): String {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "a-fake-layer-$image-$tag".toByteArray()
        val configDigest = pushBlob(image, config)
        val layerDigest = pushBlob(image, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"application/vnd.oci.image.config.v1+json",""" +
                """"digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip",""" +
                """"digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        val response = putManifest(image, tag, manifest)
        assertEquals(HTTP_CREATED, response.statusCode(), "manifest PUT should be created")
        return digestOf(manifest)
    }

    @Test
    fun `browse lists pushed images and their tags`() {
        val manifestDigest = pushImage("team/service", "1.0.0")

        val images = get("/api/repositories/apps/containers")
        assertEquals(HTTP_OK, images.statusCode())
        val imagesBody = json.readTree(images.body())
        assertEquals("apps", imagesBody.get("repository").asText())
        assertEquals("HOSTED", imagesBody.get("kind").asText())
        val image = imagesBody.get("images").first { it.get("name").asText() == "team/service" }
        assertEquals(1, image.get("tagCount").asInt())
        assertTrue(image.get("manifestCount").asInt() >= 1)
        assertTrue(image.get("lastPushed").isTextual, "lastPushed should be populated")

        val tags = get("/api/repositories/apps/containers/tags?image=team%2Fservice")
        assertEquals(HTTP_OK, tags.statusCode())
        val tagsBody = json.readTree(tags.body())
        assertEquals("team/service", tagsBody.get("image").asText())
        val tag = tagsBody.get("tags").single()
        assertEquals("1.0.0", tag.get("tag").asText())
        assertEquals(manifestDigest, tag.get("digest").asText())
        assertEquals(OCI_MANIFEST_TYPE, tag.get("mediaType").asText())
        assertTrue(tag.get("size").asLong() > 0, "manifest size should be recorded")
    }

    @Test
    fun `container browse rejects a maven repository`() {
        assertEquals(HTTP_BAD_REQUEST, get("/api/repositories/releases/containers").statusCode())
    }

    @Test
    fun `container browse reports an unknown repository`() {
        assertEquals(HTTP_NOT_FOUND, get("/api/repositories/nope/containers").statusCode())
    }
}
