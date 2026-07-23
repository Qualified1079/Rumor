package com.rumor.mesh.core.identity

import android.content.Context
import android.content.SharedPreferences
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.crypto.CryptoManager.toHex
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android implementation of [IdentityProvider]. Persists keys in encrypted SharedPreferences. */
class IdentityManager(
    private val context: Context,
) : IdentityProvider {
    private val TAG = "IdentityManager"
    private val PREFS_NAME = "rumor_identity"

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    override val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    override val isUnlocked: Boolean get() = _identity.value != null
    val hasIdentity: Boolean get() = prefs.getBoolean("identity_exists", false)

    fun createIdentity(passphrase: String) {
        val keypair = CryptoManager.generateEd25519KeyPair()
        val userId = CryptoManager.publicKeyToUserId(keypair.publicKeyBytes)
        val deviceId = generateDeviceId()
        val salt = CryptoManager.generateSalt()
        val aesKey = CryptoManager.deriveKeyFromPassphrase(passphrase, salt, CryptoManager.PBKDF2_ITERATIONS)
        val encrypted = CryptoManager.aesGcmEncrypt(keypair.privateKeyBytes, aesKey)
        aesKey.fill(0)

        prefs.edit()
            .putString("user_id", userId)
            .putString("device_id", deviceId)
            .putString("public_key", keypair.publicKeyBytes.toBase64())
            .putString("encrypted_private_key", encrypted.toBase64())
            .putString("kdf_salt", salt.toBase64())
            .putInt("kdf_iterations", CryptoManager.PBKDF2_ITERATIONS)
            .putBoolean("identity_exists", true)
            .apply()

        _identity.value = LocalIdentity(userId, deviceId, keypair.publicKeyBytes, keypair.privateKeyBytes)
        RumorLog.i(TAG, "Identity created: userId=${userId.take(16)}…")
    }

    fun unlock(passphrase: String): Boolean {
        val userId = prefs.getString("user_id", null) ?: return false
        val deviceId = prefs.getString("device_id", null) ?: return false
        val publicKeyB64 = prefs.getString("public_key", null) ?: return false
        val encryptedB64 = prefs.getString("encrypted_private_key", null) ?: return false
        val saltB64 = prefs.getString("kdf_salt", null) ?: return false

        // O115: the iteration count is part of the stored format. Absent key =
        // wrapped pre-bump at the legacy count (existing fleet installs).
        val iterations = prefs.getInt("kdf_iterations", CryptoManager.PBKDF2_ITERATIONS_LEGACY)

        return try {
            val salt = saltB64.fromBase64()
            val aesKey = CryptoManager.deriveKeyFromPassphrase(passphrase, salt, iterations)
            val ct = CryptoManager.AesGcmCiphertext.fromBase64(encryptedB64)
            val privateKeyBytes = CryptoManager.aesGcmDecrypt(ct, aesKey)
            aesKey.fill(0)
            // Re-unlock without an intervening lock(): don't leave the previous
            // key copy live on the heap.
            _identity.value?.privateKeyBytes?.fill(0)
            _identity.value = LocalIdentity(userId, deviceId, publicKeyB64.fromBase64(), privateKeyBytes)
            if (iterations < CryptoManager.PBKDF2_ITERATIONS) rewrap(privateKeyBytes, passphrase)
            RumorLog.i(TAG, "Identity unlocked: userId=${userId.take(16)}…")
            true
        } catch (e: Exception) {
            RumorLog.w(TAG, "Unlock failed — wrong passphrase or corrupted data", e)
            false
        }
    }

    /**
     * O115: upgrade stored wrapping to the current PBKDF2 work factor on the
     * first successful unlock after the bump. Fresh salt per re-wrap; the
     * passphrase is unchanged so this is invisible to the user.
     */
    private fun rewrap(privateKeyBytes: ByteArray, passphrase: String) {
        val newSalt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKeyFromPassphrase(passphrase, newSalt, CryptoManager.PBKDF2_ITERATIONS)
        val encrypted = CryptoManager.aesGcmEncrypt(privateKeyBytes, newKey)
        newKey.fill(0)
        prefs.edit()
            .putString("encrypted_private_key", encrypted.toBase64())
            .putString("kdf_salt", newSalt.toBase64())
            .putInt("kdf_iterations", CryptoManager.PBKDF2_ITERATIONS)
            .apply()
        RumorLog.i(TAG, "Identity re-wrapped at ${CryptoManager.PBKDF2_ITERATIONS} PBKDF2 iterations")
    }

    fun lock() {
        // O115: zero before dropping the reference — Identity.kt's contract.
        // In-flight sign() calls race this by design; lock means stop.
        _identity.value?.privateKeyBytes?.fill(0)
        _identity.value = null
        RumorLog.i(TAG, "Identity locked")
    }

    fun changePassphrase(newPassphrase: String): Boolean {
        val id = _identity.value ?: return false
        val newSalt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKeyFromPassphrase(newPassphrase, newSalt, CryptoManager.PBKDF2_ITERATIONS)
        val encrypted = CryptoManager.aesGcmEncrypt(id.privateKeyBytes, newKey)
        newKey.fill(0)
        prefs.edit()
            .putString("encrypted_private_key", encrypted.toBase64())
            .putString("kdf_salt", newSalt.toBase64())
            .putInt("kdf_iterations", CryptoManager.PBKDF2_ITERATIONS)
            .apply()
        RumorLog.i(TAG, "Passphrase changed")
        return true
    }

    fun sign(data: ByteArray): ByteArray? {
        val priv = _identity.value?.privateKeyBytes ?: return null
        return CryptoManager.sign(data, priv)
    }

    fun verify(data: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean =
        CryptoManager.verify(data, signature, publicKeyBytes)

    /**
     * Opaque per-install identifier, shown only in Settings — never a security
     * anchor (that is [userId] = SHA-256 of the public key) and never on the wire.
     * A plain 128-bit random value from the same SecureRandom the keys use: the
     * old derivation mixed device hardware descriptors with a random UUID, which
     * was neither a stable hardware fingerprint (the UUID randomised it) nor free
     * of a device-fingerprinting smell (the Build/sensor terms). Random is what it
     * was actually behaving as, so make that explicit.
     */
    private fun generateDeviceId(): String = CryptoManager.randomBytes(16).toHex()
}
