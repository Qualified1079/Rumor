package com.rumor.mesh.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * O91 — Standard Ed25519 → X25519 derivation per RFC 7748 (key
 * clamping) and the well-known Edwards-to-Montgomery birational
 * map.
 *
 * **JVM-only for now.** A multi-platform actual would need to
 * mirror this on iOS via CryptoKit / a Swift bridge; the
 * underlying math is identical, just expressed in different
 * primitives. Filed as O91 in CLAUDE.md.
 *
 * **NOT yet wired into the production DM path.** `composeDirect`
 * and `ThreadViewModel.decryptPayload` still pass Ed25519 bytes
 * directly to `x25519Agreement`, producing wrong shared secrets
 * (the pinned-broken state in `Ed25519AsX25519RoundtripTest`).
 * This file ships the derivation primitive so it can be verified
 * in isolation; wiring is a separate focused commit so the
 * production change is reviewable.
 *
 * **Verification:** the test `Ed25519ToX25519ConversionTest`
 * asserts the round-trip property — two Ed25519 keypairs, each
 * converted to X25519, produce matching DH shared secrets on
 * both sides. This is the strongest property the conversion can
 * have without external test vectors and is sufficient for
 * verifying the derivation is internally consistent.
 */
object Ed25519ToX25519 {

    /** Curve25519 prime: 2^255 - 19. */
    private val P: BigInteger =
        BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /**
     * Convert an Ed25519 32-byte seed (the form
     * `Ed25519PrivateKeyParameters.encoded` returns) to an X25519
     * 32-byte private key.
     *
     * Standard derivation: take SHA-512 of the seed, low 32 bytes,
     * apply X25519 clamping per RFC 7748 §5.
     *
     * @throws IllegalArgumentException if [ed25519Seed] is not 32 bytes.
     */
    fun ed25519PrivToX25519Priv(ed25519Seed: ByteArray): ByteArray {
        require(ed25519Seed.size == 32) {
            "Ed25519 seed must be 32 bytes; got ${ed25519Seed.size}"
        }
        val sha = MessageDigest.getInstance("SHA-512").digest(ed25519Seed)
        val x = sha.copyOf(32)
        // X25519 clamping (RFC 7748 §5):
        x[0]  = (x[0].toInt()  and 0xF8).toByte()   // clear low 3 bits
        x[31] = (x[31].toInt() and 0x7F).toByte()   // clear high bit
        x[31] = (x[31].toInt() or  0x40).toByte()   // set bit 6
        return x
    }

    /**
     * Convert an Ed25519 32-byte public key to an X25519 32-byte
     * public key via the Edwards-to-Montgomery birational map:
     *
     *   u = (1 + y) / (1 - y) mod p
     *
     * where p = 2^255 - 19 and `y` is the Edwards y-coordinate
     * encoded little-endian in the input (the high bit of byte
     * 31 holds the sign bit of x, which is irrelevant for the
     * Montgomery x-coordinate we're computing).
     *
     * @throws IllegalArgumentException if [ed25519Pub] is not 32 bytes.
     * @throws IllegalArgumentException if `y == 1` (degenerate
     *   point with no Montgomery preimage; never occurs for
     *   legitimate Ed25519 keys but checked for safety).
     */
    fun ed25519PubToX25519Pub(ed25519Pub: ByteArray): ByteArray {
        require(ed25519Pub.size == 32) {
            "Ed25519 public key must be 32 bytes; got ${ed25519Pub.size}"
        }
        // Clear the sign-of-x bit in byte 31 (it's not part of y).
        val yBytes = ed25519Pub.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = leBytesToBigInt(yBytes).mod(P)

        require(y != BigInteger.ONE) {
            "Degenerate Ed25519 public key: y == 1 has no Montgomery preimage"
        }

        // u = (1 + y) * (1 - y)^(-1) mod p
        val numerator = BigInteger.ONE.add(y).mod(P)
        val denomInverse = BigInteger.ONE.subtract(y).mod(P).modInverse(P)
        val u = numerator.multiply(denomInverse).mod(P)

        return bigIntToLeBytes(u, 32)
    }

    private fun leBytesToBigInt(bytes: ByteArray): BigInteger {
        // Reverse bytes for BigInteger's big-endian constructor.
        val be = ByteArray(bytes.size) { bytes[bytes.size - 1 - it] }
        // Prepend a 0x00 byte so BigInteger treats it as unsigned.
        return BigInteger(byteArrayOf(0x00) + be)
    }

    private fun bigIntToLeBytes(value: BigInteger, length: Int): ByteArray {
        val be = value.toByteArray()
        // Strip leading sign byte if present, pad/truncate to `length`.
        val unsigned = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        val padded = ByteArray(length)
        if (unsigned.size <= length) {
            // Right-align big-endian into the buffer, then reverse.
            unsigned.copyInto(padded, length - unsigned.size)
        } else {
            // Should never happen since we mod by P which fits in 255 bits.
            unsigned.copyInto(padded, 0, unsigned.size - length, unsigned.size)
        }
        return ByteArray(length) { padded[length - 1 - it] }
    }
}
