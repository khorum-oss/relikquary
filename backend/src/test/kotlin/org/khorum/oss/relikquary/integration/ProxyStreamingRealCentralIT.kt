package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Automates the previously-manual streaming-cache smoke check against **real Maven Central** (feature 015,
 * T023 / quickstart "Manual smoke"): a cold-cache resolve through the `maven-central` proxy returns bytes
 * byte-identical to the upstream, a cache entry is written, and a second resolve is served from that cache.
 *
 * Guarded to auto-skip when Maven Central is unreachable (offline / egress-restricted CI), mirroring the
 * guarded real-Central round-trip (spec 006) and the s3mock + MinIO split (spec 003). The deterministic,
 * always-on coverage of the same behaviour lives in [ProxyStreamingCacheIT] against the in-process stub;
 * this adds the real-upstream, real-checksum confirmation the manual step used to provide.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
class ProxyStreamingRealCentralIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var storage: ArtifactStorage

    // Route through the JVM's configured proxy, exactly as the app's UpstreamClient does.
    private val http: HttpClient = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build()

    companion object {
        // A small, stable, permanently-published coordinate (a POM — an immutable artifact file, so it is
        // cached like any non-metadata file).
        private const val COORD = "org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom"
        private const val CENTRAL_BASE = "https://repo1.maven.org/maven2"
        private const val HTTP_OK = 200
        private const val POLL_MS = 50L

        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("relikquary.persistence.sqlite.path") { storageRoot.resolve("test.db").toString() }
            // Intentionally NOT overriding RELIKQUARY_MAVEN_CENTRAL_URL — this resolves against real Central.
        }

        private fun centralReachable(): Boolean = runCatching {
            val probe = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            probe.send(
                HttpRequest.newBuilder(URI.create("$CENTRAL_BASE/$COORD")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode() == HTTP_OK
        }.getOrDefault(false)
    }

    @Test
    fun `cold-cache resolve through the maven-central proxy is byte-identical to upstream and served from cache`() {
        assumeTrue(centralReachable(), "Maven Central unreachable — skipping real streaming-cache smoke")

        val upstream = http.send(
            HttpRequest.newBuilder(URI.create("$CENTRAL_BASE/$COORD")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assumeTrue(upstream.statusCode() == HTTP_OK, "upstream fetch did not return 200")
        val upstreamBytes = upstream.body()

        // Cold-cache resolve through the proxy — byte-identical to the upstream (the manual checksum match).
        val first = getBytes("/maven-central/$COORD")
        assertEquals(HTTP_OK, first.statusCode())
        assertArrayEquals(upstreamBytes, first.body(), "proxy-resolved bytes differ from upstream")

        // The tee commits the cache just after the response completes; wait for the entry to exist.
        val key = "maven-central/$COORD"
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!storage.exists(key) && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertTrue(storage.exists(key)) { "cold-cache resolve did not write a cache entry" }

        // Second resolve is served from the local cache, still byte-identical.
        val second = getBytes("/maven-central/$COORD")
        assertEquals(HTTP_OK, second.statusCode())
        assertArrayEquals(upstreamBytes, second.body(), "cached bytes differ from upstream")
    }

    private fun getBytes(path: String): HttpResponse<ByteArray> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
}
