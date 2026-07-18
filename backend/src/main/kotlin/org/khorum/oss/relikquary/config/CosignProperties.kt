package org.khorum.oss.relikquary.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Global cosign verification settings (feature 024). [defaultPublicKey] is the cosign public key used to
 * verify a container repository's image signatures when that repository sets no [RepositoryProperties.Repo.cosignPublicKey]
 * of its own — an inline PEM (`-----BEGIN PUBLIC KEY-----…`) or a path to a PEM file. Absent ⇒ repositories
 * without their own key report trust `unknown`. Supply via env/file; never commit a key.
 */
@ConfigurationProperties(prefix = "relikquary.cosign")
data class CosignProperties(
    val defaultPublicKey: String? = null,
)
