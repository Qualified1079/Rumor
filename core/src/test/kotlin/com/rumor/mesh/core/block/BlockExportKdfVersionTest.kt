package com.rumor.mesh.core.block

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.data.memory.InMemoryBlockEntryRepository
import com.rumor.mesh.core.data.memory.InMemoryBlocklistEntryRepository
import com.rumor.mesh.core.data.memory.InMemorySubscribedBlocklistRepository
import com.rumor.mesh.core.platform.PlatformRandom
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O115: the PBKDF2 work factor became part of the export-blob format when it
 * was bumped 100k→600k. New blobs carry the `v2:` prefix; un-prefixed legacy
 * blobs must keep importing at the legacy count or every pre-bump backup a
 * user holds silently stops decrypting.
 */
class BlockExportKdfVersionTest {

    private fun manager() = BlockManager(
        InMemoryBlockEntryRepository(),
        InMemorySubscribedBlocklistRepository(),
        InMemoryBlocklistEntryRepository(),
    )

    private val userA = "a".repeat(64)
    private val userB = "b".repeat(64)

    @Test
    fun `v2 export round-trips`() = runBlocking {
        val src = manager()
        src.block(userA, reason = "spam")
        src.block(userB)
        val blob = src.exportEncrypted("hunter2")
        assertTrue(blob.startsWith("v2:"), "new exports must carry the version prefix")

        val dst = manager()
        assertEquals(2, dst.importEncrypted(blob, "hunter2"))
        assertTrue(dst.isBlocked(userA))
        assertTrue(dst.isBlocked(userB))
    }

    @Test
    fun `legacy un-prefixed blob still imports at the legacy iteration count`() = runBlocking {
        // Build a blob exactly as the pre-O115 exportEncrypted did: no prefix,
        // 100k iterations.
        val src = manager()
        src.block(userA, reason = "legacy")

        val salt = PlatformRandom.nextBytes(16)
        val legacyKey = CryptoManager.deriveKeyFromPassphrase(
            "hunter2", salt, CryptoManager.PBKDF2_ITERATIONS_LEGACY,
        )
        // Reuse the manager's own wire encoding by decrypting a v2 export and
        // re-encrypting under the legacy parameters.
        val v2 = src.exportEncrypted("hunter2").removePrefix("v2:")
        val (v2SaltB64, v2CtB64) = v2.split(":", limit = 2).let { it[0] to it[1] }
        val v2Key = CryptoManager.deriveKeyFromPassphrase(
            "hunter2", v2SaltB64.fromBase64(), CryptoManager.PBKDF2_ITERATIONS,
        )
        val plaintext = CryptoManager.aesGcmDecrypt(
            CryptoManager.AesGcmCiphertext.fromBase64(v2CtB64), v2Key,
        )
        val legacyCt = CryptoManager.aesGcmEncrypt(plaintext, legacyKey)
        val legacyBlob = salt.toBase64() + ":" + legacyCt.toBase64()

        val dst = manager()
        assertEquals(1, dst.importEncrypted(legacyBlob, "hunter2"))
        assertTrue(dst.isBlocked(userA))
    }

    @Test
    fun `wrong passphrase fails cleanly`() = runBlocking {
        val src = manager()
        src.block(userA)
        val blob = src.exportEncrypted("right")
        val dst = manager()
        val result = runCatching { dst.importEncrypted(blob, "wrong") }
        assertTrue(result.isFailure, "AEAD must reject a wrong-passphrase import")
    }
}
