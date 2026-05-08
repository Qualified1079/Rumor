package com.rumor.mesh.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.protocol.MessageStore
import com.rumor.mesh.service.MeshControllerHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(
    private val messageStore: MessageStore,
    private val controllerHolder: MeshControllerHolder,
) : ViewModel() {

    val broadcasts: StateFlow<List<RumorMessage>> = messageStore
        .observeBroadcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun sendBroadcast(text: String) {
        if (text.isBlank()) return
        controllerHolder.controller().sendBroadcast(text.trim())
    }

    fun relay(message: RumorMessage) {
        controllerHolder.controller().manualRelay(message)
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
