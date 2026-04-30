package com.rumor.mesh

import android.app.Application
import com.rumor.mesh.core.logging.RumorLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RumorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RumorLog.i("RumorApp", "Rumor ${BuildConfig.VERSION_NAME} starting")
    }
}
