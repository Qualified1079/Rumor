package com.rumor.mesh.simulator.engine

import com.rumor.mesh.core.data.memory.InMemoryContactRepository
import com.rumor.mesh.core.data.memory.InMemoryMessageRepository
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.MessagePayload
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.DuplicateFilter
import com.rumor.mesh.core.protocol.MessageStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O144 — the length-prefixed v2 signature transcript is the core auth primitive.
 * `signableBytes` is only exercised indirectly by the ingest suite; this pins the
 * transcript property itself: it is deterministic, domain-tagged, and — the whole
 * point of v2 — INJECTIVE across re-partitionings of the trailing free-string
 * fields, so no two distinct field-sequences share a transcript and the splice is
 * structurally impossible (independent of any signature).
 */
class SignableBytesTranscriptTest {

    private val store = MessageStore(
        InMemoryMessageRepository(),
        InMemoryContactRepository(),
        DuplicateFilter(),
    )

    private fun msg(
        id: String = "i".repeat(64),
        senderId: String = "a".repeat(64),
        content: String? = null,
        encryptedPayload: String? = null,
        recipientId: String? = null,
        type: MessageType = MessageType.BROADCAST,
    ) = RumorMessage(
        id = id,
        senderId = senderId,
        senderPublicKey = "pk",
        sequenceNumber = 1,
        sentAtMs = 1_000L,
        type = type,
        hopsToLive = 7,
        payload = content?.let { MessagePayload(ContentType.TEXT, it) },
        encryptedPayload = encryptedPayload,
        recipientId = recipientId,
        signature = "",
    )

    @Test
    fun `transcript is deterministic and domain-tagged`() {
        val m = msg(content = "hello")
        assertArrayEquals(store.signableBytes(m), store.signableBytes(m.copy()))
        assertTrue(
            "transcript must be prefixed with the v2 domain tag",
            String(store.signableBytes(m)).startsWith("rumor-msg-v2:"),
        )
    }

    @Test
    fun `field is length-prefixed so a value containing the delimiter stays unambiguous`() {
        // content "3:x" would be re-partitionable under a bare-delimiter scheme;
        // the length prefix (framed = "<len>:<value>") fixes the boundary.
        val t = String(store.signableBytes(msg(content = "3:x")))
        assertTrue("content framed as its char length + ':' + value", t.contains("3:3:x"))
    }

    @Test
    fun `re-partitioning bytes across content and encryptedPayload changes the transcript`() {
        // The exact O144 splice: "hello" as content, vs "hel" + "lo" across the
        // content/encryptedPayload boundary. v1 concatenation made these identical.
        val whole = store.signableBytes(msg(content = "hello", encryptedPayload = ""))
        val split = store.signableBytes(msg(content = "hel", encryptedPayload = "lo"))
        assertFalse("v2 framing must distinguish the re-partitioned fields", whole.contentEquals(split))
    }

    @Test
    fun `re-partitioning across encryptedPayload and recipientId changes the transcript`() {
        val a = store.signableBytes(
            msg(type = MessageType.DIRECT, encryptedPayload = "ctxy", recipientId = "z".repeat(60)),
        )
        val b = store.signableBytes(
            msg(type = MessageType.DIRECT, encryptedPayload = "ct", recipientId = "xy" + "z".repeat(60)),
        )
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `every field independently affects the transcript`() {
        val base = msg(id = "i".repeat(64), senderId = "a".repeat(64), content = "x", type = MessageType.DIRECT, encryptedPayload = "e", recipientId = "r".repeat(64))
        val variants = listOf(
            base.copy(id = "j".repeat(64)),
            base.copy(senderId = "b".repeat(64)),
            base.copy(senderPublicKey = "pk2"),
            base.copy(sequenceNumber = 2),
            base.copy(sentAtMs = 2_000L),
            base.copy(type = MessageType.BROADCAST),
            base.copy(payload = MessagePayload(ContentType.TEXT, "y")),
            base.copy(encryptedPayload = "e2"),
            base.copy(recipientId = "s".repeat(64)),
        )
        val baseBytes = store.signableBytes(base)
        variants.forEach { v ->
            assertFalse(
                "changing a signed field must change the transcript",
                baseBytes.contentEquals(store.signableBytes(v)),
            )
        }
    }
}
