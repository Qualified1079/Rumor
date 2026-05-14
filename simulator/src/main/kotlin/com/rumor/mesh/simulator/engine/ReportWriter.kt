package com.rumor.mesh.simulator.engine

import com.rumor.mesh.simulator.params.SimParamRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles a snapshot of the simulation state into a zip archive the user
 * can download and forward for troubleshooting.
 *
 * Contents:
 *  - manifest.json  — timestamp, rumor version, sim params used
 *  - nodes.json     — per-node state at report time
 *  - edges.json     — per-edge conditioner state
 *  - metrics.json   — current WorldMetrics
 *  - replay.json    — seed + params sufficient to reproduce the scenario
 *  - summary.md     — human-readable summary
 */
object ReportWriter {

    private val json = Json { prettyPrint = true }

    fun generate(world: SimWorld, params: SimParamRegistry, rumorVersion: String = "dev"): ByteArray {
        val nodes   = world.nodeSnapshots()
        val edges   = world.edgeSnapshots()
        val metrics = world.metrics.value

        val manifest = Manifest(
            generatedAt   = Instant.now().toString(),
            rumorVersion  = rumorVersion,
            simTimeMs     = metrics.simTimeMs,
            nodeCount     = metrics.nodeCount,
            edgeCount     = metrics.edgeCount,
        )
        val replay = ReplaySpec(
            seed   = params.seed.value,
            params = params.descriptors().associate { it.id to it.current },
        )
        val summary = buildSummary(metrics, nodes, edges)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putFile("manifest.json",  json.encodeToString(manifest))
            zip.putFile("nodes.json",     json.encodeToString(nodes.map(NodeSnapshot::toReport)))
            zip.putFile("edges.json",     json.encodeToString(edges.map(EdgeSnapshot::toReport)))
            zip.putFile("metrics.json",   json.encodeToString(metrics.toReport()))
            zip.putFile("replay.json",    json.encodeToString(replay))
            zip.putFile("summary.md",     summary)
        }
        return baos.toByteArray()
    }

    private fun buildSummary(metrics: WorldMetrics, nodes: List<NodeSnapshot>, edges: List<EdgeSnapshot>): String {
        val heaviestNode = nodes.maxByOrNull { it.queueDepth }
        val partitioned  = edges.count { it.partitioned }
        return """
# Rumor Simulator Report

**Generated:** ${Instant.now()}
**Sim time elapsed:** ${metrics.simTimeMs / 1000}s

## Network
- Nodes: ${metrics.nodeCount}
- Edges: ${metrics.edgeCount}
- Partitioned edges: $partitioned

## Traffic
- Messages this tick: ${metrics.totalMsgsThisTick}
- Total dropped: ${metrics.totalDropped}

## Memory
- Heap used: ${metrics.heapUsedMb} MB / ${metrics.heapMaxMb} MB

## Hotspots
- Heaviest scheduler queue: node ${heaviestNode?.index} (${heaviestNode?.queueDepth} msgs)
- Highest dup-drop node: ${nodes.maxByOrNull { it.dupDrops }?.let { "node ${it.index} (${it.dupDrops})" } ?: "n/a"}

To reproduce: load `replay.json` in the simulator.
""".trimIndent()
    }

    private fun ZipOutputStream.putFile(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    @Serializable data class Manifest(
        val generatedAt: String, val rumorVersion: String,
        val simTimeMs: Long, val nodeCount: Int, val edgeCount: Int,
    )
    @Serializable data class ReplaySpec(val seed: Long, val params: Map<String, String>)
}

private fun NodeSnapshot.toReport() = mapOf(
    "index" to index, "userId" to userId,
    "queueDepth" to queueDepth, "messagesProcessed" to messagesProcessed, "dupDrops" to dupDrops,
)
private fun EdgeSnapshot.toReport() = mapOf(
    "from" to from, "to" to to,
    "latencyMs" to latencyMs, "lossRate" to lossRate, "partitioned" to partitioned,
)
private fun WorldMetrics.toReport() = mapOf(
    "nodeCount" to nodeCount, "edgeCount" to edgeCount,
    "totalMsgsThisTick" to totalMsgsThisTick, "totalDropped" to totalDropped,
    "simTimeMs" to simTimeMs, "heapUsedMb" to heapUsedMb, "heapMaxMb" to heapMaxMb,
)
