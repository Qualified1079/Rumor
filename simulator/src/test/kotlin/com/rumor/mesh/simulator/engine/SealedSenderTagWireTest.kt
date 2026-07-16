package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.protocol.SealedSenderKey
import com.rumor.mesh.core.protocol.SealedSenderTag
import com.rumor.mesh.core.wire.sealedSenderTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O53 wire integration — composeDirect stamps `_ext.t` with the
 * sealed-sender HMAC tag, and the recipient (independently deriving the
 * per-contact key from the long-term Ed25519 pair) computes the same tag
 * for the same messageId. This is the property a relay's pre-match table
 * exploits to route DMs without seeing plaintext recipientId.
 *
 * The outer Ed25519 sig is over `signableBytes` which excludes `_ext`, so
 * adding `_ext.t` does NOT invalidate the sig — pinned implicitly by
 * `messagesForExchange` still serving the message after stamping.
 */
class SealedSenderTagWireTest {

    @Test
    fun `composeDirect stamps sealed-sender tag that recipient can rederive`() = runBlocking {
        val (sender, recipient) = twoNodes()

        val recipientIdentity = recipient.identityProvider.identity.value!!
        val senderIdentity = sender.identityProvider.identity.value!!

        val msg = sender.gossipEngine.composeDirect(
            recipientId = recipientIdentity.userId,
            recipientPublicKey = recipientIdentity.publicKeyBytes,
            text = "for ${recipientIdentity.userId}",
        ) ?: error("composeDirect returned null")

        // Sender stamped the tag.
        val stampedB64 = msg.sealedSenderTag
        assertNotNull("composeDirect MUST stamp _ext.t for native DMs", stampedB64)
        val stamped = stampedB64!!.fromBase64()
        assertEquals("Tag must be HMAC-SHA256 width", 32, stamped.size)

        // Recipient derives the per-contact key from its own Ed25519 seed
        // and the sender's published Ed25519 pubkey. The key derivation
        // sorts userIds into the HKDF info so direction doesn't matter.
        val recipientKey = SealedSenderKey.derive(
            myUserId = recipientIdentity.userId,
            myEd25519Priv = recipientIdentity.privateKeyBytes,
            theirUserId = senderIdentity.userId,
            theirEd25519Pub = senderIdentity.publicKeyBytes,
        )
        val recipientTag = SealedSenderTag.tagFor(recipientKey, msg.id)

        assertTrue(
            "Recipient-derived tag must match sender-stamped tag — relay's " +
                "pre-match table works iff this holds",
            recipientTag.contentEquals(stamped)
        )
    }

    @Test
    fun `different recipients on the same sender produce different stamped tags`() = runBlocking {
        val (sender, a, b) = threeNodes()

        val aId = a.identityProvider.identity.value!!
        val bId = b.identityProvider.identity.value!!

        val toA = sender.gossipEngine.composeDirect(
            recipientId = aId.userId, recipientPublicKey = aId.publicKeyBytes, text = "to A",
        )!!
        val toB = sender.gossipEngine.composeDirect(
            recipientId = bId.userId, recipientPublicKey = bId.publicKeyBytes, text = "to B",
        )!!

        val tagA = toA.sealedSenderTag!!.fromBase64()
        val tagB = toB.sealedSenderTag!!.fromBase64()
        assertTrue(
            "DMs to different recipients must stamp different tags — otherwise " +
                "a relay holding one contact's key could mis-match for another",
            !tagA.contentEquals(tagB)
        )
    }

    private fun twoNodes(): Pair<SimNode, SimNode> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return SimNode(0, scope) to SimNode(1, scope)
    }

    private fun threeNodes(): Triple<SimNode, SimNode, SimNode> {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return Triple(SimNode(0, scope), SimNode(1, scope), SimNode(2, scope))
    }
}
