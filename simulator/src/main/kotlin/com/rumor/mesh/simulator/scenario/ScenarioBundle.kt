package com.rumor.mesh.simulator.scenario

import com.rumor.mesh.core.logging.RumorLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "ScenarioBundle"

/**
 * Runs every *.json scenario under [scenariosDir] in lexicographic order,
 * collects the results, and bundles them into a single zip at [outputZip].
 *
 * Bundle layout:
 *   summary.json                       — pass/fail + headline metrics per scenario
 *   <scenario>/result.json             — full assertion details + final metrics
 *   <scenario>/trace.json              — per-tick trace (if requested or on failure)
 *   <scenario>/scenario.json           — copy of input for replay
 *
 * Returns true iff every scenario passed.
 */
object ScenarioBundle {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun runAll(
        scenariosDir: File,
        outputZip: File,
        /**
         * Polled between scenarios. When true, the loop exits early and the
         * partial bundle (results collected so far) is written to disk so the
         * dashboard's Cancel button produces a usable download instead of a
         * never-written zip. Default is "never cancel" which preserves
         * original CLI behaviour.
         */
        isCancelled: () -> Boolean = { false },
        /**
         * Periodic progress callback. Called with the 0-based index of the
         * scenario currently running, total count, its name, and an
         * in-scenario 0.0..1.0 fraction. Lets the dashboard render an
         * actual progress bar instead of just "elapsed seconds." Default
         * is a no-op for the CLI.
         */
        onProgress: (currentIndex: Int, total: Int, currentName: String, currentFraction: Float) -> Unit = { _, _, _, _ -> },
    ): Boolean {
        require(scenariosDir.isDirectory) { "not a directory: $scenariosDir" }
        val scenarioFiles = scenariosDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()
        require(scenarioFiles.isNotEmpty()) { "no .json scenarios in $scenariosDir" }

        val runner = ScenarioRunner()
        val results = mutableListOf<RunRecord>()
        val total = scenarioFiles.size

        for ((idx, file) in scenarioFiles.withIndex()) {
            if (isCancelled()) {
                RumorLog.i(TAG, "cancelled before ${file.name} — writing partial bundle")
                break
            }
            onProgress(idx, total, file.nameWithoutExtension, 0f)
            RumorLog.i(TAG, "── running ${file.name} ──")
            val (scenario, parseError) = runCatching {
                json.decodeFromString<Scenario>(file.readText())
            }.fold({ it to null }, { null to it.message })

            if (scenario == null) {
                RumorLog.e(TAG, "failed to parse ${file.name}: $parseError")
                results.add(RunRecord(
                    name = file.nameWithoutExtension,
                    sourceFile = file.name,
                    passed = false,
                    parseError = parseError,
                    scenario = null,
                    result = null,
                ))
                continue
            }

            val result = runCatching {
                runner.run(scenario) { fraction -> onProgress(idx, total, file.nameWithoutExtension, fraction) }
            }
                .getOrElse { e ->
                    RumorLog.e(TAG, "runtime error in ${file.name}: ${e.message}", e)
                    ScenarioResult(
                        name = scenario.name,
                        passed = false,
                        finalMetrics = TraceSample(0, 0, 0, 0, 0, 0),
                        assertions = emptyList(),
                        trace = emptyList(),
                        errors = listOf("${e::class.simpleName}: ${e.message}"),
                    )
                }
            RumorLog.i(TAG, "${scenario.name}: ${if (result.passed) "PASS" else "FAIL"} " +
                "(${result.assertions.count { it.passed }}/${result.assertions.size} assertions)")
            results.add(RunRecord(
                name = scenario.name,
                sourceFile = file.name,
                passed = result.passed,
                parseError = null,
                scenario = scenario,
                result = result,
            ))
        }

        writeBundle(outputZip, results)
        val passed = results.count { it.passed }
        val total  = results.size
        RumorLog.i(TAG, "Bundle written to ${outputZip.absolutePath} ($passed/$total passed)")
        return passed == total
    }

    private fun writeBundle(outputZip: File, results: List<RunRecord>) {
        outputZip.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outputZip)).use { zip ->
            // 1) summary.json
            val summary = Summary(
                generatedAt = Instant.now().toString(),
                totalScenarios = results.size,
                passed = results.count { it.passed },
                scenarios = results.map { r ->
                    SummaryEntry(
                        name = r.name,
                        sourceFile = r.sourceFile,
                        passed = r.passed,
                        assertionsPassed = r.result?.assertions?.count { it.passed } ?: 0,
                        assertionsTotal  = r.result?.assertions?.size ?: 0,
                        parseError = r.parseError,
                        finalMetrics = r.result?.finalMetrics,
                    )
                },
            )
            zip.putFile("summary.json", json.encodeToString(summary))

            // 2) per-scenario directories
            for (r in results) {
                val dir = sanitize(r.name)
                if (r.scenario != null) {
                    zip.putFile("$dir/scenario.json", json.encodeToString(r.scenario))
                }
                if (r.result != null) {
                    zip.putFile("$dir/result.json", json.encodeToString(r.result))
                    val includeTrace = r.scenario?.trace == true || !r.passed
                    if (includeTrace && r.result.trace.isNotEmpty()) {
                        zip.putFile("$dir/trace.json", json.encodeToString(r.result.trace))
                    }
                }
                if (r.parseError != null) {
                    zip.putFile("$dir/parse-error.txt", r.parseError)
                }
            }
        }
    }

    private fun ZipOutputStream.putFile(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Make a name safe for use as a directory inside a zip. */
    private fun sanitize(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}

private data class RunRecord(
    val name: String,
    val sourceFile: String,
    val passed: Boolean,
    val parseError: String?,
    val scenario: Scenario?,
    val result: ScenarioResult?,
)

@Serializable
private data class Summary(
    val generatedAt: String,
    val totalScenarios: Int,
    val passed: Int,
    val scenarios: List<SummaryEntry>,
)

@Serializable
private data class SummaryEntry(
    val name: String,
    val sourceFile: String,
    val passed: Boolean,
    val assertionsPassed: Int,
    val assertionsTotal: Int,
    val parseError: String? = null,
    val finalMetrics: TraceSample? = null,
)
