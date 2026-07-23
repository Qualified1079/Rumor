package com.rumor.mesh.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O135(4) — the web-of-trust frontier's load-bearing properties, per the
 * `sybil_research.md` synthesis (SybilLimit per-attack-edge bound,
 * block-the-signer, Advogato-inoculation).
 */
class TrustGraphTest {

    @Test
    fun `hops=1 admits only direct vouches, hops=2 admits friends-of-friends`() {
        val vouches = mapOf(
            "me" to setOf("alice", "bob"),
            "alice" to setOf("carol"),
            "bob" to setOf("dave"),
        )
        assertEquals(setOf("alice", "bob"), TrustGraph.frontier("me", vouches, hops = 1))
        assertEquals(
            setOf("alice", "bob", "carol", "dave"),
            TrustGraph.frontier("me", vouches, hops = 2),
        )
    }

    @Test
    fun `sybils enter ONLY through an attack edge — the SybilLimit bound`() {
        // 'traitor' is a real person 'me' vouched for; the traitor was fooled
        // into vouching for a swarm of sybils. Separately, a disconnected sybil
        // ring vouches for itself with NO path from 'me'.
        val sybils = (1..500).map { "sybil$it" }.toSet()
        val ring = (1..500).map { "ring$it" }
        val vouches = buildMap {
            put("me", setOf("traitor", "honestFriend"))
            put("traitor", sybils)                 // the ONE attack edge
            put("honestFriend", setOf("goodPerson"))
            // self-vouching sybil ring, unreachable from 'me'
            ring.forEachIndexed { i, r -> put(r, setOf(ring[(i + 1) % ring.size])) }
        }

        val admitted = TrustGraph.frontier("me", vouches, hops = 3)

        // The self-vouching ring never enters — no real person vouched for it.
        assertTrue("unreachable sybil ring must be excluded", admitted.none { it.startsWith("ring") })
        // The sybils reachable via the traitor ARE admitted — the cost was ONE
        // attack edge (persuading a real person), not minting 500 keys.
        assertTrue("sybils behind the attack edge are admitted at hops>=2", admitted.containsAll(sybils))
        assertTrue(admitted.contains("goodPerson"))
    }

    @Test
    fun `block-the-signer severs the entire sybil subtree`() {
        val sybils = (1..500).map { "sybil$it" }.toSet()
        val vouches = mapOf(
            "me" to setOf("traitor", "honestFriend"),
            "traitor" to sybils,
            "honestFriend" to setOf("goodPerson"),
        )
        // Block the ONE person who vouched for the sybils.
        val admitted = TrustGraph.frontier("me", vouches, blocked = setOf("traitor"), hops = 3)

        assertFalse("the blocked signer is gone", admitted.contains("traitor"))
        assertTrue("every sybil behind the blocked signer is severed", admitted.none { it in sybils })
        // The honest branch is untouched — blocking one signer doesn't harm others.
        assertEquals(setOf("honestFriend", "goodPerson"), admitted)
    }

    @Test
    fun `a blocked node deep in an honest chain prunes only its own subtree`() {
        val vouches = mapOf(
            "me" to setOf("a"),
            "a" to setOf("b", "x"),
            "b" to setOf("c"),
            "x" to setOf("spam1", "spam2"), // x turned bad
        )
        val admitted = TrustGraph.frontier("me", vouches, blocked = setOf("x"), hops = 5)
        assertEquals(setOf("a", "b", "c"), admitted)
        assertTrue(admitted.none { it.startsWith("spam") })
    }

    @Test
    fun `Advogato-inoculation — depth is structural, a fooled node cannot shortcut its vouchees shallower`() {
        // A chain: me -> a -> b -> c. With hops=2, c (depth 3) must NOT be
        // admitted no matter how 'b' vouches — b cannot grant c its own
        // (shallower) reach. Depth is computed from the seed, never inherited.
        val vouches = mapOf(
            "me" to setOf("a"),
            "a" to setOf("b"),
            "b" to setOf("c"),
        )
        val h2 = TrustGraph.frontier("me", vouches, hops = 2)
        assertEquals(setOf("a", "b"), h2)
        assertFalse("c is depth 3 — beyond hops=2 regardless of who vouches", h2.contains("c"))

        // Even if 'b' ALSO appears as a direct vouch of a shallow node, its
        // vouchees still sit at their true minimum depth (BFS shortest path).
        val vouches2 = vouches + ("me" to setOf("a", "b")) // b now also depth 1
        val h2b = TrustGraph.frontier("me", vouches2, hops = 2)
        assertTrue("with b at depth 1, c is now depth 2 and admitted", h2b.contains("c"))
    }

    @Test
    fun `blocks are the seed's own set, not auto-inherited through the graph`() {
        // 'friend' blocks 'z' in THEIR world, but that block is not in the
        // seed's set, so the seed still admits z (Rumor's granular model, not
        // SSB's forced transitive block). The seed only prunes with ITS blocks.
        val vouches = mapOf(
            "me" to setOf("friend"),
            "friend" to setOf("z"),
        )
        // Seed passes no blocks → z admitted even though 'friend' might block it.
        assertTrue(TrustGraph.frontier("me", vouches, blocked = emptySet(), hops = 2).contains("z"))
        // Seed's OWN block of z prunes it.
        assertFalse(TrustGraph.frontier("me", vouches, blocked = setOf("z"), hops = 2).contains("z"))
    }
}
