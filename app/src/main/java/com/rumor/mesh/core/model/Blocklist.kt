package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format types for blocklist publishing and subscription.
 *
 * Every published artifact is signed by the publisher's Ed25519 key. Subscribers
 * verify the signature before applying. Diffs are signed independently of full
 * snapshots so a man-in-the-middle can't inject entries into a delta update.
 */

/**
 * Full blocklist snapshot. Used for one-time subscriptions and as the periodic
 * "ground truth" anchor that diffs are applied against.
 */
@Serializable
@SerialName("blocklist")
data class Blocklist(
    val publisherId: String,
    val version: Long,
    val entries: List<String>,
    /** Ed25519 signature over (publisherId || version || sorted entries) with domain tag "rumor-blocklist-v1:". */
    val signature: String,
)

/**
 * Incremental blocklist update. Subscribers diff their last-applied version against
 * [fromVersion] and only accept the diff if it matches. [toVersion] becomes the new
 * applied version on success.
 */
@Serializable
@SerialName("blocklist_diff")
data class BlocklistDiff(
    val publisherId: String,
    val fromVersion: Long,
    val toVersion: Long,
    val added: List<String>,
    val removed: List<String>,
    /** Ed25519 signature over (publisherId || fromVersion || toVersion || sorted added || sorted removed) with domain tag "rumor-blocklist-diff-v1:". */
    val signature: String,
)

/** Subscription mode for an external blocklist. */
enum class BlocklistMode {
    /** Apply once at subscribe time, ignore further updates. The list is frozen locally. */
    ONE_TIME,
    /** Pull updates from the publisher during gossip; auto-apply additions and removals. */
    CONTINUOUS,
}

/** Domain-tagged bytes a publisher signs for a [Blocklist] snapshot. */
fun blocklistSignableBytes(publisherId: String, version: Long, entries: List<String>): ByteArray =
    buildString {
        append("rumor-blocklist-v1:")
        append(publisherId)
        append('|')
        append(version)
        append('|')
        entries.sorted().forEach { append(it); append(',') }
    }.toByteArray(Charsets.UTF_8)

/** Domain-tagged bytes a publisher signs for a [BlocklistDiff]. */
fun blocklistDiffSignableBytes(
    publisherId: String,
    fromVersion: Long,
    toVersion: Long,
    added: List<String>,
    removed: List<String>,
): ByteArray = buildString {
    append("rumor-blocklist-diff-v1:")
    append(publisherId)
    append('|')
    append(fromVersion)
    append("->")
    append(toVersion)
    append('|')
    append("+")
    added.sorted().forEach { append(it); append(',') }
    append("|")
    append("-")
    removed.sorted().forEach { append(it); append(',') }
}.toByteArray(Charsets.UTF_8)
