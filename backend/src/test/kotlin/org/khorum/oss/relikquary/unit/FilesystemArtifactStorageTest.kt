package org.khorum.oss.relikquary.unit

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.khorum.oss.relikquary.config.StorageProperties
import org.khorum.oss.relikquary.storage.FilesystemArtifactStorage
import java.nio.file.Path
import kotlin.random.Random

class FilesystemArtifactStorageTest {

    private fun storage(root: Path) =
        FilesystemArtifactStorage(StorageProperties(filesystem = StorageProperties.Filesystem(root = root.toString())))

    @Test
    fun `stores and serves bytes byte-for-byte`(@TempDir root: Path) {
        val store = storage(root)
        val bytes = Random.nextBytes(4096)
        val key = "com/example/widget/1.0.0/widget-1.0.0.jar"

        val written = store.write(key, bytes.inputStream())

        assertEquals(bytes.size.toLong(), written)
        assertTrue(store.exists(key))
        val read = store.openRead(key)!!.stream.use { it.readBytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `reports the stored size`(@TempDir root: Path) {
        val store = storage(root)
        val bytes = Random.nextBytes(1234)
        store.write("g/a/1/a-1.pom", bytes.inputStream())
        assertEquals(1234L, store.openRead("g/a/1/a-1.pom")!!.sizeBytes)
    }

    @Test
    fun `returns null for an absent key`(@TempDir root: Path) {
        assertNull(storage(root).openRead("g/a/1/missing.jar"))
        assertFalse(storage(root).exists("g/a/1/missing.jar"))
    }

    @Test
    fun `overwrites an existing key with new bytes`(@TempDir root: Path) {
        val store = storage(root)
        val key = "g/a/1.0-SNAPSHOT/a-1.0-SNAPSHOT.jar"
        store.write(key, Random.nextBytes(100).inputStream())
        val updated = Random.nextBytes(200)
        store.write(key, updated.inputStream())
        assertArrayEquals(updated, store.openRead(key)!!.stream.use { it.readBytes() })
    }

    @Test
    fun `lists folders and files with metadata`(@TempDir root: Path) {
        val store = storage(root)
        store.write("releases/g/a/1.0.0/a-1.0.0.jar", Random.nextBytes(10).inputStream())
        store.write("releases/g/a/1.0.0/a-1.0.0.pom", Random.nextBytes(20).inputStream())

        val underA = store.list("releases/g/a")
        assertEquals(listOf("1.0.0"), underA.map { it.name })
        assertTrue(underA.single().isDirectory)

        val files = store.list("releases/g/a/1.0.0")
        assertEquals(setOf("a-1.0.0.jar", "a-1.0.0.pom"), files.map { it.name }.toSet())
        assertTrue(files.all { !it.isDirectory && it.sizeBytes != null && it.lastModified != null })
    }

    @Test
    fun `delete removes a file and prunes empty parents`(@TempDir root: Path) {
        val store = storage(root)
        store.write("releases/g/a/1.0.0/a-1.0.0.jar", Random.nextBytes(8).inputStream())
        assertTrue(store.delete("releases/g/a/1.0.0/a-1.0.0.jar"))
        assertFalse(store.delete("releases/g/a/1.0.0/a-1.0.0.jar"))
        // The now-empty version/artifact folders are pruned, so the repo lists empty.
        assertTrue(store.list("releases").isEmpty())
    }

    @Test
    fun `deletePrefix removes all files under a folder`(@TempDir root: Path) {
        val store = storage(root)
        store.write("releases/g/a/1.0.0/a-1.0.0.jar", Random.nextBytes(8).inputStream())
        store.write("releases/g/a/1.0.0/a-1.0.0.pom", Random.nextBytes(8).inputStream())
        store.write("releases/g/a/2.0.0/a-2.0.0.jar", Random.nextBytes(8).inputStream())
        assertEquals(2, store.deletePrefix("releases/g/a/1.0.0"))
        assertTrue(store.exists("releases/g/a/2.0.0/a-2.0.0.jar"))
    }
}
