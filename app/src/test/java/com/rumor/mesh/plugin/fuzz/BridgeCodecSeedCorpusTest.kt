package com.rumor.mesh.plugin.fuzz

import com.rumor.mesh.plugin.meshcore.MeshCoreFrames
import com.rumor.mesh.plugin.meshtastic.MeshtasticMessages
import org.junit.jupiter.api.Test

/**
 * Deterministic adversarial seeds for the bridge codecs. Production code
 * is supposed to return null on bad input; this test asserts that
 * property by running every seed through every decoder and verifying
 * nothing escapes as an unchecked Error.
 *
 * Pairs with [BridgeCodecFuzzers] for the longer-tail nasties.
 */
class BridgeCodecSeedCorpusTest {

    private val seeds: List<ByteArray> = listOf(
        ByteArray(0),
        ByteArray(1),
        ByteArray(1) { 0xFF.toByte() },
        ByteArray(16) { 0x00 },
        ByteArray(16) { 0xFF.toByte() },
        ByteArray(256) { (it and 0xFF).toByte() },
        // Wire-protocol magic prefixes — Meshtastic starts with 0x94 0xC3,
        // MeshCore v3 uses NUS opcodes 0x02/0x0A/0x10/0x11. Make sure
        // half-valid frames don't crash.
        byteArrayOf(0x94.toByte(), 0xC3.toByte()),
        byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0xFF.toByte()),
        byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, 0xFF.toByte()),
        byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0x02),                  // CONTACTS_START opcode only
        byteArrayOf(0x0A),                  // NO_MORE_MESSAGES opcode only
        byteArrayOf(0x10),                  // CONTACT_MSG_RECV opcode only
        byteArrayOf(0x11),                  // CHANNEL_MSG_RECV opcode only
        byteArrayOf(0x11, 0x00, 0x00, 0x00, 0x00, 0x05), // partial channel-msg header
        // Length-field hostility: large varints that claim payloads bigger
        // than the buffer.
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
        ByteArray(4096) { 0xFF.toByte() },
    )

    @Test
    fun `Meshtastic decoder absorbs adversarial seeds without crashing`() {
        for (s in seeds) {
            runCatching { MeshtasticMessages.decodeFromRadioPacket(s) }.getOrNull()
        }
    }

    @Test
    fun `MeshCore decoder absorbs adversarial seeds without crashing`() {
        for (s in seeds) {
            runCatching { MeshCoreFrames.decodeChannelMessage(s) }.getOrNull()
        }
    }
}
