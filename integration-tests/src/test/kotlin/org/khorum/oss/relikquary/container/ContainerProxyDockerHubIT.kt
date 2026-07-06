package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
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
 * A REAL Docker Hub pull-through of a small official image through the `dockerhub` proxy (feature 018),
 * guarded to auto-skip when Docker Hub is unreachable (offline / egress-restricted CI) — mirroring the
 * guarded real-Maven-Central test (spec 006) and MinIO test (spec 003). When it runs it proves the live
 * Bearer-token handshake, `library/` normalization, and manifest-index passthrough end-to-end (SC-001).
 *
 * This uses the default upstream (registry-1.docker.io); RELIKQUARY_DOCKERHUB_URL is NOT overridden.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["relikquary.security.enabled=false"],
)
@ActiveProfiles("container")
class ContainerProxyDockerHubIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { tempDir.resolve("store").toString() }
            registry.add("relikquary.persistence.sqlite.path") { tempDir.resolve("test.db").toString() }
        }

        private fun dockerHubReachable(): Boolean = runCatching {
            val probe = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val response = probe.send(
                HttpRequest.newBuilder(URI.create("https://registry-1.docker.io/v2/"))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            // 200 or 401 (needs auth) both mean the registry answered.
            response.statusCode() == 200 || response.statusCode() == 401
        }.getOrDefault(false)
    }

    @Test
    fun `pulls the alpine manifest through the proxy from real Docker Hub`() {
        assumeTrue(dockerHubReachable(), "Docker Hub unreachable — skipping real pull-through test")

        val manifest = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v2/dockerhub/library/alpine/manifests/latest"))
                .header("Accept", "application/vnd.oci.image.index.v1+json," +
                    "application/vnd.docker.distribution.manifest.list.v2+json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

        assertEquals(200, manifest.statusCode()) { "expected a manifest from Docker Hub through the proxy" }
        assertTrue(manifest.headers().firstValue("Docker-Content-Digest").isPresent) {
            "manifest response should carry a Docker-Content-Digest"
        }
        assertTrue(manifest.body().isNotEmpty()) { "manifest body should not be empty" }
    }
}
