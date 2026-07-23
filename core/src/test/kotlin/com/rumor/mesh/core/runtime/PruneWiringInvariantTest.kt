package com.rumor.mesh.core.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * O120 source guard: three independent subsystems (BreadcrumbCache,
 * TopologyTracker, OnlineStatusTracker) each shipped a prune that no
 * production code ever called, so their tables grew without bound over
 * exactly the months-long uptimes O55 designs for. The fix is one hygiene
 * entry point ([GossipEngine.pruneMaintenance]) ticked by MeshRuntime.
 *
 * This test greps the source for both halves of that wiring — the tick and
 * the calls — because "periodic maintenance exists but is never invoked" is
 * a failure mode this codebase has now produced three times. Same idiom as
 * SourceInvariantTest: brittle on purpose; a refactor that moves the wiring
 * should consciously update the patterns here.
 */
class PruneWiringInvariantTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("Could not find repo root")
    }

    private fun source(path: String): String =
        File(repoRoot, path).also { check(it.isFile) { "missing: $it" } }.readText()

    @Test
    fun `MeshRuntime ticks pruneMaintenance`() {
        val runtime = source("core/src/main/kotlin/com/rumor/mesh/core/runtime/MeshRuntime.kt")
        if (!Regex("""gossipEngine\.pruneMaintenance\(\)""").containsMatchIn(runtime)) {
            fail(
                """
                |MeshRuntime no longer calls gossipEngine.pruneMaintenance() — the O120
                |hygiene tick is unwired and breadcrumb/route/online-status tables will
                |again grow without bound. If the tick moved to another host, update
                |this pattern after verifying EVERY host (MeshService and :node both
                |start MeshRuntime, which is why the loop lives there).
                |""".trimMargin()
            )
        }
    }

    @Test
    fun `pruneMaintenance covers all three accreting subsystems`() {
        val engine = source("core/src/main/kotlin/com/rumor/mesh/core/protocol/GossipEngine.kt")
        val body = Regex("""fun pruneMaintenance\(\)\s*\{(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(engine)?.groupValues?.get(1)
            ?: fail("GossipEngine.pruneMaintenance() not found — O120 hygiene entry point deleted")
        for (call in listOf("breadcrumbs?.pruneOld()", "topologyTracker.pruneStale()", "onlineStatusTracker.pruneStale()")) {
            if (call !in body) {
                fail(
                    """
                    |pruneMaintenance() lost the call `$call`. Each of these subsystems
                    |accretes state on every exchange and relies on this single entry
                    |point for cleanup (their prunes had zero call sites before O120).
                    |""".trimMargin()
                )
            }
        }
    }
}
