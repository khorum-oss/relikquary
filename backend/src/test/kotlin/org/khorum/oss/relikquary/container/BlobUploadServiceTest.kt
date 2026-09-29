package org.khorum.oss.relikquary.container

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.container.persistence.BlobUpload
import org.khorum.oss.relikquary.container.persistence.BlobUploadRepository
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Chunked `docker push` upload sessions (HS-29). The defects under test are both in the chunk-append
 * path: it used to be a read-modify-write on a single pending key, so a retried chunk was appended
 * twice and concurrent chunks clobbered each other — losing bytes silently until the digest check
 * caught it at finalize.
 */
class BlobUploadServiceTest {

    private val repository = "libs-release"
    private val image = "relikquary-backend"

    /** A real storage over a temp dir, and an in-memory stand-in for the JPA session table. */
    private fun service(root: Path): BlobUploadService {
        val storage = FilesystemArtifactStorage(
            StorageProperties(filesystem = StorageProperties.Filesystem(root = root.toString())),
        )
        val rows = mutableMapOf<String, BlobUpload>()
        val uploads = mockk<BlobUploadRepository>()
        every { uploads.save(any<BlobUpload>()) } answers {
            val row = firstArg<BlobUpload>()
            rows[row.uploadId] = row
            row
        }
        every { uploads.findById(any()) } answers { Optional.ofNullable(rows[firstArg<String>()]) }
        every { uploads.deleteById(any()) } answers { rows.remove(firstArg<String>()); Unit }
        return BlobUploadService(storage, ContainerStorage(storage), uploads)
    }

    @Test
    fun `a chunk retried at the same offset is stored once, not twice`(@TempDir root: Path) {
        val svc = service(root)
        val row = svc.session(svc.start(repository, image))!!
        val first = "0123456789".toByteArray()
        val second = "abcdefghij".toByteArray()

        svc.append(row, first.inputStream(), offset = 0)
        svc.append(row, second.inputStream(), offset = 10)
        // The network hiccups and the client resends the same chunk at the same offset.
        svc.append(row, second.inputStream(), offset = 10)

        val expected = first + second
        val written = svc.finalize(row, ByteArray(0).inputStream(), Digest.of(expected), offset = 20)

        assertEquals(expected.size.toLong(), written)
        assertArrayEquals(expected, readBlob(root, expected))
    }

    @Test
    fun `chunks arriving out of order assemble in offset order`(@TempDir root: Path) {
        val svc = service(root)
        val row = svc.session(svc.start(repository, image))!!
        val a = "AAAA".toByteArray()
        val b = "BBBB".toByteArray()
        val c = "CCCC".toByteArray()

        svc.append(row, c.inputStream(), offset = 8)
        svc.append(row, a.inputStream(), offset = 0)
        svc.append(row, b.inputStream(), offset = 4)

        val expected = a + b + c
        svc.finalize(row, ByteArray(0).inputStream(), Digest.of(expected), offset = 12)

        assertArrayEquals(expected, readBlob(root, expected))
    }

    @Test
    fun `concurrent chunk appends lose no bytes`(@TempDir root: Path) {
        val svc = service(root)
        val row = svc.session(svc.start(repository, image))!!
        val chunkSize = 4096
        val chunks = (0 until 16).map { Random(it).nextBytes(chunkSize) }

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = chunks.mapIndexed { i, chunk ->
                pool.submit {
                    start.await()
                    svc.append(row, chunk.inputStream(), offset = (i * chunkSize).toLong())
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val expected = chunks.reduce { acc, c -> acc + c }
        val written = svc.finalize(
            row, ByteArray(0).inputStream(), Digest.of(expected), offset = (chunks.size * chunkSize).toLong(),
        )

        assertEquals(expected.size.toLong(), written)
        assertArrayEquals(expected, readBlob(root, expected))
    }

    @Test
    fun `a monolithic finalize with no prior chunks stores the whole body`(@TempDir root: Path) {
        val svc = service(root)
        val row = svc.session(svc.start(repository, image))!!
        val body = Random(99).nextBytes(8192)

        val written = svc.finalize(row, body.inputStream(), Digest.of(body), offset = 0)

        assertEquals(body.size.toLong(), written)
        assertArrayEquals(body, readBlob(root, body))
    }

    @Test
    fun `parses the chunk offset from either Content-Range spelling`() {
        // The OCI spec's bare form, which is what buildx sends.
        assertEquals(0L, BlobUploadService.parseChunkOffset("0-1023"))
        assertEquals(1024L, BlobUploadService.parseChunkOffset("1024-2047"))
        // The full HTTP form, which some clients send instead.
        assertEquals(1024L, BlobUploadService.parseChunkOffset("bytes 1024-2047/4096"))
        assertEquals(0L, BlobUploadService.parseChunkOffset("  0-1023  "))
    }

    @Test
    fun `returns null for an absent or unparseable Content-Range`() {
        // Null means "caller decides" — the endpoint falls back to the session's current offset rather
        // than guessing zero, which would silently overwrite the first chunk.
        assertNull(BlobUploadService.parseChunkOffset(null))
        assertNull(BlobUploadService.parseChunkOffset(""))
        assertNull(BlobUploadService.parseChunkOffset("garbage"))
        assertNull(BlobUploadService.parseChunkOffset("-5"))
    }

    /** Reads the promoted, content-addressed blob back out of the store. */
    private fun readBlob(root: Path, content: ByteArray): ByteArray {
        val digest = Digest.of(content)
        val storage = FilesystemArtifactStorage(
            StorageProperties(filesystem = StorageProperties.Filesystem(root = root.toString())),
        )
        return ContainerStorage(storage).readBlob(repository, digest)!!.stream.use { it.readBytes() }
    }
}
