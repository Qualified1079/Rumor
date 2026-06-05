package com.rumor.mesh.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the invariants on `RumorMessage.trafficClass` that the Rumor architecture
 * depends on. From CLAUDE.md "Architecture at a glance":
 *
 * > `trafficClass` is derived, never on the wire — a sender cannot claim
 * > INFRASTRUCTURE class for a bulk payload.
 * > Size ceilings enforced in `trafficClass`: INFRA/REALTIME=16 KB,
 * > TRANSFER_SETUP=256 KB. Oversized → BULK.
 *
 * These tests exist so a sender cannot mislabel a bulky payload to jump
 * the scheduler queue, and so the type→class mapping stays explicit and
 * documented in executable form. If a new `MessageType` is added without
 * being mapped here, the `assertEveryMessageTypeIsExhausted` test must
 * fail, prompting whoever adds it to think about the priority semantics.
 */
class TrafficClassInvariantTest {

    private fun msg(
        type: MessageType,
        contentType: ContentType? = null,
        contentLength: Int = 0,
        encryptedLength: Int = 0,
    ) = RumorMessage(
        id = "00",
        senderId = "00",
        senderPublicKey = "00",
        sequenceNumber = 0,
        sentAtMs = 0,
        type = type,
        hopsToLive = 1,
        payload = contentType?.let {
            MessagePayload(contentType = it, content = "x".repeat(contentLength))
        },
        encryptedPayload = if (encryptedLength > 0) "y".repeat(encryptedLength) else null,
        recipientId = null,
        signature = "00",
    )

    // ── INFRASTRUCTURE-tier types ────────────────────────────────────────────

    @Test fun `PING is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.PING).trafficClass)

    @Test fun `PONG is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.PONG).trafficClass)

    @Test fun `CHUNK_REQUEST is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.CHUNK_REQUEST).trafficClass)

    @Test fun `TRANSFER_CANCEL is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.TRANSFER_CANCEL).trafficClass)

    @Test fun `BLOCKLIST_DIFF is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.BLOCKLIST_DIFF).trafficClass)

    @Test fun `PRIORITY_LINK_REQUEST is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.PRIORITY_LINK_REQUEST).trafficClass)

    @Test fun `PRIORITY_LINK_ACCEPT is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.PRIORITY_LINK_ACCEPT).trafficClass)

    @Test fun `SELF_PRESENCE is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE, msg(MessageType.SELF_PRESENCE).trafficClass)

    // ── TRANSFER_SETUP-tier types ────────────────────────────────────────────

    @Test fun `TRANSFER_METADATA is TRANSFER_SETUP`() =
        assertEquals(TrafficClass.TRANSFER_SETUP, msg(MessageType.TRANSFER_METADATA).trafficClass)

    @Test fun `BLOCKLIST_PUBLISH is TRANSFER_SETUP (full snapshot is bulky)`() =
        assertEquals(TrafficClass.TRANSFER_SETUP, msg(MessageType.BLOCKLIST_PUBLISH).trafficClass)

    @Test fun `KEYWORD_FILTER_PUBLISH is TRANSFER_SETUP (bulky snapshot)`() =
        assertEquals(TrafficClass.TRANSFER_SETUP, msg(MessageType.KEYWORD_FILTER_PUBLISH).trafficClass)

    // ── BULK-tier types ──────────────────────────────────────────────────────

    @Test fun `CHUNK is BULK regardless of size`() =
        assertEquals(TrafficClass.BULK, msg(MessageType.CHUNK).trafficClass)

    // ── BROADCAST / DIRECT / BRIDGE_VOUCHED content-driven ────────────────────

    @Test fun `BROADCAST with TEXT payload is REALTIME`() =
        assertEquals(TrafficClass.REALTIME,
            msg(MessageType.BROADCAST, ContentType.TEXT, contentLength = 100).trafficClass)

    @Test fun `DIRECT with no payload (DM ciphertext only) is REALTIME`() =
        assertEquals(TrafficClass.REALTIME,
            msg(MessageType.DIRECT, encryptedLength = 100).trafficClass)

    @Test fun `BROADCAST with IMAGE payload is BULK`() =
        assertEquals(TrafficClass.BULK,
            msg(MessageType.BROADCAST, ContentType.IMAGE, contentLength = 100).trafficClass)

    @Test fun `BROADCAST with VOICE payload is BULK`() =
        assertEquals(TrafficClass.BULK,
            msg(MessageType.BROADCAST, ContentType.VOICE, contentLength = 100).trafficClass)

    @Test fun `BROADCAST with FILE payload is BULK`() =
        assertEquals(TrafficClass.BULK,
            msg(MessageType.BROADCAST, ContentType.FILE, contentLength = 100).trafficClass)

    @Test fun `BROADCAST with CONTROL payload is INFRASTRUCTURE`() =
        assertEquals(TrafficClass.INFRASTRUCTURE,
            msg(MessageType.BROADCAST, ContentType.CONTROL, contentLength = 100).trafficClass)

    @Test fun `BRIDGE_VOUCHED with TEXT payload is REALTIME`() =
        assertEquals(TrafficClass.REALTIME,
            msg(MessageType.BRIDGE_VOUCHED, ContentType.TEXT, contentLength = 100).trafficClass)

    // ── Size-ceiling demotion: the anti-spoofing invariant ────────────────────

    @Test fun `INFRA type over 16KB ceiling demotes to BULK`() {
        // A custom client claiming PRIORITY_LINK_REQUEST but stuffing 20KB of
        // content gets demoted — can't jump the scheduler queue by mislabelling.
        // PRIORITY_LINK_REQUEST/ACCEPT carry no payload normally, but the
        // mislabel attack is what we're guarding.
        val oversized = msg(MessageType.PRIORITY_LINK_REQUEST, ContentType.TEXT, contentLength = 17_000)
        assertEquals(TrafficClass.BULK, oversized.trafficClass)
    }

    @Test fun `REALTIME (BROADCAST text) over 16KB demotes to BULK`() {
        val oversized = msg(MessageType.BROADCAST, ContentType.TEXT, contentLength = 17_000)
        assertEquals(TrafficClass.BULK, oversized.trafficClass)
    }

    @Test fun `TRANSFER_SETUP over 256KB demotes to BULK`() {
        val oversized = msg(MessageType.TRANSFER_METADATA, ContentType.CONTROL, contentLength = 300_000)
        assertEquals(TrafficClass.BULK, oversized.trafficClass)
    }

    @Test fun `INFRA at exactly 16KB stays INFRA (boundary)`() {
        val atLimit = msg(MessageType.SELF_PRESENCE, ContentType.CONTROL, contentLength = 16 * 1024)
        // 16*1024 is the constant; ">" not ">=" in the check, so exactly 16384 stays INFRA.
        assertEquals(TrafficClass.INFRASTRUCTURE, atLimit.trafficClass)
    }

    @Test fun `INFRA one byte over 16KB demotes`() {
        val justOver = msg(MessageType.SELF_PRESENCE, ContentType.CONTROL, contentLength = 16 * 1024 + 1)
        assertEquals(TrafficClass.BULK, justOver.trafficClass)
    }

    // ── Exhaustiveness guard: every MessageType must have a test above ───────

    @Test fun `assertEveryMessageTypeIsExhausted`() {
        // If a new MessageType lands and nobody tested it here, the assertion
        // catches it before the priority scheduler silently misclassifies it.
        // Update this list and add a test above when adding a new type.
        val tested = setOf(
            MessageType.PING,
            MessageType.PONG,
            MessageType.CHUNK_REQUEST,
            MessageType.TRANSFER_CANCEL,
            MessageType.BLOCKLIST_DIFF,
            MessageType.PRIORITY_LINK_REQUEST,
            MessageType.PRIORITY_LINK_ACCEPT,
            MessageType.SELF_PRESENCE,
            MessageType.TRANSFER_METADATA,
            MessageType.BLOCKLIST_PUBLISH,
            MessageType.KEYWORD_FILTER_PUBLISH,
            MessageType.CHUNK,
            MessageType.BROADCAST,
            MessageType.DIRECT,
            MessageType.BRIDGE_VOUCHED,
        )
        val missing = MessageType.values().toSet() - tested
        assertEquals(emptySet(), missing,
            "MessageType(s) added without TrafficClass coverage: $missing. " +
                "Add a test in this file and append the type to the tested set.")
    }
}
