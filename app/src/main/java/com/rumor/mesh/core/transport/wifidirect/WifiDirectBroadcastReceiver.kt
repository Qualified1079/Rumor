package com.rumor.mesh.core.transport.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import com.rumor.mesh.core.logging.RumorLog

/**
 * Receives Wi-Fi Direct system broadcasts and forwards them to [WifiDirectManager].
 * Registered/unregistered by MeshService — not declared statically in the manifest
 * because it needs a live reference to WifiP2pManager.
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val listener: Listener,
) : BroadcastReceiver() {

    private val TAG = "WifiDirectBR"

    interface Listener {
        fun onWifiP2pEnabled(enabled: Boolean)
        fun onPeersChanged()
        fun onConnectionChanged(connected: Boolean)
        fun onThisDeviceChanged(device: WifiP2pDevice?)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                RumorLog.d(TAG, "Wi-Fi Direct enabled: $enabled")
                listener.onWifiP2pEnabled(enabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                RumorLog.d(TAG, "Peers changed")
                listener.onPeersChanged()
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                    WifiP2pManager.EXTRA_NETWORK_INFO
                )
                val connected = networkInfo?.isConnected == true
                RumorLog.d(TAG, "Connection changed: connected=$connected")
                listener.onConnectionChanged(connected)
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<WifiP2pDevice>(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                )
                listener.onThisDeviceChanged(device)
            }
        }
    }
}
