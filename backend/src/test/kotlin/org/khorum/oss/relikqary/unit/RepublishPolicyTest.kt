package org.khorum.oss.relikqary.unit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikqary.config.PublishProperties
import org.khorum.oss.relikqary.config.PublishProperties.ReleasePolicy
import org.khorum.oss.relikqary.coordinate.RepositoryPath
import org.khorum.oss.relikqary.ingestion.RepublishPolicy

class RepublishPolicyTest {

    private val release = RepositoryPath.of("com/example/widget/1.0.0/widget-1.0.0.jar")
    private val snapshot = RepositoryPath.of("com/example/widget/1.1.0-SNAPSHOT/widget-1.1.0-SNAPSHOT.jar")
    private val metadata = RepositoryPath.of("com/example/widget/maven-metadata.xml")

    private fun policy(p: ReleasePolicy) = RepublishPolicy(PublishProperties(releasePolicy = p))

    @Test
    fun `allows publishing a brand-new path regardless of policy`() {
        assertTrue(policy(ReleasePolicy.REJECT).isAllowed(release, alreadyExists = false))
    }

    @Test
    fun `rejects re-publishing an existing release under the default policy`() {
        assertFalse(policy(ReleasePolicy.REJECT).isAllowed(release, alreadyExists = true))
    }

    @Test
    fun `allows re-publishing an existing release when overwrite is configured`() {
        assertTrue(policy(ReleasePolicy.OVERWRITE).isAllowed(release, alreadyExists = true))
    }

    @Test
    fun `always allows re-publishing an existing snapshot`() {
        assertTrue(policy(ReleasePolicy.REJECT).isAllowed(snapshot, alreadyExists = true))
    }

    @Test
    fun `always allows overwriting artifact metadata`() {
        assertTrue(policy(ReleasePolicy.REJECT).isAllowed(metadata, alreadyExists = true))
    }
}
