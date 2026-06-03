package com.rumor.mesh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Body of an [MessageType.IDENTITY_ROTATION] message (O41).
 *
 * Rumor's `userId` is `SHA-256(publicKey).toHex()`. Rotating the keypair
 * therefore produces a new userId — without explicit signalling, every
 * contact treats the new userId as a stranger and the contact graph snaps.
 * This message is the bridge: a signed statement by the *old* key that the
 * holder now controls the new key, so peers can rebind their contact record
 * (userId / publicKey / display name preserved) without re-onboarding.
 *
 * Trust comes from [continuitySignature]: it is by the old Ed25519 key over
 * the bytes returned by [identityRotationSignableBytes]. A recipient holding
 * the old userId as a contact already has the old public key on file and
 * can verify locally. An attacker who has stolen only the new key cannot
 * forge this — the old key is the proof.
 *
 * The outer [RumorMessage.signature] is by the *new* key; it lets relay
 * nodes verify the wire frame the same way as any other message. The two
 * signatures together establish "the new key knows the old key, and the
 * old key consented" — strictly stronger than either alone.
 *
 * Coercion model: an attacker who forces a victim to sign a rotation under
 * duress wins the same as for any other coerced signature. O46 social
 * recovery's time-delay + public continuity window addresses that case
 * separately and is the right place to defend against it.
 */
@Serializable
data class IdentityRotationPayload(
    val oldUserId: String,
    val newUserId: String,
    /** Base64-encoded raw bytes of the new Ed25519 public key. */
    val newPublicKey: String,
    /** Wall-clock epoch ms when the rotation was authorized. Anti-replay binding. */
    val authorizedAtMs: Long,
    /** Ed25519 signature by the *old* key over [identityRotationSignableBytes], Base64. */
    val continuitySignature: String,
    /** Reserved forward-compat carrier. NOT covered by [continuitySignature]. */
    @SerialName("_ext") val ext: Map<String, JsonElement>? = null,
)

/**
 * Domain-tagged canonical bytes the *old* key signs to authorize a rotation
 * to [newUserId] / [newPublicKey]. Field order is fixed; never reorder
 * without bumping the `rumor-identity-rotation-vN:` tag.
 */
fun identityRotationSignableBytes(
    oldUserId: String,
    newUserId: String,
    newPublicKey: String,
    authorizedAtMs: Long,
): ByteArray = buildString {
    append("rumor-identity-rotation-v1:")
    append(oldUserId); append('|')
    append(newUserId); append('|')
    append(newPublicKey); append('|')
    append(authorizedAtMs)
}.toByteArray(Charsets.UTF_8)
