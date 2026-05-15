package com.rumor.mesh.plugin.meshcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MeshCore companion-protocol frame encoding/decoding.
 *
 * The companion protocol is documented at
 * https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol.
 *
 * Wire layout
 * -----------
 * Over BLE GATT the frame is the raw payload of a single write or notify;
 * there's no length prefix because GATT already preserves message boundaries.
 * Each frame begins with a 1-byte opcode followed by up to 255 data bytes.
 * USB CDC adds a `>`/`<` framing layer with a 2-byte little-endian length;
 * this bridge only does BLE so that framer isn't needed here.
 *
 * Opcodes split into three disjoint ranges so direction is implicit:
 *   0x00–0x18  RESP_*    synchronous reply to a prior CMD_*
 *   0x01–0x38  CMD_*     app → device commands (ranges happen to overlap RESP
 *                        but RESP is only ever seen after a CMD round-trip)
 *   0x80–0x8A  PUSH_*    asynchronous device → app pushes
 *
 * We only model the subset needed for plaintext channel and contact messages.
 * Field layouts are byte-exact; multi-byte integers are little-endian per the
 * spec. Strings are UTF-8 with no terminator — length is implicit from the
 * surrounding frame length on BLE.
 */
internal object MeshCoreFrames {

    // ── Opcodes ───────────────────────────────────────────────────────────────

    // Outbound (app → device)
    const val CMD_APP_START               = 0x01.toByte()
    const val CMD_SEND_TXT_MSG            = 0x02.toByte()
    const val CMD_SEND_CHANNEL_MESSAGE    = 0x03.toByte()
    const val CMD_SYNC_NEXT_MESSAGE       = 0x0A.toByte()
    const val CMD_GET_CONTACTS            = 0x04.toByte()

    // Sync responses (device → app, in reply to a CMD_*)
    const val RESP_CODE_OK                = 0x00.toByte()
    const val RESP_CODE_ERR               = 0x01.toByte()
    const val RESP_CODE_CONTACTS_START    = 0x04.toByte()
    const val RESP_CODE_SELF_INFO         = 0x05.toByte()
    const val RESP_CODE_NO_MORE_MESSAGES  = 0x07.toByte()
    const val RESP_CODE_CONTACT_MSG_RECV  = 0x10.toByte()
    const val RESP_CODE_CHANNEL_MSG_RECV  = 0x11.toByte()

    // Async pushes (device → app, unsolicited)
    const val PUSH_CODE_ADVERTISEMENT     = 0x80.toByte()
    const val PUSH_CODE_PATH_UPDATED      = 0x81.toByte()
    const val PUSH_CODE_SEND_CONFIRMED    = 0x82.toByte()
    const val PUSH_CODE_MESSAGES_WAITING  = 0x83.toByte()
    const val PUSH_CODE_RAW_DATA          = 0x84.toByte()
    const val PUSH_CODE_LOGIN_SUCCESS     = 0x85.toByte()

    /**
     * Decoded view of a channel message received from the radio. We don't keep
     * a sealed hierarchy because (a) we only act on a handful of opcodes and
     * (b) the bridge passes the decoded text straight into Rumor — there's no
     * second consumer that would benefit from polymorphism.
     */
    data class ChannelMessage(
        val channelIdx: Int,
        val timestampSec: Long,
        val senderName: String,
        val text: String,
    )

    /**
     * Encode `CMD_SEND_CHANNEL_MESSAGE`. Layout:
     *
     *     [0x03][0x00 attempt-and-flags][channel_idx u8][timestamp u32 LE][text utf8]
     *
     * The radio caps text at 133 bytes including a header it prepends server-side;
     * we cap input here at 120 bytes to stay safely under that envelope without
     * having to mirror the exact firmware constant.
     */
    fun encodeSendChannelMessage(channelIdx: Int, text: String, nowSec: Long): ByteArray {
        // UTF-8 truncation: cut bytes, not chars, so emoji that span multiple
        // bytes either fit entirely or get dropped — never split mid-codepoint.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val capped = if (bytes.size <= MAX_TEXT_BYTES) bytes else safeTruncateUtf8(bytes, MAX_TEXT_BYTES)
        val buf = ByteBuffer.allocate(1 + 1 + 1 + 4 + capped.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(CMD_SEND_CHANNEL_MESSAGE)
        buf.put(0x00)                           // attempts=0, flags=0
        buf.put((channelIdx and 0xFF).toByte())
        buf.putInt(nowSec.toInt())              // u32 LE; valid until 2106
        buf.put(capped)
        return buf.array()
    }

    /**
     * Parse a channel message coming back as RESP_CODE_CHANNEL_MSG_RECV or via
     * the equivalent push. Returns null on any layout error rather than throwing
     * — a corrupt frame on one side shouldn't kill the bridge for everything else.
     */
    fun decodeChannelMessage(frame: ByteArray): ChannelMessage? {
        // Minimum: opcode + channel + timestamp + 1-byte sender_len + 0-byte name + 0-byte text
        if (frame.size < 7) return null
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buf.get()                                // opcode (already matched by caller)
        val channelIdx = buf.get().toInt() and 0xFF
        val ts = buf.int.toLong() and 0xFFFFFFFFL
        val senderLen = buf.get().toInt() and 0xFF
        if (senderLen > buf.remaining()) return null
        val senderBytes = ByteArray(senderLen).also { buf.get(it) }
        val textBytes = ByteArray(buf.remaining()).also { buf.get(it) }
        return ChannelMessage(
            channelIdx = channelIdx,
            timestampSec = ts,
            senderName = String(senderBytes, Charsets.UTF_8),
            text = String(textBytes, Charsets.UTF_8),
        )
    }

    /**
     * UTF-8-safe truncation. Walks back from `max` until the next byte either
     * starts a new codepoint (top bits != 10xxxxxx) or we hit position 0. This
     * avoids producing a string whose final character is half a multibyte sequence.
     */
    private fun safeTruncateUtf8(bytes: ByteArray, max: Int): ByteArray {
        var cut = max
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return bytes.copyOf(cut)
    }

    private const val MAX_TEXT_BYTES = 120
}
