package org.khorum.oss.relikquary.container

import org.khorum.oss.relikquary.repository.RepositoryFormat
import org.khorum.oss.relikquary.repository.RepositoryNotFoundException
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The images in a container repository, plus the repo's kind so the UI can explain proxy behaviour. */
data class ContainerImagesResponse(
    val repository: String,
    val kind: String,
    val images: List<ContainerImageSummary>,
)

/** The tags of one image in a container repository. */
data class ContainerTagsResponse(
    val repository: String,
    val image: String,
    val tags: List<ContainerTagSummary>,
)

/**
 * JSON browse API for container repositories (feature 018), a sibling of [BrowseController] under
 * `/api/repositories/{repo}/containers`. It exposes the stored images and tags for the web UI so a
 * CONTAINER repo can be browsed like a Maven repo. Read-only; the authoritative pull/push surface is the
 * OCI `/v2` API. Non-container repositories are rejected with 400 so the UI never mixes the two browsers.
 */
@RestController
@RequestMapping("/api/repositories/{repo}/containers")
class ContainerBrowseController(
    private val registry: RepositoryRegistry,
    private val browse: ContainerBrowseService,
) {

    @GetMapping
    fun images(@PathVariable repo: String): ContainerImagesResponse {
        val repository = requireContainerRepo(repo)
        return ContainerImagesResponse(repo, repository.kind.name, browse.images(repo))
    }

    @GetMapping("/tags")
    fun tags(@PathVariable repo: String, @RequestParam image: String): ContainerTagsResponse {
        requireContainerRepo(repo)
        return ContainerTagsResponse(repo, image, browse.tags(repo, image))
    }

    @GetMapping("/manifest")
    fun manifest(@PathVariable repo: String, @RequestParam digest: String): ManifestDetail {
        requireContainerRepo(repo)
        return browse.manifestDetail(repo, digest)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such manifest")
    }

    private fun requireContainerRepo(name: String) = registry.require(name).also {
        if (it.format != RepositoryFormat.CONTAINER) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "not a container repository")
        }
    }

    @ExceptionHandler(RepositoryNotFoundException::class)
    fun handleUnknownRepository(e: RepositoryNotFoundException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.message)
}
