package org.khorum.oss.relikquary.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class DigestTest {

    @Test
    fun `computes the known sha256 of empty and simple content`() {
        // sha256("") and sha256("abc") are well-known vectors.
        assertEquals(
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digest.of(ByteArray(0)).value,
        )
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digest.of("abc".toByteArray()).value,
        )
    }

    @Test
    fun `stream and byte digests agree`() {
        val bytes = "hello container world".toByteArray()
        assertEquals(Digest.of(bytes), Digest.of(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `hex exposes the value without the algorithm prefix`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Digest.of("abc".toByteArray()).hex)
    }

    @Test
    fun `parse accepts a well-formed sha256 digest`() {
        val raw = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(raw, Digest.parse(raw).value)
    }

    @Test
    fun `parse rejects malformed, wrong-algorithm, and short digests`() {
        assertThrows(InvalidDigestException::class.java) { Digest.parse("ba7816bf") }
        assertThrows(InvalidDigestException::class.java) { Digest.parse("sha512:ba7816bf") }
        assertThrows(InvalidDigestException::class.java) { Digest.parse("sha256:XYZ") }
        assertThrows(InvalidDigestException::class.java) { Digest.parse("sha256:ba7816bf8f01cfea") }
    }

    @Test
    fun `isDigest distinguishes digests from tags`() {
        assertTrue(Digest.isDigest("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertFalse(Digest.isDigest("latest"))
        assertFalse(Digest.isDigest("1.4.0"))
    }
}
