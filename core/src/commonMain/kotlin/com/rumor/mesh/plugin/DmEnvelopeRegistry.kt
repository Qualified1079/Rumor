package com.rumor.mesh.plugin

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Thread-safe registry mapping recipient userId prefixes to [DmEnvelope] implementations.
 *
 * Security invariants (from O5a design):
 * - One envelope per prefix. [register] throws [IllegalStateException] on collision —
 *   prevents prefix-squatting plugins from intercepting another bridge's DMs.
 * - [unregister] is atomic and idempotent; called by [PluginRegistry] on plugin teardown
 *   before [RumorPlugin.onDetach] so the envelope is unreachable before cleanup runs.
 * - [forRecipient] performs a prefix scan; first match wins. Registration order is
 *   preserved (insertion-ordered map) so longer prefixes registered first take priority
 *   if prefixes ever overlap — avoid overlapping prefixes; the registry does not sort.
 */
class DmEnvelopeRegistry {

    private val lock = SynchronizedObject()
    private val byPrefix = LinkedHashMap<String, DmEnvelope>()

    /**
     * Register [envelope] for its [DmEnvelope.recipientPrefix].
     *
     * Prefix validation (M1): must end with `:` and be at least 2 chars. A trivial or
     * empty prefix could intercept native Rumor DMs (hex userIds never contain `:`),
     * silently diverting them into a plugin envelope.
     *
     * Envelope id validation (L1): restricted to `[A-Za-z0-9_.-]+` so it can't contain
     * the `:` separator used to delimit `envelopeId:base64ciphertext` on the wire.
     *
     * @throws IllegalArgumentException if the prefix or envelope id is invalid.
     * @throws IllegalStateException if the prefix is already owned by another envelope.
     */
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    fun register(envelope: DmEnvelope) {
        require(envelope.recipientPrefix.endsWith(":") && envelope.recipientPrefix.length >= 2) {
            "DmEnvelope.recipientPrefix must end with ':' and be at least 2 chars " +
                "(got '${envelope.recipientPrefix}')"
        }
        require(ENVELOPE_ID_PATTERN.matches(envelope.envelopeId)) {
            "DmEnvelope.envelopeId must match $ENVELOPE_ID_PATTERN " +
                "(got '${envelope.envelopeId}')"
        }
        synchronized(lock) {
            check(envelope.recipientPrefix !in byPrefix) {
                "DmEnvelope prefix '${envelope.recipientPrefix}' is already registered " +
                    "by envelope '${byPrefix[envelope.recipientPrefix]?.envelopeId}'"
            }
            byPrefix[envelope.recipientPrefix] = envelope
        }
    }

    private companion object {
        private val ENVELOPE_ID_PATTERN = Regex("^[A-Za-z0-9_.-]+$")
    }

    /** Remove the envelope for [recipientPrefix]. No-op if not registered. */
    fun unregister(recipientPrefix: String) {
        synchronized(lock) { byPrefix.remove(recipientPrefix) }
    }

    /**
     * Returns the envelope whose [DmEnvelope.recipientPrefix] is a prefix of [userId],
     * or null if no envelope matches (caller falls back to native Rumor X25519+AES-GCM).
     */
    fun forRecipient(userId: String): DmEnvelope? = synchronized(lock) {
        byPrefix.entries.firstOrNull { (prefix, _) -> userId.startsWith(prefix) }?.value
    }
}
