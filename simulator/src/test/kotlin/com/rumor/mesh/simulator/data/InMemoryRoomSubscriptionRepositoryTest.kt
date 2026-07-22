package com.rumor.mesh.simulator.data

import com.rumor.mesh.core.data.RoomSubscription
import com.rumor.mesh.core.data.RoomSubscriptionMode
import com.rumor.mesh.core.data.memory.InMemoryRoomSubscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the simulator's in-memory
 * [RoomSubscriptionRepository] (O79). The Android Room-backed
 * adapter shares the same contract — if this passes, the contract
 * is honoured on the simulator side; the Android adapter is
 * covered by its own Robolectric-flavored test (lives in `:app`
 * once it's added).
 */
class InMemoryRoomSubscriptionRepositoryTest {

    @Test fun `upsert then get returns the same subscription`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        val sub = RoomSubscription(
            roomId = "room-1",
            mode = RoomSubscriptionMode.OPEN,
            routingKey = ByteArray(0),
            joinedAtMs = 100L,
        )
        repo.upsert(sub)
        assertEquals(sub, repo.get("room-1"))
    }

    @Test fun `get on unknown roomId returns null`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        assertNull(repo.get("not-there"))
    }

    @Test fun `delete removes the subscription`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        val sub = RoomSubscription("r", RoomSubscriptionMode.OPEN, ByteArray(0), 0L)
        repo.upsert(sub)
        assertNotNull(repo.get("r"))
        repo.delete("r")
        assertNull(repo.get("r"))
    }

    @Test fun `upsert is idempotent`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        val sub = RoomSubscription("r", RoomSubscriptionMode.OPEN, ByteArray(0), 0L)
        repo.upsert(sub)
        repo.upsert(sub)
        assertEquals(1, repo.getAll().size)
    }

    @Test fun `upsert with same roomId replaces prior subscription`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        repo.upsert(RoomSubscription("r", RoomSubscriptionMode.OPEN, ByteArray(0), 0L))
        repo.upsert(RoomSubscription("r", RoomSubscriptionMode.ENCRYPTED, ByteArray(32) { 0x42 }, 100L))
        val now = repo.get("r")
        assertNotNull(now)
        assertEquals(RoomSubscriptionMode.ENCRYPTED, now!!.mode)
        assertEquals(100L, now.joinedAtMs)
        assertTrue(now.routingKey.contentEquals(ByteArray(32) { 0x42 }))
    }

    @Test fun `getAll returns every subscription`() = runBlocking {
        val repo = InMemoryRoomSubscriptionRepository()
        val subs = listOf(
            RoomSubscription("a", RoomSubscriptionMode.OPEN, ByteArray(0), 1L),
            RoomSubscription("b", RoomSubscriptionMode.ENCRYPTED, ByteArray(32), 2L),
            RoomSubscription("c", RoomSubscriptionMode.OPEN, ByteArray(0), 3L),
        )
        subs.forEach { repo.upsert(it) }
        val all = repo.getAll()
        assertEquals(3, all.size)
        assertEquals(subs.map { it.roomId }.toSet(), all.map { it.roomId }.toSet())
    }

    @Test fun `OPEN and ENCRYPTED subscriptions coexist`() = runBlocking {
        // Sanity for the receive-dispatch use case: the engine pulls
        // openRoomIds + encryptedRoomSubscriptions separately from the
        // same repo snapshot. Both surfaces must be reachable.
        val repo = InMemoryRoomSubscriptionRepository()
        repo.upsert(RoomSubscription("open-room", RoomSubscriptionMode.OPEN, ByteArray(0), 0L))
        repo.upsert(RoomSubscription("enc-room", RoomSubscriptionMode.ENCRYPTED, ByteArray(32), 0L))
        val all = repo.getAll()
        assertEquals(setOf("open-room"), all.filter { it.mode == RoomSubscriptionMode.OPEN }.map { it.roomId }.toSet())
        assertEquals(setOf("enc-room"), all.filter { it.mode == RoomSubscriptionMode.ENCRYPTED }.map { it.roomId }.toSet())
    }
}
