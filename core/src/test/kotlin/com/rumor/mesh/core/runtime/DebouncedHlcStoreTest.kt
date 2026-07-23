package com.rumor.mesh.core.runtime

import com.rumor.mesh.core.time.HlcTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O130(e): a partition-heal burst of HLC advances must not translate into a
 * burst of durable writes. The debounce coalesces many saves into at most one
 * per interval, but the LATEST value must always be the one that lands.
 */
class DebouncedHlcStoreTest {

    private class CountingStore : HlcStore {
        val writes = AtomicInteger(0)
        @Volatile var last: HlcTimestamp = HlcTimestamp(0, 0)
        override fun load(): HlcTimestamp = last
        override fun save(ts: HlcTimestamp) { writes.incrementAndGet(); last = ts }
    }

    @Test
    fun `a burst of saves coalesces to far fewer writes and keeps the latest`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val delegate = CountingStore()
        val store = DebouncedHlcStore(delegate, scope, intervalMs = 50)

        // 200 rapid advances (the partition-heal shape).
        for (i in 1..200) store.save(HlcTimestamp(1000, i.toLong()))
        delay(120) // let the trailing flush fire

        assertTrue(
            delegate.writes.get() <= 3,
            "expected far fewer than 200 durable writes, got ${delegate.writes.get()}",
        )
        assertEquals(
            HlcTimestamp(1000, 200), delegate.last,
            "the latest value must be the one persisted",
        )
        scope.cancel()
    }

    @Test
    fun `flushNow persists the latest pending value immediately`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val delegate = CountingStore()
        val store = DebouncedHlcStore(delegate, scope, intervalMs = 10_000)

        store.save(HlcTimestamp(2000, 5))
        store.flushNow()
        assertEquals(HlcTimestamp(2000, 5), delegate.last)
        scope.cancel()
    }

    @Test
    fun `load delegates through`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val delegate = CountingStore().apply { last = HlcTimestamp(42, 7) }
        val store = DebouncedHlcStore(delegate, scope)
        assertEquals(HlcTimestamp(42, 7), store.load())
        scope.cancel()
    }
}
