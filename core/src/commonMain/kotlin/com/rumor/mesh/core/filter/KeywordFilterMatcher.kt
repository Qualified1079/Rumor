package com.rumor.mesh.core.filter

import com.rumor.mesh.core.model.FilterAction
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.FilterHit
import com.rumor.mesh.core.model.FilterMatch
import com.rumor.mesh.core.model.FilterSubscription
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.MatchKind

/**
 * O67 — Stateless matcher over a set of subscribed [KeywordFilterList]s.
 *
 * Hot path is per-message: the UI calls [match] once when rendering a
 * message; result drives whether to collapse / hide / render normally.
 *
 * Composition rules:
 *  - Lists merge by union (one match anywhere is a match).
 *  - Strictest action wins ([FilterAction.BLOCK] beats [FilterAction.WARN]).
 *  - A userId in the publisher's `userIdAllowlist` OR the subscriber's
 *    `localAllowlistAdditions` skips that list entirely (the user explicitly
 *    said "don't filter this sender on this list" — communities
 *    reclaiming slurs, misclassified senders, etc).
 *  - Subscriber action overrides ([FilterSubscription.actionOverrides])
 *    take precedence over the published per-entry action.
 *  - Disabled subscriptions ([FilterSubscription.enabled] = false) are
 *    skipped entirely.
 *
 * The matcher is pure — no IO, no allocation beyond the result struct
 * for the common no-match case. Called once per message render; must be
 * cheap.
 */
object KeywordFilterMatcher {

    /**
     * @param text The message text to evaluate. Empty / null → returns null.
     * @param senderUserId The message's senderId, consulted against per-list
     *   allowlists.
     * @param lists Currently-subscribed filter lists, paired with the
     *   subscriber's local override layer.
     * @return null if no entry matched (render normally); otherwise the
     *   strictest action plus every hit for UI display ("matched X, Y, Z").
     */
    fun match(
        text: String?,
        senderUserId: String,
        lists: List<Pair<KeywordFilterList, FilterSubscription>>,
    ): FilterMatch? {
        if (text.isNullOrEmpty()) return null

        val hits = mutableListOf<FilterHit>()
        for ((list, sub) in lists) {
            if (!sub.enabled) continue

            val effectiveAllowlist = list.userIdAllowlist + sub.localAllowlistAdditions
            if (senderUserId in effectiveAllowlist) continue

            for (entry in list.entries) {
                if (matches(entry, text)) {
                    val effectiveAction = sub.actionOverrides[entry.pattern] ?: entry.action
                    hits.add(FilterHit(list.publisherId, list.name, entry.copy(action = effectiveAction)))
                }
            }
        }

        if (hits.isEmpty()) return null
        val strictest = if (hits.any { it.entry.action == FilterAction.BLOCK }) FilterAction.BLOCK else FilterAction.WARN
        return FilterMatch(strictest, hits)
    }

    /**
     * Single-entry match. Exposed so callers (e.g. tests, debug-render
     * tooling) can probe one pattern at a time.
     */
    fun matches(entry: FilterEntry, text: String): Boolean = when (entry.matchKind) {
        MatchKind.SUBSTRING_CI -> text.contains(entry.pattern, ignoreCase = true)
        MatchKind.SUBSTRING_CS -> text.contains(entry.pattern, ignoreCase = false)
        MatchKind.WORD_CI -> {
            // Word boundary on either side. Cheap manual scan to avoid Regex
            // alloc + (commonMain) the cross-platform regex flavor caveats.
            val needle = entry.pattern.lowercase()
            val hay = text.lowercase()
            var idx = hay.indexOf(needle)
            var found = false
            while (idx >= 0) {
                val leftOk = idx == 0 || !hay[idx - 1].isLetterOrDigit()
                val rightIdx = idx + needle.length
                val rightOk = rightIdx == hay.length || !hay[rightIdx].isLetterOrDigit()
                if (leftOk && rightOk) { found = true; break }
                idx = hay.indexOf(needle, idx + 1)
            }
            found
        }
    }
}
