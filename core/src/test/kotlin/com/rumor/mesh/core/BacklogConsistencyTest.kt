package com.rumor.mesh.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The backlog is an index (docs/backlog/INDEX.md) plus one file per row
 * (docs/backlog/open, done). It drifted under hand edits when it was three big
 * tables, and CLAUDE.md grew past what a session can afford to load. This pins
 * the invariants on every machine that runs the suite; `scripts/backlog.py`
 * makes the edits and its `check` runs a superset of these.
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
                line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ") -> inTier = false
                inTier -> Regex("""^\| (O\d+)\b""").find(line)?.let { rows[it.groupValues[1]] = line }
            }
        }
        return rows
    }

    private fun rowFiles(dir: String): List<File> =
        File(root, "docs/backlog/$dir").listFiles { f -> f.extension == "md" }.orEmpty().toList()

    @Test
    fun `every indexed row has a body file and every body file is indexed`() {
        val index = tierRows("docs/backlog/INDEX.md").keys
        val bodies = rowFiles("open").map { it.nameWithoutExtension }.toSet()
        assertTrue("the index parsed no rows", index.size > 20)
        assertTrue("indexed without docs/backlog/open/<row>.md: ${index - bodies}", (index - bodies).isEmpty())
        assertTrue("docs/backlog/open file without an INDEX.md row: ${bodies - index}", (bodies - index).isEmpty())
    }

    @Test
    fun `no closed row is still indexed as open`() {
        val done = tierRows("docs/backlog/INDEX.md").filterValues { it.contains("**`[DONE") }.keys
        assertTrue("[DONE] rows still in the open index, close them with scripts/backlog.py: $done", done.isEmpty())
    }

    @Test
    fun `completed rows are one file per G number`() {
        val files = rowFiles("done")
        assertTrue("docs/backlog/done is empty", files.size > 20)
        val odd = files.map { it.name }.filterNot { Regex("""[GP]\d+\.md""").matches(it) }
        assertTrue("unexpected files in docs/backlog/done: $odd", odd.isEmpty())
    }

    @Test
    fun `CLAUDE md stays an always-loaded summary, not the backlog`() {
        val claude = File(root, "CLAUDE.md")
        assertTrue("CLAUDE.md has tier tables again, the index lives in docs/backlog/INDEX.md", tierRows("CLAUDE.md").isEmpty())
        assertTrue(
            "CLAUDE.md is ${claude.length()} bytes, over the 24000 budget: move detail to a module CLAUDE.md or docs/",
            claude.length() <= 24_000,
        )
    }

    @Test
    fun `row files have no line long enough to be truncated by a file reader`() {
        val long = listOf("open", "done", "archive").flatMap { rowFiles(it) }
            .filter { f -> f.readLines().any { it.length > 1500 } }.map { it.name }
        assertTrue("rows with a line over 1500 chars, wrap them: $long", long.isEmpty())
    }
}
