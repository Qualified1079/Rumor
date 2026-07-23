package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.HostileStrings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O112: userId shape-validation at local (typed) entry points. A userId is
 * exactly 64 lowercase hex chars by construction; every hostile-corpus shape
 * must be rejected, while a real derived userId is accepted.
 */
class UserIdValidationTest {

    @Test
    fun `a real derived userId is accepted`() {
        val kp = CryptoManager.generateEd25519KeyPair()
        val userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes)
        assertTrue(CryptoManager.isValidUserId(userId), "derived userId must validate: $userId")
    }

    @Test
    fun `the whole hostile-strings corpus is rejected`() {
        for (s in HostileStrings.allShort + HostileStrings.oversize) {
            assertFalse(CryptoManager.isValidUserId(s), "must reject hostile input: ${s.take(24)}")
        }
    }

    @Test
    fun `near-misses are rejected`() {
        val hex64 = "a".repeat(64)
        assertTrue(CryptoManager.isValidUserId(hex64))
        assertFalse(CryptoManager.isValidUserId("a".repeat(63)), "too short")
        assertFalse(CryptoManager.isValidUserId("a".repeat(65)), "too long")
        assertFalse(CryptoManager.isValidUserId("A".repeat(64)), "uppercase hex not canonical")
        assertFalse(CryptoManager.isValidUserId("g".repeat(64)), "non-hex letter")
        assertFalse(CryptoManager.isValidUserId("a".repeat(63) + " "), "trailing space")
    }
}
