package org.khorum.oss.relikquary.integration

import com.fasterxml.jackson.databind.JsonNode
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
import java.util.Base64

/**
 * Per-user theme preferences over the wire (feature 019): an authenticated user saves and reads back their
 * own theme (a preset + optional accent), the choice is scoped to the principal (one user never sees
 * another's), malformed themes are rejected `400`, and an anonymous request is challenged `401`. Any
 * authenticated user qualifies — no PUBLISH role required — unlike the `/api/admin` surface.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A distinct user per test (all share one app-state DB, so unique principals keep the tests
    // order-independent — one test's saved theme never bleeds into another's assertions).
    properties = [
        "relikquary.security.enabled=true",
        "relikquary.security.users[0].username=alice",
        "relikquary.security.users[0].password={noop}pw",
        "relikquary.security.users[1].username=bob",
        "relikquary.security.users[1].password={noop}pw",
        "relikquary.security.users[2].username=carol",
        "relikquary.security.users[2].password={noop}pw",
        "relikquary.security.users[3].username=dave",
        "relikquary.security.users[3].password={noop}pw",
        "relikquary.security.users[4].username=erin",
        "relikquary.security.users[4].password={noop}pw",
        "relikquary.security.users[5].username=frank",
        "relikquary.security.users[5].password={noop}pw",
    ],
)
class PreferenceApiTest {

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun uri() = URI.create("http://127.0.0.1:$port/api/me/preferences")
    private fun basic(user: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:pw".toByteArray())

    private fun get(auth: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri()).GET()
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun put(body: String, auth: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri()).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
        auth?.let { builder.header("Authorization", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun theme(response: HttpResponse<String>): JsonNode = json.readTree(response.body()).path("theme")

    @Test
    fun `an anonymous request is rejected with 401`() {
        assertEquals(401, get(null).statusCode())
        assertEquals(401, put("""{"preset":"emerald"}""", null).statusCode())
    }

    @Test
    fun `a fresh user has no theme yet`() {
        val response = get(basic("erin"))
        assertEquals(200, response.statusCode())
        assertTrue(theme(response).isNull, "a user who has never chosen a theme should read back null")
    }

    @Test
    fun `a saved theme round-trips for the same user`() {
        assertEquals(200, put("""{"preset":"emerald","accent":"#112233"}""", basic("alice")).statusCode())

        val saved = theme(get(basic("alice")))
        assertEquals("emerald", saved.path("preset").asText())
        assertEquals("#112233", saved.path("accent").asText())
    }

    @Test
    fun `a preset with no accent is stored with a null accent`() {
        assertEquals(200, put("""{"preset":"slate"}""", basic("bob")).statusCode())

        val saved = theme(get(basic("bob")))
        assertEquals("slate", saved.path("preset").asText())
        assertTrue(saved.path("accent").isNull, "an omitted accent should persist as null")
    }

    @Test
    fun `themes are isolated per user`() {
        assertEquals(200, put("""{"preset":"emerald"}""", basic("carol")).statusCode())
        assertEquals(200, put("""{"preset":"crimson"}""", basic("dave")).statusCode())

        assertEquals("emerald", theme(get(basic("carol"))).path("preset").asText())
        assertEquals("crimson", theme(get(basic("dave"))).path("preset").asText())
    }

    @Test
    fun `a malformed theme is rejected with 400`() {
        assertEquals(400, put("""{"preset":"neon-pink"}""", basic("frank")).statusCode())
        assertEquals(400, put("""{"preset":"emerald","accent":"not-a-color"}""", basic("frank")).statusCode())
    }

    companion object {
        @TempDir
        @JvmStatic
        lateinit var storageRoot: Path

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("relikquary.storage.filesystem.root") { storageRoot.toString() }
            registry.add("relikquary.persistence.sqlite.path") { storageRoot.resolve("prefs.db").toString() }
        }
    }
}
