package com.rumor.mesh.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A non-ASCII character in a backtick test name ends up in a class-file name
 * (the test body's lambda), and under a POSIX locale — the default in cloud
 * containers — neither kotlinc nor Gradle's file hashing can touch that file, so
 * the whole module's test compile fails. Fails here first, with the culprit named.
 */
class AsciiTestNamesTest {

    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `test method names are ASCII`() {
        val name = Regex("fun `([^`\n]*)`")
        val offenders = listOf("core", "simulator", "node", "app")
            .map { File(root, "$it/src/test") }
            .filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { f ->
                name.findAll(f.readText()).map { it.groupValues[1] }
                    .filter { n -> n.any { it.code > 127 } }
                    .map { "${f.relativeTo(root).invariantSeparatorsPath}: $it" }
                    .toList()
            }
        assertTrue("non-ASCII test names (use '-' not an em dash):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
