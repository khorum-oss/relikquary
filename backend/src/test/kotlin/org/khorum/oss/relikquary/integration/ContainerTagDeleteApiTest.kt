package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Feature 022: deleting a container tag through the browse API. After a `/v2` push of an image under two
 * tags to the hosted `apps` repo, `DELETE …/containers/tags` removes one tag (204) while the manifest stays
 * retrievable by digest and the other tag remains; deleting the last tag drops the image from the hosted
 * image list; a missing tag → 404, a proxy repo → 405, a maven repo → 400. Auth disabled (the delete
 * authorization mapping is covered by RepositoryAuthzRequestMappingTest).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ContainerTagDeleteApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NO_CONTENT = 204
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_NOT_FOUND = 404
        const val OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val IMAGE = "team/service"

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

    private fun delete(path: String): Int =
        http.send(HttpRequest.newBuilder(url(path)).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun pushBlob(image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
        return digest
    }

    private fun putManifest(image: String, ref: String, body: ByteArray): Int {
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/manifests/$ref"))
            .header("Content-Type", OCI_MANIFEST_TYPE)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    /** Pushes an image (config + layer) under [tags], returning the manifest digest. */
    private fun pushImage(vararg tags: String): String {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "a-layer".toByteArray()
        val configDigest = pushBlob(IMAGE, config)
        val layerDigest = pushBlob(IMAGE, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        tags.forEach { assertEquals(HTTP_CREATED, putManifest(IMAGE, it, manifest)) }
        return digestOf(manifest)
    }

    private fun tagNames(): List<String> =
        json.readTree(get("/api/repositories/apps/containers/tags?image=team%2Fservice").body())
            .get("tags").map { it.get("tag").asText() }

    private fun imageNames(): List<String> =
        json.readTree(get("/api/repositories/apps/containers").body()).get("images").map { it.get("name").asText() }

    @Test
    fun `deleting a tag removes the pointer, keeps the manifest, and drops the image on the last tag`() {
        val digest = pushImage("1.0.0", "latest")
        assertTrue(tagNames().containsAll(listOf("1.0.0", "latest")))

        // Delete one tag → 204; it disappears; the manifest is still retrievable by digest; latest remains.
        assertEquals(HTTP_NO_CONTENT, delete("/api/repositories/apps/containers/tags?image=team%2Fservice&tag=1.0.0"))
        val remaining = tagNames()
        assertFalse(remaining.contains("1.0.0"), "deleted tag must be gone")
        assertTrue(remaining.contains("latest"), "other tag must remain")
        assertEquals(HTTP_OK, get("/v2/apps/$IMAGE/manifests/$digest").statusCode(), "manifest retained by digest")
        assertTrue(imageNames().contains(IMAGE), "image still listed while it has a tag")

        // Delete the last tag → the image drops out of the hosted image list (no GC of the manifest).
        assertEquals(HTTP_NO_CONTENT, delete("/api/repositories/apps/containers/tags?image=team%2Fservice&tag=latest"))
        assertFalse(imageNames().contains(IMAGE), "image drops out once its last tag is deleted")
        assertEquals(HTTP_OK, get("/v2/apps/$IMAGE/manifests/$digest").statusCode(), "manifest still retained (no GC)")
    }

    @Test
    fun `deleting a missing tag is 404`() {
        pushImage("1.0.0")
        assertEquals(HTTP_NOT_FOUND, delete("/api/repositories/apps/containers/tags?image=team%2Fservice&tag=nope"))
    }

    @Test
    fun `deleting a tag on a proxy repository is 405`() {
        assertEquals(HTTP_METHOD_NOT_ALLOWED, delete("/api/repositories/dockerhub/containers/tags?image=library%2Falpine&tag=3.20"))
    }

    @Test
    fun `deleting a tag on a maven repository is 400`() {
        assertEquals(HTTP_BAD_REQUEST, delete("/api/repositories/releases/containers/tags?image=x&tag=y"))
    }
}
