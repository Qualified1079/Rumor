package com.rumor.mesh.core.sync

import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire-form mirrors of [RbsrFrame] (O42). Separated from the in-memory algorithm
 * types so the algorithm can keep `ByteArray` fingerprints while the wire stays
 * `String`-based (Base64) for JSON cleanliness. Conversion at the session
 * boundary via [toMemory] / [toWire].
 *
 * Range bounds are encoded as `(ts, id)` tuples on the wire; sentinel values
 * map back to [RbsrBound.MIN] / [RbsrBound.MAX] at the boundary so the
 * algorithm doesn't have to special-case `Long.MIN_VALUE` / `Long.MAX_VALUE`
 * sniffing.
 */
@Serializable
sealed class RbsrFrameWire {

    @Serializable @SerialName("skip")
    data class Skip(
        val loTs: Long,
        val loId: String,
        val hiTs: Long,
        val hiId: String,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : RbsrFrameWire()

    @Serializable @SerialName("fp")
    data class Fingerprint(
        val loTs: Long,
        val loId: String,
        val hiTs: Long,
        val hiId: String,
        /** Base64-encoded 32-byte XOR fingerprint. */
        val fp: String,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : RbsrFrameWire()

    @Serializable @SerialName("ids")
    data class IdList(
        val loTs: Long,
        val loId: String,
        val hiTs: Long,
        val hiId: String,
        val ids: List<String>,
        @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
    ) : RbsrFrameWire()
}

fun RbsrFrame.toWire(): RbsrFrameWire = when (this) {
    is RbsrFrame.Skip ->
        RbsrFrameWire.Skip(lower.timestamp, lower.id, upper.timestamp, upper.id)
    is RbsrFrame.Fingerprint ->
        RbsrFrameWire.Fingerprint(lower.timestamp, lower.id, upper.timestamp, upper.id, fp.toBase64())
    is RbsrFrame.IdList ->
        RbsrFrameWire.IdList(lower.timestamp, lower.id, upper.timestamp, upper.id, ids)
}

fun RbsrFrameWire.toMemory(): RbsrFrame = when (this) {
    is RbsrFrameWire.Skip ->
        RbsrFrame.Skip(boundFrom(loTs, loId), boundFrom(hiTs, hiId))
    is RbsrFrameWire.Fingerprint ->
        RbsrFrame.Fingerprint(boundFrom(loTs, loId), boundFrom(hiTs, hiId), fp.fromBase64())
    is RbsrFrameWire.IdList ->
        RbsrFrame.IdList(boundFrom(loTs, loId), boundFrom(hiTs, hiId), ids)
}

private fun boundFrom(ts: Long, id: String): RbsrBound = when {
    ts == Long.MIN_VALUE && id.isEmpty() -> RbsrBound.MIN
    ts == Long.MAX_VALUE -> RbsrBound.MAX
    else -> RbsrBound(ts, id)
}
