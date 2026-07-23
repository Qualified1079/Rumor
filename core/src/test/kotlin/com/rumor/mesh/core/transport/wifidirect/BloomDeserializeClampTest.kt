package com.rumor.mesh.core.transport.wifidirect


import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O117: `deserialize` must bind the claimed `expectedItems` to the actual
 * payload size BEFORE allocating — the O13 catch handles the OOM crash path,
 * but a value large enough to allocate heavily without OOMing was a free
 * slow-DoS (GC pressure per received summary). Conventions per
 * docs/SIMULATOR_TESTING.md: baseline (honest input passes), attack input
 * rejected, and a teeth-check that the rejection is really size-binding.
 */
class BloomDeserializeClampTest {

    @Test
    fun `honest round-trip still deserializes and matches`() {
        val filter = BloomFilterData(expectedItems = 500)
        val ids = (0 until 500).map { "msg-$it" }
        ids.forEach { filter.add(it) }

        val back = BloomFilterData.deserializeOrNull(filter.serialize(), 500)
        assertNotNull(back)
        assertTrue(ids.all { back.mightContain(it) })
    }

    @Test
    fun `huge claimed expectedItems with tiny payload is rejected without allocation`() {
        val tinyPayload = BloomFilterData(expectedItems = 10).serialize()
        // Before the clamp this drove a ~268 MB LongArray allocation attempt.
        val startNs = System.nanoTime()
        val result = BloomFilterData.deserializeOrNull(tinyPayload, Int.MAX_VALUE)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertNull(result)
        assertTrue(elapsedMs < 500, "rejection must be cheap (pre-allocation), took ${elapsedMs}ms")
    }

    @Test
    fun `negative expectedItems is rejected`() {
        val payload = BloomFilterData(expectedItems = 10).serialize()
        assertNull(BloomFilterData.deserializeOrNull(payload, -1))
    }

    @Test
    fun `mismatched payload length for honest-sized claim is rejected`() {
        // Claimed 5000 items but shipped the bytes of a 10-item filter: the
        // size binding, not just a ceiling, is what rejects this.
        val smallPayload = BloomFilterData(expectedItems = 10).serialize()
        assertNull(BloomFilterData.deserializeOrNull(smallPayload, 5000))
    }

    @Test
    fun `teeth-check - strict deserialize throws on the attack shape`() {
        val tinyPayload = BloomFilterData(expectedItems = 10).serialize()
        assertFailsWith<IllegalArgumentException> {
            BloomFilterData.deserialize(tinyPayload, 5000)
        }
    }

    @Test
    fun `garbage base64 is rejected not crashed`() {
        assertNull(BloomFilterData.deserializeOrNull("%%% not base64 %%%", 100))
    }

    @Test
    fun `zero expectedItems round-trips`() {
        val empty = BloomFilterData(expectedItems = 0)
        assertNotNull(BloomFilterData.deserializeOrNull(empty.serialize(), 0))
    }
}
