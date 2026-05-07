package com.rumor.mesh.core.transport.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.transport.DeviceQuirks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE discovery module.
 *
 * Role: advertise that this node is a Rumor peer, and detect other Rumor peers nearby.
 * Output: [peerNearbySignal] — a boolean that flips true when at least one other
 * Rumor node is visible. Wi-Fi Direct discovery is triggered from this signal.
 *
 * Identity is established later via the gossip session HELLO handshake.
 */
@Singleton
class BleDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "BleDiscovery"

    companion object {
        /** Fixed service UUID that identifies Rumor nodes. */
        val RUMOR_SERVICE_UUID: UUID = UUID.fromString("e4ec5ef3-7d6d-4f85-b3c7-2d0f7e8a1b9c")
        private val RUMOR_PARCEL_UUID = ParcelUuid(RUMOR_SERVICE_UUID)

        /** How long a "peer nearby" signal stays true after the last advertisement seen. */
        private const val PEER_NEARBY_TIMEOUT_MS = 60_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _peerNearbySignal = MutableStateFlow(false)
    val peerNearbySignal: StateFlow<Boolean> = _peerNearbySignal.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastPeerSeenMs = 0L

    // Runnable that clears the nearby signal after timeout
    private val peerTimeoutRunnable = Runnable {
        _peerNearbySignal.value = false
        RumorLog.d(TAG, "Peer nearby signal timed out")
    }

    // Samsung quirk: restart scan every 25 minutes to prevent silent scan death
    private val scanRestartRunnable = object : Runnable {
        override fun run() {
            if (_isScanning.value) {
                RumorLog.d(TAG, "Restarting scan (Samsung 30-min workaround)")
                stopScan()
                startScan()
            }
            val interval = DeviceQuirks.bleScanRestartIntervalMs
            if (interval != Long.MAX_VALUE) {
                handler.postDelayed(this, interval)
            }
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                    PackageManager.PERMISSION_GRANTED
        } else true  // No explicit permission needed below API 31
    }

    // ── Advertise ─────────────────────────────────────────────────────────────

    fun startAdvertising() {
        if (_isAdvertising.value) return
        if (!hasAdvertisePermission()) {
            RumorLog.w(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }

        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            // Some low-end chipsets don't support peripheral mode — degrade gracefully
            RumorLog.w(TAG, "BLE advertising not supported on this device (scan-only mode)")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(false)
            .setTimeout(0)  // advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(RUMOR_PARCEL_UUID)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                _isAdvertising.value = true
                RumorLog.i(TAG, "BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                _isAdvertising.value = false
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    ADVERTISE_FAILED_DATA_TOO_LARGE  -> "data too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "unsupported"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    else -> "error $errorCode"
                }
                RumorLog.w(TAG, "BLE advertising failed: $reason")
            }
        }

        advertiser = adv
        adv.startAdvertising(settings, data, advertiseCallback!!)
    }

    fun stopAdvertising() {
        if (!_isAdvertising.value) return
        try {
            if (hasAdvertisePermission()) {
                advertiser?.stopAdvertising(advertiseCallback ?: return)
            }
        } catch (e: Exception) {
            RumorLog.w(TAG, "Error stopping BLE advertising", e)
        }
        advertiser = null
        advertiseCallback = null
        _isAdvertising.value = false
        RumorLog.i(TAG, "BLE advertising stopped")
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    fun startScan() {
        if (_isScanning.value) return
        if (!hasScanPermission()) {
            RumorLog.w(TAG, "Missing BLE scan permission")
            return
        }

        val leScanner = adapter?.bluetoothLeScanner
        if (leScanner == null) {
            RumorLog.w(TAG, "BLE scanner unavailable (Bluetooth off?)")
            return
        }

        // Always filter by service UUID — prevents Android background scan throttle (API 26+)
        val filter = ScanFilter.Builder()
            .setServiceUuid(RUMOR_PARCEL_UUID)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onRumorNodeSeen()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) onRumorNodeSeen()
            }

            override fun onScanFailed(errorCode: Int) {
                _isScanning.value = false
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED        -> "already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED    -> "unsupported"
                    SCAN_FAILED_INTERNAL_ERROR         -> "internal error"
                    else -> "error $errorCode"
                }
                RumorLog.w(TAG, "BLE scan failed: $reason")
            }
        }

        leScanner.startScan(listOf(filter), settings, scanCallback!!)
        scanner = leScanner
        _isScanning.value = true
        RumorLog.i(TAG, "BLE scan started")

        // Schedule Samsung-specific periodic restart if needed
        val restartInterval = DeviceQuirks.bleScanRestartIntervalMs
        if (restartInterval != Long.MAX_VALUE) {
            handler.postDelayed(scanRestartRunnable, restartInterval)
        }
    }

    fun stopScan() {
        handler.removeCallbacks(scanRestartRunnable)
        handler.removeCallbacks(peerTimeoutRunnable)
        if (!_isScanning.value) return
        try {
            if (hasScanPermission()) {
                scanner?.stopScan(scanCallback ?: return)
            }
        } catch (e: Exception) {
            RumorLog.w(TAG, "Error stopping BLE scan", e)
        }
        scanner = null
        scanCallback = null
        _isScanning.value = false
        RumorLog.i(TAG, "BLE scan stopped")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        DeviceQuirks.logDeviceProfile()
        startAdvertising()
        startScan()
    }

    fun stop() {
        stopScan()
        stopAdvertising()
        _peerNearbySignal.value = false
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun onRumorNodeSeen() {
        lastPeerSeenMs = System.currentTimeMillis()
        if (!_peerNearbySignal.value) {
            _peerNearbySignal.value = true
            RumorLog.d(TAG, "Rumor peer detected nearby")
        }
        // Reset the timeout window
        handler.removeCallbacks(peerTimeoutRunnable)
        handler.postDelayed(peerTimeoutRunnable, PEER_NEARBY_TIMEOUT_MS)
    }
}
