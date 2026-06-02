package com.rumor.mesh.ui.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.block.BlockManager
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.SubscribedBlocklist
import com.rumor.mesh.data.adapter.SubscribedBlocklistRepositoryAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Snapshot UI state for the block management screen.
 *
 * [localBlocks] are blocks the user placed personally. [subscriptions] are
 * external publisher feeds the user has trusted; their entries contribute to
 * the effective blocked set but aren't shown row-by-row here.
 */
data class BlockManagementState(
    val localBlocks: List<BlockEntry> = emptyList(),
    val subscriptions: List<SubscribedBlocklist> = emptyList(),
    val statusMessage: String? = null,
)

/**
 * Backed by [BlockManager] for writes and the [SubscribedBlocklistDao] for the
 * read-side subscription roster. Pure view-model logic — no UI concerns leak in.
 */
class BlockManagementViewModel(
    private val blockManager: BlockManager,
    private val subscribedBlocklistRepo: SubscribedBlocklistRepositoryAdapter,
) : ViewModel() {

    private val _state = MutableStateFlow(BlockManagementState())
    val state: StateFlow<BlockManagementState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val locals = blockManager.activeLocalBlocks()
            val subs = subscribedBlocklistRepo.getAll()
            _state.update { it.copy(localBlocks = locals, subscriptions = subs) }
        }
    }

    /** [durationMinutes] = null for permanent. */
    fun block(userId: String, durationMinutes: Long?, reason: String?) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            blockManager.block(
                userId = userId.trim(),
                durationMs = durationMinutes?.let { it * 60_000L },
                reason = reason?.takeIf { it.isNotBlank() },
            )
            refresh()
        }
    }

    fun unblock(userId: String) {
        viewModelScope.launch {
            blockManager.unblock(userId)
            refresh()
        }
    }

    fun exportEncrypted(passphrase: String, onResult: (String?) -> Unit) {
        if (passphrase.length < 8) {
            _state.update { it.copy(statusMessage = "Passphrase must be at least 8 characters") }
            onResult(null); return
        }
        viewModelScope.launch {
            runCatching { blockManager.exportEncrypted(passphrase) }
                .onSuccess { blob ->
                    _state.update { it.copy(statusMessage = "Exported ${state.value.localBlocks.size} blocks") }
                    onResult(blob)
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Export failed: ${err.message}") }
                    onResult(null)
                }
        }
    }

    fun importEncrypted(blob: String, passphrase: String) {
        viewModelScope.launch {
            runCatching { blockManager.importEncrypted(blob, passphrase) }
                .onSuccess { count ->
                    _state.update { it.copy(statusMessage = "Imported $count blocks") }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Import failed: ${err.message}") }
                }
        }
    }

    fun unsubscribe(publisherId: String) {
        viewModelScope.launch {
            subscribedBlocklistRepo.delete(publisherId)
            blockManager.refreshExternal()
            refresh()
        }
    }

    fun clearStatus() {
        _state.update { it.copy(statusMessage = null) }
    }
}
