package com.rumor.mesh.simulator

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.engine.SimWorld
import com.rumor.mesh.simulator.params.SimParamRegistry
import com.rumor.mesh.simulator.scenario.ScenarioBundle
import com.rumor.mesh.simulator.server.DashboardServer
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

private const val TAG = "Main"

/**
 * Simulator entry point. Two modes:
 *
 *   Dashboard (default):
 *     java -jar simulator.jar [--port N] [--version X]
 *   Builds an empty [SimWorld], starts the embedded dashboard server, and
 *   waits. The simulation itself stays paused until the user hits Start.
 *
 *   Headless scenario runner:
 *     java -jar simulator.jar --scenarios <path> [--out <zip>]
 *   Path may be a single .json scenario or a directory of them. Each
 *   scenario is run in order; results bundled into a single zip. Exit
 *   code 1 if any scenario failed an assertion.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)

    if (opts.scenarios != null) {
        runScenarios(opts)
        return
    }

    val world = SimWorld(SimParamRegistry())
    RumorLog.i(TAG, "Rumor simulator — open the dashboard and press Start")

    DashboardServer(
        world        = world,
        port         = opts.port,
        rumorVersion = opts.version,
    ).start(wait = true)
}

private fun runScenarios(opts: Options) {
    val input = File(opts.scenarios!!).absoluteFile
    require(input.exists()) { "scenarios path does not exist: $input" }
    val outZip = (opts.out?.let(::File) ?: File("rumor-bundle.zip")).absoluteFile
    outZip.parentFile?.mkdirs()

    val dir = if (input.isDirectory) {
        // Filter by --only=name1,name2 if provided. Match by basename or prefix.
        val only = opts.only?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (only.isNullOrEmpty()) {
            input
        } else {
            val tmp = java.nio.file.Files.createTempDirectory("rumor-scenario-").toFile()
            val available = input.listFiles { f -> f.isFile && f.extension == "json" }?.toList() ?: emptyList()
            val matched = available.filter { f ->
                only.any { name ->
                    val n = name.removeSuffix(".json")
                    f.nameWithoutExtension == n ||
                        f.nameWithoutExtension.startsWith("$n-") ||
                        f.name.contains(n)
                }
            }
            require(matched.isNotEmpty()) {
                "no scenarios matched --only=${opts.only}. Available: ${available.map { it.name }}"
            }
            matched.forEach { it.copyTo(File(tmp, it.name), overwrite = true) }
            println("[scenarios] selected ${matched.size} of ${available.size}: ${matched.map { it.name }}")
            tmp
        }
    } else {
        // Single-file convenience: copy into a temp dir so the bundler can iterate it.
        val tmp = java.nio.file.Files.createTempDirectory("rumor-scenario-").toFile()
        input.copyTo(File(tmp, input.name), overwrite = true)
        tmp
    }

    println("[scenarios] writing bundle to: $outZip")
    val started = System.currentTimeMillis()
    val allPassed = ScenarioBundle.runAll(dir, outZip)
    val elapsedSec = (System.currentTimeMillis() - started) / 1000.0
    val tag = if (allPassed) "ALL PASSED" else "SOME FAILED"
    println("[scenarios] $tag in ${"%.1f".format(elapsedSec)}s — bundle at: $outZip (${outZip.length() / 1024} KB)")
    exitProcess(if (allPassed) 0 else 1)
}

private data class Options(
    val port: Int = 8080,
    val version: String = "dev",
    val scenarios: String? = null,
    val out: String? = null,
    val only: String? = null,
)

private fun parseArgs(args: Array<String>): Options {
    var opts = Options()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port"      -> opts = opts.copy(port = args.getOrNull(++i)?.toIntOrNull() ?: opts.port)
            "--version"   -> opts = opts.copy(version = args.getOrNull(++i) ?: opts.version)
            "--scenarios" -> opts = opts.copy(scenarios = args.getOrNull(++i))
            "--out"       -> opts = opts.copy(out = args.getOrNull(++i))
            "--only"      -> opts = opts.copy(only = args.getOrNull(++i))
        }
        i++
    }
    return opts
}
