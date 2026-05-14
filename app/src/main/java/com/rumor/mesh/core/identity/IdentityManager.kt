package com.rumor.mesh.core.identity

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.crypto.CryptoManager.toBase64
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

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
        val aesKey = CryptoManager.deriveKeyFromPassphrase(passphrase, salt)
        val encrypted = CryptoManager.aesGcmEncrypt(keypair.privateKeyBytes, aesKey)

        prefs.edit()
            .putString("user_id", userId)
            .putString("device_id", deviceId)
            .putString("public_key", keypair.publicKeyBytes.toBase64())
            .putString("encrypted_private_key", encrypted.toBase64())
            .putString("kdf_salt", salt.toBase64())
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

        return try {
            val salt = saltB64.fromBase64()
            val aesKey = CryptoManager.deriveKeyFromPassphrase(passphrase, salt)
            val ct = CryptoManager.AesGcmCiphertext.fromBase64(encryptedB64)
            val privateKeyBytes = CryptoManager.aesGcmDecrypt(ct, aesKey)
            _identity.value = LocalIdentity(userId, deviceId, publicKeyB64.fromBase64(), privateKeyBytes)
            RumorLog.i(TAG, "Identity unlocked: userId=${userId.take(16)}…")
            true
        } catch (e: Exception) {
            RumorLog.w(TAG, "Unlock failed — wrong passphrase or corrupted data", e)
            false
        }
    }

    fun lock() {
        _identity.value = null
        RumorLog.i(TAG, "Identity locked")
    }

    fun changePassphrase(newPassphrase: String): Boolean {
        val id = _identity.value ?: return false
        val newSalt = CryptoManager.generateSalt()
        val newKey = CryptoManager.deriveKeyFromPassphrase(newPassphrase, newSalt)
        val encrypted = CryptoManager.aesGcmEncrypt(id.privateKeyBytes, newKey)
        prefs.edit()
            .putString("encrypted_private_key", encrypted.toBase64())
            .putString("kdf_salt", newSalt.toBase64())
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

    private fun generateDeviceId(): String {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val hwFingerprint = buildString {
            append(android.os.Build.FINGERPRINT)
            append(android.os.Build.BOARD)
            append(android.os.Build.HARDWARE)
            append(android.os.Build.MODEL)
            append(accel?.resolution ?: "0")
            append(accel?.maximumRange ?: "0")
        }
        val combined = "$hwFingerprint:${UUID.randomUUID()}"
        return MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
