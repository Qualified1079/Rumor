package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.PrekeyPublish
import com.rumor.mesh.core.platform.ConcurrentMap

/**
 * O38 — sender-side cache of the freshest valid X25519 prekey per recipient.
 *
 * Senders consult this before [com.rumor.mesh.core.protocol.GossipEngine.composeDirect]
 * to decide whether to DH against a short-lived prekey (giving receiver-side
 * FS — the recipient deletes the prekey private after [PrekeyPublish.validToMs]
 * and stored ciphertext becomes unrecoverable) or fall back to the recipient's
 * long-term static identity key.
 *
 * Stateless eviction: every read clock-checks the cached entry's validity
 * window against the caller-supplied wall clock. Expired entries return null
 * from [freshestFor] and are dropped on the next [put] for that recipient.
 * No background sweeper — entries can outlive their window in memory until
 * touched, which is acceptable because the validity-window check on read
 * is what makes them safe to use, not their presence in the map.
 *
 * Validity precedence within a recipient: the entry with the latest
 * [PrekeyPublish.validToMs] wins. Same-window ties are broken by
 * [PrekeyPublish.validFromMs] (more recently issued wins). A late-arriving
 * older prekey can not displace a fresher one — relays cannot trick a
 * sender into using a stale-but-still-valid prekey by replaying it after
 * the recipient has already published a newer one.
 */
class PrekeyCache {

    private val byRecipient = ConcurrentMap<String, PrekeyPublish>()

    /**
     * Cache [publish] for its [PrekeyPublish.publisherId]. Caller MUST have
     * already verified the publish via [PrekeyVerifier]; this class does
     * NOT re-verify.
     *
     * No-op if a strictly fresher entry is already cached for this recipient.
     */
    fun put(publish: PrekeyPublish) {
        byRecipient.compute(publish.publisherId) { _, existing ->
            if (existing == null || isStrictlyFresher(publish, existing)) publish else existing
        }
    }

    /**
     * Return the freshest cached prekey for [recipientId] if one exists and
     * is valid at [nowMs]. Null otherwise — caller falls back to the
     * recipient's long-term static identity for the DH agreement.
     */
    fun freshestFor(recipientId: String, nowMs: Long): PrekeyPublish? {
        val entry = byRecipient[recipientId] ?: return null
        if (nowMs < entry.validFromMs || nowMs >= entry.validToMs) return null
        return entry
    }

    /** Test-affordance: forget everything. Not for production paths. */
    fun clear() { byRecipient.clear() }

    private fun isStrictlyFresher(candidate: PrekeyPublish, existing: PrekeyPublish): Boolean {
        if (candidate.validToMs > existing.validToMs) return true
        if (candidate.validToMs < existing.validToMs) return false
        return candidate.validFromMs > existing.validFromMs
    }
}
