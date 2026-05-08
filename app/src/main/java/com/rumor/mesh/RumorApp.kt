package com.rumor.mesh

import android.app.Application
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RumorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RumorApp)
            modules(appModule)
        }
        RumorLog.i("RumorApp", "Rumor ${BuildConfig.VERSION_NAME} starting")
    }
}
