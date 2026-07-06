package org.khorum.oss.relikquary.container

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A deterministic, in-process upstream OCI registry for proxy tests (feature 018), built on the JDK HTTP
 * server (no dependency, no Docker). It enforces Docker Hub's Bearer-token handshake — an unauthenticated
 * resource request gets a `401` with a `WWW-Authenticate: Bearer` challenge pointing at its own `/token`
 * endpoint, and only a request bearing the issued token is served — so the proxy's token flow (FR-008) is
 * exercised offline and CI-safe. Manifests and blobs are seeded by digest.
 */
class StubOciRegistry {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val manifestsByTag = HashMap<String, Seeded>()
    private val manifestsByDigest = HashMap<String, Seeded>()
    private val blobs = HashMap<String, ByteArray>()

    private class Seeded(val bytes: ByteArray, val mediaType: String, val digest: String)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start(): StubOciRegistry {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    /** Seeds a manifest for [name] reachable by [tag] and by its computed digest; returns the digest. */
    fun seedManifest(name: String, tag: String, bytes: ByteArray, mediaType: String): String {
        val digest = "sha256:${sha256Hex(bytes)}"
        val seeded = Seeded(bytes, mediaType, digest)
        manifestsByTag["$name|$tag"] = seeded
        manifestsByDigest["$name|$digest"] = seeded
        return digest
    }

    /** Seeds a blob for [name]; returns its digest. */
    fun seedBlob(name: String, bytes: ByteArray): String {
        val digest = "sha256:${sha256Hex(bytes)}"
        blobs["$name|$digest"] = bytes
        return digest
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/token" -> respond(exchange, HTTP_OK, TOKEN_BODY.toByteArray(), "application/json")
            path == "/v2/" -> respond(exchange, HTTP_OK, "{}".toByteArray(), "application/json")
            !isAuthorized(exchange) -> challenge(exchange)
            path.contains("/manifests/") -> serveManifest(exchange, path)
            path.contains("/blobs/") -> serveBlob(exchange, path)
            else -> respond(exchange, HTTP_NOT_FOUND, ByteArray(0), "application/json")
        }
        exchange.close()
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean =
        exchange.requestHeaders.getFirst("Authorization") == "Bearer $TOKEN"

    private fun challenge(exchange: HttpExchange) {
        val name = nameOf(exchange.requestURI.path)
        exchange.responseHeaders.add(
            "WWW-Authenticate",
            """Bearer realm="$baseUrl/token",service="stub",scope="repository:$name:pull"""",
        )
        respond(exchange, HTTP_UNAUTHORIZED, ByteArray(0), "application/json")
    }

    private fun serveManifest(exchange: HttpExchange, path: String) {
        val name = path.substringAfter("/v2/").substringBefore("/manifests/")
        val ref = path.substringAfter("/manifests/")
        val seeded = manifestsByTag["$name|$ref"] ?: manifestsByDigest["$name|$ref"]
        if (seeded == null) {
            respond(exchange, HTTP_NOT_FOUND, ByteArray(0), "application/json")
            return
        }
        exchange.responseHeaders.add("Docker-Content-Digest", seeded.digest)
        respond(exchange, HTTP_OK, seeded.bytes, seeded.mediaType)
    }

    private fun serveBlob(exchange: HttpExchange, path: String) {
        val name = path.substringAfter("/v2/").substringBefore("/blobs/")
        val digest = path.substringAfter("/blobs/")
        val bytes = blobs["$name|$digest"]
        if (bytes == null) {
            respond(exchange, HTTP_NOT_FOUND, ByteArray(0), "application/json")
            return
        }
        respond(exchange, HTTP_OK, bytes, "application/octet-stream")
    }

    private fun nameOf(path: String): String =
        path.substringAfter("/v2/").substringBefore("/manifests/").substringBefore("/blobs/")

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray, contentType: String) {
        exchange.responseHeaders.add("Content-Type", contentType)
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val TOKEN = "stub-token"
        const val TOKEN_BODY = """{"token":"stub-token"}"""

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
