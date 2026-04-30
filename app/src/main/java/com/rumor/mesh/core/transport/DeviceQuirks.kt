package com.rumor.mesh.core.transport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.rumor.mesh.core.logging.RumorLog

/**
 * Centralised registry of known OEM/OS quirks for BLE and Wi-Fi Direct.
 * Both transport modules consult this object so workaround logic stays in one place.
 */
object DeviceQuirks {

    private const val TAG = "DeviceQuirks"

    val manufacturer: String = Build.MANUFACTURER.lowercase()
    val model: String = Build.MODEL.lowercase()
    val sdkInt: Int = Build.VERSION.SDK_INT

    // ── BLE ──────────────────────────────────────────────────────────────────

    /**
     * Samsung BLE scanning silently dies after ~30 minutes.
     * Restart the scan every 25 minutes as a precaution.
     */
    val bleScanRestartIntervalMs: Long
        get() = if (isSamsung) 25 * 60 * 1000L else Long.MAX_VALUE

    /**
     * Some low-end Qualcomm and MediaTek chipsets do not support BLE peripheral
     * (advertising) mode. Check at runtime with BluetoothLeAdvertiser != null.
     */
    val mustCheckAdvertiserSupport: Boolean = true  // always check; log gracefully if null

    /**
     * On API < 31, BLE scanning requires ACCESS_FINE_LOCATION even when location
     * is not used. This is an Android platform quirk, not an OEM issue.
     */
    val bleRequiresLocationPermission: Boolean = sdkInt < Build.VERSION_CODES.S

    /**
     * Unfiltered background BLE scans are throttled to 15 per 30 s on API 26+.
     * Always scan with a service UUID filter to bypass the throttle.
     */
    val bleMustUseFilter: Boolean = sdkInt >= Build.VERSION_CODES.O

    // ── Wi-Fi Direct ─────────────────────────────────────────────────────────

    /**
     * Stale Wi-Fi Direct groups survive reboots on most devices and block new
     * connections. Always call removeGroup() before attempting anything.
     */
    val wifiDirectMustRemoveGroupOnStart: Boolean = true

    /**
     * The Group Owner's IP address in an Android Wi-Fi Direct group is always
     * 192.168.49.1. Parsing it from WifiP2pInfo.groupOwnerAddress is unreliable
     * on several OEMs (returns null or 0.0.0.0 until DHCP completes).
     */
    const val WIFI_DIRECT_GO_IP = "192.168.49.1"
    const val WIFI_DIRECT_GOSSIP_PORT = 8787

    /**
     * After a Wi-Fi Direct connection is established, the DHCP handshake can
     * take several seconds. Retry TCP connects with this backoff profile.
     */
    val tcpConnectRetryDelaysMs: List<Long> = listOf(500, 1000, 2000, 4000, 8000)

    /**
     * Samsung and many MediaTek devices demand to be Group Owner regardless of
     * the GO intent value (0–15) the app sets. Both sides must therefore run a
     * ServerSocket AND attempt a client connect; whichever TCP connection
     * succeeds first wins.
     */
    val wifiDirectDualRoleRequired: Boolean = isSamsung || isMediaTek

    /**
     * On Android < 10, starting a Wi-Fi Direct group tears down the existing
     * Wi-Fi STA connection on some Qualcomm devices. Warn the user.
     */
    val wifiDirectMayDisableWifi: Boolean = sdkInt < Build.VERSION_CODES.Q && isQualcomm

    /**
     * WifiP2pManager calls can return BUSY if issued too quickly in succession.
     * Route all calls through a serialized queue and retry on BUSY.
     */
    val wifiDirectOperationsNeedQueue: Boolean = true

    /**
     * API 33+ uses NEARBY_WIFI_DEVICES instead of ACCESS_FINE_LOCATION for
     * Wi-Fi Direct peer discovery.
     */
    val wifiDirectRequiresNearbyWifiDevices: Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU
    val wifiDirectRequiresLocationPermission: Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

    // ── Battery optimisation ─────────────────────────────────────────────────

    /**
     * Returns an Intent that opens the battery optimisation exemption screen
     * for the current OEM, or null if the standard Android setting suffices.
     * Callers should present this intent to users when the mesh service is killed
     * unexpectedly in the background.
     */
    fun batteryOptimisationIntent(context: Context): Intent? {
        val pkg = context.packageName
        return when {
            isSamsung -> Intent("com.samsung.android.sm_cn.ACTION_BATTERY_SAVE")
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: standardBatteryIntent(pkg)
            isXiaomi -> Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST")
                .putExtra("pkg_name", pkg)
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: standardBatteryIntent(pkg)
            isHuawei -> Intent()
                .setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: standardBatteryIntent(pkg)
            isOppo || isOnePlus -> Intent()
                .setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
                .takeIf { it.resolveActivity(context.packageManager) != null }
                ?: standardBatteryIntent(pkg)
            else -> standardBatteryIntent(pkg)
        }.also {
            RumorLog.d(TAG, "Battery optimisation intent for $manufacturer/$model: $it")
        }
    }

    private fun standardBatteryIntent(pkg: String) = Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$pkg")
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    private val isSamsung   get() = manufacturer.contains("samsung")
    private val isXiaomi    get() = manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
    private val isHuawei    get() = manufacturer.contains("huawei") || manufacturer.contains("honor")
    private val isOppo      get() = manufacturer.contains("oppo")
    private val isOnePlus   get() = manufacturer.contains("oneplus")
    private val isMediaTek  get() = isDeviceMediaTek()
    private val isQualcomm  get() = isDeviceQualcomm()

    private fun isDeviceMediaTek(): Boolean {
        return try {
            val cpu = System.getProperty("ro.product.board") ?: ""
            cpu.contains("mt", ignoreCase = true) || cpu.contains("mediatek", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun isDeviceQualcomm(): Boolean {
        return try {
            val cpu = System.getProperty("ro.product.board") ?: ""
            cpu.contains("msm", ignoreCase = true) || cpu.contains("sdm", ignoreCase = true)
                    || cpu.contains("sm", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun logDeviceProfile() {
        RumorLog.i(TAG, "Device: $manufacturer / $model / API $sdkInt")
        RumorLog.i(TAG, "BLE scan restart: ${bleScanRestartIntervalMs}ms | filter required: $bleMustUseFilter")
        RumorLog.i(TAG, "WiFiDirect dual-role: $wifiDirectDualRoleRequired | may disable wifi: $wifiDirectMayDisableWifi")
    }
}
