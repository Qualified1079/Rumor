package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.LocalIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrekeyRotatorTest {

    private class FakeClock(var t: Long) : Clock {
        override fun now(): Long = t
    }

    private fun identity(): LocalIdentity {
        val kp = CryptoManager.generateEd25519KeyPair()
        return LocalIdentity(
            userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes),
            deviceId = "dev",
            publicKeyBytes = kp.publicKeyBytes,
            privateKeyBytes = kp.privateKeyBytes,
        )
    }

    @Test
    fun `first rotate mints a prekey the real verifier accepts`() {
        val clock = FakeClock(1_000_000L)
        val rotator = PrekeyRotator(clock)
        val id = identity()

        val publish = rotator.rotateIfDue(id)
        assertNotNull(publish, "the first call must mint a prekey")
        // The signing bytes must match what PrekeyVerifier reconstructs — proves
        // the rotator and the verifier agree on the domain-tagged transcript.
        assertTrue(PrekeyVerifier.verify(publish) is PrekeyVerifier.Result.Accepted)
        assertEquals(id.userId, publish.publisherId)
        assertEquals(clock.t, publish.validFromMs)
        assertEquals(clock.t + PrekeyRotator.DEFAULT_VALIDITY_WINDOW_MS, publish.validToMs)
    }

    @Test
    fun `no re-mint within the rotation period, mints again after it elapses`() {
        val clock = FakeClock(0L)
        val rotator = PrekeyRotator(clock)
        val id = identity()

        val first = rotator.rotateIfDue(id)!!
        // Still inside the period: nothing due.
        clock.t += PrekeyRotator.DEFAULT_ROTATION_PERIOD_MS - 1
        assertNull(rotator.rotateIfDue(id), "must not re-mint before the cadence elapses")
        // Cadence elapsed: a fresh, distinct prekey.
        clock.t += 2
        val second = rotator.rotateIfDue(id)
        assertNotNull(second)
        assertFalse(first.prekeyPublic == second.prekeyPublic, "each rotation is a fresh key")
    }

    @Test
    fun `minted prekey drives a matching X25519 agreement both directions`() {
        // The money property: a sender DHing its ephemeral against the published
        // prekey public derives the same key the receiver derives from the stored
        // private — i.e. the prekey is a usable X25519 static for DM crypto.
        val clock = FakeClock(500L)
        val rotator = PrekeyRotator(clock)
        val id = identity()
        val publish = rotator.rotateIfDue(id)!!

        val senderEphemeral = CryptoManager.generateX25519KeyPair()
        val senderShared = CryptoManager.x25519Agreement(
            senderEphemeral.privateKeyBytes, publish.prekeyPublic.fromBase64(),
        )
        val receiverPriv = rotator.privateFor(publish.prekeyPublic)
        assertNotNull(receiverPriv, "the private for a freshly minted prekey must be present")
        val receiverShared = CryptoManager.x25519Agreement(
            receiverPriv, senderEphemeral.publicKeyBytes,
        )
        assertTrue(senderShared.contentEquals(receiverShared), "both sides must derive the same key")
    }

    @Test
    fun `private is purged and zeroed after validity window plus grace`() {
        val clock = FakeClock(0L)
        val grace = 10_000L
        val rotator = PrekeyRotator(clock, retentionGraceMs = grace)
        val id = identity()
        val publish = rotator.rotateIfDue(id)!!

        // Still retained within grace.
        clock.t = publish.validToMs + grace - 1
        assertNotNull(rotator.privateFor(publish.prekeyPublic), "retained through the grace window")

        // Past validTo + grace: purged, so the private no longer exists — stored
        // ciphertext to this prekey is now unrecoverable (the FS property).
        clock.t = publish.validToMs + grace
        assertNull(rotator.privateFor(publish.prekeyPublic), "purged after grace")
        assertEquals(0, rotator.retainedCount())
    }

    @Test
    fun `privateFor returns null for an unknown prekey`() {
        val rotator = PrekeyRotator(FakeClock(0L))
        rotator.rotateIfDue(identity())
        val unknown = CryptoManager.generateX25519KeyPair().publicKeyBytes.toBase64()
        assertNull(rotator.privateFor(unknown))
    }

    @Test
    fun `an older prekey stays usable within its own window across a rotation`() {
        val clock = FakeClock(0L)
        val rotator = PrekeyRotator(clock)
        val id = identity()
        val first = rotator.rotateIfDue(id)!!

        // Rotate again after the cadence; the first prekey is still inside its
        // 24h validity window, so its private must remain available.
        clock.t += PrekeyRotator.DEFAULT_ROTATION_PERIOD_MS
        val second = rotator.rotateIfDue(id)!!
        assertFalse(first.prekeyPublic == second.prekeyPublic)
        assertNotNull(rotator.privateFor(first.prekeyPublic), "old prekey still valid in its window")
        assertNotNull(rotator.privateFor(second.prekeyPublic))
    }
}
