package com.rumor.mesh.service

import com.rumor.mesh.core.model.RumorMessage

/**
 * Interface the UI uses to interact with the mesh.
 * Hides all service, transport, and protocol internals from UI code.
 */
interface MeshController {
    fun sendBroadcast(text: String)
    fun sendDirect(recipientId: String, text: String)
    fun manualRelay(message: RumorMessage)
    fun triggerActiveScan()
    fun isServiceRunning(): Boolean
}
