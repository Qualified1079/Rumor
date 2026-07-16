package com.rumor.mesh.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O31 — pin the v2 challenge byte format so a refactor can't silently
 * drift the sig domain tag or field order.
 */
class HelloChallengeV2Test {

    @Test fun `v1 and v2 differ in domain tag`() {
        val v1 = helloChallengeBytes("nonce", 1, 1, emptyList()).decodeToString()
        val v2 = helloChallengeBytesV2("nonce", 1, 1, emptyList(), emptyList()).decodeToString()
        assertTrue(v1.startsWith("rumor-hello-v1:"))
        assertTrue(v2.startsWith("rumor-hello-v2:"))
        assertFalse(v1 == v2, "Different domain tags must produce different bytes")
    }

    @Test fun `v2 with empty list still differs from v1`() {
        // Concrete check: even when recentlyExchangedWith is empty, v2 is
        // a wire-format-incompatible signing scheme. A peer cannot accept
        // a v1 signature as a v2 signature even by accident.
        val v1 = helloChallengeBytes("n", 1, 1, listOf("a"))
        val v2 = helloChallengeBytesV2("n", 1, 1, listOf("a"), emptyList())
        assertFalse(v1.contentEquals(v2))
    }

    @Test fun `v2 byte format pin — empty advertisements`() {
        val bytes = helloChallengeBytesV2(
            nonceBase64 = "abc",
            protocolVersion = 1,
            maxProtocolVersion = 1,
            supportedFeatures = listOf("route-adv-v1"),
            recentlyExchangedWith = emptyList(),
        ).decodeToString()
        assertEquals("rumor-hello-v2:abc|1|1|route-adv-v1|", bytes)
    }

    @Test fun `v2 byte format pin — populated advertisements sorted`() {
        val bytes = helloChallengeBytesV2(
            nonceBase64 = "abc",
            protocolVersion = 1,
            maxProtocolVersion = 1,
            supportedFeatures = listOf("route-adv-v1"),
            recentlyExchangedWith = listOf("bob", "alice", "charlie"),
        ).decodeToString()
        // Sorted: alice,bob,charlie — receivers can independently re-derive
        // without trusting sender's order.
        assertEquals("rumor-hello-v2:abc|1|1|route-adv-v1|alice,bob,charlie", bytes)
    }

    @Test fun `v2 sorts supportedFeatures and advertisements independently`() {
        val a = helloChallengeBytesV2("n", 1, 1, listOf("b", "a"), listOf("y", "x"))
        val b = helloChallengeBytesV2("n", 1, 1, listOf("a", "b"), listOf("x", "y"))
        assertTrue(a.contentEquals(b), "sort must be deterministic on both lists")
    }
}
