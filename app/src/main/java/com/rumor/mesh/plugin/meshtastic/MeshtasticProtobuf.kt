package com.rumor.mesh.plugin.meshtastic

import java.io.ByteArrayOutputStream

/**
 * Minimal hand-rolled protobuf codec for the Meshtastic subset we need.
 *
 * Why hand-rolled instead of `protoc`
 * -----------------------------------
 * We touch ~5 message types (FromRadio, ToRadio, MeshPacket, Data, MyNodeInfo)
 * and within those, ~12 fields total. Pulling the `com.google.protobuf` Gradle
 * plugin and a 300 KB runtime to handle a dozen fields is the textbook
 * definition of premature abstraction. Hand-rolled also means the bridge has
 * no Protobuf-Lite dependency, which keeps the F-Droid build trivial.
 *
 * What is supported
 * -----------------
 *  • Varint encode/decode (the lingua franca of protobuf — tags, ints, lengths)
 *  • Length-delimited fields (strings, bytes, sub-messages)
 *  • Fixed32 (used by Meshtastic for some IDs)
 *  • Skipping unknown fields so we don't choke on firmware-added extensions
 *
 * Wire format primer
 * ------------------
 * Each field is preceded by a varint tag:
 *     tag = (fieldNumber << 3) | wireType
 *     wireType 0 = varint
 *     wireType 1 = fixed64       (we don't use — skip if seen)
 *     wireType 2 = length-delimited (bytes, string, sub-message)
 *     wireType 5 = fixed32
 * Unknown wireTypes 3/4 (legacy groups) shouldn't appear in modern protos —
 * we throw if they do, because their length is implicit and we can't skip
 * them without a full parse.
 */
internal object MeshtasticProtobuf {

    /**
     * Wire-format reader. Backed by a single byte array — Meshtastic frames
     * are small (max 512 bytes per the framing spec) so we don't bother with
     * streaming. Treating this as a value-type-ish helper keeps callers simple.
     */
    class Reader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {

        fun hasMore() = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "Truncated varint at pos=$pos" }
                val b = bytes[pos++].toLong() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80L) == 0L) return result
                shift += 7
                require(shift < 64) { "Varint too long" }
            }
        }

        fun readInt() = readVarint().toInt()

        fun readFixed32(): Int {
            require(pos + 4 <= end) { "Truncated fixed32" }
            // Little-endian per protobuf spec.
            val v = (bytes[pos].toInt() and 0xFF) or
                    ((bytes[pos+1].toInt() and 0xFF) shl 8) or
                    ((bytes[pos+2].toInt() and 0xFF) shl 16) or
                    ((bytes[pos+3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun readBytes(): ByteArray {
            val len = readInt()
            require(pos + len <= end) { "Truncated length-delimited at pos=$pos len=$len" }
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun readString() = String(readBytes(), Charsets.UTF_8)

        /** Parse a sub-message via [block] inside a length-delimited boundary. */
        fun <T> readSubMessage(block: (Reader) -> T): T {
            val len = readInt()
            val sub = Reader(bytes, pos, pos + len)
            pos += len
            return block(sub)
        }

        /**
         * Skip an unknown field's payload. Called when we see a tag whose
         * fieldNumber we don't care about — keeps the parser forward-compatible
         * with newer firmware versions that add fields we haven't modelled.
         */
        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { require(pos + 8 <= end); pos += 8 }
                2 -> { val len = readInt(); require(pos + len <= end); pos += len }
                5 -> { require(pos + 4 <= end); pos += 4 }
                else -> error("Unsupported wireType $wireType (legacy group?) — cannot skip")
            }
        }
    }

    /** Decompose a wire tag back into (fieldNumber, wireType). */
    fun fieldNumber(tag: Int) = tag ushr 3
    fun wireType(tag: Int) = tag and 0x7

    // ── Writer ────────────────────────────────────────────────────────────────

    class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun writeVarint(value: Long) {
            var v = value
            // Protobuf uses unsigned interpretation; for negative ints callers
            // should use zigzag (we don't need it for any field we touch).
            while ((v and 0x7F.inv().toLong()) != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt() and 0x7F)
        }

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        /** Write a length-delimited field (bytes / string / sub-message body). */
        fun writeLengthDelimited(fieldNumber: Int, payload: ByteArray) {
            writeTag(fieldNumber, 2)
            writeVarint(payload.size.toLong())
            out.write(payload)
        }

        fun writeString(fieldNumber: Int, value: String) =
            writeLengthDelimited(fieldNumber, value.toByteArray(Charsets.UTF_8))

        fun writeInt32(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, 0)
            writeVarint(value.toLong() and 0xFFFFFFFFL)
        }

        fun writeFixed32(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, 5)
            // Little-endian per spec.
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        /** Write a sub-message by serialising [block] into a fresh sub-writer. */
        fun writeSubMessage(fieldNumber: Int, block: (Writer) -> Unit) {
            val sub = Writer()
            block(sub)
            writeLengthDelimited(fieldNumber, sub.toByteArray())
        }
    }
}
