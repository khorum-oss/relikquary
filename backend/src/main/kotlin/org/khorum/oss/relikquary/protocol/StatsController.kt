package org.khorum.oss.relikquary.protocol

import org.khorum.oss.relikquary.container.ContainerBrowseService
import org.khorum.oss.relikquary.observability.metrics.StorageUsage
import org.khorum.oss.relikquary.protocol.dto.StatsResponse
import org.khorum.oss.relikquary.repository.RepositoryFormat
import org.khorum.oss.relikquary.repository.RepositoryRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only summary feed for the Dashboard (feature 016, Phase 2): the configured-repository count plus
 * the total stored object count and size. Reuses the shared [StorageUsage] snapshot (the same source as
 * the storage gauges), so a Dashboard load never walks storage. Open to read like `/api/repositories`.
 */
@RestController
@RequestMapping("/api")
class StatsController(
    private val registry: RepositoryRegistry,
    private val usage: StorageUsage,
    private val containerBrowse: ContainerBrowseService,
) {

    @GetMapping("/stats")
    fun stats(): StatsResponse {
        val snapshot = usage.snapshot()
        return StatsResponse(
            repositories = registry.all().size,
            artifacts = snapshot.count,
            storageBytes = snapshot.bytes,
            images = distinctImageCount(),
        )
    }

    /** Distinct container images across all container repositories (feature 023). */
    private fun distinctImageCount(): Long =
        registry.all()
            .filter { it.format == RepositoryFormat.CONTAINER }
            .sumOf { containerBrowse.distinctImageCount(it.name, it.kind).toLong() }
}
