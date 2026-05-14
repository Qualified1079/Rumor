package com.rumor.mesh.simulator

import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.simulator.engine.SimWorld
import com.rumor.mesh.simulator.params.SimParamRegistry
import com.rumor.mesh.simulator.server.DashboardServer

private const val TAG = "Main"

/**
 * Simulator entry point.
 *
 * Usage:
 *   java -jar simulator.jar [--port N] [--version X]
 *
 * Builds an empty [SimWorld], starts the embedded dashboard server, and waits.
 * The simulation itself stays paused until the user hits "Start" in the browser.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)

    val world = SimWorld(SimParamRegistry())
    RumorLog.i(TAG, "Rumor simulator — open the dashboard and press Start")

    DashboardServer(
        world        = world,
        port         = opts.port,
        rumorVersion = opts.version,
    ).start(wait = true)
}

private data class Options(val port: Int = 8080, val version: String = "dev")

private fun parseArgs(args: Array<String>): Options {
    var opts = Options()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port"    -> opts = opts.copy(port = args.getOrNull(++i)?.toIntOrNull() ?: opts.port)
            "--version" -> opts = opts.copy(version = args.getOrNull(++i) ?: opts.version)
        }
        i++
    }
    return opts
}
