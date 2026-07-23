package com.rumor.mesh.core.policy

/**
 * User-configurable rules that gate what the inbox displays.
 *
 * Policy applies only to the inbox emit path — never to relay. Like blocking,
 * suppressing media you don't want doesn't deprive the rest of the mesh of it.
 *
 * Defaults are conservative: accept everything. Users opt in to stricter rules.
 */
data class InboxPolicy(
    /** If true, only contacts can deliver IMAGE/VOICE/FILE — strangers can still text. */
    val contactsOnlyMedia: Boolean = false,
    /** If true, drop any incoming TRANSFER_METADATA from non-contacts before assembly starts. */
    val rejectUnknownTransfers: Boolean = false,
    /**
     * Maximum bytes for an incoming transfer. Metadata above this size is rejected
     * before assembly starts. Null = no limit.
     */
    val maxIncomingBytes: Long? = null,
    /**
     * O135(1)/O136: when true, only FRIENDED senders reach the inbox (the
     * "known peers only" toggle). Relay and storage are unaffected — this is
     * the display-layer allowlist that makes a sybil flood invisible without
     * depriving the mesh. "Friended" is an explicit user act (O136), NOT a
     * mere contact (every exchanged peer auto-becomes a contact, so contacts
     * are worthless as the trust signal — see O134). Enforced by
     * [KnownSendersInboxFilter], which owns the friended-predicate.
     */
    val friendedSendersOnly: Boolean = false,
) {
    companion object {
        val DEFAULT = InboxPolicy()
    }
}
