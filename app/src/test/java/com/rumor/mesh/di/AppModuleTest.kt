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
 * [extraTypes] lists types that Koin can't see and shouldn't:
 * - [Context] — supplied via Android-specific `androidContext()` helper.
 * - [CoroutineScope] — appears as a defaulted constructor parameter on
 *   classes like `GossipEngine` and `TransferAssembler`. We don't inject
 *   a project-wide scope; each consumer uses its own internally-managed
 *   default (Dispatchers.Default + SupervisorJob). Koin verify doesn't
 *   know about Kotlin defaults so we tell it the type is satisfied.
 */
class AppModuleTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `appModule resolves all bindings`() {
        appModule.verify(
            extraTypes = listOf(Context::class, CoroutineScope::class),
        )
    }
}
