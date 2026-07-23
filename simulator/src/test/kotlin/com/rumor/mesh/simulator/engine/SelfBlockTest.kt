package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant: a node never blocks its own identity. Blocking self is meaningless
 * (relay is blocklist-blind, the local feed renders from the store, inbound-self
 * is echo-dropped) and only invites the publish-then-hide-me footgun.
 */
class SelfBlockTest {

    private val self = "5".repeat(64)
    private val other = "a".repeat(64)

    private fun manager() = BlockManager(
        InMemoryBlockEntryRepository(),
        InMemorySubscribedBlocklistRepository(),
        InMemoryBlocklistEntryRepository(),
        localUserId = { self },
    )

    @Test
    fun `block refuses own identity and blocks others`() = runBlocking {
        val bm = manager()

        assertFalse("block(self) must be a no-op returning false", bm.block(self))
        assertFalse("self must never be in the blocked set", bm.isBlocked(self))

        assertTrue("blocking a peer succeeds", bm.block(other))
        assertTrue(bm.isBlocked(other))
    }

    @Test
    fun `a null localUserId provider leaves the guard disabled`() = runBlocking {
        // Simulator/tests that don't wire an identity can still block any id.
        val bm = BlockManager(
            InMemoryBlockEntryRepository(),
            InMemorySubscribedBlocklistRepository(),
            InMemoryBlocklistEntryRepository(),
        )
        assertTrue(bm.block(self))
        assertTrue(bm.isBlocked(self))
    }
}
