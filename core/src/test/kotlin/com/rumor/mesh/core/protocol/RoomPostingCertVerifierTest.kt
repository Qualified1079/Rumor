package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.model.RoomPostingCert
import com.rumor.mesh.core.model.roomPostingCertSignableBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomPostingCertVerifierTest {

    private data class Mod(val priv: ByteArray, val pub: ByteArray, val userId: String, val pubB64: String)

    private fun newMod(): Mod {
        val (priv, pub) = CryptoManager.generateEd25519KeyPair()
        return Mod(priv, pub, CryptoManager.publicKeyToUserId(pub), pub.toBase64())
    }

    private fun signed(
        mod: Mod,
        roomId: String = "room-1",
        channel: String? = null,
        userId: String = "userX",
        from: Long = 100L,
        to: Long = 200L,
        overrideSignedToMs: Long? = null,
    ): RoomPostingCert {
        val signedBytes = roomPostingCertSignableBytes(
            roomId, channel, userId, from, overrideSignedToMs ?: to, mod.userId, mod.pubB64,
        )
        val sig = CryptoManager.sign(signedBytes, mod.priv).toBase64()
        return RoomPostingCert(
            roomId = roomId,
            channel = channel,
            userId = userId,
            validFromMs = from,
            validToMs = to,
            moderatorId = mod.userId,
            moderatorPublicKey = mod.pubB64,
            signature = sig,
        )
    }

    @Test fun `valid cert is accepted`() {
        val mod = newMod()
        assertTrue(RoomPostingCertVerifier.verify(signed(mod)) is RoomPostingCertVerifier.Result.Accepted)
    }

    @Test fun `tampered grantee userId rejected`() {
        val mod = newMod()
        val c = signed(mod).copy(userId = "attacker")
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `tampered validity window rejected`() {
        val mod = newMod()
        val c = signed(mod).copy(validToMs = 99999L)
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `tampered channel rejected`() {
        val mod = newMod()
        val c = signed(mod, channel = "general").copy(channel = "private")
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `tampered roomId rejected`() {
        val mod = newMod()
        val c = signed(mod).copy(roomId = "other-room")
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `moderatorId not matching pubkey rejected`() {
        val mod = newMod()
        val c = signed(mod).copy(moderatorId = "attacker")
        val r = RoomPostingCertVerifier.verify(c)
        assertTrue(r is RoomPostingCertVerifier.Result.Rejected)
        assertEquals("moderatorId does not hash from moderatorPublicKey", r.reason)
    }

    @Test fun `inverted window rejected`() {
        val mod = newMod()
        val c = signed(mod, from = 200L, to = 100L)
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `bad base64 signature rejected`() {
        val mod = newMod()
        val c = signed(mod).copy(signature = "***not_b64***")
        assertTrue(RoomPostingCertVerifier.verify(c) is RoomPostingCertVerifier.Result.Rejected)
    }

    @Test fun `channel null and non-null produce distinct signed bytes`() {
        val mod = newMod()
        val noChan = signed(mod, channel = null)
        val withChan = signed(mod, channel = "general")
        // Both verify against their own sigs, but cross-substitution breaks.
        val swapped = noChan.copy(channel = "general")
        assertTrue(RoomPostingCertVerifier.verify(swapped) is RoomPostingCertVerifier.Result.Rejected)
        assertTrue(RoomPostingCertVerifier.verify(noChan) is RoomPostingCertVerifier.Result.Accepted)
        assertTrue(RoomPostingCertVerifier.verify(withChan) is RoomPostingCertVerifier.Result.Accepted)
    }
}
