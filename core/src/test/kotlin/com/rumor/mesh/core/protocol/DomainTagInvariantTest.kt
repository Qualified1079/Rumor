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
 *
 * **Coverage:** every domain tag string actually emitted by `:core`
 * commonMain code as a signature scope, HMAC prefix, HKDF info, or
 * AEAD AAD. If you grep for `"rumor-` under commonMain you should
 * find a matching test below; if you add a new tag, also add the
 * matching test — the additive cost is one method and the failure
 * cost of an unguarded tag is silently breaking the wire format
 * for every existing deployment.
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
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-route-v1:",
            purpose = "OPEN room routing tag SHA-256 prefix",
        )
    }

    @Test
    fun `O79 ENCRYPTED room per-message tag prefix is rumor-room-msg-tag-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-msg-tag-v1:",
            purpose = "ENCRYPTED room per-message routing tag HMAC prefix",
        )
    }

    @Test
    fun `O79 routing-key HKDF info is rumor-room-routing-key-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/RoomRoutingTag.kt",
            tag = "rumor-room-routing-key-v1",
            purpose = "HKDF info string for per-room routing key derivation",
        )
    }

    @Test
    fun `O79 envelope signable-bytes domain tag is rumor-room-envelope-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/MultiRecipientEnvelope.kt",
            tag = "rumor-room-envelope-v1:",
            purpose = "MultiRecipientEnvelope outer Ed25519 signature scope",
        )
    }

    @Test
    fun `O79 wrap-key HKDF info prefix is rumor-room-wrap-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/MultiRecipientEnvelopeCodec.kt",
            tag = "rumor-room-wrap-v1:",
            purpose = "Per-recipient wrap-key HKDF info prefix",
        )
    }

    @Test
    fun `O53 DM recipient tag prefix is rumor-dm-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/SealedSenderTag.kt",
            tag = "rumor-dm-v1:",
            purpose = "Sealed-sender DM recipient tag HMAC domain",
        )
    }

    @Test
    fun `O40 message-delete signable-bytes domain tag is rumor-message-delete-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/MessageDelete.kt",
            tag = "rumor-message-delete-v1:",
            purpose = "MESSAGE_DELETE signed-bytes scope",
        )
    }

    @Test
    fun `O67 keyword-filter signable-bytes domain tag is rumor-keyword-filter-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/KeywordFilter.kt",
            tag = "rumor-keyword-filter-v1:",
            purpose = "KeywordFilterList signed-bytes scope",
        )
    }

    // ── Foundational tags (in source since v0.1) ─────────────────────────────
    //
    // These are the load-bearing wire-format tags that have been in source
    // since the protocol's pre-release stage. They aren't in the reserved-
    // forward table of `docs/RENAMED_FIELDS_NEVER_REUSE.md` because the
    // policy treats anything that's been load-bearing since v0.1 as reserved
    // by source convention rather than by explicit table entry. Pinning them
    // here means a careless rename surfaces as a test failure rather than a
    // silent break of every existing signature.

    @Test
    fun `RumorMessage signable-bytes domain tag is rumor-msg-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/MessageStore.kt",
            tag = "rumor-msg-v1:",
            purpose = "Outer Ed25519 signature scope on every RumorMessage. " +
                "Bumping breaks every existing signature across the network.",
        )
    }

    @Test
    fun `HELLO v1 challenge-bytes domain tag is rumor-hello-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/GossipPacket.kt",
            tag = "rumor-hello-v1:",
            purpose = "HELLO v1 challenge-response signature scope. Binds " +
                "nonce + protocol-version-negotiation to the proof (TLS 1.3 " +
                "downgrade-MITM lesson).",
        )
    }

    @Test
    fun `O31 HELLO v2 challenge-bytes domain tag is rumor-hello-v2`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/GossipPacket.kt",
            tag = "rumor-hello-v2:",
            purpose = "HELLO v2 challenge-response signature scope. Adds " +
                "recentlyExchangedWith to the signed transcript (route-adv-v1).",
        )
    }

    // ── Blocklist signature scopes ────────────────────────────────────────────

    @Test
    fun `Blocklist signable-bytes domain tag is rumor-blocklist-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Blocklist.kt",
            tag = "rumor-blocklist-v1:",
            purpose = "BLOCKLIST_PUBLISH full-snapshot signature scope.",
        )
    }

    @Test
    fun `BlocklistDiff signable-bytes domain tag is rumor-blocklist-diff-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Blocklist.kt",
            tag = "rumor-blocklist-diff-v1:",
            purpose = "BLOCKLIST_DIFF incremental-update signature scope. " +
                "Separate domain from the full-snapshot tag so a snapshot " +
                "sig can't be lifted into a diff context (or vice versa).",
        )
    }

    // ── G13 BRIDGE_VOUCHED outer signature ────────────────────────────────────

    @Test
    fun `G13 bridge-vouched signable-bytes domain tag is rumor-bridge-vouched-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/BridgeVouched.kt",
            tag = "rumor-bridge-vouched-v1:",
            purpose = "Outer Rumor signature scope on BRIDGE_VOUCHED messages " +
                "(the bridge's signature certifying foreign-network delivery).",
        )
    }

    // ── O38 receiver-side forward secrecy ────────────────────────────────────

    @Test
    fun `O38 prekey-publish signable-bytes domain tag is rumor-prekey-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Prekey.kt",
            tag = "rumor-prekey-v1:",
            purpose = "PREKEY_PUBLISH signature scope. Binds publisher + key " +
                "+ validity window so a relay can't extend the window or " +
                "substitute the prekey without breaking the sig.",
        )
    }

    // ── O79 Room signed events (create / invite / action) ─────────────────────

    @Test
    fun `O79 RoomCreate signable-bytes domain tag is rumor-room-create-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Room.kt",
            tag = "rumor-room-create-v1:",
            purpose = "RoomCreate signature scope. Separate domain from " +
                "RoomInvite / RoomAction so a sig from one struct can't be " +
                "lifted to another.",
        )
    }

    @Test
    fun `O79 RoomInvite signable-bytes domain tag is rumor-room-invite-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Room.kt",
            tag = "rumor-room-invite-v1:",
            purpose = "RoomInvite signature scope. Moderator-signed envelope " +
                "delivering INVITE/CLOSED-room membership-seed token.",
        )
    }

    @Test
    fun `O79 RoomAction signable-bytes domain tag is rumor-room-action-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/Room.kt",
            tag = "rumor-room-action-v1:",
            purpose = "RoomAction signature scope. Moderator-signed actions " +
                "(REMOVE_MESSAGE, KICK_USER, BAN_USER, UNBAN_USER).",
        )
    }

    @Test
    fun `O53 sealed-sender key HKDF info prefix is rumor-dm-recipient-tag-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/protocol/SealedSenderKey.kt",
            tag = "rumor-dm-recipient-tag-v1:",
            purpose = "HKDF info prefix deriving the per-contact sealed-sender " +
                "tag key from the static-static X25519 agreement. Drift means " +
                "sender and recipient derive different tag keys and every " +
                "delivery-tag match silently fails.",
        )
    }

    @Test
    fun `O89 room posting-cert signable-bytes domain tag is rumor-room-posting-cert-v1`() {
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/model/RoomPostingCert.kt",
            tag = "rumor-room-posting-cert-v1:",
            purpose = "Posting-certificate signature scope (mod-signed write " +
                "permission). Separate domain from the other Room structs so " +
                "a cert sig can't be lifted onto an action or invite.",
        )
    }

    // ── O42 RBSR set reconciliation ───────────────────────────────────────────

    @Test
    fun `O42 RBSR v1 fingerprint domain tag is rumor-rbsr-v1`() {
        // NB: RBSR v2 (NIP-77 / hoytech-compatible) deliberately does NOT use
        // any domain tag — that's part of the byte-compat requirement with
        // strfry and any external NIP-77 implementation. The v1 (Rumor-original)
        // formula is the only one that needs a drift guard here.
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/sync/Rbsr.kt",
            tag = "rumor-rbsr-v1:",
            purpose = "RBSR v1 (XOR-reduce-of-SHA-256) per-item domain prefix. " +
                "Bumping silently desynchronises every two peers running " +
                "different versions — they'd compute different fingerprints " +
                "on the same set and never converge.",
        )
    }

    // ── O76 compression AAD ───────────────────────────────────────────────────

    @Test
    fun `O76 compression AAD domain tag is rumor-o76`() {
        // NB: this tag is byte-bound into the AES-GCM tag as Associated Data;
        // not strictly a signature scope, but it's part of the wire-format
        // contract and a drift would break decryption between any two clients
        // computing the AAD differently.
        assertContainsTag(
            file = "core/src/main/kotlin/com/rumor/mesh/core/wire/CompressedPaddedExt.kt",
            tag = "rumor-o76:",
            purpose = "Canonical AAD prefix bound into the AEAD tag, binding " +
                "the `_ext.cl` originalLength field so a relay can't tamper " +
                "with the decompression target size without breaking the tag.",
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
