package com.rumor.mesh.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.service.MeshController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val messageStore: MessageStore,
    private val meshController: MeshController,
) : ViewModel() {

    val broadcasts: StateFlow<List<RumorMessage>> = messageStore
        .observeBroadcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun sendBroadcast(text: String) {
        if (text.isBlank()) return
        meshController.sendBroadcast(text.trim())
    }

    fun relay(message: RumorMessage) {
        meshController.manualRelay(message)
        viewModelScope.launch {
            messageStore.markRelayed(message.id)
        }
    }

    fun markRead(messageId: String) {
        viewModelScope.launch {
            messageStore.markRead(messageId)
        }
    }
}
