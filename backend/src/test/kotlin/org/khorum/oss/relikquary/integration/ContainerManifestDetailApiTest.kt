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
 * Container manifest detail browse API (feature 020). After a `docker push`-shaped upload to the hosted
 * `apps` repo via `/v2`, the `…/containers/manifest?digest=` endpoint returns the parsed projection: a
 * single-platform image's config + ordered layers + total size, and a manifest list's platform entries with
 * drill-in into a platform's own layers. Also covers the unknown-shape, absent-digest (404), and
 * maven-repo (400) branches. Auth disabled to focus on the projection.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ContainerManifestDetailApiTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val IMAGE_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val INDEX_TYPE = "application/vnd.oci.image.index.v1+json"
        const val CONFIG_TYPE = "application/vnd.oci.image.config.v1+json"
        const val LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"
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

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun pushBlob(image: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertEquals(HTTP_CREATED, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
        return digest
    }

    private fun putManifest(image: String, ref: String, body: ByteArray, mediaType: String): Int {
        val req = HttpRequest.newBuilder(url("/v2/apps/$image/manifests/$ref"))
            .header("Content-Type", mediaType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    /** Pushes a config blob + one layer blob and the image manifest (by its digest) that references them. */
    private fun pushImageManifest(image: String, arch: String): Pair<ByteArray, String> {
        val config = """{"architecture":"$arch","os":"linux"}""".toByteArray()
        val layer = "fake-layer-$image-$arch".toByteArray()
        val configDigest = pushBlob(image, config)
        val layerDigest = pushBlob(image, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$IMAGE_TYPE",""" +
                """"config":{"mediaType":"$CONFIG_TYPE","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"$LAYER_TYPE","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        val digest = digestOf(manifest)
        assertEquals(HTTP_CREATED, putManifest(image, digest, manifest, IMAGE_TYPE))
        return manifest to digest
    }

    @Test
    fun `image manifest detail lists config, ordered layers, and total size`() {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layerOne = "layer-one".toByteArray()
        val layerTwo = "layer-two-bigger".toByteArray()
        val configDigest = pushBlob("svc", config)
        val oneDigest = pushBlob("svc", layerOne)
        val twoDigest = pushBlob("svc", layerTwo)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$IMAGE_TYPE",""" +
                """"config":{"mediaType":"$CONFIG_TYPE","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"$LAYER_TYPE","digest":"$oneDigest","size":${layerOne.size}},""" +
                """{"mediaType":"$LAYER_TYPE","digest":"$twoDigest","size":${layerTwo.size}}]}"""
            ).toByteArray()
        assertEquals(HTTP_CREATED, putManifest("svc", "1.0.0", manifest, IMAGE_TYPE))

        val res = get("/api/repositories/apps/containers/manifest?digest=${digestOf(manifest)}")
        assertEquals(HTTP_OK, res.statusCode())
        val body = json.readTree(res.body())
        assertEquals("image", body.get("kind").asText())
        assertEquals(configDigest, body.get("config").get("digest").asText())
        val layers = body.get("layers")
        assertEquals(2, layers.size())
        assertEquals(oneDigest, layers[0].get("digest").asText(), "layer order must be preserved")
        assertEquals(twoDigest, layers[1].get("digest").asText())
        assertEquals(layerTwo.size.toLong(), layers[1].get("size").asLong())
        assertTrue(layers[0].get("present").asBoolean(), "pushed layer should be present locally")
        val expectedTotal = (config.size + layerOne.size + layerTwo.size).toLong()
        assertEquals(expectedTotal, body.get("totalSize").asLong())
    }

    @Test
    fun `image index detail lists platforms and drills into a platform's layers`() {
        val (amdBytes, amdDigest) = pushImageManifest("multi", "amd64")
        val (armBytes, armDigest) = pushImageManifest("multi", "arm64")
        val index = (
            """{"schemaVersion":2,"mediaType":"$INDEX_TYPE","manifests":[""" +
                """{"mediaType":"$IMAGE_TYPE","digest":"$amdDigest","size":${amdBytes.size},""" +
                """"platform":{"os":"linux","architecture":"amd64"}},""" +
                """{"mediaType":"$IMAGE_TYPE","digest":"$armDigest","size":${armBytes.size},""" +
                """"platform":{"os":"linux","architecture":"arm64","variant":"v8"}}]}"""
            ).toByteArray()
        assertEquals(HTTP_CREATED, putManifest("multi", "1.0.0", index, INDEX_TYPE))

        val res = get("/api/repositories/apps/containers/manifest?digest=${digestOf(index)}")
        assertEquals(HTTP_OK, res.statusCode())
        val body = json.readTree(res.body())
        assertEquals("index", body.get("kind").asText())
        val entries = body.get("manifests")
        assertEquals(2, entries.size())
        val arm = entries.first { it.get("digest").asText() == armDigest }
        assertEquals("arm64", arm.get("platform").get("architecture").asText())
        assertEquals("v8", arm.get("platform").get("variant").asText())
        assertTrue(arm.get("present").asBoolean(), "the sub-manifest was pushed, so it is present")

        // Drill into the amd64 platform's own manifest.
        val platform = get("/api/repositories/apps/containers/manifest?digest=$amdDigest")
        assertEquals(HTTP_OK, platform.statusCode())
        val platformBody = json.readTree(platform.body())
        assertEquals("image", platformBody.get("kind").asText())
        assertEquals(1, platformBody.get("layers").size())
    }

    @Test
    fun `an unrecognized manifest shape is reported as unknown, not an error`() {
        val odd = """{"schemaVersion":2,"mediaType":"$IMAGE_TYPE"}""".toByteArray()
        assertEquals(HTTP_CREATED, putManifest("weird", "x", odd, IMAGE_TYPE))
        val body = json.readTree(get("/api/repositories/apps/containers/manifest?digest=${digestOf(odd)}").body())
        assertEquals("unknown", body.get("kind").asText())
        assertFalse(body.has("layers") && !body.get("layers").isNull, "unknown carries no layer breakdown")
    }

    @Test
    fun `an absent digest is a 404`() {
        val absent = "sha256:" + "0".repeat(SHA256_HEX_LENGTH)
        assertEquals(HTTP_NOT_FOUND, get("/api/repositories/apps/containers/manifest?digest=$absent").statusCode())
    }

    @Test
    fun `manifest detail rejects a maven repository`() {
        val any = "sha256:" + "0".repeat(SHA256_HEX_LENGTH)
        assertEquals(HTTP_BAD_REQUEST, get("/api/repositories/releases/containers/manifest?digest=$any").statusCode())
    }
}
