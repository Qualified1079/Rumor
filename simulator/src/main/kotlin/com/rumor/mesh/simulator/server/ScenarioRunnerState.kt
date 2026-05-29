package com.rumor.mesh.simulator.server

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.scenario.ScenarioBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ScenarioRunnerState"

/**
 * Backs the dashboard's "Scenarios" panel — discovers bundled scenarios,
 * accepts uploaded ones, and runs a user-selected subset sequentially via
 * [ScenarioBundle], producing a downloadable zip.
 *
 * Single-job model: only one scenario run can be in progress at a time. The
 * dashboard polls [currentJob] to update progress. Job state persists until
 * a new run starts (so the user can download the zip after the run finishes).
 *
 * Scenario sources, scanned in this order:
 *   1. `simulator/scenarios/` — bundled, read-only
 *   2. an in-memory uploaded-files temp dir, written via [acceptUpload]
 *
 * Names are de-duplicated by file basename. Uploaded files with the same name
 * as a bundled scenario shadow the bundled copy for the rest of the process.
 */
class ScenarioRunnerState(
    bundledDir: File = File("simulator/scenarios"),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bundledRoot: File =
        if (bundledDir.isDirectory) bundledDir else File("scenarios")
    private val uploadedRoot: File =
        Files.createTempDirectory("rumor-uploads-").toFile().also { it.deleteOnExit() }

    private val jobRef = AtomicReference<JobRecord?>(null)
    val currentJob: JobRecord? get() = jobRef.get()

    /**
     * Cancel the currently-running job, if any. The currently-executing
     * scenario will run to completion (no clean way to interrupt
     * ScenarioBundle mid-scenario from outside), but no further scenarios
     * will start and the partial zip is finalised on disk.
     */
    fun cancelCurrent(): Boolean {
        val record = jobRef.get() ?: return false
        if (record.status != JobStatus.RUNNING) return false
        record.coroutineJob?.cancel()
        jobRef.set(record.copy(
            status = JobStatus.CANCELLED,
            finishedAtMs = System.currentTimeMillis(),
        ))
        return true
    }

    /** Bundled + uploaded scenario filenames, sorted, uploads shadow bundled. */
    fun listScenarios(): List<ScenarioEntry> {
        val bundled = bundledRoot.takeIf { it.isDirectory }?.listFiles { f ->
            f.isFile && f.extension == "json"
        }?.sortedBy { it.name } ?: emptyList()
        val uploaded = uploadedRoot.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()

        val byName = LinkedHashMap<String, ScenarioEntry>()
        bundled.forEach { byName[it.name] = ScenarioEntry(it.name, ScenarioSource.BUNDLED) }
        uploaded.forEach { byName[it.name] = ScenarioEntry(it.name, ScenarioSource.UPLOADED) }
        return byName.values.toList()
    }

    /**
     * Parse [name]'s scenario file just far enough to expose `durationSec`
     * and `speedMult`, for the dashboard's wall-clock-time estimate. Returns
     * null if the scenario doesn't exist or fails to parse. Errors are
     * intentionally swallowed: the estimate is best-effort UI, not validation.
     */
    fun preview(name: String): ScenarioPreview? {
        val available = listScenarios().associateBy { it.name }
        val entry = available[name] ?: return null
        val src = when (entry.source) {
            ScenarioSource.BUNDLED  -> File(bundledRoot, name)
            ScenarioSource.UPLOADED -> File(uploadedRoot, name)
        }
        return runCatching {
            val parsed = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                classDiscriminator = "kind"
            }.decodeFromString<com.rumor.mesh.simulator.scenario.Scenario>(src.readText())
            ScenarioPreview(parsed.name, parsed.durationSec, parsed.speedMult)
        }.getOrNull()
    }

    /**
     * Persist an uploaded scenario file under [filename] (basename only — any
     * path components are stripped). Overwrites any prior upload with the same
     * name. Bundled scenarios are NOT overwritten on disk; uploads shadow them
     * via the resolution order in [listScenarios].
     */
    fun acceptUpload(filename: String, content: String): File {
        val safe = File(filename).name.takeIf { it.endsWith(".json") }
            ?: error("upload must be a .json file: $filename")
        val out = File(uploadedRoot, safe)
        out.writeText(content)
        RumorLog.i(TAG, "uploaded ${out.absolutePath} (${content.length} bytes)")
        return out
    }

    /**
     * Start a sequential run of the selected scenarios. Returns the new job's
     * id. If a previous job is finished, its zip stays available until a new
     * run starts. If a previous job is still running, throws.
     */
    fun startRun(selectedNames: List<String>): String {
        val existing = jobRef.get()
        if (existing != null && existing.status == JobStatus.RUNNING) {
            error("a scenario run is already in progress (${existing.id})")
        }

        // Resolve each name to a source file under bundledRoot or uploadedRoot.
        // Stage everything into a fresh temp dir so ScenarioBundle can iterate.
        val staging = Files.createTempDirectory("rumor-job-").toFile()
        staging.deleteOnExit()
        val available = listScenarios().associateBy { it.name }
        for (name in selectedNames) {
            val entry = available[name] ?: error("unknown scenario: $name")
            val src = when (entry.source) {
                ScenarioSource.BUNDLED  -> File(bundledRoot, name)
                ScenarioSource.UPLOADED -> File(uploadedRoot, name)
            }
            src.copyTo(File(staging, name), overwrite = true)
        }

        val jobId = "job-${Instant.now().toEpochMilli()}"
        val outZip = File(staging, "$jobId.zip")
        val record = JobRecord(
            id = jobId,
            startedAtMs = System.currentTimeMillis(),
            selected = selectedNames,
            status = JobStatus.RUNNING,
            outputZip = outZip,
            error = null,
        )
        jobRef.set(record)

        val coroutineJob = scope.launch {
            try {
                val allPassed = ScenarioBundle.runAll(staging, outZip)
                jobRef.updateAndGet { cur ->
                    if (cur?.id != jobId) cur  // someone cancelled / replaced — leave it
                    else cur.copy(
                        status = if (allPassed) JobStatus.PASSED else JobStatus.FAILED,
                        finishedAtMs = System.currentTimeMillis(),
                    )
                }
                RumorLog.i(TAG, "$jobId finished: allPassed=$allPassed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                RumorLog.i(TAG, "$jobId cancelled")
                throw e
            } catch (e: Throwable) {
                jobRef.updateAndGet { cur ->
                    if (cur?.id != jobId) cur
                    else cur.copy(
                        status = JobStatus.ERROR,
                        finishedAtMs = System.currentTimeMillis(),
                        error = "${e::class.simpleName}: ${e.message}",
                    )
                }
                RumorLog.e(TAG, "$jobId errored: ${e.message}", e)
            }
        }
        jobRef.updateAndGet { cur ->
            if (cur?.id == jobId) cur.copy(coroutineJob = coroutineJob) else cur
        }

        return jobId
    }
}

enum class ScenarioSource { BUNDLED, UPLOADED }

@Serializable
data class ScenarioEntry(val name: String, val source: ScenarioSource)

@Serializable
data class ScenarioPreview(val name: String, val durationSec: Int, val speedMult: Double)

enum class JobStatus { RUNNING, PASSED, FAILED, ERROR, CANCELLED }

data class JobRecord(
    val id: String,
    val startedAtMs: Long,
    val selected: List<String>,
    val status: JobStatus,
    val outputZip: File,
    val finishedAtMs: Long? = null,
    val error: String? = null,
    val coroutineJob: Job? = null,
)

@Serializable
data class JobStatusDto(
    val id: String,
    val status: String,
    val selected: List<String>,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val error: String? = null,
    val hasZip: Boolean,
)
