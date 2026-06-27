package org.khorum.oss.relikquary.integration

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
import java.util.Base64

/**
 * DELETE on the browse API requires the PUBLISH role when auth is enabled (feature 005, FR-005).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "relikquary.security.enabled=true",
        "relikquary.security.users[0].username=ci",
        "relikquary.security.users[0].password={noop}secret",
        "relikquary.security.users[0].roles[0]=PUBLISH",
    ],
)
class BrowseDeleteAuthTest {

    @LocalServerPort
    var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun storageProps(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
        }
    }

    private val path = "com/example/widget/1.0.0/widget-1.0.0.jar"
    private fun basic(u: String, p: String) = "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray())

    private fun publish() {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/releases/$path"))
            .header("Authorization", basic("ci", "secret"))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(byteArrayOf(9, 9, 9)))
            .build()
        assertEquals(201, http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode())
    }

    private fun delete(auth: String?): Int {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/repositories/releases/$path")).DELETE()
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `delete requires credentials then succeeds`() {
        publish()
        assertEquals(401, delete(null))
        assertEquals(204, delete(basic("ci", "secret")))
    }
}
