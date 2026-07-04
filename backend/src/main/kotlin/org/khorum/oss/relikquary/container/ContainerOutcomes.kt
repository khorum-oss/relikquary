package org.khorum.oss.relikquary.container

import java.io.InputStream

/**
 * Outcomes of a container manifest/blob/tags request, shared by the hosted and proxy paths (feature 018).
 * [NotFound] ⇒ 404 (nothing to serve, nothing cached); [UpstreamError] ⇒ 502 (a proxy upstream/token
 * failure — never cached, so a transient outage is not recorded as a miss).
 */
sealed interface ManifestOutcome {
    /** [bytes] is the exact manifest/index content; [mediaType] is returned as `Content-Type`. */
    data class Found(val bytes: ByteArray, val mediaType: String, val digest: Digest) : ManifestOutcome
    data object NotFound : ManifestOutcome
    data object UpstreamError : ManifestOutcome
}

sealed interface BlobOutcome {
    /** The caller closes [stream]. [length] is null when unknown (chunked). */
    data class Found(val stream: InputStream, val length: Long?, val digest: Digest) : BlobOutcome
    data object NotFound : BlobOutcome
    data object UpstreamError : BlobOutcome
}

sealed interface TagsOutcome {
    /** [json] is the `{"name":…,"tags":[…]}` body bytes. */
    data class Found(val json: ByteArray) : TagsOutcome
    data object NotFound : TagsOutcome
    data object UpstreamError : TagsOutcome
}
