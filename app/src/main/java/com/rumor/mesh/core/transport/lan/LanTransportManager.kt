package com.rumor.mesh.core.transport.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.rumor.mesh.core.logging.RumorLog
import java.net.Inet4Address

private const val TAG = "LanTransportManager"

/**
 * O93 Android host for [LanTransport]: brings the LAN path up exactly while
 * the device is associated to a Wi-Fi network — the situation where Wi-Fi
 * Direct discovery is dead (STA-associated, field-confirmed) and a P2P group
 * would disrupt the AP. Holds a MulticastLock while up (Android filters
 * multicast — mDNS — in hardware without it).
 *
 * The transport itself is pure JVM and owns sockets/mDNS; this class only
 * watches connectivity, extracts the Wi-Fi interface's IPv4, and drives
 * start/stop. MeshService owns this manager's lifecycle.
 */
class LanTransportManager(
    private val context: Context,
    private val transportFactory: () -> LanTransport,
) {
    /** The live transport while a Wi-Fi LAN is usable; null otherwise. */
    @Volatile var transport: LanTransport? = null
        private set

    /** Called with the fresh transport whenever the LAN path comes up. */
    var onTransportUp: (LanTransport) -> Unit = {}

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val addr = lp.linkAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress } ?: return
                if (transport != null) return
                RumorLog.i(TAG, "Wi-Fi LAN up (${addr.hostAddress}) — starting LAN transport")
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock(TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                val t = transportFactory()
                transport = t
                t.start(addr)
                onTransportUp(t)
            }

            override fun onLost(network: Network) {
                stopTransport("Wi-Fi LAN lost")
            }
        }
        callback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            cb,
        )
    }

    fun stop() {
        callback?.let {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        callback = null
        stopTransport("manager stopped")
    }

    private fun stopTransport(reason: String) {
        val t = transport ?: return
        transport = null
        RumorLog.i(TAG, "Stopping LAN transport — $reason")
        t.stop()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }
}
