package com.rumor.mesh.core.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.mode.AutoModeController
import com.rumor.mesh.core.mode.Charging
import com.rumor.mesh.core.mode.DeviceState
import com.rumor.mesh.core.mode.LocalTime
import com.rumor.mesh.core.mode.Network
import com.rumor.mesh.core.mode.Screen
import com.rumor.mesh.core.mode.Weekday
import com.rumor.mesh.core.policy.ModeStateManager.Source
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ModeOrchestrator"
private const val TICK_MS = 60_000L

/**
 * O80/O57 — the Android half of mode auto-fire. Assembles a [DeviceState]
 * from battery/plug broadcasts, screen state, and connectivity; feeds it to
 * [AutoModeController] on every signal change plus a 60s tick (time-of-day
 * rules); applies the result via [ModeStateManager.setMode] — but only while
 * the user has auto mode enabled (manual-override-wins, O57).
 *
 * Lifecycle-owned by MeshService: [start] in startMesh, [stop] in stopMesh.
 * Both are idempotent; each start builds a fresh scope (a cancelled
 * CoroutineScope can't be reused).
 */
class ModeOrchestrator(
    private val context: Context,
    private val modeStateManager: ModeStateManager,
) {
    private val controller = AutoModeController()
    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Latest values from the sticky ACTION_BATTERY_CHANGED broadcast.
    @Volatile private var batteryPercent = 100
    @Volatile private var charging = Charging.Discharging
    @Volatile private var screenOn = true

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s

        screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> readBattery(intent)
                    Intent.ACTION_SCREEN_ON -> screenOn = true
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                }
                evaluateNow()
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // ACTION_BATTERY_CHANGED is sticky — registration returns the current
        // snapshot immediately, so battery/plug state is seeded before the
        // first evaluation.
        context.registerReceiver(r, filter)?.let { readBattery(it) }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = evaluateNow()
            override fun onLost(network: android.net.Network) = evaluateNow()
        }
        networkCallback = cb
        // Not registerDefaultNetworkCallback — that's API 24+, minSdk is 23.
        cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb)

        s.launch {
            while (isActive) {
                evaluateNow()
                delay(TICK_MS)
            }
        }
        // Flipping auto on in Settings should take effect immediately, not at
        // the next tick or signal change.
        s.launch {
            modeStateManager.autoEnabled.collect { if (it) evaluateNow() }
        }
        RumorLog.i(TAG, "started (auto=${modeStateManager.autoEnabled.value})")
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        networkCallback?.let {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        scope?.cancel()
        scope = null
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && batteryScale > 0) batteryPercent = level * 100 / batteryScale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        charging = when {
            !plugged -> Charging.Discharging
            status == BatteryManager.BATTERY_STATUS_CHARGING -> Charging.Charging
            else -> Charging.PluggedNotCharging
        }
    }

    private fun evaluateNow() {
        if (!modeStateManager.autoEnabled.value) return
        val target = controller.evaluate(currentDeviceState(), modeStateManager.mode.value) ?: return
        modeStateManager.setMode(target, Source.AUTO)
    }

    private fun currentDeviceState(): DeviceState {
        val cal = Calendar.getInstance()
        return DeviceState(
            batteryPercent = batteryPercent,
            charging = charging,
            screen = if (screenOn) Screen.ON else Screen.OFF,
            network = currentNetwork(),
            timeOfDay = LocalTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
            weekday = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> Weekday.MON
                Calendar.TUESDAY -> Weekday.TUE
                Calendar.WEDNESDAY -> Weekday.WED
                Calendar.THURSDAY -> Weekday.THU
                Calendar.FRIDAY -> Weekday.FRI
                Calendar.SATURDAY -> Weekday.SAT
                else -> Weekday.SUN
            },
        )
    }

    private fun currentNetwork(): Network {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Network.OFFLINE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Network.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Network.CELLULAR
            else -> Network.OFFLINE
        }
    }
}
