package com.rumor.mesh.core.filter

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.FilterEntry
import com.rumor.mesh.core.model.KeywordFilterList
import com.rumor.mesh.core.model.keywordFilterListSignableBytes

/**
 * O67 — Publishes the local user's own [KeywordFilterList]s, signed
 * with their Ed25519 identity key. Mirrors
 * [com.rumor.mesh.core.block.BlocklistPublisher]'s shape.
 *
 * A "publisher" here is just any user authoring a filter list — there's
 * no central authority. Community moderators / curators are users who
 * happen to be trusted by their subscribers (see "auto-intake by
 * publisher" in the O67 backlog row).
 *
 * [version] uses wall-clock `SystemClock.now()` to give subscribers a
 * monotonic ordering across re-publishes by the same author. Honest
 * monotonicity matters more than absolute time — a future-dated version
 * is fine as long as it's strictly greater than every previous one from
 * this publisher.
 */
class KeywordFilterPublisher(
    private val identityProvider: IdentityProvider,
) {
    private val TAG = "KeywordFilterPublisher"

    /**
     * Sign and return a [KeywordFilterList] with the local identity.
     * Returns null if identity is locked.
     */
    fun publish(
        name: String,
        entries: List<FilterEntry>,
        userIdAllowlist: Set<String> = emptySet(),
        version: Long = SystemClock.now(),
    ): KeywordFilterList? {
        val identity = identityProvider.identity.value ?: run {
            RumorLog.w(TAG, "Cannot publish — identity locked")
            return null
        }
        val signed = keywordFilterListSignableBytes(
            identity.userId, version, name, entries, userIdAllowlist,
        )
        val sig = CryptoManager.sign(signed, identity.privateKeyBytes)
        return KeywordFilterList(
            publisherId = identity.userId,
            version = version,
            name = name,
            entries = entries,
            userIdAllowlist = userIdAllowlist,
            signature = sig.toBase64(),
        )
    }
}
