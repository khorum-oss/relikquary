package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Feature 021 (US2): a proxy container repository pull-through round-trip against the running server with
 * real filesystem storage and an in-JVM OCI upstream ([OciStubUpstream]). Proves a cache miss is resolved
 * from the upstream with faithful bytes/digests, and a subsequent pull of the same digest is served from
 * the local cache with the upstream stopped — the offline half of feature 018's proxy promise.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application-container-it.yml"],
)
class ContainerProxyRoundTripTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        const val HTTP_OK = 200
        const val IMAGE_TYPE = "application/vnd.oci.image.manifest.v1+json"
        const val CONFIG_TYPE = "application/vnd.oci.image.config.v1+json"
        const val LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"
        const val IMAGE = "library/demo"
        const val DIGEST_HEADER = "Docker-Content-Digest"

        /** Started at class load so its base URL is known when @DynamicPropertySource runs. */
        @JvmStatic
        val upstream: OciStubUpstream = OciStubUpstream().start()

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            // Resolves the `${TEST_OCI_UPSTREAM:…}` placeholder for the `mirror` proxy's remoteUrl at bind
            // time, pointing its upstream at the in-JVM stub (the whole list still binds from the yml).
            registry.add("TEST_OCI_UPSTREAM") { upstream.baseUrl }
        }
    }

    @Test
    fun `proxy serves a pull-through on cache miss then from cache with the upstream stopped`() {
        // Seed the upstream with a config blob, a layer blob, and a manifest referencing them.
        val config = """{"architecture":"amd64","os":"linux"}""".toByteArray()
        val layer = "proxied-layer-bytes".toByteArray()
        val configDigest = upstream.seedBlob(IMAGE, config)
        val layerDigest = upstream.seedBlob(IMAGE, layer)
        val manifest = (
            """{"schemaVersion":2,"mediaType":"$IMAGE_TYPE",""" +
                """"config":{"mediaType":"$CONFIG_TYPE","digest":"$configDigest","size":${config.size}},""" +
                """"layers":[{"mediaType":"$LAYER_TYPE","digest":"$layerDigest","size":${layer.size}}]}"""
            ).toByteArray()
        val manifestDigest = upstream.seedManifest(IMAGE, "1.0", manifest, IMAGE_TYPE)

        // --- Cache MISS: pull the tag through the proxy; resolved from the upstream, faithful bytes/digest ---
        val miss = getManifest("1.0")
        assertEquals(HTTP_OK, miss.statusCode())
        assertArrayEquals(manifest, miss.body(), "proxied manifest must be byte-identical to the upstream's")
        assertEquals(manifestDigest, miss.headers().firstValue(DIGEST_HEADER).orElse(""))
        // Pull both blobs through the proxy so they are cached (the tee caches on full read).
        assertArrayEquals(config, getBlob(configDigest).body())
        assertArrayEquals(layer, getBlob(layerDigest).body())

        // --- Cache HIT: stop the upstream; the same digests are still served from the local cache ---
        upstream.stop()
        val hitManifest = getManifest(manifestDigest)
        assertEquals(HTTP_OK, hitManifest.statusCode(), "cached manifest must be served without the upstream")
        assertArrayEquals(manifest, hitManifest.body())
        assertArrayEquals(config, getBlob(configDigest).body(), "cached blob must be served without the upstream")
        assertArrayEquals(layer, getBlob(layerDigest).body())
    }

    private fun url(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun getManifest(ref: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(url("/v2/mirror/$IMAGE/manifests/$ref")).header("Accept", IMAGE_TYPE).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

    private fun getBlob(digest: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(url("/v2/mirror/$IMAGE/blobs/$digest")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
}
