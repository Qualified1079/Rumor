package com.rumor.mesh.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.core.model.PeerPresence
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.displayTimeMs
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Aggregated view of one DM conversation: most recent message + the contact
 * (if known) + presence.
 */
data class ThreadSummary(
    val peerId: String,
    val contact: ContactEntity?,
    val presence: PeerPresence?,
    val lastMessage: RumorMessage,
    val unreadCount: Int,
)

/**
 * Lists all DM threads. Aggregates [MessageStore.observeAllDirect] in-memory
 * by peer instead of pushing the grouping into Room — the active-thread set
 * is small in practice, and keeping the DAO surface narrow keeps storage swap-able.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(
    private val messageStore: MessageStore,
    private val contactDao: ContactDao,
    private val onlineStatusTracker: OnlineStatusTracker,
    private val identityManager: IdentityManager,
) : ViewModel() {

    val threads: StateFlow<List<ThreadSummary>> =
        identityManager.identity
            .flatMapLatest { identity ->
                if (identity == null) flowOf(emptyList())
                else combine(
                    messageStore.observeAllDirect(identity.userId),
                    contactDao.observeAll(),
                    onlineStatusTracker.statuses,
                ) { dms, contacts, statuses ->
                    summarize(identity.userId, dms, contacts, statuses)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun summarize(
        localUserId: String,
        dms: List<RumorMessage>,
        contacts: List<ContactEntity>,
        statuses: Map<String, PeerPresence>,
    ): List<ThreadSummary> {
        val contactsById = contacts.associateBy { it.userId }
        val byPeer = LinkedHashMap<String, MutableList<RumorMessage>>()
        for (msg in dms) {
            val peerId = if (msg.senderId == localUserId) msg.recipientId ?: continue else msg.senderId
            byPeer.getOrPut(peerId) { mutableListOf() }.add(msg)
        }
        return byPeer.map { (peerId, msgs) ->
            val latest = msgs.maxBy { it.displayTimeMs }
            val unread = msgs.count { it.recipientId == localUserId && !it.isRead }
            ThreadSummary(
                peerId = peerId,
                contact = contactsById[peerId],
                presence = statuses[peerId],
                lastMessage = latest,
                unreadCount = unread,
            )
        }.sortedByDescending { it.lastMessage.displayTimeMs }
    }
}
