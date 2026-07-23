package com.rumor.mesh.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.model.PeerPresence
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.data.ContactDao
import com.rumor.mesh.data.ContactEntity
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val controllerHolder: MeshControllerHolder,
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

    /**
     * O136 — the explicit friend gesture. Sets the [ContactEntity.friended] bit
     * the O135(1) "known peers only" inbox filter keys on. Deliberate user act,
     * never automatic.
     */
    fun setFriended(userId: String, enabled: Boolean) {
        viewModelScope.launch { contactDao.setFriended(userId, enabled) }
    }

    private val _isScanning = MutableStateFlow(false)
    /**
     * No end-to-end "discovery complete" signal exists yet (BLE + Wi-Fi Direct
     * rediscovery are both fire-and-forget). Shown for a fixed window after tap
     * so the user gets feedback that the scan actually started.
     */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun scanForPeers() {
        if (_isScanning.value) return
        controllerHolder.controller().triggerActiveScan()
        viewModelScope.launch {
            _isScanning.value = true
            delay(5_000)
            _isScanning.value = false
        }
    }
}
