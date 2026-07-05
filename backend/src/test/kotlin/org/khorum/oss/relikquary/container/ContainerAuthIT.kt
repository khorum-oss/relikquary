package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Base64

/**
 * Per-repository authorization for a HOSTED container repo with security enabled (feature 018, US3, SC-006):
 * an anonymous `docker push` (blob upload POST) is challenged with `WWW-Authenticate: Basic`, an
 * authenticated publisher's push is accepted, and reads stay open (the version check and an unknown-manifest
 * GET are not challenged). This reuses the existing per-repo authorization (feature 007) via the registry's
 * Basic credential path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("container-authz")
class ContainerAuthIT {

    @LocalServerPort
    var port: Int = 0

    private val client: HttpClient = HttpClient.newHttpClient()

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
    }

    @Test
    fun `anonymous push is challenged, publisher push is accepted, reads stay open`() {
        // The version check is open even anonymously.
        assertEquals(HTTP_OK, get("/v2/", null).statusCode())

        // Anonymous push (start a blob upload) is challenged with a Basic realm.
        val anon = post("/v2/containers/team/app/blobs/uploads/", null)
        assertEquals(HTTP_UNAUTHORIZED, anon.statusCode())
        assertTrue(anon.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic")) {
            "expected a Basic challenge, got ${anon.headers().firstValue("WWW-Authenticate")}"
        }

        // A user WITHOUT the PUBLISH role is forbidden.
        assertEquals(HTTP_FORBIDDEN, post("/v2/containers/team/app/blobs/uploads/", basic("reader", "pw")).statusCode())

        // The publisher can start the upload (202 Accepted, not challenged).
        assertEquals(HTTP_ACCEPTED, post("/v2/containers/team/app/blobs/uploads/", basic("publisher", "pw")).statusCode())

        // Reads are open: an unknown manifest returns 404 (not a 401 challenge) without credentials.
        assertEquals(HTTP_NOT_FOUND, get("/v2/containers/team/app/manifests/1.0", null).statusCode())
    }

    private fun base() = "http://127.0.0.1:$port"

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun get(path: String, authorization: String?): HttpResponse<Void> =
        client.send(authorized(HttpRequest.newBuilder(URI.create(base() + path)).GET(), authorization),
            HttpResponse.BodyHandlers.discarding())

    private fun post(path: String, authorization: String?): HttpResponse<Void> =
        client.send(
            authorized(HttpRequest.newBuilder(URI.create(base() + path)).POST(HttpRequest.BodyPublishers.noBody()), authorization),
            HttpResponse.BodyHandlers.discarding(),
        )

    private fun authorized(builder: HttpRequest.Builder, authorization: String?): HttpRequest {
        authorization?.let { builder.header("Authorization", it) }
        return builder.build()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
