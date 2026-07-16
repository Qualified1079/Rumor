package com.rumor.mesh.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Source-level guards for the three architectural rules in CLAUDE.md
 * "Architecture at a glance" that, if quietly deleted, would silently
 * break Rumor's security posture.
 *
 * These tests grep the production source for the specific guard
 * patterns. They are intentionally brittle: a cosmetic refactor of
 * `GossipEngine` that moves the guard somewhere else or rewords it
 * will trip these tests. That's the point — the refactor is exactly
 * when a human should re-verify the invariant holds, then update the
 * pattern here. Linux kernel uses the same idiom (scripts/checkpatch.pl)
 * for memory-safety rules.
 *
 * Each rule has a short docstring explaining WHY it matters and what
 * happens if the guard is gone. If a future maintainer deletes a guard
 * with a "this was redundant" PR, the failure message has to be
 * informative enough to short-circuit the merge.
 */
class SourceInvariantTest {

    private val gossipEngine by lazy {
        File(findRepoRoot(), "core/src/main/kotlin/com/rumor/mesh/core/protocol/GossipEngine.kt")
            .also { check(it.isFile) { "GossipEngine.kt not at expected path: $it" } }
            .readText()
    }

    /**
     * **Architecture invariant 1: `TrustLevel.BRIDGED` messages are NEVER re-relayed.**
     *
     * Bridge-injected traffic carries the `BRIDGE_UNSIGNED` sentinel signature
     * and never had a real Ed25519 sig over its content. Re-relaying it would
     * launder unverifiable foreign-network content into the signed Rumor mesh
     * — every honest receiver would see the bridge node as the originator with
     * no way to detect the laundering. The relay path MUST short-circuit on
     * BRIDGED trust before doing any work.
     *
     * If this test fails: a refactor moved or removed the guard. Verify
     * `GossipEngine.relay()` still rejects BRIDGED trust before any scheduler
     * enqueue, then update the pattern here.
     */
    @Test
    fun `BRIDGED messages are not re-relayed`() {
        val pattern = Regex("""if\s*\(\s*msg\.trustLevel\s*==\s*TrustLevel\.BRIDGED\s*\)\s*return""")
        if (!pattern.containsMatchIn(gossipEngine)) {
            fail(
                """
                |Could not find the `if (msg.trustLevel == TrustLevel.BRIDGED) return` guard
                |in GossipEngine.kt. This guard prevents bridge-injected foreign-network
                |content from being re-relayed onto the signed Rumor mesh — without it,
                |a bridge node's identity gets attached to unverifiable content.
                |
                |If you refactored the guard (e.g. moved to a helper, restructured the
                |relay path), update the regex in SourceInvariantTest after confirming
                |the architectural property still holds.
                |""".trimMargin()
            )
        }
    }

    /**
     * **Architecture invariant 2: `BRIDGE_UNSIGNED` sentinel is honored ONLY for
     * `MessageSource.LOCAL_BRIDGE`.**
     *
     * The BRIDGE_UNSIGNED string is the per-process marker that a message came
     * from a local bridge plugin (not the network). A network peer could send
     * a message with `signature = "BRIDGE_UNSIGNED"` and — without this gate —
     * skip Ed25519 verification entirely. The compound check in
     * `processIncoming` is what makes that attack impossible.
     *
     * If this test fails: a refactor moved or removed the AND-clause that
     * binds BRIDGE_UNSIGNED to LOCAL_BRIDGE. Verify the per-transport trust
     * gate still works, then update the pattern.
     */
    @Test
    fun `BRIDGE_UNSIGNED is honored only from LOCAL_BRIDGE source`() {
        val pattern = Regex(
            """source\s*==\s*MessageSource\.LOCAL_BRIDGE\s*&&\s*\w+\.signature\s*==\s*BRIDGE_UNSIGNED"""
        )
        if (!pattern.containsMatchIn(gossipEngine)) {
            fail(
                """
                |Could not find the per-transport trust gate
                |  `source == MessageSource.LOCAL_BRIDGE && <msg>.signature == BRIDGE_UNSIGNED`
                |in GossipEngine.kt. Without this AND-clause, a network peer can send
                |`signature = "BRIDGE_UNSIGNED"` and bypass Ed25519 verification —
                |silent acceptance of unsigned messages from the wire.
                |
                |If you refactored the trust check, update the regex after verifying
                |the architectural property still holds.
                |""".trimMargin()
            )
        }
    }

    /**
     * **Architecture invariant 3: dedup gates BEFORE relay enqueue.**
     *
     * `if (!isNew) return` must short-circuit `processIncoming` before any
     * relay/enqueue work. Without it, the relay scheduler receives duplicate
     * copies of every already-seen message, which:
     *  - re-floods the mesh on every receive, multiplying bandwidth
     *  - breaks the TTL-extender-attack analysis (attacker can resurrect
     *    long-eviced ids by holding the cache turnover window)
     *  - undoes the gossip-exchange model (peers offer what they have, not
     *    what they've previously received)
     *
     * If this test fails: the relay/enqueue path got reordered. Verify
     * `processIncoming` still gates on `isNew` before any scheduler
     * interaction, then update the pattern.
     */
    @Test
    fun `dedup short-circuits processIncoming before relay`() {
        // The guard line: `if (!isNew) return`.
        val pattern = Regex("""if\s*\(\s*!isNew\s*\)\s*return""")
        val match = pattern.find(gossipEngine)
            ?: fail(
                """
                |Could not find the `if (!isNew) return` short-circuit in
                |GossipEngine.kt. This is the only thing preventing the relay
                |scheduler from receiving every already-seen message id on
                |every receive. Without it, bandwidth use grows with cache
                |turnover and the TTL-extender attack analysis is wrong.
                |""".trimMargin()
            )

        // Verify nothing else has already mutated relay state earlier in
        // processIncoming. The scheduler.enqueue / enqueueRelayed / relay
        // calls must all come AFTER this guard.
        //
        // We look for the position of the guard and any earlier relay call.
        val guardPos = match.range.first
        val processIncomingStart = gossipEngine.indexOf("fun processIncoming")
        check(processIncomingStart in 0 until guardPos) {
            "processIncoming declaration not before the guard — source structure changed."
        }

        val regionBeforeGuard = gossipEngine.substring(processIncomingStart, guardPos)
        val earlyRelay = Regex("""enqueueRelayed|scheduler\.enqueue|relay\s*\(""")
            .find(regionBeforeGuard)
        if (earlyRelay != null) {
            fail(
                """
                |Found a relay-enqueue call BEFORE the `if (!isNew) return` dedup
                |guard in processIncoming. Pattern matched: '${earlyRelay.value}'.
                |
                |The architectural rule is: dedup gates relay. Anything that
                |feeds the scheduler before checking isNew breaks the rule. Move
                |the dedup short-circuit earlier in the function, or refactor
                |the guard.
                |""".trimMargin()
            )
        }
    }

    /**
     * **Architecture invariant 4 (O39): per-message ephemeral key material is
     * actively zeroed after use in `composeDirect`.**
     *
     * Sender-side forward secrecy depends on the ephemeral X25519 private and
     * the derived AES key not surviving past the AEAD call. Kotlin doesn't zero
     * local `ByteArray`s; bytes sit on the heap until GC and remain readable in
     * a process-memory dump until then. The explicit `.fill(0)` makes the FS
     * window a property of the code, not luck. If a refactor removes these calls,
     * a memory snapshot taken seconds after a DM is sent can recover the
     * ephemeral private and re-derive the AEAD key.
     *
     * If this test fails: `composeDirect` lost the `ephemeral.privateKeyBytes
     * .fill(0)` or `sharedKey.fill(0)` line. Verify the zeroing still happens
     * AFTER the AEAD call (zeroing before makes encryption fail with zero key),
     * then update the regex.
     */
    @Test
    fun `composeDirect zeros ephemeral private and shared key after use`() {
        val privFillPattern = Regex("""ephemeral\.privateKeyBytes\.fill\(\s*0\s*\)""")
        val sharedFillPattern = Regex("""sharedKey\.fill\(\s*0\s*\)""")
        if (!privFillPattern.containsMatchIn(gossipEngine)) {
            fail(
                """
                |Could not find `ephemeral.privateKeyBytes.fill(0)` in GossipEngine.kt.
                |Without this, the ephemeral X25519 private key sits on the heap until
                |GC after every DM compose — a process-memory dump in that window
                |recovers it and lets the attacker re-derive every AEAD key for every
                |outbound DM in that batch. O39 audit (now G25) made the zeroing
                |explicit; do not remove it.
                |""".trimMargin()
            )
        }
        if (!sharedFillPattern.containsMatchIn(gossipEngine)) {
            fail(
                """
                |Could not find `sharedKey.fill(0)` in GossipEngine.kt. The derived
                |AEAD key must be zeroed after the AES-GCM call in composeDirect; see
                |O39 (G25). Without it the sender-side FS guarantee leaks until GC.
                |""".trimMargin()
            )
        }
    }

    /**
     * **Architecture invariant 5: O53 sealed-sender tagKey is zeroed after stamping.**
     *
     * `SealedSenderKey.derive` returns a 32-byte per-contact symmetric key derived
     * from the X25519 ECDH of long-term static identities. The same key is reused
     * for every DM to that contact for the process lifetime; leaving it on the
     * heap until GC turns the privacy property into "until the next memory dump."
     * `composeDirect` must call `tagKey.fill(0)` in a `finally` after computing
     * the tag so the key buffer is unrecoverable on the same per-message FS basis
     * as the ephemeral private (invariant 4).
     *
     * If this test fails: a refactor moved the tag stamp out of composeDirect or
     * dropped the finally block. Re-verify the key is zeroed on every code path
     * (including exception during HMAC), then update the pattern.
     */
    @Test
    fun `composeDirect zeros sealed-sender tagKey after use`() {
        val tagKeyFillPattern = Regex("""tagKey\.fill\(\s*0\s*\)""")
        if (!tagKeyFillPattern.containsMatchIn(gossipEngine)) {
            fail(
                """
                |Could not find `tagKey.fill(0)` in GossipEngine.kt. The per-contact
                |sealed-sender tag key (O53) is derived from the X25519 ECDH of two
                |long-term Ed25519 identities and would otherwise outlive every
                |composeDirect call on the heap. The finally block in composeDirect
                |after `SealedSenderKey.derive` MUST zero it before return; without
                |this guard a memory dump recovers the per-contact tag key and the
                |observer can match every future tag for that contact pair.
                |""".trimMargin()
            )
        }
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
