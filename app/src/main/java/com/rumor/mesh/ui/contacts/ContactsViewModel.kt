package com.rumor.mesh.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.model.PeerPresence
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactWithStatus(
    val contact: ContactEntity,
    /** Null if this contact has never been seen on the mesh. */
    val presence: PeerPresence?,
)
class ContactsViewModel(
    private val contactDao: ContactDao,
    private val onlineStatusTracker: OnlineStatusTracker,
) : ViewModel() {

    val contacts: StateFlow<List<ContactWithStatus>> =
        contactDao.observeAll()
            .combine(onlineStatusTracker.statuses) { contacts, statuses ->
                contacts.map { contact ->
                    ContactWithStatus(contact, statuses[contact.userId])
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAutoRelay(userId: String, enabled: Boolean) {
        viewModelScope.launch { contactDao.setAutoRelay(userId, enabled) }
    }

    fun setAlwaysSave(userId: String, enabled: Boolean) {
        viewModelScope.launch { contactDao.setAlwaysSave(userId, enabled) }
    }

    fun setDisplayName(userId: String, name: String) {
        viewModelScope.launch { contactDao.setDisplayName(userId, name) }
    }
}
