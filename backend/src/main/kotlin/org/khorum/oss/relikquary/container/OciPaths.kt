package org.khorum.oss.relikquary.container

import com.fasterxml.jackson.databind.ObjectMapper

/** Builds `/v2` `Location` values and the `tags/list` body for hosted responses (feature 018). */
object OciPaths {

    private val objectMapper = ObjectMapper()

    fun manifestLocation(repository: String, imageName: String, digest: Digest): String =
        "/v2/$repository/$imageName/manifests/${digest.value}"

    fun blobLocation(repository: String, imageName: String, digest: Digest): String =
        "/v2/$repository/$imageName/blobs/${digest.value}"

    fun uploadLocation(repository: String, imageName: String, uuid: String): String =
        "/v2/$repository/$imageName/blobs/uploads/$uuid"

    /** The `{"name":…,"tags":[…]}` body for `GET …/tags/list`. */
    fun tagsJson(imageName: String, tags: List<String>): ByteArray =
        objectMapper.writeValueAsBytes(mapOf("name" to imageName, "tags" to tags))
}
