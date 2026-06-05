package com.rumor.mesh.core.platform

/**
 * iOS actual for [Compression] — placeholder.
 *
 * Apple's `libcompression.dylib` exposes raw deflate via the
 * `compression_stream` API (`COMPRESSION_ZLIB` algorithm), reachable
 * from Kotlin/Native via cinterop. Implementation deferred until the
 * iOS toolchain (xtool or Mac mini) is set up; the pattern mirrors the
 * Swift-bridge gap documented in `docs/IOS_SWIFT_BRIDGE_SPEC.md` — same
 * "spec it, ship the stub, fill in on the first day of a real iOS
 * toolchain" approach.
 *
 * Until then: any call throws. The compression-v1 feature is opt-in via
 * HELLO `supportedFeatures` capability negotiation (see O76), so an iOS
 * peer simply won't advertise the capability and won't be sent
 * compressed payloads.
 */
private const val PENDING = "iOS Compression: pending libcompression cinterop wiring (O76)"

actual object Compression {
    actual fun deflate(plaintext: ByteArray): ByteArray = throw NotImplementedError(PENDING)
    actual fun inflate(compressed: ByteArray, maxOutputBytes: Int): ByteArray? = throw NotImplementedError(PENDING)
}
