package com.rumor.mesh.plugin.meshtastic

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
 * BLE GATT client for a Meshtastic radio.
 *
 * Why this differs from the MeshCore client
 * ----------------------------------------
 * Meshtastic doesn't push messages as they arrive — instead it sends a counter
 * notification on FromNum and expects the companion to repeatedly READ from
 * FromRadio until it returns an empty frame. That drain loop is the central
 * complication and the reason these two bridges don't share code: simulating
 * a unified "incoming frames" stream would force the MeshCore side to pretend
 * it has a counter it doesn't have.
 *
 * Bonding
 * -------
 * Default Meshtastic firmware enforces BLE bonding with MITM protection. We
 * don't automate the PIN entry — Android shows the system pairing dialog and
 * the user enters the code shown on the radio's display (or the fixed 123456
 * for screenless devices). Once bonded, future connects skip the prompt.
 *
 * If a connect fails because the device is unbonded, we log it and stop. The
 * user can fix it by going to Android Settings → Bluetooth → pair, then
 * re-enabling the plugin. We do NOT silently disable security by toggling the
 * device into NO_PIN mode — that would be hostile.
 */
@SuppressLint("MissingPermission")
internal class MeshtasticBleClient(private val context: Context) {

    private val tag = "MeshtasticBle"
    private val btAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    // Per the protocol, every FromNum tick should be followed by a drain loop
    // that reads FromRadio repeatedly until empty. The reads are async (each
    // returns via onCharacteristicRead), so we serialise via this latch — set
    // when a tick arrives, cleared when the drain hits an empty frame.
    @Volatile private var draining = false

    private val frames = Channel<ByteArray>(capacity = 64)
    val inboundFrames: Flow<ByteArray> = frames.receiveAsFlow()

    private var scanCallback: ScanCallback? = null

    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        val scanner = btAdapter?.bluetoothLeScanner ?: run {
            RumorLog.w(tag, "BLE scanner unavailable"); return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESHTASTIC_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
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
     * Connect and bond if needed. The radio gates everything on bonding, so we
     * trigger [BluetoothDevice.createBond] explicitly before connecting if the
     * device isn't bonded yet. Android shows the system pairing dialog with
     * the PIN the user can read off the radio.
     */
    fun connect(device: BluetoothDevice, onReady: () -> Unit) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            RumorLog.i(tag, "Device not bonded — initiating pairing")
            // createBond is async; we proceed to connect immediately. If the
            // user cancels the PIN dialog the GATT connection will fail with
            // an auth error, surfacing the problem clearly in the log.
            device.createBond()
        }
        gatt = device.connectGatt(context, /*autoConnect=*/false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        RumorLog.i(tag, "Connected to ${device.address}")
                        // Request a larger MTU. Meshtastic frames can hit
                        // ~512 bytes; without this we'd fragment every read
                        // and burn time on multiple round-trips per FromRadio.
                        g.requestMtu(517)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        RumorLog.i(tag, "Disconnected (status=$status)")
                        g.close()
                        gatt = null
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                // Service discovery deferred until after MTU is negotiated.
                // Doing it earlier sometimes triggers GATT_INSUF_AUTHENTICATION
                // before bonding completes on certain Android versions.
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    RumorLog.w(tag, "Service discovery failed: $status"); return
                }
                val svc = g.getService(MESHTASTIC_SERVICE) ?: run {
                    RumorLog.w(tag, "Meshtastic service not present — wrong device?"); return
                }
                fromRadioChar = svc.getCharacteristic(FROM_RADIO)
                toRadioChar = svc.getCharacteristic(TO_RADIO)
                fromNumChar = svc.getCharacteristic(FROM_NUM)
                val fn = fromNumChar ?: run {
                    RumorLog.w(tag, "FromNum characteristic missing"); return
                }
                g.setCharacteristicNotification(fn, true)
                fn.getDescriptor(CCCD)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                    onReady()
                    // Drain anything queued before we attached — the radio buffers
                    // messages while no client is connected.
                    drainFromRadio()
                }
            }

            @Deprecated("API 33+ has a value-bearing overload but we still need this for older devices")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == FROM_NUM) {
                    // The radio is telling us "I have N packets queued". The actual
                    // packet contents arrive via repeated reads of FromRadio.
                    drainFromRadio()
                }
            }

            @Deprecated("API 33+ has a value-bearing overload")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid != FROM_RADIO) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    RumorLog.w(tag, "FromRadio read failed: $status"); draining = false; return
                }
                val value = characteristic.value
                if (value == null || value.isEmpty()) {
                    // Empty frame signals drain is done. Wait for next FromNum tick.
                    draining = false
                    return
                }
                val snapshot = value.copyOf()
                val sent = frames.trySend(snapshot)
                if (sent.isFailure) RumorLog.w(tag, "Inbound buffer full — frame dropped")
                // Keep draining — there may be more queued.
                fromRadioChar?.let { g.readCharacteristic(it) }
            }
        })
    }

    /**
     * Trigger one drain cycle if not already in flight. The cycle continues in
     * onCharacteristicRead until an empty frame ends it. Re-entrant calls during
     * an active drain are no-ops because subsequent FromNum ticks just mean
     * "look again when you're done", which we will.
     */
    private fun drainFromRadio() {
        val g = gatt ?: return
        val ch = fromRadioChar ?: return
        if (draining) return
        draining = true
        g.readCharacteristic(ch)
    }

    /**
     * Write a frame to ToRadio. The frame is a `ToRadio` protobuf; the radio
     * unwraps it and dispatches per its own rules.
     *
     * We use WRITE_TYPE_DEFAULT (with response) — outbound text is rare enough
     * that the extra round-trip cost is invisible, and we want the stack to
     * report failure if it can't deliver.
     */
    fun writeFrame(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = toRadioChar ?: return false
        ch.value = frame
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return g.writeCharacteristic(ch)
    }

    fun disconnect() {
        runCatching { gatt?.disconnect() }
    }

    companion object {
        // The "Meshtastic Radio Service" — current as of firmware 2.x.
        // Hard-coded because the protocol does not provide service discovery
        // beyond the standard GATT layer.
        val MESHTASTIC_SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val FROM_RADIO: UUID         = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val TO_RADIO: UUID           = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_NUM: UUID           = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        val CCCD: UUID               = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
