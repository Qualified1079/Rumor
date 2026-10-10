package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.platform.Sha256

/**
 * Derived room identity — a roomId is a function of its creator, never an
 * arbitrary string the creator asserts.
 *
 * **Why this exists (security review finding, 2026-10-10).** The first cut of
 * O187 let `RoomCreate` carry any `roomId` and resolved collisions
 * first-writer-wins. Since a roomId is public (the routing tag is a keyless
 * hash of it, computable by anyone), an attacker who learned a roomId before a
 * given node saw the genuine CREATE could announce it first and become that
 * node's creator-moderator for the room — a squatting/hijack race whose winner
 * differed per node, so the mesh disagreed about who governed what.
 *
 * Binding the id to the creator removes the race entirely rather than
 * arbitrating it: a CREATE must be signed by `createdBy` AND carried by that
 * same sender (see `handleRoomLifecycle`), so an attacker cannot produce a
 * CREATE for an id that derives from *someone else's* identity, and an id that
 * derives from their own identity is a different room. There is nothing left to
 * win by being first.
 *
 * This follows the codebase's existing grain — `userId = SHA-256(publicKey)`,
 * `trafficClass` derived from type, the routing tag derived from the roomId —
 * where identity is computed and re-checkable, never claimed.
 */
object RoomId {

    private const val DOMAIN = "rumor-room-id-v1:"

    /**
     * The canonical roomId for a room created by [creatorId] with [nonce].
     *
     * [nonce] lets one creator make many rooms and keeps ids unguessable for
     * rooms that have not been announced yet; it is carried in the CREATE so
     * every receiver can recompute the id and reject a mismatch.
     */
    fun derive(creatorId: String, nonce: String): String =
        Sha256.digest("$DOMAIN$creatorId:$nonce".encodeToByteArray()).toBase64()

    /** Whether [roomId] genuinely derives from [creatorId] and [nonce]. */
    fun matches(roomId: String, creatorId: String, nonce: String): Boolean =
        roomId == derive(creatorId, nonce)
}
