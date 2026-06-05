package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * O67 — Signed shareable keyword filter lists. Distinct data type from
 * blocklists (which target senders); these target message *content*.
 * Shares ONLY the signing / publish / subscribe / diff distribution
 * machinery with [Blocklist] — schema, semantics, UI, and user model are
 * separate.
 *
 * **What a filter does:** matches against a [com.rumor.mesh.core.model.RumorMessage]
 * payload text and tags the message with [FilterAction.WARN] (render
 * collapsed-with-banner) or [FilterAction.BLOCK] (do not render). Action is
 * a display-layer concern — the relay path is unaffected per the
 * "relay never sees blocklist/filter" architectural invariant.
 *
 * **Not a blocklist:** filters do not affect signature verification, do
 * not enter routing decisions, and do not affect what this node relays
 * to its peers. They only affect what the local user sees.
 *
 * Wire format and gossip distribution follow [Blocklist]'s pattern (see
 * `core/block/`); per-userId exception list ([userIdAllowlist]) is filter-
 * specific.
 */
@Serializable
@SerialName("keyword_filter_list")
data class KeywordFilterList(
    val publisherId: String,
    val version: Long,
    /** Short human-readable name for the UI list editor — not load-bearing. */
    val name: String,
    val entries: List<FilterEntry>,
    /**
     * UserIds the entire list does NOT apply to. Subscriber's local
     * additions to this list are stored separately in [FilterSubscription];
     * this field carries the publisher's own exceptions (e.g. a community-
     * slur list excluding members of that community).
     */
    val userIdAllowlist: Set<String> = emptySet(),
    /** Ed25519 signature over [keywordFilterListSignableBytes]. */
    val signature: String,
    /** Reserved forward-compat carrier. Not covered by [signature]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * One filter rule. [pattern] is matched against the message's text payload
 * with [matchKind] semantics. [action] is the user-overridable default
 * the publisher recommends; subscribers can promote WARN → BLOCK or demote
 * BLOCK → WARN per-list locally without re-publishing.
 */
@Serializable
data class FilterEntry(
    val pattern: String,
    val action: FilterAction,
    val matchKind: MatchKind = MatchKind.SUBSTRING_CI,
    /** Optional label shown in the warning banner ("contains sexual content"). */
    val warnLabel: String? = null,
)

@Serializable
enum class FilterAction {
    /** Render with a warning banner; collapsed by default; user can tap to reveal. */
    WARN,
    /** Do not render the message in the UI at all. Still relayed normally. */
    BLOCK,
}

@Serializable
enum class MatchKind {
    /** Case-insensitive substring of the text. Default — cheapest and most permissive. */
    SUBSTRING_CI,
    /** Case-sensitive substring. */
    SUBSTRING_CS,
    /** Whole-word match (word boundaries), case-insensitive. */
    WORD_CI,
}

/** Subscription mode mirroring [BlocklistMode] for symmetry. */
enum class FilterSubscriptionMode { ONE_TIME, CONTINUOUS }

/**
 * Subscriber-local override layer over a published [KeywordFilterList].
 * The publisher's bytes are immutable; this struct carries the local
 * user's customisations (action promotion/demotion, extra allowlisted
 * userIds, opt-out without unsubscribing). Stored alongside the
 * verified list copy.
 */
data class FilterSubscription(
    val listPublisherId: String,
    val listName: String,
    val mode: FilterSubscriptionMode,
    /** Local user's added userId exceptions (composed with the publisher's). */
    val localAllowlistAdditions: Set<String> = emptySet(),
    /** Per-pattern action override: null entry = inherit publisher's recommendation. */
    val actionOverrides: Map<String, FilterAction> = emptyMap(),
    /** False = locally disabled without unsubscribing (keeps publisher updates flowing). */
    val enabled: Boolean = true,
    /**
     * Gossip privacy: if false, this node holds the list but refuses to
     * offer it back on gossip. Lets a user benefit from the filter
     * without advertising the political/social commitment of subscribing.
     */
    val gossipShare: Boolean = true,
    /**
     * Base64-encoded publisher Ed25519 pubkey. Subscriber-internal
     * tracking state — needed to verify inbound list snapshots against
     * the right key. Persisted alongside the override layer so a
     * subscription survives across process restarts.
     */
    val publisherPublicKey: String = "",
    /** Highest version we've applied so far (monotonicity gate). */
    val lastAppliedVersion: Long = 0L,
)

/**
 * Result of evaluating one or more [KeywordFilterList]s against a message.
 * Strictest applicable action wins (BLOCK > WARN > null).
 */
data class FilterMatch(
    val action: FilterAction,
    /** All matched patterns + which list they came from. */
    val hits: List<FilterHit>,
) {
    val warnLabels: List<String> get() = hits.mapNotNull { it.entry.warnLabel }.distinct()
}

data class FilterHit(val listPublisherId: String, val listName: String, val entry: FilterEntry)

/** Domain-tagged signable bytes for a [KeywordFilterList]. */
fun keywordFilterListSignableBytes(
    publisherId: String,
    version: Long,
    name: String,
    entries: List<FilterEntry>,
    userIdAllowlist: Set<String>,
): ByteArray = buildString {
    append("rumor-keyword-filter-v1:")
    append(publisherId)
    append('|')
    append(version)
    append('|')
    append(name)
    append('|')
    // Sort for deterministic ordering across publishers / versions.
    entries.sortedWith(compareBy({ it.pattern }, { it.matchKind.name })).forEach {
        append(it.pattern); append(':'); append(it.action.name); append(':'); append(it.matchKind.name); append(',')
    }
    append('|')
    userIdAllowlist.sorted().forEach { append(it); append(',') }
}.toByteArray(Charsets.UTF_8)
