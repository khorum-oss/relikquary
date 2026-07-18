package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.Base64

/**
 * Feature 023 (US2): container catalog rows respect per-repository READ authorization (feature 007). Against
 * a standalone topology (an open container repo + an alice-only private container repo), a user who cannot
 * read the private repo never sees its images in the catalog, while a permitted user does — the same
 * privacy contract the Maven catalog already honors.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-catalog-it.yml"],
)
class CatalogContainerAuthzTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    companion object {
        const val OCI_MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val HTTP_CREATED = 201

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

    private fun basic(user: String) = "Basic " + Base64.getEncoder().encodeToString("$user:pw".toByteArray())

    private fun catalogRepos(user: String): List<String> {
        val req = HttpRequest.newBuilder(url("/api/catalog?pageSize=500"))
            .header("Authorization", basic(user)).GET().build()
        val body = json.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body())
        return body["entries"].map { it["repository"].asText() }
    }

    private fun pushImage(repo: String, image: String, tag: String, user: String) {
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "layer-$repo-$image".toByteArray()
        val configDigest = pushBlob(repo, image, config, user)
        val layerDigest = pushBlob(repo, image, layer, user)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$OCI_MANIFEST_TYPE",""" +
                """"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/manifests/$tag"))
            .header("Content-Type", OCI_MANIFEST_TYPE).header("Authorization", basic(user))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(manifest)).build()
        assertTrue(http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_CREATED)
    }

    private fun pushBlob(repo: String, image: String, bytes: ByteArray, user: String): String {
        val digest = digestOf(bytes)
        val req = HttpRequest.newBuilder(url("/v2/$repo/$image/blobs/uploads/?digest=$digest"))
            .header("Content-Type", "application/octet-stream").header("Authorization", basic(user))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        assertTrue(http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_CREATED)
        return digest
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    @Test
    fun `container catalog entries respect per-repo read authorization`() {
        pushImage("openimg", "team/open", "1.0.0", "alice")
        pushImage("privimg", "team/priv", "1.0.0", "alice")

        // bob cannot read the private repo: he sees the open repo's images but not the private repo's.
        val bobRepos = catalogRepos("bob")
        assertTrue(bobRepos.contains("openimg"), "bob sees the open container repo's images")
        assertFalse(bobRepos.contains("privimg"), "bob must not see the private container repo's images")

        // alice can read both.
        val aliceRepos = catalogRepos("alice")
        assertTrue(aliceRepos.contains("openimg"))
        assertTrue(aliceRepos.contains("privimg"), "alice sees the private repo's images")
    }
}
