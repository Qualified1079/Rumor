package com.rumor.mesh.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The backlog lives in three files (CLAUDE.md index, docs/OPEN_BACKLOG.md bodies,
 * docs/COMPLETED_GAPS.md archive) that drifted apart under hand edits: closed rows
 * left indexed, indexed rows with no body, duplicated G numbers. This pins the
 * invariants on every machine that runs the suite; `scripts/backlog.py` makes the
 * edits and runs a superset of these checks.
 */
class BacklogConsistencyTest {

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun tierRows(file: String): Map<String, String> {
        val rows = linkedMapOf<String, String>()
        var inTier = false
        File(root, file).readLines().forEach { line ->
            when {
                line.startsWith("#### Tier ") -> inTier = true
                line.startsWith("### ") || line.startsWith("#### ") -> inTier = false
                inTier -> Regex("""^\| (O\d+)\b""").find(line)?.let { rows[it.groupValues[1]] = line }
            }
        }
        return rows
    }

    @Test
    fun `every indexed row has a body and every body is indexed`() {
        val index = tierRows("CLAUDE.md").keys
        val bodies = tierRows("docs/OPEN_BACKLOG.md").keys
        assertTrue("indexed in CLAUDE.md without a body in OPEN_BACKLOG.md: ${index - bodies}", (index - bodies).isEmpty())
        assertTrue("body in OPEN_BACKLOG.md without a CLAUDE.md index row: ${bodies - index}", (bodies - index).isEmpty())
    }

    @Test
    fun `no closed row is still indexed as open`() {
        val done = tierRows("CLAUDE.md").filterValues { it.contains("**`[DONE") }.keys
        assertTrue("[DONE] rows still in the open index — close them into COMPLETED_GAPS.md: $done", done.isEmpty())
    }

    @Test
    fun `G numbers are unique`() {
        val gs = Regex("""^\| G(\d+) \|""", RegexOption.MULTILINE)
            .findAll(File(root, "docs/COMPLETED_GAPS.md").readText()).map { it.groupValues[1] }.toList()
        val dup = gs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate G numbers: $dup", dup.isEmpty())
    }
}
