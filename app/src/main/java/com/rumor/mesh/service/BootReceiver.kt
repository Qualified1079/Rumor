package com.rumor.mesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rumor.mesh.core.logging.RumorLog

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
