package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.data.BlockEntryDao
import com.rumor.mesh.data.BlockEntryEntity
import com.rumor.mesh.data.BlocklistEntryDao
import com.rumor.mesh.data.SubscribedBlocklistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the inbox-layer blocked set.
 *
 * Local blocks live in [BlockEntryDao]; subscribed blocks live in [BlocklistEntryDao].
 * The effective blocked set is their union, refreshed on writes and on a 60s timer
 * (the timer covers expiring timed blocks).
 *
 * [isBlocked] is the only method the gossip engine calls — and it is called only
 * from the inbox emit path, never from the relay path. This separation is the
 * load-bearing invariant of the architecture: blocking suppresses what the user
 * sees, never what the node forwards.
 */
class BlockManager(
    private val blockEntryDao: BlockEntryDao,
    private val subscribedBlocklistDao: SubscribedBlocklistDao,
    private val blocklistEntryDao: BlocklistEntryDao,
) {
    private val TAG = "BlockManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** In-memory mirror of the union (local + subscribed). Refreshed on writes and timer. */
    @Volatile private var blockedSet: Set<String> = emptySet()

    private val _blockedFlow = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blockedFlow.asStateFlow()

    init {
        scope.launch {
            refresh()
            while (true) {
                delay(60_000)
                blockEntryDao.pruneExpired()
                refresh()
            }
        }
    }

    /**
     * Hot-path check used by the inbox filter. Reads the in-memory mirror, no I/O.
     */
    fun isBlocked(userId: String): Boolean = userId in blockedSet

    // ── Local blocks ──────────────────────────────────────────────────────────

    /** [durationMs] = null for permanent. */
    suspend fun block(userId: String, durationMs: Long? = null, reason: String? = null) {
        val now = System.currentTimeMillis()
        blockEntryDao.upsert(
            BlockEntryEntity(
                userId = userId,
                createdAtMs = now,
                expiresAtMs = durationMs?.let { now + it },
                reason = reason,
            )
        )
        refresh()
        RumorLog.i(TAG, "Blocked ${userId.take(16)}… ${if (durationMs == null) "permanently" else "for ${durationMs / 1000}s"}")
    }

    suspend fun unblock(userId: String) {
        blockEntryDao.delete(userId)
        refresh()
    }

    suspend fun activeLocalBlocks(): List<BlockEntryEntity> = blockEntryDao.getActive()

    /**
     * Refresh the in-memory union after an externally-driven write — e.g.
     * [BlocklistGossipBridge] applying a subscribed publisher's snapshot.
     */
    suspend fun refreshExternal() = refresh()

    // ── Encrypted export / import ─────────────────────────────────────────────

    /**
     * Encrypt the local block list with a passphrase-derived key for backup.
     * Output format: PBKDF2 salt (16) || AES-GCM iv+ciphertext (Base64-combined).
     */
    suspend fun exportEncrypted(passphrase: String): String {
        val entries = blockEntryDao.getActive()
        val plaintext = encodeEntries(entries)
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = CryptoManager.deriveKeyFromPassphrase(passphrase, salt)
        val ct = CryptoManager.aesGcmEncrypt(plaintext, key)
        return salt.toBase64() + ":" + ct.toBase64()
    }

    suspend fun importEncrypted(blob: String, passphrase: String): Int {
        val (saltB64, ctB64) = blob.split(":", limit = 2).let { it[0] to it[1] }
        val salt = saltB64.fromBase64()
        val key = CryptoManager.deriveKeyFromPassphrase(passphrase, salt)
        val plaintext = CryptoManager.aesGcmDecrypt(
            CryptoManager.AesGcmCiphertext.fromBase64(ctB64), key,
        )
        val entries = decodeEntries(plaintext)
        for (e in entries) blockEntryDao.upsert(e)
        refresh()
        return entries.size
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun refresh() {
        val local = blockEntryDao.getActiveIds().toSet()
        val subscribed = blocklistEntryDao.getAllBlockedIds().toSet()
        val union = local + subscribed
        blockedSet = union
        _blockedFlow.value = union
    }

    /** Simple line-based encoding for export: one entry per line, tab-separated fields. */
    private fun encodeEntries(entries: List<BlockEntryEntity>): ByteArray = buildString {
        for (e in entries) {
            append(e.userId); append('\t')
            append(e.createdAtMs); append('\t')
            append(e.expiresAtMs ?: ""); append('\t')
            append(e.reason ?: "")
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun decodeEntries(bytes: ByteArray): List<BlockEntryEntity> =
        String(bytes, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            BlockEntryEntity(
                userId = f[0],
                createdAtMs = f[1].toLong(),
                expiresAtMs = f.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLong(),
                reason = f.getOrNull(3)?.takeIf { it.isNotEmpty() },
            )
        }.toList()
}
