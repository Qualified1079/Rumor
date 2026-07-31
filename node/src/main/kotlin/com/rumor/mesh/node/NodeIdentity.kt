package com.rumor.mesh.node

import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.runtime.HlcStore
import com.rumor.mesh.core.time.HlcTimestamp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "NodeIdentity"

/** Owner-only (0600) POSIX permission set for the unencrypted seed file. */
private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

/**
 * File-backed identity for the headless test node. Generates an Ed25519
 * keypair on first run and persists the seed so the node keeps a stable
 * userId across restarts (a fresh identity per boot would look like a new
 * peer every time — useless as a debugging instrument).
 *
 * TEST INSTRUMENT ONLY: the seed is stored unencrypted (0600). The product
 * :node gets the O44 file-based *encrypted* identity impl behind this same
 * [IdentityProvider] interface; don't promote this class past the harness.
 */
class NodeIdentityProvider(dataDir: File) : IdentityProvider {

    private val file = File(dataDir, "identity.properties")

    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    override val identity: StateFlow<LocalIdentity?> = _identity
    override val isUnlocked: Boolean get() = _identity.value != null

    init {
        val props = Properties()
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        // Same stored shape as the Android IdentityManager: public + seed side
        // by side, userId re-derived (and thus re-verified) from the public.
        val seed = props.getProperty("seed")?.let { Base64.getDecoder().decode(it) }
        val pub = props.getProperty("publicKey")?.let { Base64.getDecoder().decode(it) }
        if (seed != null && seed.size == 32 && pub != null && pub.size == 32) {
            _identity.value = LocalIdentity(
                userId = CryptoManager.publicKeyToUserId(pub),
                deviceId = props.getProperty("deviceId") ?: UUID.randomUUID().toString(),
                publicKeyBytes = pub,
                privateKeyBytes = seed,
            )
        } else {
            val pair = CryptoManager.generateEd25519KeyPair()
            val deviceId = UUID.randomUUID().toString()
            props.setProperty("seed", Base64.getEncoder().encodeToString(pair.privateKeyBytes))
            props.setProperty("publicKey", Base64.getEncoder().encodeToString(pair.publicKeyBytes))
            props.setProperty("deviceId", deviceId)
            file.parentFile?.mkdirs()
            writeSeedOwnerOnly(file) { out ->
                props.store(out, "rumor node identity — TEST INSTRUMENT, unencrypted")
            }
            _identity.value = LocalIdentity(
                userId = CryptoManager.publicKeyToUserId(pair.publicKeyBytes),
                deviceId = deviceId,
                publicKeyBytes = pair.publicKeyBytes,
                privateKeyBytes = pair.privateKeyBytes,
            )
        }
    }
}

/**
 * O180: write the unencrypted key-seed file so it is NEVER world/group-readable,
 * not even briefly. The old path wrote at the default umask and chmod'd to 0600
 * afterwards — a real window where a fresh seed sat readable on a shared box —
 * and swallowed a failed chmod silently. Here the file is created owner-only up
 * front via an atomic CREATE_NEW with the 0600 attribute, so on any POSIX FS
 * there is no window. On a non-POSIX FS (no createFile-with-perms support) we
 * fall back to a post-hoc restrict and LOG loudly if even that fails — an
 * unreadable warning beats a silently exposed key.
 */
private fun writeSeedOwnerOnly(file: File, write: (java.io.OutputStream) -> Unit) {
    val path = file.toPath()
    Files.deleteIfExists(path)
    val createdOwnerOnly = runCatching {
        Files.newByteChannel(
            path,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(OWNER_ONLY),
        ).close()
    }.isSuccess
    file.outputStream().use(write)   // truncates the pre-created 0600 file, perms unchanged
    if (!createdOwnerOnly) {
        val restricted = runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }.isSuccess
        if (!restricted) {
            RumorLog.w(TAG, "could not make identity seed owner-only ($path) — " +
                "unencrypted key may be readable by other users on this machine")
        }
    }
}

/** File-backed [HlcStore]; same two-longs shape as the Android SharedPreferences impl. */
class FileHlcStore(dataDir: File) : HlcStore {
    private val file = File(dataDir, "hlc.properties")

    override fun load(): HlcTimestamp {
        if (!file.exists()) return HlcTimestamp(0L, 0L)
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }
        return HlcTimestamp(
            props.getProperty("wallMs")?.toLongOrNull() ?: 0L,
            props.getProperty("counter")?.toLongOrNull() ?: 0L,
        )
    }

    override fun save(ts: HlcTimestamp) {
        val props = Properties()
        props.setProperty("wallMs", ts.wallMs.toString())
        props.setProperty("counter", ts.counter.toString())
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }
    }
}
