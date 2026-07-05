package org.khorum.oss.relikquary.container

/** Thrown when a `/v2/…` path is not a valid registry request (⇒ 400 NAME_INVALID / UNSUPPORTED). */
class InvalidImageReferenceException(message: String) : RuntimeException(message)

/** The registry operation a `/v2/{repo}/…` path addresses (feature 018). */
enum class ContainerOperation { MANIFEST, BLOB, BLOB_UPLOAD, TAGS_LIST }

/**
 * A parsed OCI Distribution request path (feature 018). The path handed in is everything after `/v2/`;
 * its first segment is the Relikquary [repository] name, and the remainder is the OCI [imageName] (which
 * may contain slashes) plus the [operation] and its [reference].
 *
 * Grammar is validated on construction so path traversal and malformed names are rejected before any
 * storage access (FR-017): each name component matches the OCI grammar, and no component is a `.`/`..`
 * traversal segment, a backslash, or a control character.
 */
data class ImageReference(
    val repository: String,
    val imageName: String,
    val operation: ContainerOperation,
    /** Manifest: a tag or a digest. Blob: a digest. Blob upload: the session uuid (or empty for start). Tags: unused. */
    val reference: String,
) {

    /** Whether [reference] is a `sha256:<hex>` digest rather than a tag. */
    fun referenceIsDigest(): Boolean = Digest.isDigest(reference)

    companion object {
        private const val MIN_PRINTABLE = 0x20
        private val NAME_COMPONENT = Regex("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*")
        private val TAG = Regex("[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}")

        /**
         * Parses the path after `/v2/` (no leading slash). Parsing is right-anchored on the operation
         * verb so image names containing slashes are handled: `library/alpine/manifests/3.20`,
         * `team/app/blobs/sha256:…`, `team/app/blobs/uploads[/uuid]`, `name/tags/list`.
         */
        fun parse(pathAfterV2: String): ImageReference {
            val segments = pathAfterV2.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.size < MIN_SEGMENTS) fail("not a registry request: /v2/$pathAfterV2")
            val repository = segments.first()

            val (op, verbIndex, reference) = classify(segments)
            val imageName = segments.subList(1, verbIndex).joinToString("/")
            if (imageName.isEmpty()) fail("missing image name: /v2/$pathAfterV2")
            validateName(imageName)
            if (op == ContainerOperation.MANIFEST) validateManifestRef(reference)
            if (op == ContainerOperation.BLOB && !Digest.isDigest(reference)) fail("blob reference must be a digest")
            return ImageReference(repository, imageName, op, reference)
        }

        /** Locates the operation verb from the right and returns (operation, verbIndex, reference). */
        private fun classify(segments: List<String>): Triple<ContainerOperation, Int, String> {
            val last = segments.size - 1
            val secondLast = segments.size - 2
            return when {
                segments[secondLast] == "tags" && segments[last] == "list" ->
                    Triple(ContainerOperation.TAGS_LIST, secondLast, "")
                segments[secondLast] == "blobs" && segments[last] == "uploads" ->
                    Triple(ContainerOperation.BLOB_UPLOAD, secondLast, "")
                segments.size >= UPLOAD_WITH_ID_SEGMENTS &&
                    segments[segments.size - UPLOAD_WITH_ID_OFFSET] == "blobs" &&
                    segments[secondLast] == "uploads" ->
                    Triple(ContainerOperation.BLOB_UPLOAD, segments.size - UPLOAD_WITH_ID_OFFSET, segments[last])
                segments[secondLast] == "manifests" -> Triple(ContainerOperation.MANIFEST, secondLast, segments[last])
                segments[secondLast] == "blobs" -> Triple(ContainerOperation.BLOB, secondLast, segments[last])
                else -> fail("unsupported registry operation in /v2/${segments.joinToString("/")}")
            }
        }

        private fun validateName(imageName: String) {
            imageName.split('/').forEach { component ->
                when {
                    component == "." || component == ".." -> fail("path traversal in image name: $imageName")
                    component.contains('\\') -> fail("backslash not allowed in image name: $imageName")
                    component.any { it.code < MIN_PRINTABLE } -> fail("control character in image name")
                    !NAME_COMPONENT.matches(component) -> fail("invalid image name component: $component")
                }
            }
        }

        private fun validateManifestRef(reference: String) {
            if (!Digest.isDigest(reference) && !TAG.matches(reference)) fail("invalid manifest reference: $reference")
        }

        private fun fail(message: String): Nothing = throw InvalidImageReferenceException(message)

        private const val MIN_SEGMENTS = 3
        private const val UPLOAD_WITH_ID_SEGMENTS = 4
        private const val UPLOAD_WITH_ID_OFFSET = 3
    }
}
