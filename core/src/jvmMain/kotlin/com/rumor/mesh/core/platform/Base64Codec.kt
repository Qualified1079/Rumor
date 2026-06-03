package com.rumor.mesh.core.platform

import java.util.Base64

/**
 * JVM actual for [Base64Codec]. Delegates to `java.util.Base64`, which is
 * the standard-alphabet codec since JDK 8. Throws `IllegalArgumentException`
 * on malformed decode input.
 */
actual object Base64Codec {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    actual fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    actual fun decode(s: String): ByteArray = decoder.decode(s)
}
