package org.khorum.oss.relikquary.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A deterministic, in-process OCI / Docker Registry V2 upstream for the container proxy round-trip
 * (feature 021), built on the JDK HTTP server (no dependency) exactly like the Maven [StubUpstream]. It
 * serves the registry read surface with no auth — it answers 200 directly, so the proxy's
 * `ContainerUpstreamClient` needs no bearer-token handshake — and can be [stop]ped to prove the proxy cache
 * serves a previously-pulled digest without the upstream.
 *
 * Endpoints: `GET /v2/` (version), `GET /v2/{name}/manifests/{ref}` (by tag or digest),
 * `GET /v2/{name}/blobs/{digest}`, `GET /v2/{name}/tags/list`.
 */
class OciStubUpstream {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val manifestsByRef = HashMap<String, Seeded>()
    private val blobs = HashMap<String, ByteArray>()
    private val tagsByImage = HashMap<String, MutableList<String>>()

    private class Seeded(val bytes: ByteArray, val mediaType: String, val digest: String)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start(): OciStubUpstream {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    /** Seeds a manifest for [name] retrievable both by [tag] and by its digest; records the tag. */
    fun seedManifest(name: String, tag: String, bytes: ByteArray, mediaType: String): String {
        val digest = digestOf(bytes)
        val seeded = Seeded(bytes, mediaType, digest)
        manifestsByRef["$name|$tag"] = seeded
        manifestsByRef["$name|$digest"] = seeded
        tagsByImage.getOrPut(name) { mutableListOf() }.add(tag)
        return digest
    }

    /** Seeds a blob for [name] retrievable by its digest. */
    fun seedBlob(name: String, bytes: ByteArray): String {
        val digest = digestOf(bytes)
        blobs["$name|$digest"] = bytes
        return digest
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/v2/" || path == "/v2" -> version(exchange)
            path.endsWith("/tags/list") -> tags(exchange, imageOf(path, "/tags/list"))
            path.contains("/manifests/") -> manifest(exchange, path)
            path.contains("/blobs/") -> blob(exchange, path)
            else -> respond(exchange, HTTP_NOT_FOUND, ByteArray(0), null)
        }
    }

    private fun version(exchange: HttpExchange) {
        exchange.responseHeaders.add(DOCKER_API_VERSION_HEADER, "registry/2.0")
        respond(exchange, HTTP_OK, "{}".toByteArray(), "application/json")
    }

    private fun manifest(exchange: HttpExchange, path: String) {
        val name = imageOf(path, "/manifests/")
        val ref = path.substringAfterLast("/manifests/")
        val seeded = manifestsByRef["$name|$ref"]
        if (seeded == null) {
            respond(exchange, HTTP_NOT_FOUND, ByteArray(0), null)
            return
        }
        exchange.responseHeaders.add(DOCKER_DIGEST_HEADER, seeded.digest)
        respond(exchange, HTTP_OK, seeded.bytes, seeded.mediaType)
    }

    private fun blob(exchange: HttpExchange, path: String) {
        val name = imageOf(path, "/blobs/")
        val digest = path.substringAfterLast("/blobs/")
        val bytes = blobs["$name|$digest"]
        if (bytes == null) {
            respond(exchange, HTTP_NOT_FOUND, ByteArray(0), null)
            return
        }
        exchange.responseHeaders.add(DOCKER_DIGEST_HEADER, digest)
        respond(exchange, HTTP_OK, bytes, "application/octet-stream")
    }

    private fun tags(exchange: HttpExchange, name: String) {
        val list = tagsByImage[name].orEmpty().joinToString(",") { "\"$it\"" }
        respond(exchange, HTTP_OK, """{"name":"$name","tags":[$list]}""".toByteArray(), "application/json")
    }

    /** The image name between `/v2/` and [op] (may contain slashes). */
    private fun imageOf(path: String, op: String): String = path.substringAfter("/v2/").substringBefore(op)

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray, contentType: String?) {
        contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
    }

    private fun digestOf(bytes: ByteArray): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val DOCKER_DIGEST_HEADER = "Docker-Content-Digest"
        const val DOCKER_API_VERSION_HEADER = "Docker-Distribution-API-Version"
    }
}
