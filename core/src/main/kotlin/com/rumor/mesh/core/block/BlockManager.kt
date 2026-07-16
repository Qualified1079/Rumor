package com.rumor.mesh.core.block

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.BlockEntryRepository
import com.rumor.mesh.core.data.BlocklistEntryRepository
import com.rumor.mesh.core.data.SubscribedBlocklistRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.BlockEntry
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
 * [isBlocked] is the only method the gossip engine calls — called only from the
 * inbox emit path, never from the relay path. Blocking suppresses what the user
 * sees; it never affects what the node forwards.
 */
class BlockManager(
    private val blockEntryRepo: BlockEntryRepository,
    private val subscribedBlocklistRepo: SubscribedBlocklistRepository,
    private val blocklistEntryRepo: BlocklistEntryRepository,
    /**
     * The local node's own userId, if known. Enforces the invariant that a node
     * never blocks itself: [block] refuses it, and [refresh] excludes it from the
     * effective set even if it slipped in via a subscribed list. Defaults to
     * unknown (simulator/tests) — a null provider disables the guard harmlessly.
     */
    private val localUserId: () -> String? = { null },
) {
    private val TAG = "BlockManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @kotlin.concurrent.Volatile private var blockedSet: Set<String> = emptySet()

    private val _blockedFlow = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blockedFlow.asStateFlow()

    init {
        scope.launch {
            refresh()
            while (true) {
                delay(60_000)
                blockEntryRepo.pruneExpired()
                refresh()
            }
        }
    }

    fun isBlocked(userId: String): Boolean = userId in blockedSet

    /** Returns false (a no-op) if [userId] is the local node's own identity. */
    suspend fun block(userId: String, durationMs: Long? = null, reason: String? = null): Boolean {
        if (userId == localUserId()) {
            RumorLog.w(TAG, "Refusing to block own identity ${userId.take(16)}…")
            return false
        }
        val now = SystemClock.now()
        blockEntryRepo.upsert(
            BlockEntry(
                userId = userId,
                createdAtMs = now,
                expiresAtMs = durationMs?.let { now + it },
                reason = reason,
            )
        )
        refresh()
        RumorLog.i(TAG, "Blocked ${userId.take(16)}… ${if (durationMs == null) "permanently" else "for ${durationMs / 1000}s"}")
        return true
    }

    suspend fun unblock(userId: String) {
        blockEntryRepo.delete(userId)
        refresh()
    }

    suspend fun activeLocalBlocks(): List<BlockEntry> = blockEntryRepo.getActive()

    suspend fun refreshExternal() = refresh()

    suspend fun exportEncrypted(passphrase: String): String {
        val entries = blockEntryRepo.getActive()
        val plaintext = encodeEntries(entries)
        val salt = com.rumor.mesh.core.platform.PlatformRandom.nextBytes(16)
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
        for (e in entries) blockEntryRepo.upsert(e)
        refresh()
        return entries.size
    }

    private suspend fun refresh() {
        val local = blockEntryRepo.getActiveIds().toSet()
        val subscribed = blocklistEntryRepo.getAllBlockedIds().toSet()
        // Never treat our own identity as blocked, even if a subscribed list names
        // it — blocking self is meaningless (relay is blocklist-blind, our own feed
        // renders from the store) and only invites the publish-then-hide-me footgun.
        val union = (local + subscribed) - setOfNotNull(localUserId())
        blockedSet = union
        _blockedFlow.value = union
    }

    private fun encodeEntries(entries: List<BlockEntry>): ByteArray = buildString {
        for (e in entries) {
            append(e.userId); append('\t')
            append(e.createdAtMs); append('\t')
            append(e.expiresAtMs ?: ""); append('\t')
            append(e.reason ?: "")
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun decodeEntries(bytes: ByteArray): List<BlockEntry> =
        bytes.decodeToString().lineSequence().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            BlockEntry(
                userId = f[0],
                createdAtMs = f[1].toLong(),
                expiresAtMs = f.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toLong(),
                reason = f.getOrNull(3)?.takeIf { it.isNotEmpty() },
            )
        }.toList()
}
