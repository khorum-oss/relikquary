package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.container.proxy.ContainerUpstreamClient
import org.khorum.oss.relikquary.container.proxy.UpstreamBlob
import org.khorum.oss.relikquary.container.proxy.UpstreamManifest
import org.khorum.oss.relikquary.repository.RepositoryFormat
import org.khorum.oss.relikquary.repository.RepositoryKind

/**
 * Unit coverage for [ContainerUpstreamClient] (feature 018): the Bearer-token handshake against a stub
 * that challenges unauthenticated requests, `library/` normalization of official-image names, and the
 * NotFound / Error mappings. No Spring context — the client is exercised directly.
 */
class ContainerUpstreamClientTest {

    private val client = ContainerUpstreamClient()

    companion object {
        private val stub = StubOciRegistry().start()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            runCatching { stub.stop() }
        }
    }

    private fun repo(url: String?) = RepositoryProperties.Repo(
        name = "dockerhub",
        kind = RepositoryKind.PROXY,
        format = RepositoryFormat.CONTAINER,
        remoteUrl = url,
    )

    @Test
    fun `performs the bearer handshake and normalizes an official image to library`() {
        val bytes = """{"schemaVersion":2}""".toByteArray()
        stub.seedManifest("library/alpine", "3.20", bytes, "application/vnd.oci.image.manifest.v1+json")

        // "alpine" has no namespace → normalized to library/alpine; the stub 401s then serves after token.
        val response = client.fetchManifest(repo(stub.baseUrl), "alpine", "3.20")
        val found = assertInstanceOf(UpstreamManifest.Found::class.java, response)
        assertArrayEquals(bytes, found.bytes)
    }

    @Test
    fun `fetches a blob after authenticating`() {
        val blob = "layer-bytes".toByteArray()
        val digest = Digest.of(blob)
        stub.seedBlob("library/busybox", blob)

        val response = client.fetchBlob(repo(stub.baseUrl), "busybox", digest)
        val found = assertInstanceOf(UpstreamBlob.Found::class.java, response)
        assertArrayEquals(blob, found.stream.use { it.readBytes() })
    }

    @Test
    fun `maps an absent manifest to NotFound`() {
        val response = client.fetchManifest(repo(stub.baseUrl), "library/nope", "0.0.1")
        assertInstanceOf(UpstreamManifest.NotFound::class.java, response)
    }

    @Test
    fun `maps an unreachable upstream to Error`() {
        // Port 1 is not listening → connection refused → Error (not NotFound).
        val response = client.fetchManifest(repo("http://127.0.0.1:1"), "library/alpine", "3.20")
        assertInstanceOf(UpstreamManifest.Error::class.java, response)
    }
}
