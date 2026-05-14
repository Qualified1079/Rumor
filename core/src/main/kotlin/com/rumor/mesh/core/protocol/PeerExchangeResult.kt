package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.model.RumorMessage

data class PeerExchangeResult(
    val peerUserId: String,
    val messagesReceived: List<RumorMessage>,
    val ackedByPeer: List<String>,
    val peerOnlineUsers: Map<String, Long>,
    val durationMs: Long,
)
