package com.rumor.mesh.core.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Source-level guard against `RumorMessage.sentAtMs` regressions.
 *
 * Per O64: `sentAtMs` is sender-asserted, trivially forgeable, and (post-
 * collapse / sparse-mesh) not even synchronised between honest nodes. The
 * vast majority of code that wants "when was this message?" should consume
 * [com.rumor.mesh.core.model.displayTimeMs] (`min(sentAtMs, receivedAtMs)`)
 * or `receivedAtMs` directly — both of which are bounded by what THIS node
 * actually witnessed.
 *
 * Direct `.sentAtMs` reads ARE allowed at a small set of sites:
 *  - Compose-time stamps (`sentAtMs = clock.now()`)
 *  - The Ed25519 signature payload (covered by the signature; we don't
 *    "trust" the value, we sign it so receivers can detect tampering)
 *  - Bridge plugins stamping foreign-network timestamps on inbound traffic
 *  - Room/model adapters that mirror the wire layout for storage
 *  - Wire-format ordering keys (RBSR's `(timestamp, id)` tuple — the
 *    ordering only needs to be consistent across peers, not honest)
 *  - The field declaration itself + docstrings explaining the trade-off
 *
 * Any other site is a likely O64-class bug: someone implicitly trusted a
 * sender-controlled timestamp. This test fails on the first such site so
 * the reviewer can either (a) route the new caller through `displayTimeMs`
 * or (b) explicitly justify the new access by adding it to [ALLOWED].
 *
 * Not run inside `commonTest` because it uses `java.io.File`; lives in
 * `jvmTest` and operates on source paths relative to the repo root.
 */
class SentAtMsLintTest {

    /**
     * Sites where direct `.sentAtMs` access is documented and acceptable.
     * Match is *substring on the file path*, so a single entry covers
     * every line within that file. Adding a site here is the explicit
     * "I know what I'm doing" gesture.
     */
    private val ALLOWED = listOf(
        // Field declaration + displayTimeMs computation + docstrings
        "core/src/commonMain/kotlin/com/rumor/mesh/core/model/Message.kt",
        // Compose-time stamps via clock.now() + the RBSR rbsrItems mapping
        "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/GossipEngine.kt",
        // Ed25519 signable bytes — value is signed so receivers detect tampering
        "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/MessageStore.kt",
        // Room <-> model conversion (mirror of wire field)
        "app/src/main/java/com/rumor/mesh/data/adapter/MessageRepositoryAdapter.kt",
        // Bridge plugins stamping foreign-network timestamps onto bridged traffic
        "app/src/main/java/com/rumor/mesh/plugin/meshcore/MeshCoreBridge.kt",
        "app/src/main/java/com/rumor/mesh/plugin/meshtastic/MeshtasticBridge.kt",
        // Simulator compose + RBSR ordering key
        "simulator/src/main/kotlin/com/rumor/mesh/simulator/engine/SimTransport.kt",
        "simulator/src/main/kotlin/com/rumor/mesh/simulator/engine/MessageGenerator.kt",
        // Room entity column + DAO ordering query (the O64-safe `MIN(sentAtMs, receivedAtMs)`)
        "app/src/main/java/com/rumor/mesh/data/Entities.kt",
        "app/src/main/java/com/rumor/mesh/data/MessageDao.kt",
        // Test fixtures (stamping demo messages, fuzz seed JSON literals)
        "core/src/jvmTest/kotlin/com/rumor/mesh/core/model/TrafficClassInvariantTest.kt",
        "core/src/jvmTest/kotlin/com/rumor/mesh/core/fuzz/SeedCorpusTest.kt",
        // This test file itself references the symbol in regex form
        "core/src/jvmTest/kotlin/com/rumor/mesh/core/model/SentAtMsLintTest.kt",
    )

    @Test
    fun `every direct sentAtMs access is in the allowlist`() {
        val repoRoot = findRepoRoot()
        val pattern = Regex("""\bsentAtMs\b""")

        val violations = mutableListOf<String>()

        val sourceRoots = listOf(
            File(repoRoot, "core/src"),
            File(repoRoot, "app/src/main"),
            File(repoRoot, "simulator/src/main"),
        )

        for (root in sourceRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.relativeTo(repoRoot).path
                    if (ALLOWED.any { relative.contains(it) }) return@forEach

                    file.readLines().forEachIndexed { i, raw ->
                        // Strip inline comments — `// ...` and obvious docstrings.
                        val stripped = raw.substringBefore("//").trim()
                        if (stripped.startsWith("*") || stripped.startsWith("/*")) return@forEachIndexed
                        if (pattern.containsMatchIn(stripped)) {
                            violations.add("$relative:${i + 1}: $stripped")
                        }
                    }
                }
        }

        assertEquals(
            emptyList(),
            violations,
            buildString {
                appendLine("New `.sentAtMs` direct access(es) found outside the O64 allowlist.")
                appendLine("Either route the caller through `displayTimeMs` (min of sent + received) or")
                appendLine("add the file path to `ALLOWED` in SentAtMsLintTest with a one-line justification.")
                appendLine()
                appendLine("Violations:")
                violations.forEach { appendLine("  $it") }
            }
        )
    }

    /** Walk up from cwd looking for the repo root (settings.gradle.kts marker). */
    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not find repo root from ${File(".").canonicalPath}")
    }
}
