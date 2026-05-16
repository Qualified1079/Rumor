package com.rumor.mesh.di

import android.content.Context
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
 * [extraTypes] lists types that Koin can't see because they're supplied via
 * Android-specific helpers like `androidContext()`. We declare those here so
 * verify() doesn't flag them as unresolved.
 */
class AppModuleTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `appModule resolves all bindings`() {
        appModule.verify(
            extraTypes = listOf(Context::class),
        )
    }
}
