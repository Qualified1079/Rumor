package com.rumor.mesh.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rumor.mesh.core.protocol.GossipEngine
import com.rumor.mesh.core.protocol.MetricsSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class DebugMetricsViewModel(
    private val engine: GossipEngine,
) : ViewModel() {

    val metrics = flow {
        while (true) {
            engine.canaryMetrics.publish(engine.queueDepth)
            emit(engine.canaryMetrics.flow.value)
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricsSnapshot(0,0,0,0,0,0,0,0))
}
