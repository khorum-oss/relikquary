package org.khorum.oss.relikquary.container.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.container.Digest
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/** A raw upstream manifest response (bytes buffered so the digest/media type can be recorded). */
sealed interface UpstreamManifest {
    data class Found(val bytes: ByteArray, val mediaType: String) : UpstreamManifest
    data object NotFound : UpstreamManifest
    data object Error : UpstreamManifest
}

/** A raw upstream blob response (streamed so a large layer is not buffered). */
sealed interface UpstreamBlob {
    data class Found(val stream: InputStream, val contentLength: Long?) : UpstreamBlob
    data object NotFound : UpstreamBlob
    data object Error : UpstreamBlob
}

/** A raw upstream tags-list response. */
sealed interface UpstreamTags {
    data class Found(val bytes: ByteArray) : UpstreamTags
    data object NotFound : UpstreamTags
    data object Error : UpstreamTags
}

/**
 * Fetches container manifests, blobs, and tag lists from a proxy repository's upstream OCI registry —
 * Docker Hub (`registry-1.docker.io`) by default (feature 018). Performs Docker Hub's Bearer-token
 * handshake: a `401` with `WWW-Authenticate: Bearer …` triggers a scoped pull-token fetch from the
 * indicated token service, and the request is retried with `Authorization: Bearer`. Tokens are cached per
 * image name and transparently re-negotiated on expiry. Official images given without a namespace are
 * normalized to the `library/` namespace. Optional configured Basic credentials (for higher rate limits
 * or permitted private images) are used only against the token service and never surface to clients.
 */
@Component
class ContainerUpstreamClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(ProxySelector.getDefault())
        .connectTimeout(CONNECT_TIMEOUT)
        .build()

    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Cached Bearer tokens keyed by "{base}|{image}" (a pull scope). */
    private val tokens = ConcurrentHashMap<String, String>()

    fun fetchManifest(repo: RepositoryProperties.Repo, imageName: String, reference: String): UpstreamManifest {
        val name = normalize(imageName)
        val uri = URI.create("${base(repo)}/v2/$name/manifests/$reference")
        return try {
            val response = authorizedSend(repo, name, uri, MANIFEST_ACCEPT, HttpResponse.BodyHandlers.ofByteArray())
            when {
                response.statusCode() in OK_RANGE -> UpstreamManifest.Found(
                    response.body(),
                    response.headers().firstValue("content-type").orElse(DOCKER_MANIFEST_V2),
                )
                response.statusCode() in NOT_FOUND_CODES -> UpstreamManifest.NotFound
                else -> {
                    logger.warn { "Container upstream manifest ${repo.name} ($uri) returned ${response.statusCode()}" }
                    UpstreamManifest.Error
                }
            }
        } catch (e: java.io.IOException) {
            logger.warn { "Container upstream manifest fetch failed for ${repo.name} ($uri): ${e.message}" }
            UpstreamManifest.Error
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            UpstreamManifest.Error
        }
    }

    fun fetchBlob(repo: RepositoryProperties.Repo, imageName: String, digest: Digest): UpstreamBlob {
        val name = normalize(imageName)
        val uri = URI.create("${base(repo)}/v2/$name/blobs/${digest.value}")
        return try {
            val response = authorizedSend(repo, name, uri, BLOB_ACCEPT, HttpResponse.BodyHandlers.ofInputStream())
            when {
                response.statusCode() in OK_RANGE -> {
                    val length = response.headers().firstValueAsLong("content-length").takeIf { it.isPresent }?.asLong
                    UpstreamBlob.Found(response.body(), length)
                }
                response.statusCode() in NOT_FOUND_CODES -> {
                    response.body().close()
                    UpstreamBlob.NotFound
                }
                else -> {
                    response.body().close()
                    logger.warn { "Container upstream blob ${repo.name} ($uri) returned ${response.statusCode()}" }
                    UpstreamBlob.Error
                }
            }
        } catch (e: java.io.IOException) {
            logger.warn { "Container upstream blob fetch failed for ${repo.name} ($uri): ${e.message}" }
            UpstreamBlob.Error
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            UpstreamBlob.Error
        }
    }

    fun fetchTags(repo: RepositoryProperties.Repo, imageName: String): UpstreamTags {
        val name = normalize(imageName)
        val uri = URI.create("${base(repo)}/v2/$name/tags/list")
        return try {
            val response = authorizedSend(repo, name, uri, "application/json", HttpResponse.BodyHandlers.ofByteArray())
            when {
                response.statusCode() in OK_RANGE -> UpstreamTags.Found(response.body())
                response.statusCode() in NOT_FOUND_CODES -> UpstreamTags.NotFound
                else -> UpstreamTags.Error
            }
        } catch (e: java.io.IOException) {
            logger.warn { "Container upstream tags fetch failed for ${repo.name} ($uri): ${e.message}" }
            UpstreamTags.Error
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            UpstreamTags.Error
        }
    }

    /**
     * Sends [uri] with the cached Bearer token (if any); on a `401` carrying a Bearer challenge, obtains a
     * fresh scoped token and retries once. The token is cached for subsequent requests to the same image.
     */
    private fun <T> authorizedSend(
        repo: RepositoryProperties.Repo,
        name: String,
        uri: URI,
        accept: String,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val key = "${base(repo)}|$name"
        val first = send(uri, accept, tokens[key], handler)
        if (first.statusCode() != HTTP_UNAUTHORIZED) return first
        val challenge = first.headers().firstValue("www-authenticate").orElse(null) ?: return first
        discardBody(first)
        val token = negotiateToken(repo, name, challenge) ?: return send(uri, accept, null, handler)
        tokens[key] = token
        return send(uri, accept, token, handler)
    }

    private fun <T> send(uri: URI, accept: String, token: String?, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", accept).GET()
        token?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), handler)
    }

    /** Parses the Bearer challenge, fetches a pull-scoped token from its realm, and returns it (or null). */
    private fun negotiateToken(repo: RepositoryProperties.Repo, name: String, challenge: String): String? {
        val params = parseChallenge(challenge)
        val realm = params["realm"] ?: return null
        val service = params["service"]
        val scope = params["scope"] ?: "repository:$name:pull"
        val query = buildString {
            service?.let { append("service=").append(encode(it)).append('&') }
            append("scope=").append(encode(scope))
        }
        val builder = HttpRequest.newBuilder(URI.create("$realm?$query")).timeout(REQUEST_TIMEOUT).GET()
        repo.remoteUsername?.let { user ->
            val basic = Base64.getEncoder().encodeToString("$user:${repo.remotePassword.orEmpty()}".toByteArray())
            builder.header("Authorization", "Basic $basic")
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in OK_RANGE) {
            logger.warn { "Token endpoint $realm for ${repo.name} returned ${response.statusCode()}" }
            return null
        }
        val node = objectMapper.readTree(response.body())
        return node.get("token")?.asText() ?: node.get("access_token")?.asText()
    }

    private fun base(repo: RepositoryProperties.Repo): String =
        (repo.remoteUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_REGISTRY).trimEnd('/')

    /** Docker Hub official images are addressed under `library/`; a single-component name is normalized. */
    private fun normalize(imageName: String): String =
        if (!imageName.contains('/')) "library/$imageName" else imageName

    private companion object {
        const val DEFAULT_REGISTRY = "https://registry-1.docker.io"
        const val DOCKER_MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json"
        val MANIFEST_ACCEPT = listOf(
            DOCKER_MANIFEST_V2,
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.oci.image.index.v1+json",
        ).joinToString(", ")
        const val BLOB_ACCEPT = "application/octet-stream"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        val OK_RANGE = 200..299
        val NOT_FOUND_CODES = setOf(404, 410)
        const val HTTP_UNAUTHORIZED = 401

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun discardBody(response: HttpResponse<*>) {
            (response.body() as? InputStream)?.close()
        }

        /** Parses `Bearer realm="…",service="…",scope="…"` into its comma-separated key="value" params. */
        fun parseChallenge(header: String): Map<String, String> {
            val paramsPart = header.substringAfter("Bearer", "").trim()
            if (paramsPart.isEmpty()) return emptyMap()
            return paramsPart.split(',').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) return@mapNotNull null
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim().trim('"')
                name to value
            }.toMap()
        }
    }
}
