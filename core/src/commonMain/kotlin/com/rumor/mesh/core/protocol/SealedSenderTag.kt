package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.HmacSha256

/**
 * O53 — Sealed-sender DM recipient tag.
 *
 * **Purpose:** replace the plaintext `recipientId` field on DIRECT
 * messages with `T = HMAC-SHA256(K_recipient_shared, "rumor-dm-v1:" ||
 * messageId)`. Relays match incoming DMs against the small set of
 * `T`s they've precomputed for keys they hold; observers (in-mesh or
 * later-seizing-a-relay) cannot link a DM to a specific recipientId.
 *
 * **Trust model:** the shared key `K_recipient_shared` is per-contact
 * — derived by both parties from the X25519 long-term static keys
 * they hold for each other. Sender and recipient independently
 * compute the same K, so both can compute the same tag for any
 * messageId without prior coordination.
 *
 * **Cost on receive:** for each inbound DIRECT message, run HMAC
 * against every per-contact key (`O(contacts) HMAC` per receive).
 * Cheap — HMAC-SHA256 is ~microseconds; a node with 1000 contacts
 * runs ~milliseconds of total work per inbound DM. The privacy
 * win pays for it.
 *
 * **Wiring is NOT done in this commit** — see CLAUDE.md O53 row.
 * This file is the helper function so a future GossipEngine
 * integration has one stable place to look up the domain-tag format
 * and the HMAC call. Field placement in `_ext` and the migration
 * from plaintext-recipientId-coexisting to tag-only-on-wire are the
 * remaining design decisions.
 *
 * Domain tag is RESERVED — never reuse `rumor-dm-v1:` for any other
 * purpose (per `docs/RENAMED_FIELDS_NEVER_REUSE.md` policy).
 */
object SealedSenderTag {

    private const val DOMAIN_TAG = "rumor-dm-v1:"

    /**
     * Compute the recipient tag for a DM.
     *
     * @param sharedKey Per-contact symmetric key — both sender and
     *   recipient derive the same value from the X25519 static keys
     *   they hold for each other. Recommended derivation:
     *   `HKDF(x25519(my_priv, their_pub), info = "rumor-dm-recipient-tag")`,
     *   but any deterministic derivation both sides agree on works.
     * @param messageId The RumorMessage.id this tag covers.
     * @return 32-byte HMAC-SHA256 output. Callers typically Base64-
     *   encode for wire transmission.
     */
    fun tagFor(sharedKey: ByteArray, messageId: String): ByteArray =
        HmacSha256.mac(sharedKey, (DOMAIN_TAG + messageId).encodeToByteArray())
}
