package com.rumor.mesh.plugin.meshcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.rumor.mesh.core.logging.RumorLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Thin BLE GATT client for a MeshCore companion radio.
 *
 * MeshCore exposes the Nordic UART Service (NUS) layout:
 *   service: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   RX     : 6E400002-... (we write here, device receives)
 *   TX     : 6E400003-... (device notifies us; we read)
 *
 * Bonding is NOT required by default firmware — we rely only on the GATT
 * layer. (This is a deliberate MeshCore design choice; do not silently fall
 * back to bonding if a connect fails, because that masks misconfiguration.)
 *
 * Threading: every BluetoothGatt callback fires on the system binder thread.
 * We bounce all state changes onto a [Channel] so the plugin can consume them
 * from its own coroutine scope without dealing with the callback's thread rules.
 *
 * Lifecycle: the host's [com.rumor.mesh.plugin.PluginContext.scope] is the
 * single owner of everything started here. When the plugin is toggled off, the
 * scope cancels, the collector unsubscribes from [inboundFrames], and a call
 * to [disconnect] tears down the BLE side. We never retain anything across a
 * disable→enable cycle — a fresh client is constructed each time the plugin
 * is enabled, matching the [PluginCatalog] factory contract.
 */
@SuppressLint("MissingPermission") // permissions are checked by the host before constructing this
internal class MeshCoreBleClient(private val context: Context) {

    private val tag = "MeshCoreBle"

    private val btManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    // Buffered to allow the plugin a moment to subscribe before the first
    // notification arrives. 64 is plenty — incoming bursts above that mean
    // we have a much bigger problem than a dropped notification.
    private val frames = Channel<ByteArray>(capacity = 64)
    val inboundFrames: Flow<ByteArray> = frames.receiveAsFlow()

    // Scan callback held as a field so we can cancel the same instance we
    // started with — Android requires identity match for stopScan().
    private var scanCallback: ScanCallback? = null

    /**
     * Scan for nearby MeshCore radios for up to [timeoutMs] milliseconds and
     * invoke [onFound] with the first matching device. Caller is responsible
     * for stopping the scan after a successful match (we don't auto-stop on
     * found because the caller may want to surface multiple choices later).
     */
    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            RumorLog.w(tag, "BLE scanner unavailable")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            // Low-latency burns battery; this scan should be triggered by a
            // user action ("Scan for MeshCore device") and stopped soon after.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                RumorLog.w(tag, "BLE scan failed: $errorCode")
            }
        }
        scanCallback = cb
        scanner.startScan(listOf(filter), settings, cb)
    }

    fun stopScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    /**
     * Connect to [device] and prepare the GATT pipes. The future once-only
     * [onReady] callback fires when notifications are enabled and the link is
     * usable. Failures log and silently drop — the plugin can retry by toggling.
     */
    fun connect(device: BluetoothDevice, onReady: () -> Unit) {
        // autoConnect=false: we want an immediate failure if the device is out
        // of range, not a silent indefinite background reconnect. The plugin
        // can re-trigger via its UI when the user wants to retry.
        gatt = device.connectGatt(context, /*autoConnect=*/false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        RumorLog.i(tag, "Connected to ${device.address}")
                        // Discover after a short delay only if the platform demands it.
                        // Most modern stacks tolerate an immediate call; if we see
                        // GATT_133 on first connect we can add a delay here.
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        RumorLog.i(tag, "Disconnected (status=$status)")
                        g.close()
                        gatt = null
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    RumorLog.w(tag, "Service discovery failed: $status"); return
                }
                val svc = g.getService(NUS_SERVICE)
                rxChar = svc?.getCharacteristic(NUS_RX)
                txChar = svc?.getCharacteristic(NUS_TX)
                val tx = txChar ?: run {
                    RumorLog.w(tag, "Device is missing the Nordic UART TX characteristic — not a MeshCore radio?"); return
                }
                g.setCharacteristicNotification(tx, true)
                // CCCD descriptor enables peer-side notifications. Required for NUS.
                tx.getDescriptor(CCCD)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                    onReady()
                }
            }

            @Deprecated("API 33+ has a value-bearing overload but we still need the legacy callback for older devices")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid != NUS_TX) return
                // Snapshot the value here — characteristic.value is reused by the stack
                // and may be overwritten before our coroutine consumer reads it.
                val snapshot = characteristic.value?.copyOf() ?: return
                val sent = frames.trySend(snapshot)
                if (sent.isFailure) RumorLog.w(tag, "Inbound frame buffer full — frame dropped")
            }
        })
    }

    /**
     * Write a frame to the radio's RX characteristic. Returns true if the write
     * was queued — actual on-air transmission completes asynchronously and the
     * device sends back a RESP_CODE_OK for confirmed commands.
     *
     * No length prefix or framing: BLE GATT preserves message boundaries up to
     * the negotiated MTU (default 23 bytes minus 3 byte ATT header = 20 byte
     * payload, which is enough for short text — MeshCore caps text at 133).
     * If we later need to send frames > 20 bytes consistently we should request
     * an MTU bump via gatt.requestMtu(247) after connect.
     */
    fun writeFrame(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val rx = rxChar ?: return false
        rx.value = frame
        // WRITE_TYPE_NO_RESPONSE is faster but loses delivery confirmation.
        // We use WRITE_TYPE_DEFAULT (with-response) so the stack reports
        // failure if the device is out of range or the descriptor is wrong.
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return g.writeCharacteristic(rx)
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
        // close() must follow disconnect to release the binder, but the actual
        // disconnect callback fires async — we close() there.
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX: UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        // Standard Bluetooth Client Characteristic Configuration Descriptor UUID.
        val CCCD: UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
