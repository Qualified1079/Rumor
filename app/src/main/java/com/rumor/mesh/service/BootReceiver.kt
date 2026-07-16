package com.rumor.mesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rumor.mesh.core.logging.RumorLog

/**
 * Restarts [MeshService] after a device reboot or app upgrade.
 *
 * Without this, a phone that reboots overnight would silently stop carrying
 * mesh traffic until the user manually re-opened the app — bad in the
 * post-disaster regime Rumor targets, where the mesh is most useful when
 * left running unattended.
 *
 * Registered for `BOOT_COMPLETED` (post-reboot) and `MY_PACKAGE_REPLACED`
 * (post-upgrade) in the manifest. Identity may still be locked at this point —
 * [MeshService.startMesh] no-ops gracefully and surfaces "Locked" in the
 * notification until the user opens the app and unlocks.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            RumorLog.i("BootReceiver", "Boot/update received — starting MeshService")
            val serviceIntent = Intent(context, MeshService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
