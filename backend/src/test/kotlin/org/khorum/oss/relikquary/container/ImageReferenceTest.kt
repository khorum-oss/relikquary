package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageReferenceTest {

    private val digest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `parses a manifest pull by tag with a multi-segment image name`() {
        val ref = ImageReference.parse("dockerhub/library/alpine/manifests/3.20")
        assertEquals("dockerhub", ref.repository)
        assertEquals("library/alpine", ref.imageName)
        assertEquals(ContainerOperation.MANIFEST, ref.operation)
        assertEquals("3.20", ref.reference)
        assertEquals(false, ref.referenceIsDigest())
    }

    @Test
    fun `parses a manifest pull by digest`() {
        val ref = ImageReference.parse("containers/team/app/manifests/$digest")
        assertEquals("team/app", ref.imageName)
        assertEquals(ContainerOperation.MANIFEST, ref.operation)
        assertTrue(ref.referenceIsDigest())
    }

    @Test
    fun `parses a blob get by digest`() {
        val ref = ImageReference.parse("containers/team/app/blobs/$digest")
        assertEquals(ContainerOperation.BLOB, ref.operation)
        assertEquals("team/app", ref.imageName)
        assertEquals(digest, ref.reference)
    }

    @Test
    fun `parses blob upload start and continuation`() {
        val start = ImageReference.parse("containers/team/app/blobs/uploads")
        assertEquals(ContainerOperation.BLOB_UPLOAD, start.operation)
        assertEquals("", start.reference)

        val cont = ImageReference.parse("containers/team/app/blobs/uploads/9c2e-uuid")
        assertEquals(ContainerOperation.BLOB_UPLOAD, cont.operation)
        assertEquals("9c2e-uuid", cont.reference)
        assertEquals("team/app", cont.imageName)
    }

    @Test
    fun `parses a tags list`() {
        val ref = ImageReference.parse("containers/team/app/tags/list")
        assertEquals(ContainerOperation.TAGS_LIST, ref.operation)
        assertEquals("team/app", ref.imageName)
    }

    @Test
    fun `rejects a blob reference that is not a digest`() {
        assertThrows(InvalidImageReferenceException::class.java) {
            ImageReference.parse("containers/team/app/blobs/notadigest")
        }
    }

    @Test
    fun `rejects path traversal and invalid names`() {
        assertThrows(InvalidImageReferenceException::class.java) {
            ImageReference.parse("containers/team/../secret/manifests/1.0")
        }
        assertThrows(InvalidImageReferenceException::class.java) {
            ImageReference.parse("containers/Team/App/manifests/1.0") // uppercase not allowed
        }
    }

    @Test
    fun `rejects a path with no operation`() {
        assertThrows(InvalidImageReferenceException::class.java) { ImageReference.parse("containers/team/app") }
    }
}
