package org.khorum.oss.relikquary.container

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Builds the HTTP responses of the container registry surface (feature 018): manifest/blob/tags success
 * responses with the required OCI headers, and the standard OCI error envelope
 * `{"errors":[{"code":…,"message":…}]}`. Shared by the proxy (US1) and hosted (US2) paths so both emit an
 * identical wire shape.
 */
object OciResponses {

    const val DOCKER_DIGEST_HEADER = "Docker-Content-Digest"

    private val objectMapper = ObjectMapper()

    fun manifest(outcome: ManifestOutcome): ResponseEntity<*> = when (outcome) {
        is ManifestOutcome.Found ->
            ResponseEntity.ok()
                .header(DOCKER_DIGEST_HEADER, outcome.digest.value)
                .header(HttpHeaders.CONTENT_TYPE, outcome.mediaType)
                .header(HttpHeaders.CONTENT_LENGTH, outcome.bytes.size.toString())
                .body(outcome.bytes)
        ManifestOutcome.NotFound -> error(HttpStatus.NOT_FOUND, "MANIFEST_UNKNOWN", "manifest unknown")
        ManifestOutcome.UpstreamError -> error(HttpStatus.BAD_GATEWAY, "UNSUPPORTED", "upstream registry error")
    }

    fun blob(outcome: BlobOutcome): ResponseEntity<*> = when (outcome) {
        is BlobOutcome.Found -> {
            val builder = ResponseEntity.ok()
                .header(DOCKER_DIGEST_HEADER, outcome.digest.value)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
            outcome.length?.let { builder.contentLength(it) }
            builder.body(InputStreamResource(outcome.stream))
        }
        BlobOutcome.NotFound -> error(HttpStatus.NOT_FOUND, "BLOB_UNKNOWN", "blob unknown")
        BlobOutcome.UpstreamError -> error(HttpStatus.BAD_GATEWAY, "UNSUPPORTED", "upstream registry error")
    }

    fun tags(outcome: TagsOutcome): ResponseEntity<*> = when (outcome) {
        is TagsOutcome.Found -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(outcome.json)
        TagsOutcome.NotFound -> error(HttpStatus.NOT_FOUND, "NAME_UNKNOWN", "repository name not known")
        TagsOutcome.UpstreamError -> error(HttpStatus.BAD_GATEWAY, "UNSUPPORTED", "upstream registry error")
    }

    fun notImplemented(): ResponseEntity<*> =
        error(HttpStatus.NOT_IMPLEMENTED, "UNSUPPORTED", "hosted container repositories are not yet available")

    fun error(status: HttpStatus, code: String, message: String): ResponseEntity<*> {
        val payload = mapOf("errors" to listOf(mapOf("code" to code, "message" to message)))
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsBytes(payload))
    }
}
