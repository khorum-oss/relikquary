package org.khorum.oss.relikquary.container

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.khorum.oss.relikquary.config.RepositoryProperties
import org.khorum.oss.relikquary.container.proxy.ContainerProxyService
import org.khorum.oss.relikquary.repository.RepositoryFormat
import org.khorum.oss.relikquary.repository.RepositoryKind
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val logger = KotlinLogging.logger {}

/**
 * The container registry (OCI Distribution / Docker Registry V2) surface under `/v2` (feature 018). The
 * first path segment after `/v2/` is the Relikquary repository name; the remainder is the OCI image name
 * plus the operation. Dispatches by the repository's format+kind: a PROXY container repo serves through
 * the [ContainerProxyService] pull-through cache; a HOSTED container repo (push/pull) arrives in a later
 * slice (US2) and currently answers `501`. The bare `GET /v2/` version check advertises V2 support.
 *
 * The `/v2` wildcard mapping is more specific than the Maven controller's catch-all `/` mapping, so
 * container requests never disturb Maven serving.
 */
@RestController
class ContainerRegistryController(
    private val registry: RepositoryRegistry,
    private val proxy: ContainerProxyService,
) {

    @RequestMapping(path = ["/v2", "/v2/"], method = [RequestMethod.GET, RequestMethod.HEAD])
    fun version(): ResponseEntity<*> =
        ResponseEntity.ok()
            .header(DOCKER_API_VERSION_HEADER, "registry/2.0")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}".toByteArray())

    @RequestMapping(path = ["/v2/**"], method = [RequestMethod.GET, RequestMethod.HEAD])
    fun read(request: HttpServletRequest): ResponseEntity<*> {
        val rest = pathAfterV2(request)
        if (rest.isEmpty()) return version()
        val ref = ImageReference.parse(rest)
        val repo = requireContainerRepo(ref.repository)
        return when (repo.kind) {
            RepositoryKind.PROXY -> proxyRead(repo, ref)
            RepositoryKind.HOSTED -> OciResponses.notImplemented()
            RepositoryKind.GROUP -> OciResponses.error(HttpStatus.NOT_FOUND, "NAME_UNKNOWN", "unknown repository")
        }
    }

    @RequestMapping(
        path = ["/v2/**"],
        method = [RequestMethod.POST, RequestMethod.PATCH, RequestMethod.PUT, RequestMethod.DELETE],
    )
    fun write(request: HttpServletRequest): ResponseEntity<*> {
        val ref = ImageReference.parse(pathAfterV2(request))
        val repo = requireContainerRepo(ref.repository)
        return when (repo.kind) {
            RepositoryKind.PROXY ->
                OciResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED", "push to a proxy repository is not allowed")
            RepositoryKind.HOSTED -> OciResponses.notImplemented()
            RepositoryKind.GROUP -> OciResponses.error(HttpStatus.NOT_FOUND, "NAME_UNKNOWN", "unknown repository")
        }
    }

    private fun proxyRead(repo: RepositoryProperties.Repo, ref: ImageReference): ResponseEntity<*> =
        when (ref.operation) {
            ContainerOperation.MANIFEST -> OciResponses.manifest(proxy.getManifest(repo, ref.imageName, ref.reference))
            ContainerOperation.BLOB -> OciResponses.blob(proxy.getBlob(repo, ref.imageName, Digest.parse(ref.reference)))
            ContainerOperation.TAGS_LIST -> OciResponses.tags(proxy.listTags(repo, ref.imageName))
            ContainerOperation.BLOB_UPLOAD ->
                OciResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "UNSUPPORTED", "push to a proxy repository is not allowed")
        }

    private fun requireContainerRepo(name: String): RepositoryProperties.Repo {
        val repo = registry.require(name)
        if (repo.format != RepositoryFormat.CONTAINER) throw RepositoryNotFoundException(name)
        return repo
    }

    private fun pathAfterV2(request: HttpServletRequest): String {
        val decoded = URLDecoder.decode(request.requestURI.removePrefix(request.contextPath), StandardCharsets.UTF_8)
        return decoded.trimStart('/').removePrefix("v2").trimStart('/')
    }

    @ExceptionHandler(InvalidImageReferenceException::class)
    fun handleInvalidReference(e: InvalidImageReferenceException): ResponseEntity<*> {
        logger.debug { "Rejected invalid image reference: ${e.message}" }
        return OciResponses.error(HttpStatus.BAD_REQUEST, "NAME_INVALID", e.message ?: "invalid image reference")
    }

    @ExceptionHandler(InvalidDigestException::class)
    fun handleInvalidDigest(e: InvalidDigestException): ResponseEntity<*> {
        logger.debug { "Rejected invalid digest: ${e.message}" }
        return OciResponses.error(HttpStatus.BAD_REQUEST, "DIGEST_INVALID", e.message ?: "invalid digest")
    }

    @ExceptionHandler(RepositoryNotFoundException::class)
    fun handleUnknownRepository(e: RepositoryNotFoundException): ResponseEntity<*> {
        logger.debug { "Rejected unknown container repository: ${e.message}" }
        return OciResponses.error(HttpStatus.NOT_FOUND, "NAME_UNKNOWN", e.message ?: "unknown repository")
    }

    private companion object {
        const val DOCKER_API_VERSION_HEADER = "Docker-Distribution-API-Version"
    }
}
