package com.rumor.mesh.core.crypto

import com.rumor.mesh.core.platform.Sha256

/**
 * HMAC-SHA-256 in pure Kotlin over the cross-platform [Sha256] shim.
 *
 * No platform actual — the construction is standard (RFC 2104) and the
 * inner SHA-256 call is the only platform-specific piece, already
 * provided by Sha256. Avoids adding another expect/actual surface that
 * each iOS/Native build has to mirror.
 *
 * Used by O53 (sealed-sender DM recipient tags) for the recipient-
 * derivable delivery tag pattern: `T = HMAC(K, "rumor-dm-v1:" || id)`.
 */
object HmacSha256 {

    private const val BLOCK_SIZE = 64    // SHA-256 block in bytes
    private const val OUTPUT_SIZE = 32   // SHA-256 output in bytes

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        // Per RFC 2104: K' = K padded to BLOCK_SIZE. If K longer than
        // BLOCK_SIZE, replace with H(K) (still padded).
        val keyBlock = ByteArray(BLOCK_SIZE)
        if (key.size > BLOCK_SIZE) {
            Sha256.digest(key).copyInto(keyBlock)
        } else {
            key.copyInto(keyBlock)
        }

        // ipad = 0x36 repeated, opad = 0x5C repeated.
        val ipadKey = ByteArray(BLOCK_SIZE) { (keyBlock[it].toInt() xor 0x36).toByte() }
        val opadKey = ByteArray(BLOCK_SIZE) { (keyBlock[it].toInt() xor 0x5C).toByte() }

        val inner = Sha256.digest(ipadKey + message)
        return Sha256.digest(opadKey + inner)
    }
}
