package com.rumor.mesh.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Static verification that every `get()` call in [appModule] has a matching
 * `single`/`factory` registration. Pure JVM — no Android runtime, no Robolectric.
 *
 * Catches DI breakage (an added constructor param, a missing `single`, a wrong
 * type) at unit-test time rather than as an opaque crash on a real device the
 * first time a user opens the app.
 *
 * [extraTypes] lists types that Koin can't see: those supplied via
 * Android-specific helpers like `androidContext()` (Context), plus constructor
 * params that have Kotlin default values the DI graph deliberately doesn't
 * override (MeshGraph's scope, relay-batch windows and breadcrumbRouting). verify() doesn't model default params, so we declare
 * them here rather than binding scopes/primitives into Koin.
 */
class AppModuleTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `appModule resolves all bindings`() {
        appModule.verify(
            extraTypes = listOf(
                Context::class,
                // MeshGraph's defaulted host knobs (scope, relay windows,
                // breadcrumbRouting) — deliberately not bound into Koin.
                CoroutineScope::class,
                Long::class,
                Boolean::class,
                // Defaulted Function0 params (e.g. OnlineStatusTracker's) that
                // verify() inspects even though MeshGraph constructs the class.
                Function0::class,
            ),
        )
    }
}
