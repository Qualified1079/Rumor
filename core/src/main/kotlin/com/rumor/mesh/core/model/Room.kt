package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O79 — Room wire-format vocabulary.
 *
 * **Scope of this file:** the smallest set of stable types future work
 * can build on without committing to the open design questions
 * (moderator key rotation; conflict resolution on contradictory mod
 * actions; fork semantics; edit/delete policy). Specifically: the
 * Room creation envelope, the membership-policy enum, the posting-
 * policy enum, room-invite payload, and the moderator-action payload.
 *
 * **What's intentionally NOT here yet:**
 *  - GossipEngine compose/relay/route integration (no
 *    `MessageType.ROOM_*` enum entries — those land when the routing
 *    decisions for room-addressed messages are settled)
 *  - Per-Room repositories (depends on whether membership state is
 *    derivable from gossiped events or needs a separate persisted
 *    membership table)
 *  - Crypto for closed-membership key distribution (depends on O52
 *    key-DM-per-member machinery, which depends on O53 sealed-sender)
 *  - UI types (member roster, "Alice has joined" events, etc.)
 *
 * **Why ship the substrate now?** Lets fuzz harnesses and tests
 * exercise the wire shapes; pins the kotlinx-serialization
 * `@SerialName` strings in the never-reuse policy; and gives the
 * future integration commits one canonical place to add ext fields
 * via O37's `_ext` carrier without rediscovering the layout.
 */

/** O79 membership-policy enum. */
@Serializable
enum class RoomMembershipPolicy {
    /** Anyone can join by knowing the roomId. Messages are plaintext. */
    OPEN,
    /** Joining requires a [RoomInvite] DM from a moderator carrying the room key. */
    INVITE,
    /** Anyone with the password can derive the room key via Argon2id(password, roomId). */
    PASSWORD,
    /** Explicit member list; room key distributed per-member via signed key DMs. */
    CLOSED,
}

/** O79 posting-policy enum. Orthogonal to membership policy. */
@Serializable
enum class RoomPostingPolicy {
    /** Only declared members may post; non-members can read but not send. */
    MEMBER_ONLY,
    /** Anyone with the room key may post; mods retain remove/kick/ban authority post-hoc. */
    ANYONE_WITH_MOD_REMOVAL,
    /** All posts require mod approval before they appear (high-friction, low-trust contexts). */
    MOD_APPROVED,
}

/**
 * Signed Room creation announcement. The creator's userId becomes the
 * initial moderator. `roomId = SHA-256(name || createdBy || createdAtMs)` —
 * derivation is the creator's job; verifiers can re-derive locally to
 * catch tampering (a relay forging a roomId that doesn't hash from the
 * other fields).
 *
 * Domain-tagged sig under `rumor-room-create-v1:` — see [roomCreateSignableBytes].
 */
@Serializable
@SerialName("room_create")
data class RoomCreate(
    val roomId: String,
    val name: String,
    val createdBy: String,
    val createdAtMs: Long,
    val membershipPolicy: RoomMembershipPolicy,
    val postingPolicy: RoomPostingPolicy,
    /** Whether the Room creator allows media attachments. False = text-only. */
    val allowMedia: Boolean,
    /** Base64-encoded Ed25519 sig over [roomCreateSignableBytes]. */
    val signature: String,
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Signed invite into a CLOSED or INVITE Room. Carries the room
 * symmetric key encrypted to the invitee's long-term X25519 — only
 * the addressee can extract.
 */
@Serializable
@SerialName("room_invite")
data class RoomInvite(
    val roomId: String,
    val invitedUserId: String,
    /** Base64-encoded ciphertext: AES-GCM(roomKey) under DH(mod_priv, invitee_pub). */
    val encryptedRoomKey: String,
    val expiresAtMs: Long,
    /** Issuing moderator's userId. */
    val moderatorId: String,
    /** Base64 Ed25519 sig by the moderator over [roomInviteSignableBytes]. */
    val moderatorSignature: String,
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/** O79 moderator actions. */
@Serializable
enum class RoomActionKind {
    /** Hide a single message from this Room's view for honoring peers. */
    REMOVE_MESSAGE,
    /** Eject a user from this Room until they rejoin. */
    KICK_USER,
    /** Eject AND block re-joining until the ban is reversed. */
    BAN_USER,
    /** Reverse a prior BAN_USER. */
    UNBAN_USER,
}

/**
 * Signed mod action. Peers honor the action if [moderatorSignature]
 * verifies against the Room's current mod set (which the creator
 * seeded — extending that set is itself a signed action, deferred).
 *
 * [target] is a userId for KICK/BAN/UNBAN or a messageId for
 * REMOVE_MESSAGE. Type discrimination is by [kind].
 */
@Serializable
@SerialName("room_action")
data class RoomAction(
    val roomId: String,
    val kind: RoomActionKind,
    val target: String,
    val reason: String?,
    val moderatorId: String,
    val takenAtMs: Long,
    val moderatorSignature: String,
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged signable bytes for a [RoomCreate]. Binds every
 * field except [signature] itself + [_ext].
 */
fun roomCreateSignableBytes(
    roomId: String,
    name: String,
    createdBy: String,
    createdAtMs: Long,
    membershipPolicy: RoomMembershipPolicy,
    postingPolicy: RoomPostingPolicy,
    allowMedia: Boolean,
): ByteArray = buildString {
    append("rumor-room-create-v1:")
    append(roomId); append('|')
    append(name); append('|')
    append(createdBy); append('|')
    append(createdAtMs); append('|')
    append(membershipPolicy.name); append('|')
    append(postingPolicy.name); append('|')
    append(allowMedia)
}.encodeToByteArray()

/** Domain-tagged signable bytes for a [RoomInvite]. */
fun roomInviteSignableBytes(
    roomId: String,
    invitedUserId: String,
    encryptedRoomKey: String,
    expiresAtMs: Long,
    moderatorId: String,
): ByteArray = buildString {
    append("rumor-room-invite-v1:")
    append(roomId); append('|')
    append(invitedUserId); append('|')
    append(encryptedRoomKey); append('|')
    append(expiresAtMs); append('|')
    append(moderatorId)
}.encodeToByteArray()

/** Domain-tagged signable bytes for a [RoomAction]. */
fun roomActionSignableBytes(
    roomId: String,
    kind: RoomActionKind,
    target: String,
    reason: String?,
    moderatorId: String,
    takenAtMs: Long,
): ByteArray = buildString {
    append("rumor-room-action-v1:")
    append(roomId); append('|')
    append(kind.name); append('|')
    append(target); append('|')
    append(reason ?: ""); append('|')
    append(moderatorId); append('|')
    append(takenAtMs)
}.encodeToByteArray()
