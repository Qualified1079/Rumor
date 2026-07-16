package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O79 — Multi-recipient envelope wire format for ENCRYPTED rooms.
 *
 * Replaces the originally-spec'd "shared room key + ratchet" approach.
 * The motivation and trade-offs are recorded in the O79 row of CLAUDE.md
 * and in `docs/ROOMS_THREAT_MODEL.md`; this file is the wire-format
 * substrate only.
 *
 * **Construction (sender side):**
 *  1. Generate a fresh 32-byte content key `K_content`.
 *  2. Generate a fresh X25519 ephemeral keypair `(eph_priv, eph_pub)` —
 *     shared across all recipients in this envelope.
 *  3. AES-256-GCM encrypt the plaintext message under `K_content` with
 *     a fresh 12-byte IV → `contentCiphertext` (body || tag) +
 *     `contentIv`.
 *  4. For each authorized recipient `r`:
 *      - `shared = X25519(eph_priv, r.staticPub)`
 *      - `wrap_key = HKDF(shared, info = "rumor-room-wrap-v1:" || r.userId)`
 *      - Generate fresh 12-byte `wrapIv`.
 *      - `wrappedKey = AES-256-GCM(K_content, wrap_key, wrapIv)`
 *      - Append `KeyWrap(r.userId, base64(wrappedKey), base64(wrapIv))`
 *        to [keyWraps].
 *  5. Sign the envelope's canonical bytes (see
 *     [multiRecipientEnvelopeSignableBytes]) with the sender's
 *     long-term Ed25519 → [signature].
 *  6. Zero `K_content`, `eph_priv`, and every per-recipient `shared` /
 *     `wrap_key` after use (O39 sender-side FS pattern).
 *
 * **Decryption (recipient side):**
 *  1. Find a [KeyWrap] in [keyWraps] whose `recipientId` matches your
 *     local userId. If none, you weren't an addressee — drop.
 *  2. `shared = X25519(my_static_priv, senderEphemeralPublic)`
 *  3. `wrap_key = HKDF(shared, info = "rumor-room-wrap-v1:" || my_userId)`
 *  4. `K_content = AES-256-GCM-decrypt(wrappedKey, wrap_key, wrapIv)`
 *  5. `plaintext = AES-256-GCM-decrypt(contentCiphertext, K_content,
 *     contentIv)`
 *  6. Zero `shared`, `wrap_key`, `K_content` after use.
 *
 * **Per-message forward secrecy is automatic.** Each envelope uses
 * fresh ephemerals + a fresh content key; compromise of any
 * recipient's long-term static reveals only envelopes addressed to
 * them, not other recipients' slots.
 *
 * **Member kick:** the sender simply omits the kicked userId from
 * the recipient list on the next envelope. No re-keying, no rotation
 * cascade — the kicked member never had a slot in the new envelope
 * to decrypt. (Past envelopes they received are still plaintext on
 * their device; no protocol can unsend that.)
 *
 * **Wire size:** `contentCiphertext + contentIv` (~content + 28B)
 * plus `~96 bytes per recipient` (userId + base64-encoded 32-byte
 * wrapped key + base64-encoded 12-byte IV). At 50 members: ~5 KB
 * envelope for a 256-byte message; at 1000 members: ~100 KB.
 *
 * **Wiring NOT done in this commit** — see CLAUDE.md O79 row.
 * No `MessageType.ROOM_MESSAGE` enum entry yet, no compose helper,
 * no decrypt helper, no membership-tracking integration. This file
 * is the wire-format substrate + signature scope.
 */
@Serializable
@SerialName("multi_recipient_envelope")
data class MultiRecipientEnvelope(
    /**
     * Opaque routing tag identifying the room. Derived from a
     * per-room routing key shared among members; observers without
     * the key cannot enumerate roomIds. For OPEN rooms (which don't
     * use this envelope type) routing is by plaintext roomId on a
     * separate path.
     */
    val roomRoutingTag: String,
    val senderId: String,
    val senderPublicKey: String,
    /** Sender's X25519 ephemeral public key (Base64), shared across all wraps in this envelope. */
    val senderEphemeralPublic: String,
    /** AES-256-GCM ciphertext (body || tag) of the plaintext under the per-envelope content key. */
    val contentCiphertext: String,
    /** 12-byte IV for the content-ciphertext AEAD. */
    val contentIv: String,
    /** One slot per addressed recipient. */
    val keyWraps: List<KeyWrap>,
    /**
     * Ed25519 signature by [senderPublicKey] over the canonical bytes
     * (see [multiRecipientEnvelopeSignableBytes]). Verifies envelope
     * authenticity and integrity (including the key-wrap list — a
     * relay can't append or remove a recipient slot without breaking
     * the sig).
     */
    val signature: String,
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/** A single recipient's key-wrap slot inside a [MultiRecipientEnvelope]. */
@Serializable
data class KeyWrap(
    val recipientId: String,
    /** AES-256-GCM ciphertext (body || tag) of the 32-byte content key under HKDF-derived wrap key. */
    val wrappedKey: String,
    /** 12-byte IV for the key-wrap AEAD. */
    val wrapIv: String,
)

/**
 * Domain-tagged signable bytes for a [MultiRecipientEnvelope].
 *
 * Binds every field except [MultiRecipientEnvelope.signature] itself
 * (which is what's being computed) and [MultiRecipientEnvelope.ext]
 * (the unsigned forward-compat carrier per O37). KeyWrap entries are
 * sorted by recipientId before inclusion — without that, a relay
 * permuting slot order without re-signing could produce a
 * canonically-different byte sequence; sorting makes the signature
 * stable regardless of recipient-list ordering.
 *
 * Field order is fixed; never reorder without bumping the
 * `rumor-room-envelope-vN:` domain tag.
 */
fun multiRecipientEnvelopeSignableBytes(
    roomRoutingTag: String,
    senderId: String,
    senderPublicKey: String,
    senderEphemeralPublic: String,
    contentCiphertext: String,
    contentIv: String,
    keyWraps: List<KeyWrap>,
): ByteArray = buildString {
    append("rumor-room-envelope-v1:")
    append(roomRoutingTag); append('|')
    append(senderId); append('|')
    append(senderPublicKey); append('|')
    append(senderEphemeralPublic); append('|')
    append(contentCiphertext); append('|')
    append(contentIv); append('|')
    // Sort by recipientId — relay permutation must not produce a
    // canonically-different signable sequence.
    keyWraps.sortedBy { it.recipientId }.forEach { kw ->
        append(kw.recipientId); append(':'); append(kw.wrappedKey); append(':'); append(kw.wrapIv); append(',')
    }
}.encodeToByteArray()
