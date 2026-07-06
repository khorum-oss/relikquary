package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.S3ArtifactStorage
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import kotlin.random.Random

/**
 * S3 parity for container storage (feature 018, US3, SC-005): the digest-verified blob and manifest writes
 * of [ContainerStorage] round-trip byte-for-byte against a real S3 backend (MinIO), exactly as on the
 * filesystem — no code change. Auto-skipped when Docker is unavailable, mirroring [S3MinioIT] (spec 003).
 */
@Testcontainers(disabledWithoutDocker = true)
class ContainerStorageS3IT {

    @Test
    fun `blobs and manifests round-trip byte-for-byte on S3`() {
        val s3 = S3Client.builder()
            .endpointOverride(URI.create(minio.s3URL))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password)),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        s3.use { client ->
            client.createBucket { it.bucket(BUCKET) }
            val props = StorageProperties(backend = StorageProperties.Backend.S3, s3 = StorageProperties.S3(bucket = BUCKET))
            val storage = ContainerStorage(S3ArtifactStorage(client, props))
            val repo = "containers"

            // Blob: digest-verified write, then read back byte-identically.
            val blob = Random.nextBytes(4096)
            val blobDigest = Digest.of(blob)
            storage.writeBlobVerified(repo, blobDigest, blob.inputStream())
            assertTrue(storage.hasBlob(repo, blobDigest))
            assertArrayEquals(blob, storage.readBlob(repo, blobDigest)!!.stream.use { it.readBytes() })

            // Manifest: verbatim write, then read back byte-identically.
            val manifest = """{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json"}""".toByteArray()
            val manifestDigest = Digest.of(manifest)
            storage.writeManifestBytes(repo, manifestDigest, manifest)
            assertTrue(storage.hasManifest(repo, manifestDigest))
            assertArrayEquals(manifest, storage.readManifestBytes(repo, manifestDigest))
        }
    }

    companion object {
        private const val BUCKET = "relikquary"

        @Container
        @JvmStatic
        val minio: MinIOContainer = MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z")
    }
}
