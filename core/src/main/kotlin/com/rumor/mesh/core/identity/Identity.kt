package com.rumor.mesh.core.identity

import kotlinx.coroutines.flow.StateFlow

/**
 * The local node's persistent identity material. Held in memory
 * only while unlocked; the holder ([IdentityProvider]) zeroes its
 * StateFlow value on lock so the [privateKeyBytes] don't outlive
 * the user's session in process memory.
 *
 * **`userId`** is `SHA-256(publicKeyBytes).toHex()` — the binding is
 * cryptographic, every receiver re-derives and verifies cheaply,
 * and a holder cannot claim a userId that doesn't hash from their
 * pubkey. See `CryptoManager.publicKeyToUserId`.
 *
 * **`deviceId`** is per-install (regenerated on identity wipe / new
 * device); used only for local correlation in logs and never put on
 * the wire.
 *
 * **`publicKeyBytes`** are the 32-byte Ed25519 public; receivers use
 * for signature verification and (per O79 / O91) future X25519
 * derivation for DM AEAD wraps.
 *
 * **`privateKeyBytes`** are the 32-byte Ed25519 seed. Per O20 / O44
 * the long-term goal is to move this into Android Keystore (TEE-
 * backed wrapping) so the raw seed never lives in app process memory;
 * until then it's here and zeroed on lock.
 */
data class LocalIdentity(
    val userId: String,
    val deviceId: String,
    val publicKeyBytes: ByteArray,
    val privateKeyBytes: ByteArray,
)

/**
 * Identity is supplied to the engine via this contract — never
 * directly held inside protocol classes. `:app` implementation
 * (Android `IdentityManager`) wires biometric / passphrase unlock,
 * Keystore migration (O20 / O44), and the seed-phrase backup flow
 * (O45). `:simulator` implementation supplies a synthetic identity
 * per scenario node. Tests can stub either.
 *
 * [isUnlocked] is the canonical "do we have key material?" check;
 * [identity] is the flow downstream consumers should subscribe to
 * so they automatically clear cached references on lock.
 */
interface IdentityProvider {
    val identity: StateFlow<LocalIdentity?>
    val isUnlocked: Boolean
}
