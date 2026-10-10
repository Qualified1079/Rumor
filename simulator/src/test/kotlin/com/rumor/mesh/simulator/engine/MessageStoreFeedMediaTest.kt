package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.wire.withRoomRoutingTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O186/O36 — the ungoverned global feed is text-only, enforced as a drop
 * before enqueue.
 *
 * The rule had no implementation: it was text-only only because
 * `composeRoomMessage` happens to hardcode TEXT, so nothing would have
 * intercepted media the moment a media-to-feed path was added. The
 * discriminator needs no new wire constant — a ROOM_MESSAGE carrying a
 * plaintext `payload` is feed-class by construction, because every *governed*
 * room is ENCRYPTED (O155) and therefore arrives as `encryptedPayload`.
 */
class MessageStoreFeedMediaTest {

    private fun store() = MessageStore(
        InMemoryMessageRepository(),
        InMemoryContactRepository(),
        DuplicateFilter(),
    )

    private val kp = CryptoManager.generateEd25519KeyPair()
    private val userId = CryptoManager.publicKeyToUserId(kp.publicKeyBytes)
    private val feedTag = "ZmVlZHRhZw=="

    /** A validly-signed room message, signed after the O157 tag stamp. */
    private fun MessageStore.signedRoom(
        id: String,
        payload: MessagePayload? = null,
        encryptedPayload: String? = null,
    ): RumorMessage {
        val unsigned = RumorMessage(
            id = id,
            senderId = userId,
            senderPublicKey = kp.publicKeyBytes.toBase64(),
            sequenceNumber = 1,
            sentAtMs = 1_000L,
            type = MessageType.ROOM_MESSAGE,
            hopsToLive = 7,
            payload = payload,
            encryptedPayload = encryptedPayload,
            signature = "",
        ).withRoomRoutingTag(feedTag)
        return unsigned.copy(
            signature = CryptoManager.sign(signableBytes(unsigned), kp.privateKeyBytes).toBase64(),
        )
    }

    @Test
    fun `a plaintext room message carrying media is dropped before enqueue`() = runBlocking {
        val store = store()
        for ((i, type) in listOf(ContentType.IMAGE, ContentType.VOICE, ContentType.FILE).withIndex()) {
            val media = store.signedRoom(
                id = "%032x".format(i + 1),
                payload = MessagePayload(type, "AAAA", filename = "x", mimeType = "image/png", sizeBytes = 4),
            )
            assertFalse("$type must be dropped from the text-only feed", store.ingest(media))
        }
    }

    /** Discrimination control — the drop is specific to media, not to room messages. */
    @Test
    fun `a plaintext text room message still ingests`() = runBlocking {
        val store = store()
        val text = store.signedRoom("b".repeat(32), payload = MessagePayload(ContentType.TEXT, "hello feed"))
        assertTrue("a TEXT feed post must still ingest", store.ingest(text))
    }

    /**
     * A governed (ENCRYPTED) room carries `encryptedPayload` and no plaintext
     * payload, so the feed rule must not touch it — its media policy is its
     * creator's to set via `RoomCreate.allowMedia`. Without this the O36 rule
     * would silently become "no media in any room".
     */
    @Test
    fun `an encrypted room message is not subject to the feed text-only rule`() = runBlocking {
        val store = store()
        val governed = store.signedRoom("c".repeat(32), encryptedPayload = "opaque-ciphertext")
        assertTrue("a governed-room message must pass the feed gate untouched", store.ingest(governed))
    }

    /**
     * O212 regression — the feed drop must NOT burn the message id.
     *
     * `contentType` is NOT in the signed transcript (`signableBytes` covers
     * `payload.content` but not contentType/filename/mimeType/sizeBytes), so a
     * relay can retype a legitimate TEXT post to IMAGE and the signature still
     * verifies. This test pins that reality rather than the comfortable
     * assumption — and then pins the consequence that matters: if the media
     * drop recorded the id as seen, that costless flip would blackhole the
     * genuine post for every downstream node (the §2 censorship primitive).
     *
     * When O212 signs contentType, the first assertion flips and the drop can
     * move below the record-as-seen commit.
     */
    @Test
    fun `a retyped feed post is dropped without blackholing the genuine one`() = runBlocking {
        val store = store()
        val genuine = store.signedRoom("d".repeat(32), payload = MessagePayload(ContentType.TEXT, "hello feed"))
        val retyped = genuine.copy(payload = MessagePayload(ContentType.IMAGE, "hello feed"))

        // Today contentType is unsigned, so the forgery carries a valid signature.
        assertTrue(
            "contentType is outside signableBytes — retyping keeps the sig valid (O212)",
            CryptoManager.verify(
                store.signableBytes(retyped),
                retyped.signature.fromBase64(),
                kp.publicKeyBytes,
            ),
        )
        assertFalse("the retyped (media) post must be dropped", store.ingest(retyped))
        assertTrue(
            "the genuine TEXT post must STILL ingest — the drop must not burn the id",
            store.ingest(genuine),
        )
    }
}
