package com.rumor.mesh.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * O144/O156/O157 — the meta-guard that stops the signature-splice bug class from
 * recurring a fourth time.
 *
 * The bug: a signed transcript joins adjacent variable-length fields with a bare
 * delimiter (`|`/`,`/`:`) that the field content can itself contain, making the
 * byte string re-partitionable under one valid signature. The structural fix is
 * length-prefixed framing (see [framed] / [SignableFraming]).
 *
 * This test scans `:core` source for every `*SignableBytes` / `*ChallengeBytes`
 * transcript builder and FAILS if one introduces a bare-delimiter join without
 * being on [SPLICE_SAFE] — the allowlist of transcripts whose every field is a
 * fixed-shape token (hex / base64 / decimal / enum) that provably cannot contain
 * a delimiter, or which is self-signed with no cross-identity splice benefit.
 * A new transcript that carries free text must route through [framed]; if it is
 * genuinely delimiter-safe, add it to [SPLICE_SAFE] WITH a justification.
 */
class SignableFramingGuardTest {

    /** Transcript builders reviewed 2026-07-31 (O156) and asserted splice-safe. */
    private val SPLICE_SAFE = setOf(
        // every field is a 64-hex userId, decimal version, or `,`-joined ids that
        // are themselves hex/bridge-ids (no `|`/`,`) — no free text.
        "blocklistSignableBytes",
        "blocklistDiffSignableBytes",
        // publisherId(hex) + base64 keys + decimal window; base64 has no `|`.
        "prekeyPublishSignableBytes",
        // routing tag + base64 ciphertext/keys + hex recipient ids; no free text.
        "multiRecipientEnvelopeSignableBytes",
        // roomId/userId(hex) + base64 room key + decimal; no free text.
        "roomInviteSignableBytes",
        // self-signed by the peer proving its own key — repartitioning grants no
        // cross-identity forgery; fields are base64 nonce / ints / feature+id lists.
        "helloChallengeBytes",
        "helloChallengeBytesV2",
    )

    private val bareDelimiterAppend = Regex("""append\(\s*(['"])[|,:]\1\s*\)""")
    // A string literal with a delimiter sitting directly between two interpolations,
    // e.g. "...$a|$b..." — the messageDelete v1 shape.
    private val interpolatedDelimiter = Regex("""\}?[|,:]\s*\$[\w{]""")
    private val funHeader = Regex("""fun\s+(\w+)\s*\(""")

    @Test
    fun `no signed transcript joins fields with a bare delimiter`() {
        val root = findRepoRoot()
        val srcDir = File(root, "core/src/main/kotlin")
        check(srcDir.isDirectory) { "core source not found at $srcDir" }

        val violations = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            transcriptFunctions(text).forEach eachFun@{ (name, body) ->
                seen += name
                if (name in SPLICE_SAFE) return@eachFun
                val stripped = body.lineSequence()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .joinToString("\n")
                if (bareDelimiterAppend.containsMatchIn(stripped) ||
                    interpolatedDelimiter.containsMatchIn(stripped)
                ) {
                    violations += "$name (${file.name})"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                """
                |Signed transcript(s) join fields with a BARE DELIMITER — the O144/O156
                |signature-splice class. Route every variable-length field through
                |`framed()` / `framedList()` (com.rumor.mesh.core.protocol.SignableFraming),
                |or, if every field is provably a fixed-shape token, add the function to
                |SignableFramingGuardTest.SPLICE_SAFE with a written justification.
                |
                |Offenders: ${violations.joinToString(", ")}
                """.trimMargin(),
            )
        }

        // Guard the guard: if these disappear, the scan silently stopped working.
        check(seen.containsAll(SPLICE_SAFE)) {
            "SPLICE_SAFE names not found in source (renamed/removed?): ${SPLICE_SAFE - seen}"
        }
        check(seen.size >= 12) { "Expected to scan >=12 transcript builders, saw ${seen.size}: $seen" }
    }

    /** Extract (name, body) for every fun whose name ends with the transcript suffixes. */
    private fun transcriptFunctions(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val matches = funHeader.findAll(text).toList()
        matches.forEachIndexed { i, m ->
            val name = m.groupValues[1]
            if (!name.endsWith("SignableBytes") && !name.contains("ChallengeBytes")) return@forEachIndexed
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            out += name to text.substring(start, end)
        }
        return out
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File(".").canonicalFile}")
    }
}
