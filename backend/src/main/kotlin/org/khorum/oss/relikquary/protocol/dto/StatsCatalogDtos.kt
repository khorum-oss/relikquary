package org.khorum.oss.relikquary.protocol.dto

/**
 * Read-only summary for the Dashboard (feature 016, Phase 2). [artifacts] is the total stored object
 * count and [storageBytes] the total stored size — both from the shared storage-usage snapshot, so the
 * figures match the `relikquary.storage.*` gauges and never trigger a per-request store walk.
 */
data class StatsResponse(
    val repositories: Int,
    val artifacts: Long,
    val storageBytes: Long,
    /** Distinct container images across the repositories (feature 023); 0 when there are none. */
    val images: Long = 0,
)

/**
 * One aggregated entry in the cross-repo catalog (feature 016, Phase 2). A Maven entry is a `group:artifact`
 * with its latest version, version count, and total stored size. A container entry (feature 023) reuses the
 * same fields with container meaning — [artifact] = image name, [latestVersion] = latest tag,
 * [versionCount] = tag count, [sizeBytes] = summed manifest size, [group] = "" — discriminated by [type].
 * Derived from stored content; for proxy repositories it reflects cached content only.
 */
data class CatalogEntry(
    val repository: String,
    val group: String,
    val artifact: String,
    val latestVersion: String,
    val versionCount: Int,
    val sizeBytes: Long,
    /** "maven" (default) or "container" — lets the UI badge and link each row by kind (feature 023). */
    val type: String = "maven",
)

/** A page of catalog entries with the totals needed to drive client-side paging/disclosure. */
data class CatalogResponse(
    val entries: List<CatalogEntry>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val truncated: Boolean,
)
