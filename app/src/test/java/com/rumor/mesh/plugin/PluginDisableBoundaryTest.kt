package com.rumor.mesh.plugin

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O123 source guard for the plugin disable-boundary race. PluginRegistry needs
 * a fully-wired GossipEngine (~18 ctor params) to instantiate, so a live
 * concurrency test would be almost all scaffolding for a bounded-blast-radius
 * flag guard. Instead — same idiom as :core's SourceInvariantTest — pin the two
 * load-bearing halves of the fix in source: (1) the dispatch loop skips a plugin
 * whose holder is not alive, and (2) unregister() clears the flag BEFORE it
 * cancels the scope / runs onDetach. Reversing that order reopens the window.
 */
class PluginDisableBoundaryTest {

    private val source: String by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("repo root not found")
        File(root, "app/src/main/java/com/rumor/mesh/plugin/PluginRegistry.kt")
            .also { check(it.isFile) { "PluginRegistry.kt not at $it" } }
            .readText()
    }

    @Test
    fun `dispatch loop checks the alive flag`() {
        assertTrue(
            "onMessageReceived must skip a not-alive plugin holder (O123). " +
                "Expected an `if (!holder.alive) return@forEach` guard in the dispatch loop.",
            Regex("""if\s*\(\s*!holder\.alive\s*\)\s*return@forEach""").containsMatchIn(source),
        )
    }

    @Test
    fun `register rolls back scope and context if onAttach throws`() {
        // O25/O123: onAttach failure must not leak the scope/context (unregister
        // can't clean up a plugin that never made it into `plugins`).
        val hasTryCatch = Regex("""try\s*\{\s*plugin\.onAttach\(ctx\)""").containsMatchIn(source)
        assertTrue("register() must wrap plugin.onAttach in try/catch", hasTryCatch)
        val rollsBackScope = source.contains("pluginScopes.remove(plugin.pluginId)?.cancel()")
        val rollsBackCtx = source.contains("pluginContexts.remove(plugin.pluginId)?.unregisterAllEnvelopes()")
        assertTrue("onAttach failure must cancel the scope", rollsBackScope)
        assertTrue("onAttach failure must unregister the plugin's envelopes/context", rollsBackCtx)
    }

    @Test
    fun `unregister clears alive before tearing down`() {
        // The flag write must precede the scope cancel — otherwise a dispatch
        // in flight can still call the plugin on a cancelled scope.
        val aliveFalsePos = source.indexOf("holder.alive = false")
        val cancelPos = source.indexOf("pluginScopes.remove(pluginId)?.cancel()")
        assertTrue("O123: unregister must set holder.alive = false", aliveFalsePos >= 0)
        assertTrue("O123: unregister must cancel the plugin scope", cancelPos >= 0)
        assertTrue(
            "O123: holder.alive = false MUST come before the scope cancel in unregister() " +
                "(clearing the flag after teardown reopens the race window).",
            aliveFalsePos < cancelPos,
        )
    }
}
