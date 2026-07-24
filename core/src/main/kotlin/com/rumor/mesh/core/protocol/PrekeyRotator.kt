package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.Clock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.PrekeyPublish
import com.rumor.mesh.core.model.prekeyPublishSignableBytes

/**
 * O38 — receiver-side rotation scheduler + local prekey store.
 *
 * The half of receiver-side forward secrecy that lives on the *recipient*: it
 * mints short-lived X25519 prekeys, signs each as a [PrekeyPublish] with the
 * long-term Ed25519 identity (for the host to broadcast), holds the matching
 * private for DM decryption, and — the property that makes FS real — deletes and
 * zeroes each private once its window closes. Stored ciphertext encrypted to a
 * purged prekey is then unrecoverable *even with the long-term key*, because the
 * private literally no longer exists in process memory (privacy by structure, not
 * by comment).
 *
 * Time is injected ([Clock]) so the schedule is deterministic under test.
 *
 * NOT wired yet (follow-ups, see CLAUDE.md O38): the host loop that calls
 * [rotateIfDue] on a timer and broadcasts the result as `PREKEY_PUBLISH`; the
 * sender-side per-contact cache that DHs against a peer's freshest valid prekey;
 * and `composeDirect`/decrypt selecting the prekey path over the long-term
 * static. This class is the local-state core those layers build on.
 */
class PrekeyRotator(
    private val clock: Clock,
    private val rotationPeriodMs: Long = DEFAULT_ROTATION_PERIOD_MS,
    private val validityWindowMs: Long = DEFAULT_VALIDITY_WINDOW_MS,
    /**
     * How long a private is retained past its [validToMs] before purge, so a DM
     * composed just before expiry but delivered after mesh latency still decrypts.
     * The sender never *uses* a prekey past validToMs; this only bounds how long
     * the receiver keeps the private for in-flight ciphertext.
     */
    private val retentionGraceMs: Long = DEFAULT_ROTATION_PERIOD_MS,
) {
    private class Entry(
        val publicB64: String,
        val privateBytes: ByteArray,
        val validFromMs: Long,
        val validToMs: Long,
    )

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var lastRotationMs = Long.MIN_VALUE

    /**
     * Mint + sign a fresh prekey if one is due — no live prekey covers now, or the
     * rotation cadence has elapsed since the last mint. Returns the [PrekeyPublish]
     * the host should broadcast, or null when nothing is due. Also purges any
     * private past its retention window on the way through.
     */
    fun rotateIfDue(identity: LocalIdentity): PrekeyPublish? = synchronized(lock) {
        val now = clock.now()
        purgeExpiredLocked(now)
        val haveCurrent = entries.any { now >= it.validFromMs && now < it.validToMs }
        if (haveCurrent && now - lastRotationMs < rotationPeriodMs) return null

        val kp = CryptoManager.generateX25519KeyPair()
        val pubB64 = kp.publicKeyBytes.toBase64()
        val pubKeyB64 = identity.publicKeyBytes.toBase64()
        val validFrom = now
        val validTo = now + validityWindowMs
        val signed = prekeyPublishSignableBytes(identity.userId, pubKeyB64, pubB64, validFrom, validTo)
        val sig = CryptoManager.sign(signed, identity.privateKeyBytes)

        entries.add(Entry(pubB64, kp.privateKeyBytes, validFrom, validTo))
        if (entries.size > MAX_RETAINED) entries.removeAt(0).privateBytes.fill(0)
        lastRotationMs = now

        return PrekeyPublish(
            publisherId = identity.userId,
            publisherPublicKey = pubKeyB64,
            prekeyPublic = pubB64,
            validFromMs = validFrom,
            validToMs = validTo,
            signature = sig.toBase64(),
        )
    }

    /**
     * The X25519 private for a prekey public (Base64), for DM decryption — a copy
     * the caller may zero after use; the stored original is zeroed only on purge
     * (the FS delete). Null once the prekey has been purged (past retention) or
     * was never minted here.
     */
    fun privateFor(prekeyPublicB64: String): ByteArray? = synchronized(lock) {
        purgeExpiredLocked(clock.now())
        entries.firstOrNull { it.publicB64 == prekeyPublicB64 }?.privateBytes?.copyOf()
    }

    /** Force a purge of privates past their retention window (idempotent). */
    fun purgeExpired() = synchronized(lock) { purgeExpiredLocked(clock.now()) }

    /** Count of prekey privates currently retained — for diagnostics/tests. */
    fun retainedCount(): Int = synchronized(lock) { entries.size }

    private fun purgeExpiredLocked(now: Long) {
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now >= e.validToMs + retentionGraceMs) {
                e.privateBytes.fill(0)
                iter.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_ROTATION_PERIOD_MS = 60 * 60 * 1000L        // rotate hourly
        const val DEFAULT_VALIDITY_WINDOW_MS = 24 * 60 * 60 * 1000L   // 24h validity
        /** Safety cap on retained privates (24h/1h ≈ 25 live + grace); drops oldest. */
        const val MAX_RETAINED = 64
    }
}
