package com.rumor.mesh.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.crypto.CryptoManager
import com.rumor.mesh.core.crypto.CryptoManager.fromBase64
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.identity.LocalIdentity
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.PeerPresence
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.wire.CompressedPaddedCodec
import com.rumor.mesh.core.wire.PaddingBuckets
import com.rumor.mesh.core.wire.compressionAad
import com.rumor.mesh.core.wire.compressionOriginalLength
import com.rumor.mesh.core.wire.isCompressed
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThreadHeader(
    val peerId: String,
    val contact: ContactEntity?,
    val presence: PeerPresence?,
)

/** A message ready for display — body text already decrypted (if possible). */
data class DisplayMessage(
    val raw: RumorMessage,
    /** Displayable body: decrypted plaintext, "[sent]", "[decryption failed]", or control label. */
    val body: String,
    val isFromMe: Boolean,
)

/**
 * Per-peer DM view. The peer ID is set once via [bind] from the screen.
 *
 * Compose passes the peer ID through navigation arguments; the ViewModel
 * holds it in a StateFlow so the read streams reactively switch when the
 * user opens a different thread.
 *
 * DM decryption happens here. For messages addressed to the local user,
 * the X25519 shared secret is derived from the local private key and the
 * per-message ephemeral public key embedded in [RumorMessage.encryptedPayload].
 * Messages composed locally (senderId == local userId) cannot be decrypted
 * because the ephemeral private key is discarded after sending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(
    private val messageStore: MessageStore,
    private val contactDao: ContactDao,
    private val onlineStatusTracker: OnlineStatusTracker,
    private val identityManager: IdentityManager,
    private val controllerHolder: MeshControllerHolder,
) : ViewModel() {

    private val _peerId = MutableStateFlow<String?>(null)

    val messages: StateFlow<List<DisplayMessage>> =
        combine(_peerId, identityManager.identity) { p, i -> p to i }
            .flatMapLatest { (peerId, identity) ->
                if (peerId == null || identity == null) {
                    flowOf(emptyList())
                } else {
                    messageStore.observeThread(identity.userId, peerId).map { msgs ->
                        msgs.map { msg -> msg.toDisplay(identity) }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val header: StateFlow<ThreadHeader?> =
        _peerId.flatMapLatest { peerId ->
            if (peerId == null) flowOf(null)
            else combine(
                contactDao.observeAll().map { all -> all.firstOrNull { it.userId == peerId } },
                onlineStatusTracker.statuses,
            ) { contact, statuses ->
                ThreadHeader(peerId, contact, statuses[peerId])
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun bind(peerId: String) {
        _peerId.update { peerId }
    }

    fun send(text: String) {
        val peer = _peerId.value ?: return
        if (text.isBlank()) return
        controllerHolder.controller().sendDirect(peer, text.trim())
    }

    fun markAllRead() {
        viewModelScope.launch {
            messages.value
                .filter { !it.raw.isRead }
                .forEach { messageStore.markRead(it.raw.id) }
        }
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    private fun RumorMessage.toDisplay(identity: LocalIdentity): DisplayMessage {
        val isFromMe = senderId == identity.userId
        val ct = encryptedPayload
        val body = when {
            type == MessageType.TRANSFER_METADATA -> "[transfer]"
            ct == null -> payload?.content ?: ""
            isFromMe -> controllerHolder.controller().sentPlaintextFor(id) ?: "[sent]"
            else -> decryptPayload(this, ct, identity.privateKeyBytes)
        }
        return DisplayMessage(raw = this, body = body, isFromMe = isFromMe)
    }

    /**
     * Wire format: "{ephemeralPubKeyBase64}.{ivAndCiphertextBase64}"
     * Shared secret = X25519(localPrivKey, ephemeralPub).
     *
     * O76 path: when `_ext.c = true`, the plaintext bytes coming out of
     * AES-GCM are a padded deflate stream — unpad with originalLength
     * (recovered from `_ext.cl`, integrity-bound via AAD) then inflate.
     * The AAD must be `compressionAad(originalLength)` or the GCM tag
     * check fails.
     */
    private fun decryptPayload(msg: RumorMessage, encryptedPayload: String, localPrivKey: ByteArray): String =
        runCatching {
            val dotIdx = encryptedPayload.indexOf('.')
            require(dotIdx > 0) { "malformed payload" }
            val ephemeralPub = encryptedPayload.substring(0, dotIdx).fromBase64()
            val sharedKey = CryptoManager.x25519Agreement(localPrivKey, ephemeralPub)
            try {
                val ct = CryptoManager.AesGcmCiphertext.fromBase64(encryptedPayload.substring(dotIdx + 1))
                val aad = if (msg.isCompressed) compressionAad(msg.compressionOriginalLength) else ByteArray(0)
                val decrypted = CryptoManager.aesGcmDecrypt(ct, sharedKey, aad)
                if (msg.isCompressed) {
                    val inflated = CompressedPaddedCodec.decodeFromWire(
                        bytes = decrypted,
                        originalLength = msg.compressionOriginalLength,
                        maxOutputBytes = PaddingBuckets.MAX_SINGLE_MESSAGE,
                    ) ?: return "[decompression failed]"
                    inflated.decodeToString()
                } else {
                    decrypted.toString(Charsets.UTF_8)
                }
            } finally {
                // O39: zero the derived AES key on the way out. localPrivKey is the
                // long-term static — caller owns its lifecycle; we don't touch it.
                sharedKey.fill(0)
            }
        }.getOrElse { "[decryption failed]" }
}
