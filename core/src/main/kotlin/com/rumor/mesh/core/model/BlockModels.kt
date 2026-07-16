package com.rumor.mesh.core.model

/**
 * One personal-block record placed by the local user. Read by the
 * inbox display filter only — the relay path never consults these
 * (per the "relay never sees blocklist" architectural rule). A
 * blocked sender's messages still propagate through this node for
 * the rest of the mesh's benefit; they're suppressed only in the
 * local UI.
 *
 * @property userId The blocked sender's userId.
 * @property createdAtMs Wall-clock epoch ms when the block was set.
 * @property expiresAtMs Wall-clock epoch ms when the block expires;
 *   null = permanent.
 * @property reason User-entered free-text reason; surfaced in the
 *   local block-list UI. Not gossiped (those are O78's signed
 *   public block-reasons).
 */
data class BlockEntry(
    val userId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
    val reason: String?,
)

/**
 * Local record of a subscription to a *remotely published* blocklist
 * (the user has chosen to honour a third party's curated block list,
 * e.g. a community moderator's list).
 *
 * @property publisherId The publisher's userId.
 * @property publisherPublicKey Base64-encoded Ed25519 public key the
 *   user pinned at subscribe time; future updates must sign with
 *   this key to be accepted.
 * @property mode ONE_TIME (frozen snapshot) or CONTINUOUS (auto-pull
 *   updates from the gossip layer).
 * @property currentVersion Highest version of the publisher's
 *   blocklist this node has applied so far; gates monotonicity.
 * @property subscribedAtMs Wall-clock epoch ms of initial subscribe.
 * @property lastAppliedAtMs Wall-clock epoch ms of the last
 *   successful snapshot/diff application.
 */
data class SubscribedBlocklist(
    val publisherId: String,
    val publisherPublicKey: String,
    val mode: BlocklistMode,
    val currentVersion: Long,
    val subscribedAtMs: Long,
    val lastAppliedAtMs: Long,
)

/**
 * Per-(publisher, blocked-user) row inside the externally-subscribed
 * blocklist store. The effective subscribed-block set for inbox
 * filtering is the union of these rows across all
 * [SubscribedBlocklist]s, computed at query time so adding or
 * removing a subscription is just an upsert/delete.
 */
data class BlocklistEntry(
    val publisherId: String,
    val blockedUserId: String,
)
