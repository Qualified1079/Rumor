package com.rumor.mesh.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.data.ChunkDao
import com.rumor.mesh.data.TransferDao
import com.rumor.mesh.data.TransferEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Per-transfer row with derived progress info. */
data class TransferRow(
    val transfer: TransferEntity,
    val receivedChunks: Int,
) {
    val fraction: Float
        get() = if (transfer.totalChunks <= 0) 0f
                else (receivedChunks.coerceAtMost(transfer.totalChunks).toFloat() / transfer.totalChunks)
}

/**
 * Aggregates transfer state with per-transfer chunk counts. The chunk count
 * stream is a single grouped query, so the UI does not pay one DB round-trip
 * per row.
 */
class TransfersViewModel(
    transferDao: TransferDao,
    chunkDao: ChunkDao,
) : ViewModel() {

    val rows: StateFlow<List<TransferRow>> =
        combine(
            transferDao.observeRecent(),
            chunkDao.observeAllReceivedCounts(),
        ) { transfers, counts ->
            val countsById = counts.associateBy({ it.transferId }, { it.received })
            transfers.map { t -> TransferRow(t, countsById[t.transferId] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
