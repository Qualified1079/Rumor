package com.rumor.mesh.core.data

import com.rumor.mesh.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * The persisted directory of known [Contact]s — every userId the
 * local node has seen and chosen to remember, with the metadata
 * (Ed25519 publicKey, displayName, verification status, per-contact
 * UX toggles) that govern UI rendering and per-peer behavior.
 *
 * **Identity binding lives here.** `userId = SHA-256(publicKey)` is a
 * cryptographic identity; this repo holds the (userId, publicKey)
 * pair recipients verify outbound DMs against and senders verify
 * inbound signatures against. A contact's `publicKey` is what
 * [com.rumor.mesh.core.protocol.GossipEngine.composeDirect] consumes
 * when wrapping a DM body to that recipient.
 *
 * **Per-contact behavioural toggles** ([setAutoRelay], [setAlwaysSave],
 * [setWillingToCache], [setPriorityPeer]) configure how aggressively
 * the local node carries that contact's traffic. None of these affect
 * the relay path for someone else's messages (per the "relay never
 * sees blocklist" rule); they only govern this node's own behavior.
 *
 * **Capability cache** ([setSupportedFeatures]) is the substrate for
 * O76 compression-v1 and future per-peer feature gates: store what
 * the peer advertised in HELLO so [GossipEngine.composeDirect] can
 * decide per-recipient whether to use a new wire feature without
 * triggering an extra handshake.
 *
 * Three impls in tree, one contract — same pattern as
 * [MessageRepository].
 */
interface ContactRepository {
    suspend fun upsert(contact: Contact)
    fun observeAll(): Flow<List<Contact>>
    suspend fun getById(userId: String): Contact?
    fun observeById(userId: String): Flow<Contact?>
    suspend fun getPublicKey(userId: String): String?
    suspend fun setAutoRelay(userId: String, enabled: Boolean)
    suspend fun setAlwaysSave(userId: String, enabled: Boolean)
    suspend fun setWillingToCache(userId: String, enabled: Boolean)
    suspend fun updateLastSeen(userId: String, ms: Long)
    suspend fun setVerified(userId: String, verified: Boolean)
    suspend fun setDisplayName(userId: String, name: String)
    suspend fun delete(userId: String)
    suspend fun getAutoRelayContacts(): List<Contact>
    suspend fun setPriorityPeer(userId: String, enabled: Boolean)
    suspend fun getPriorityPeers(): List<Contact>
    /**
     * O76 / capability cache — store the peer's HELLO supportedFeatures
     * (JSON-encoded list) for this userId. Called by `GossipSession` on
     * every completed handshake. No-op if the contact isn't on file
     * (the cache is meaningful only for known contacts).
     */
    suspend fun setSupportedFeatures(userId: String, jsonEncodedFeatures: String)
}
