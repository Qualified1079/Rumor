package com.rumor.mesh.core.routing

import com.rumor.mesh.core.data.RouteRepository
import com.rumor.mesh.core.model.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal RouteRepository for testing. Mirrors the production sort
 * (bytesRelayed / (1 + failureCount), DESC) so the comparator stays in
 * lock-step with both the Room DAO query and the simulator's in-memory impl.
 */
private class TestRouteRepo : RouteRepository {
    private val routes = ConcurrentHashMap<String, Route>()
    private val flow = MutableStateFlow<List<Route>>(emptyList())
    override suspend fun upsert(route: Route) {
        routes[route.peerId] = route
        flow.value = routes.values.toList()
    }
    override suspend fun getPreferred(limit: Int): List<Route> =
        routes.values
            .sortedWith(
                compareByDescending<Route> { it.bytesRelayed.toDouble() / (1 + it.failureCount) }
                    .thenByDescending { it.sessionCount }
                    .thenByDescending { it.lastUpdatedMs }
            )
            .take(limit)
    override fun observeAll(): Flow<List<Route>> = flow
    override suspend fun getForPeer(peerId: String): Route? = routes[peerId]
    override suspend fun pruneStale(olderThanMs: Long) {}
    override suspend fun delete(peerId: String) { routes.remove(peerId) }
}

class TopologyTrackerTest {

    private fun awaitRoute(repo: TestRouteRepo, peerId: String, timeoutMs: Long = 1_000L): Route? {
        // TopologyTracker.scope uses Dispatchers.IO; we poll briefly for the
        // upsert to land instead of plumbing an injectable dispatcher.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = runBlocking { repo.getForPeer(peerId) }
            if (r != null) return r
            Thread.sleep(10)
        }
        return runBlocking { repo.getForPeer(peerId) }
    }

    @Test
    fun `recordFailure on unknown peer seeds a route with failureCount 1`() {
        val repo = TestRouteRepo()
        val tracker = TopologyTracker(repo)
        tracker.recordFailure("alice")
        val r = awaitRoute(repo, "alice")
        assertNotNull(r)
        assertEquals(1, r!!.failureCount)
        assertEquals(0L, r.bytesRelayed)
        assertEquals(0, r.sessionCount)
    }

    @Test
    fun `recordFailure increments existing failureCount`() {
        val repo = TestRouteRepo()
        val tracker = TopologyTracker(repo)
        runBlocking {
            repo.upsert(Route("bob", latencyMs = 50, hopCount = 1, lastUpdatedMs = 0L, sessionCount = 3, bytesRelayed = 1000, failureCount = 2))
        }
        tracker.recordFailure("bob")
        // Wait for failureCount to bump.
        val deadline = System.currentTimeMillis() + 1_000L
        var r: Route? = null
        while (System.currentTimeMillis() < deadline) {
            r = runBlocking { repo.getForPeer("bob") }
            if (r != null && r.failureCount == 3) break
            Thread.sleep(10)
        }
        assertEquals(3, r!!.failureCount)
        assertEquals(1000L, r.bytesRelayed)
        assertEquals(3, r.sessionCount)
    }

    @Test
    fun `getPreferred ranks reliable peer above flaky high-bytes peer`() = runBlocking {
        val repo = TestRouteRepo()
        // Flaky: 10 KB / (1 + 100) ≈ 99
        repo.upsert(Route("flaky", 0, 1, 0, 5, bytesRelayed = 10_000, failureCount = 100))
        // Steady: 5 KB / (1 + 0) = 5000
        repo.upsert(Route("steady", 0, 1, 0, 5, bytesRelayed = 5_000, failureCount = 0))
        val ranked = repo.getPreferred(10).map { it.peerId }
        assertEquals(listOf("steady", "flaky"), ranked)
    }

    @Test
    fun `recordSession preserves failureCount across successful sessions`() {
        val repo = TestRouteRepo()
        val tracker = TopologyTracker(repo)
        runBlocking {
            repo.upsert(Route("carol", 0, 1, 0, 1, bytesRelayed = 100, failureCount = 5))
        }
        tracker.recordSession("carol", latencyMs = 80, hopCount = 1, bytesTransferred = 200)
        val deadline = System.currentTimeMillis() + 1_000L
        var r: Route? = null
        while (System.currentTimeMillis() < deadline) {
            r = runBlocking { repo.getForPeer("carol") }
            if (r != null && r.bytesRelayed == 300L) break
            Thread.sleep(10)
        }
        assertEquals(300L, r!!.bytesRelayed)
        assertEquals(5, r.failureCount)
        assertEquals(2, r.sessionCount)
    }
}
