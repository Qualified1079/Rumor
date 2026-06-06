package com.rumor.mesh.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Source-level drift guards for the wire-format domain tags. Same
 * brittle-by-design pattern as `SourceInvariantTest` — a refactor
 * that silently changes a tag string would break byte-compatibility
 * with every existing message and every alternative implementation
 * built against `docs/O79_PROTOCOL_SPEC.md`. These tests fail at
 * build time before any such PR could land.
 *
 * Each tag's purpose is recorded in
 * `docs/RENAMED_FIELDS_NEVER_REUSE.md` and (where applicable)
 * `docs/O79_PROTOCOL_SPEC.md`. If a tag genuinely needs to change
 * (which should be never — bump the version suffix and reserve the
 * new tag instead), update this test along with the source AND
 * those docs AND every existing implementation in deployment.
 */
class DomainTagInvariantTest {

    private fun fileContent(relativePath: String): String {
        val repoRoot = findRepoRoot()
        val f = File(repoRoot, relativePath)
        check(f.isFile) { "Expected file not found: $f" }
        return f.readText()
    }

    private fun assertContainsTag(file: String, tag: String, purpose: String) {
        val text = fileContent(file)
        if (!text.contains(tag)) {
            fail(
                """
                |Domain tag '$tag' not found in $file.
                |Purpose: $purpose
                |
                |Tags are RESERVED FOREVER — see docs/RENAMED_FIELDS_NEVER_REUSE.md.
                |Renaming this tag would break byte-compatibility with every
                |existing message and every alternative implementation built
                |against docs/O79_PROTOCOL_SPEC.md.
                |
                |If you intentionally changed the tag, also update:
                |  - docs/RENAMED_FIELDS_NEVER_REUSE.md (reserve the new name,
                |    record the old as retired)
                |  - docs/O79_PROTOCOL_SPEC.md (byte-format spec)
                |  - every alternative implementation in deployment
                |  - this test's expected string
                |""".trimMargin()
            )
        }
    }

    @Test
    fun `O79 OPEN room routing tag prefix is rumor-room-route-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-route-v1:",
            purpose = "OPEN room routing tag SHA-256 prefix",
        )
    }

    @Test
    fun `O79 ENCRYPTED room per-message tag prefix is rumor-room-msg-tag-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-msg-tag-v1:",
            purpose = "ENCRYPTED room per-message routing tag HMAC prefix",
        )
    }

    @Test
    fun `O79 routing-key HKDF info is rumor-room-routing-key-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-routing-key-v1",
            purpose = "HKDF info string for per-room routing key derivation",
        )
    }

    @Test
    fun `O79 envelope signable-bytes domain tag is rumor-room-envelope-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/model/MultiRecipientEnvelope.kt",
            tag = "rumor-room-envelope-v1:",
            purpose = "MultiRecipientEnvelope outer Ed25519 signature scope",
        )
    }

    @Test
    fun `O79 wrap-key HKDF info prefix is rumor-room-wrap-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/MultiRecipientEnvelopeCodec.kt",
            tag = "rumor-room-wrap-v1:",
            purpose = "Per-recipient wrap-key HKDF info prefix",
        )
    }

    @Test
    fun `O53 DM recipient tag prefix is rumor-dm-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/protocol/SealedSenderTag.kt",
            tag = "rumor-dm-v1:",
            purpose = "Sealed-sender DM recipient tag HMAC domain",
        )
    }

    @Test
    fun `O40 message-delete signable-bytes domain tag is rumor-message-delete-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/model/MessageDelete.kt",
            tag = "rumor-message-delete-v1:",
            purpose = "MESSAGE_DELETE signed-bytes scope",
        )
    }

    @Test
    fun `O67 keyword-filter signable-bytes domain tag is rumor-keyword-filter-v1`() {
        assertContainsTag(
            file = "core/src/commonMain/kotlin/com/rumor/mesh/core/model/KeywordFilter.kt",
            tag = "rumor-keyword-filter-v1:",
            purpose = "KeywordFilterList signed-bytes scope",
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
