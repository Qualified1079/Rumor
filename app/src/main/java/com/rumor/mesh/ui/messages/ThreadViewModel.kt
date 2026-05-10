package com.rumor.mesh.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.model.PeerPresence
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThreadHeader(
    val peerId: String,
    val contact: ContactEntity?,
    val presence: PeerPresence?,
)

/**
 * Per-peer DM view. The peer ID is set once via [bind] from the screen.
 *
 * Compose passes the peer ID through navigation arguments; the ViewModel
 * holds it in a StateFlow so the read streams reactively switch when the
 * user opens a different thread.
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

    val messages: StateFlow<List<RumorMessage>> =
        combine(_peerId, identityManager.identity) { p, i -> p to i }
            .flatMapLatest { (peerId, identity) ->
                if (peerId == null || identity == null) {
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else {
                    messageStore.observeThread(identity.userId, peerId)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val header: StateFlow<ThreadHeader?> =
        _peerId.flatMapLatest { peerId ->
            if (peerId == null) kotlinx.coroutines.flow.flowOf(null)
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
                .filter { !it.isRead }
                .forEach { messageStore.markRead(it.id) }
        }
    }
}
