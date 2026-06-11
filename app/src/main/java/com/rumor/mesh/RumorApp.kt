package com.rumor.mesh

import android.app.Application
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application entrypoint. Starts Koin with [appModule] before any
 * activity or service can `inject()` a dependency. Wiring smoke is
 * caught at unit-test time by `AppModuleTest` (G6) rather than as a
 * device-launch crash.
 */
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
