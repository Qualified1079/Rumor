package com.rumor.mesh.core.transport.wifidirect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BloomFilterDataTest {

    @Test
    fun `round-trip preserves membership`() {
        val bloom = BloomFilterData(expectedItems = 100)
        val ids = (1..50).map { "msg-$it" }
        ids.forEach { bloom.add(it) }
        val onWire = BloomFilterData.deserialize(bloom.serialize(), 100)
        ids.forEach { assertTrue("expected $it after wire round-trip", onWire.mightContain(it)) }
    }

    @Test
    fun `tryDeserialize returns null on adversarial Int MAX_VALUE expectedItems`() {
        // A peer claiming Int.MAX_VALUE items forces a multi-GB bit array.
        // We must not allocate; we must return null so the caller can over-offer.
        val result = BloomFilterData.tryDeserialize("", Int.MAX_VALUE)
        assertNull(result)
    }

    @Test
    fun `tryDeserialize returns null on negative expectedItems`() {
        assertNull(BloomFilterData.tryDeserialize("", -1))
        assertNull(BloomFilterData.tryDeserialize("", 0))
    }

    @Test
    fun `tryDeserialize succeeds on legitimate input`() {
        val bloom = BloomFilterData(expectedItems = 100)
        bloom.add("hello")
        val result = BloomFilterData.tryDeserialize(bloom.serialize(), 100)
        assertNotNull(result)
        assertTrue(result!!.mightContain("hello"))
        assertFalse(result.mightContain("not-in-set"))
    }

    @Test
    fun `tryDeserialize returns null on malformed base64`() {
        // Invalid base64 throws IllegalArgumentException from the decoder —
        // tryDeserialize must swallow it.
        assertNull(BloomFilterData.tryDeserialize("not%%%base64!!!", 100))
    }
}
