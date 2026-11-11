package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.GossipEngine.Companion.DEFAULT_DIRECT_HOPS
import com.rumor.mesh.core.protocol.GossipEngine.Companion.MAX_DIRECT_HOPS
import com.rumor.mesh.core.wire.withCompressionMetadata
import com.rumor.mesh.core.wire.withSealedSenderTag
import com.rumor.mesh.plugin.BridgedDmOutbound
import com.rumor.mesh.plugin.DmEnvelopeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class DirectMessaging(
    private val identityProvider: IdentityProvider,
    private val dmEnvelopeRegistry: DmEnvelopeRegistry,
    private val messageStore: MessageStore,
    private val scope: CoroutineScope,
    private val outbound: OutboundComposer,
    private val userSendQueued: MutableSharedFlow<Unit>,
    private val outboundBridgedDm: MutableSharedFlow<BridgedDmOutbound>,
) {
    /**
     * Plaintext for outbound DMs keyed by message ID. The ephemeral X25519 private
     * key is discarded after encryption so the sender cannot re-derive the text.
     * Bounded to 500 entries; oldest are evicted by insertion order.
     */
    private val sentDmPlaintext = com.rumor.mesh.core.platform.BoundedFifoMap<String, String>(500)

    fun sentPlaintextFor(messageId: String): String? = sentDmPlaintext[messageId]

    /**
     * @param replyTo Optional parent messageId for thread reconstruction
     *   (O90). Carried in `_ext.replyTo`; unsigned (local-display only).
     * @param mentions Optional userIds explicitly @-mentioned by the
     *   sender (O90). Carried in `_ext.mentions`; unsigned.
     */
    fun composeDirect(
        recipientId: String,
        recipientPublicKey: ByteArray,
        text: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        hopsToLive: Int? = null,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val envelope = dmEnvelopeRegistry.forRecipient(recipientId)
        val encryptedPayload: String
        val rawCiphertext: ByteArray?
        // O76 — set only on the native-envelope path when compression succeeded.
        var compressionMeta: Pair<Int, Int>? = null
        if (envelope != null) {
            val ct = envelope.encrypt(recipientId, recipientPublicKey, text.toByteArray(Charsets.UTF_8))
            encryptedPayload = "${envelope.envelopeId}:${ct.toBase64()}"
            rawCiphertext = ct
        } else {
            // O76 compose-side flip: compress + pad TEXT plaintext, AEAD with
            // compressionAad as associated data so a relay cannot tamper with
            // originalLength. compression-v1 is in LOCAL_SUPPORTED_FEATURES so
            // every Rumor build can decode; no per-recipient gate needed for v1.
            val plaintextBytes = text.toByteArray(Charsets.UTF_8)
            val encoded = com.rumor.mesh.core.wire.CompressedPaddedCodec.encodeForWire(plaintextBytes)
            val ephemeral = CryptoManager.generateX25519KeyPair()
            // O91: recipientPublicKey is an Ed25519 identity pubkey; convert to
            // X25519 via the birational map before DH. Without this, the agreement
            // silently produces a wrong-but-stable secret on the sender side and a
            // different wrong-but-stable secret on the receiver side — pinned by
            // Ed25519AsX25519RoundtripTest. The matching conversion lives in
            // ThreadViewModel.decryptPayload (Ed25519 seed → X25519 priv).
            val recipientX25519Pub = CryptoManager.ed25519ToX25519Public(recipientPublicKey)
            val sharedKey = CryptoManager.x25519Agreement(ephemeral.privateKeyBytes, recipientX25519Pub)
            if (encoded != null) {
                val aad = com.rumor.mesh.core.wire.compressionAad(encoded.originalLength)
                val ct = CryptoManager.aesGcmEncrypt(encoded.bytes, sharedKey, aad)
                encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
                compressionMeta = Pair(encoded.bucketIndex, encoded.originalLength)
            } else {
                // Plaintext exceeded MAX_SINGLE_MESSAGE post-compress; fall back to
                // uncompressed-and-unpadded path. Chunker fallback for >64 KB
                // compressed text is the open follow-up.
                val ct = CryptoManager.aesGcmEncrypt(plaintextBytes, sharedKey)
                encryptedPayload = ephemeral.publicKeyBytes.toBase64() + "." + ct.toBase64()
                compressionMeta = null
            }
            rawCiphertext = null
            // O39 sender-side FS: actively zero the ephemeral private and the derived
            // AES key before they wait for GC. Bytes that live in the heap until GC
            // remain readable by a process-memory dump up to that point; zero-fill
            // makes the FS-after-send guarantee a property of the code, not luck.
            ephemeral.privateKeyBytes.fill(0)
            sharedKey.fill(0)
        }
        val baseMsg = outbound.buildMessage(
            identity = identity,
            type = MessageType.DIRECT,
            hopsToLive = (hopsToLive ?: DEFAULT_DIRECT_HOPS).coerceIn(1, MAX_DIRECT_HOPS),
            encryptedPayload = encryptedPayload,
            recipientId = recipientId,
        )
        val withMeta = (compressionMeta?.let { (bucket, origLen) ->
            baseMsg.withCompressionMetadata(bucket, origLen)
        } ?: baseMsg).applyThreadAndMentionExt(replyTo, mentions)
        // O53 sealed-sender: stamp _ext.t with HMAC(perContactKey, "rumor-dm-v1:" || id)
        // alongside the existing plaintext recipientId (coexistence phase). Recipient
        // can pre-match the tag against per-contact keys without learning recipientId
        // from the wire once the plaintext field is dropped. _ext is not in
        // signableBytes, so the outer Ed25519 sig stays valid. Skipped for bridged DMs
        // — recipientPublicKey is a foreign-network key, not Rumor Ed25519, so the
        // Ed25519→X25519 derivation isn't applicable.
        val msg = if (envelope == null) {
            val tagKey = SealedSenderKey.derive(
                myUserId = identity.userId,
                myEd25519Priv = identity.privateKeyBytes,
                theirUserId = recipientId,
                theirEd25519Pub = recipientPublicKey,
            )
            try {
                val tag = SealedSenderTag.tagFor(tagKey, withMeta.id)
                withMeta.withSealedSenderTag(tag.toBase64())
            } finally {
                tagKey.fill(0)
            }
        } else withMeta
        sentDmPlaintext.put(msg.id, text)
        if (envelope != null && rawCiphertext != null) {
            // Bridged DM: the bridge plugin picks this up via outboundBridgedDm and
            // forwards the raw ciphertext to the external network. Do not enqueue in the
            // gossip scheduler — bridged recipients are not reachable via Rumor peers.
            scope.launch {
                outboundBridgedDm.emit(BridgedDmOutbound(recipientId, envelope.envelopeId, rawCiphertext))
            }
        } else {
            outbound.enqueueImmediate(msg)
            userSendQueued.tryEmit(Unit)
            scope.launch { messageStore.ingestOwn(msg) }
        }
        return msg
    }

    /**
     * O202 — compose an on-receipt delivery ACK for [ackedMessageId] and route
     * it back to [originalSenderId]. Called automatically when a DIRECT DM
     * addressed to us lands (see [processIncoming]); exposed for the host loop /
     * tests. The acked id rides `payload.content` and is covered by the signed
     * transcript (recipientId + content are both signed), so a relay can neither
     * redirect the ACK nor forge which message it confirms.
     */
    fun composeDirectAck(
        originalSenderId: String,
        ackedMessageId: String,
    ): RumorMessage? {
        val identity = identityProvider.identity.value ?: return null
        val msg = outbound.buildMessage(
            identity = identity,
            type = MessageType.DIRECT_ACK,
            hopsToLive = DEFAULT_DIRECT_HOPS,
            payload = MessagePayload(ContentType.CONTROL, ackedMessageId),
            recipientId = originalSenderId,
        )
        outbound.enqueueImmediate(msg)
        return msg
    }
}
