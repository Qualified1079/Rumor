package com.rumor.mesh.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(
    private val messageStore: MessageStore,
    private val controllerHolder: MeshControllerHolder,
    private val identityProvider: IdentityProvider,
    contactDao: ContactDao,
) : ViewModel() {

    /** In-flight manual relays, keyed by message id (O184 de-bounce). */
    private val relaying = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val broadcasts: StateFlow<List<RumorMessage>> = messageStore
        .observeBroadcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** senderId → nickname, for feed rows whose sender is a named contact. */
    val senderNames: StateFlow<Map<String, String>> = contactDao.observeAll()
        .map { contacts ->
            contacts.mapNotNull { c -> c.displayName?.let { c.userId to it } }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun isOwnMessage(senderId: String): Boolean = senderId == identityProvider.identity.value?.userId

    /**
     * O164: returns false when the service isn't bound — the caller must keep
     * the user's text instead of clearing the field on a send that went nowhere.
     */
    fun sendBroadcast(text: String): Boolean {
        if (text.isBlank()) return false
        if (!controllerHolder.isBound()) return false
        controllerHolder.controller().sendBroadcast(text.trim())
        return true
    }

    /**
     * O164: `markRelayed` previously ran unconditionally, so an unbound
     * controller showed "Relayed" while nothing left the device. The mark now
     * follows the send.
     *
     * O184: `relaying` de-bounces the button — manualRelay re-enqueues and
     * bumps hops, so a double tap sent the message twice.
     */
    fun relay(message: RumorMessage): Boolean {
        if (!controllerHolder.isBound()) return false
        if (!relaying.add(message.id)) return false
        controllerHolder.controller().manualRelay(message)
        viewModelScope.launch {
            try {
                messageStore.markRelayed(message.id)
            } finally {
                relaying.remove(message.id)
            }
        }
        return true
    }

    // O183: markRead(messageId) was removed, not wired. It had no caller, and
    // connecting one would have had no observable effect either: `isRead` is
    // only consumed for DM threads (MessagesViewModel's unreadCount counts DMs
    // addressed to us) — the feed has no unread indicator to clear. A feed
    // unread badge is a UI-backlog item (O143); until it exists there is
    // nothing for a read-mark to drive.
}
