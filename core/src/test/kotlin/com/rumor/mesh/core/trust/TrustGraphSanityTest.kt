package com.rumor.mesh.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative controls (user request 2026-07-22): tests that prove the trust
 * assertions have TEETH — that a *false* claim about the frontier genuinely
 * fails, and that the "good" outcomes aren't vacuous (they'd be different if the
 * mechanism were broken). Guards against the vacuous-green trap (cf. the O129
 * RoomMessageMalformedTagTest that passed without exercising anything).
 *
 * The `assertThrows(AssertionError::class.java) { … }` blocks are deliberately
 * WRONG assertions: the test passes precisely because the wrong claim fails,
 * demonstrating the harness would catch a regression that made it true.
 */
class TrustGraphSanityTest {

    private val vouches = mapOf(
        "me" to setOf("traitor", "honestFriend"),
        "traitor" to setOf("sybilA", "sybilB", "sybilC"),
        "honestFriend" to setOf("goodPerson"),
    )

    @Test
    fun `the block test is not vacuous — WITHOUT a block the sybils really are present`() {
        // The companion "block-the-signer" assertion is only meaningful if the
        // sybils were admitted in the first place. Pin that they are.
        val noBlock = TrustGraph.frontier("me", vouches, hops = 3)
        assertTrue("sybils must be present without a block, else the block test proves nothing",
            noBlock.containsAll(setOf("sybilA", "sybilB", "sybilC")))
    }

    @Test
    fun `SANITY - claiming sybils are absent without a block MUST fail`() {
        val noBlock = TrustGraph.frontier("me", vouches, hops = 3)
        // A test asserting the sybils are gone (when no block was applied) is a
        // claim that SHOULD NOT pass — confirm it genuinely throws.
        assertThrows(AssertionError::class.java) {
            assertTrue("intentionally-wrong: sybils absent with no block", noBlock.none { it.startsWith("sybil") })
        }
    }

    @Test
    fun `SANITY - claiming block-the-signer leaves the subtree intact MUST fail`() {
        val blocked = TrustGraph.frontier("me", vouches, blocked = setOf("traitor"), hops = 3)
        // The wrong claim: "blocking the signer did NOT sever the sybils."
        assertThrows(AssertionError::class.java) {
            assertTrue("intentionally-wrong: sybils survive the block", blocked.any { it.startsWith("sybil") })
        }
    }

    @Test
    fun `SANITY - claiming an unreachable node is admitted MUST fail`() {
        // 'ghost' is vouched-for by nobody the seed can reach.
        val withGhost = vouches + ("nobody" to setOf("ghost"))
        val admitted = TrustGraph.frontier("me", withGhost, hops = 5)
        assertThrows(AssertionError::class.java) {
            assertTrue("intentionally-wrong: unreachable ghost admitted", "ghost" in admitted)
        }
    }

    @Test
    fun `SANITY - the frontier can be genuinely empty (measurement is not stuck non-empty)`() {
        // hops=0 and a seed that vouches for no one must both yield empty — proof
        // the frontier isn't a constant that only ever looks populated.
        assertEquals(emptySet<String>(), TrustGraph.frontier("me", vouches, hops = 0))
        assertEquals(emptySet<String>(), TrustGraph.frontier("island", vouches, hops = 5))
    }
}
