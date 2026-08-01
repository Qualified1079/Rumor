package com.rumor.mesh.simulator.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * O193 (design-exploration, NOT a regression test of shipped code).
 *
 * Relay-ephemeral DM caching asks: if a relay/carrier deletes its cached copy
 * of a DM early — **on-relay** (forward-then-forget), **on-ack** (a delivery
 * ACK came back), **after-X** rounds, or combinations — how much delivery does
 * each trigger cost under realistic connect/disconnect/new-peer churn, and how
 * much storage does it save? The row makes this sim a *prerequisite* to writing
 * the eviction code, because "the tradeoff can only be answered empirically."
 *
 * The eviction triggers do not exist in the real GossipEngine yet, so per
 * SIMULATOR_TESTING §4 this is a **pure-logic model** — carry/evict is set
 * algebra over a meeting schedule (a faithful abstraction of the real gossip
 * serve: a holder offers the DM to every peer it meets; eviction just removes
 * the item from the store early). Pure logic lets us run many seeds cheaply for
 * statistically stable delivery-rate curves. Once a trigger looks viable, the
 * follow-up code work gets validated through the real path on the `:node`.
 *
 * Model (see printed table for the numbers):
 *   - node 0 = sender S, node 1 = recipient R, nodes 2..N-1 = carriers.
 *   - S originates one DM->R at t=0 and keeps retrying (persistent outbox copy);
 *     eviction applies only to *carriers'* cached copies — "relay-ephemeral".
 *   - each round a churn process picks in-range pairs; a meeting syncs stores.
 *   - R receiving the DM = delivered; an ACK then spreads back (lossily) for the
 *     on-ack trigger. R is offline for stretches in the intermittent/delayed
 *     environments (the O55 "meet you today, your recipient next week" case).
 *
 * Outputs per policy, per environment: delivery rate, mean latency, and
 * storage-rounds (total carrier node-rounds a copy is held — the cost eviction
 * buys down).
 */
class RelayEvictionModelTest {

    private companion object {
        const val DEFAULT_N = 32         // small/accurate scale for the main table (30 carriers + S + R)
        const val S = 0
        const val R = 1
        const val HORIZON = 120          // meeting rounds per run
        const val SEEDS = 160            // trials averaged per (env, policy)
        const val ACK_LOSS = 0.10        // per-transfer ACK drop (on-ack isn't free)
    }

    /**
     * [serveK]: forget after serving this many distinct copies (spray-and-wait).
     *   1 = on-relay / forward-then-forget; null = never evict on this basis.
     * [onAck]: forget once a delivery ACK for this message has reached the node.
     * [afterX]: forget X rounds after acquiring the copy (TTL backstop).
     */
    private data class Policy(
        val name: String,
        val serveK: Int? = null,
        val onAck: Boolean = false,
        val afterX: Int? = null,
    )

    private val policies = listOf(
        Policy("baseline"),                                        // keep-forever = delivery ceiling
        Policy("on-relay", serveK = 1),                            // forward-then-forget (the trap / control)
        Policy("on-ack", onAck = true),                            // delete once ACK seen
        Policy("ack+ttl24", onAck = true, afterX = 24),            // fast ACK + TTL backstop
        Policy("spray4+ack", serveK = 4, onAck = true),            // bounded replication + ACK cleanup
        Policy("spray4+ack+ttl24", serveK = 4, onAck = true, afterX = 24), // HEADLINE: all three triggers
    )

    /**
     * How the delivery ACK propagates back to evict cached copies:
     *  - FLOOD: gossips through the whole mesh (optimistic; also leaks delivery
     *    to every node — not the real design).
     *  - CARRIER: rides any node that ever held the DM.
     *  - BREADCRUMB: the DEPLOYED model — walks the reverse of the *delivery
     *    path* via each hop's `BreadcrumbCache` crumb toward S. Only nodes on
     *    the winning path get it; off-path carriers rely on [afterX]. This is
     *    the honest model and the one the main table uses.
     */
    private enum class AckModel { FLOOD, CARRIER, BREADCRUMB }

    // ── Mobility / topology models ────────────────────────────────────────────
    // A model yields the in-range pairs for a given round. Built fresh per run.

    private fun interface MeetingModel {
        fun meetings(round: Int, rng: Random): List<Pair<Int, Int>>
    }

    /**
     * Erdős–Rényi mixing at a target average degree [degree] (scale-invariant:
     * pair-prob = degree/(n-1), so meetings-per-round grows ~linearly in n, not
     * quadratically — keeps a "sparse mesh" sparse as we scale n up).
     */
    private fun randomMix(n: Int, degree: Double) = MeetingModel { _, rng ->
        val p = (degree / (n - 1)).coerceIn(0.0, 1.0)
        val out = ArrayList<Pair<Int, Int>>()
        for (a in 0 until n) for (b in a + 1 until n) if (rng.nextDouble() < p) out.add(a to b)
        out
    }

    /** Sparse ferry: [meetingsPerNodeRatio]·n uniformly-random pairs meet each round. */
    private fun sparseFerry(n: Int, meetingsPerNodeRatio: Double) = MeetingModel { _, rng ->
        val m = (meetingsPerNodeRatio * n).toInt().coerceAtLeast(1)
        (0 until m).map {
            var a = rng.nextInt(n); var b = rng.nextInt(n)
            while (b == a) b = rng.nextInt(n)
            if (a < b) a to b else b to a
        }
    }

    /**
     * Two clusters: A holds S, B holds R. Dense meetings inside a cluster,
     * rare cross-cluster "ferry" contacts — delivery *requires* a carrier to
     * physically carry the DM across, the store-and-forward case. Both prob
     * arguments are target degrees, scaled by cluster size.
     */
    private fun twoCluster(n: Int, inDegree: Double, crossDegree: Double): MeetingModel {
        val half = n / 2
        val clusterA = (0 until half).toSet()   // contains S (0)
        val inP = (inDegree / (half - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val crossP = (crossDegree / (n - half)).coerceIn(0.0, 1.0)
        return MeetingModel { _, rng ->
            val out = ArrayList<Pair<Int, Int>>()
            for (a in 0 until n) for (b in a + 1 until n) {
                val sameCluster = (a in clusterA) == (b in clusterA)
                if (rng.nextDouble() < if (sameCluster) inP else crossP) out.add(a to b)
            }
            out
        }
    }

    // ── Node presence over time ───────────────────────────────────────────────
    // Both S and R have a presence schedule. The load-bearing case (O55) is a
    // sender that composes then goes offline (EARLY_ONLY): carriers are then the
    // ONLY delivery path, which is exactly where forward-then-forget can hurt.

    private enum class Presence { ALWAYS, INTERMITTENT, DELAYED, EARLY_ONLY }

    private fun presenceSchedule(kind: Presence, seed: Long, salt: Long): BooleanArray {
        val rng = Random(seed xor salt)
        return BooleanArray(HORIZON) { round ->
            when (kind) {
                Presence.ALWAYS -> true
                Presence.INTERMITTENT -> rng.nextDouble() < 0.30          // briefly in range ~30% of rounds
                Presence.DELAYED -> round >= HORIZON / 2                  // absent the first half, then present
                Presence.EARLY_ONLY -> round < 15                        // online only long enough to hand off, then gone
            }
        }
    }

    // ── One trial ─────────────────────────────────────────────────────────────

    private data class RunResult(val deliveredRound: Int, val storageRounds: Long, val everHeldCarriers: Int)

    private fun runOnce(n: Int, model: MeetingModel, sAvail: BooleanArray, rAvail: BooleanArray, policy: Policy, ackModel: AckModel, seed: Long): RunResult {
        val rng = Random(seed)
        val carriers = 2 until n
        val holds = BooleanArray(n)          // node currently caches the DM
        val acq = IntArray(n) { -1 }         // round the copy was acquired
        val served = IntArray(n)             // distinct copies this node has handed off
        val everHeld = BooleanArray(n)       // node held the DM at some point (ACK return population)
        val parent = IntArray(n) { -1 }      // breadcrumb: who delivered the DM to me (reverse-path hop)
        val ack = BooleanArray(n)            // node has seen the delivery ACK
        holds[S] = true; acq[S] = 0; everHeld[S] = true
        var deliveredRound = -1
        var storageRounds = 0L

        for (t in 0 until HORIZON) {
            // Eviction sweeps on carriers' cached copies (never S's outbox, never R).
            for (c in carriers) {
                if (!holds[c]) continue
                if (policy.afterX != null && t - acq[c] >= policy.afterX) holds[c] = false
                else if (policy.onAck && ack[c]) holds[c] = false
            }

            // A node out of range this round makes no contacts.
            var pairs = model.meetings(t, rng)
            if (!sAvail[t]) pairs = pairs.filter { it.first != S && it.second != S }
            if (!rAvail[t]) pairs = pairs.filter { it.first != R && it.second != R }

            for ((a, b) in pairs) {
                // DM transfer: flows from a holder to a non-holder.
                if (holds[a] != holds[b]) {
                    val from = if (holds[a]) a else b
                    val to = if (holds[a]) b else a
                    holds[to] = true; acq[to] = t; everHeld[to] = true; parent[to] = from
                    served[from]++
                    if (to == R) {
                        if (deliveredRound < 0) deliveredRound = t
                        ack[R] = true
                    }
                    // spray-and-wait: a carrier drops its copy after serving k copies.
                    if (policy.serveK != null && from != S && from != R && served[from] >= policy.serveK) {
                        holds[from] = false
                    }
                }
                // ACK back-propagation (only tracked when the trigger is armed).
                if (policy.onAck && ack[a] != ack[b]) {
                    val holder = if (ack[a]) a else b       // node that has the ACK
                    val other = if (ack[a]) b else a        // node that might learn it
                    val pass = when (ackModel) {
                        AckModel.FLOOD -> true
                        AckModel.CARRIER -> everHeld[other]
                        AckModel.BREADCRUMB -> parent[holder] == other  // hand the ACK to my delivery-parent
                    }
                    if (pass && rng.nextDouble() >= ACK_LOSS) ack[other] = true
                }
            }

            storageRounds += carriers.count { holds[it] }.toLong()
        }
        return RunResult(deliveredRound, storageRounds, carriers.count { everHeld[it] })
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    private data class Metrics(val deliveryRate: Double, val meanLatency: Double, val meanStorage: Double)

    private fun measure(env: Env, policy: Policy, ackModel: AckModel = AckModel.CARRIER, n: Int = DEFAULT_N): Metrics {
        var delivered = 0; var latencySum = 0L; var storageSum = 0L
        for (s in 0 until SEEDS) {
            val seed = s.toLong() * 1_000_003L + 17
            val sAvail = presenceSchedule(env.sKind, seed, 0x51_5eed)
            val rAvail = presenceSchedule(env.rKind, seed, 0x52_5eed)
            val r = runOnce(n, env.factory(n), sAvail, rAvail, policy, ackModel, seed)
            if (r.deliveredRound >= 0) { delivered++; latencySum += r.deliveredRound }
            storageSum += r.storageRounds
        }
        val carriers = (n - 2).coerceAtLeast(1)
        return Metrics(
            deliveryRate = delivered.toDouble() / SEEDS,
            meanLatency = if (delivered > 0) latencySum.toDouble() / delivered else Double.NaN,
            // normalized so scales are comparable: fraction of carrier·rounds a copy occupies.
            meanStorage = storageSum.toDouble() / SEEDS / (carriers.toDouble() * HORIZON),
        )
    }

    /** Random online schedule at a target [fraction] of rounds (sender-reconnect model). */
    private fun fractionalPresence(fraction: Double, seed: Long, salt: Long): BooleanArray {
        val rng = Random(seed xor salt)
        return BooleanArray(HORIZON) { rng.nextDouble() < fraction }
    }

    private data class Decomp(val delivery: Double, val neverLeftS: Double, val stranded: Double)

    /** Like [measure] but classifies WHY undelivered runs failed (accuracy of the ceiling). */
    private fun decompose(
        env: Env, policy: Policy, ackModel: AckModel, n: Int = DEFAULT_N,
        sSchedOverride: ((Long) -> BooleanArray)? = null,
    ): Decomp {
        var delivered = 0; var neverLeft = 0; var stranded = 0
        for (s in 0 until SEEDS) {
            val seed = s.toLong() * 1_000_003L + 17
            val sAvail = sSchedOverride?.invoke(seed) ?: presenceSchedule(env.sKind, seed, 0x51_5eed)
            val rAvail = presenceSchedule(env.rKind, seed, 0x52_5eed)
            val r = runOnce(n, env.factory(n), sAvail, rAvail, policy, ackModel, seed)
            when {
                r.deliveredRound >= 0 -> delivered++
                r.everHeldCarriers == 0 -> neverLeft++      // DM never escaped the sender
                else -> stranded++                          // reached the mesh but never met R
            }
        }
        return Decomp(delivered.toDouble() / SEEDS, neverLeft.toDouble() / SEEDS, stranded.toDouble() / SEEDS)
    }

    private data class Env(
        val label: String,
        val sKind: Presence,
        val rKind: Presence,
        val factory: (Int) -> MeetingModel,
    )

    // Degrees chosen so behaviour at n=32 matches the original p-based topology
    // (degree = p·(n−1)); expressed as degree/ratio so they stay meaningful as n scales.
    private val environments = listOf(
        Env("mix-dense  S+ R+",      Presence.ALWAYS,     Presence.ALWAYS)       { n -> randomMix(n, 3.1) },
        Env("mix-sparse S+ R+",      Presence.ALWAYS,     Presence.ALWAYS)       { n -> randomMix(n, 0.62) },
        Env("mix-sparse S+ R~",      Presence.ALWAYS,     Presence.INTERMITTENT) { n -> randomMix(n, 0.62) },
        Env("ferry(4)   S+ R+",      Presence.ALWAYS,     Presence.ALWAYS)       { n -> sparseFerry(n, 0.125) },
        Env("ferry(4)   S:early R~", Presence.EARLY_ONLY, Presence.INTERMITTENT) { n -> sparseFerry(n, 0.125) },
        Env("2cluster   S+ R:late",  Presence.ALWAYS,     Presence.DELAYED)      { n -> twoCluster(n, 1.5, 0.32) },
        Env("2cluster   S:early R:late", Presence.EARLY_ONLY, Presence.DELAYED)  { n -> twoCluster(n, 1.5, 0.32) },
    )

    // ── The sweep ─────────────────────────────────────────────────────────────

    @Test
    fun sweepDeliveryVsStorageAcrossPolicies() {
        val results = HashMap<Pair<String, String>, Metrics>()
        val sb = StringBuilder()
        sb.append("\nO193 relay-ephemeral DM caching — delivery vs. storage trade-off\n")
        sb.append("N=$DEFAULT_N (${DEFAULT_N - 2} carriers), horizon=$HORIZON rounds, $SEEDS seeds/cell, ack-loss=$ACK_LOSS\n")
        sb.append("cell = delivery% | latency(rounds) | storage(% of carrier·rounds a copy occupies — scale-comparable)\n\n")

        val colW = 26
        sb.append("environment".padEnd(22))
        for (p in policies) sb.append(p.name.padEnd(colW))
        sb.append('\n')

        for (env in environments) {
            sb.append(env.label.padEnd(22))
            for (p in policies) {
                val m = measure(env, p, AckModel.BREADCRUMB)
                results[env.label to p.name] = m
                val lat = if (m.meanLatency.isNaN()) "  -" else "%.0f".format(m.meanLatency)
                val cell = "%.0f%% | %s | %.1f%%".format(m.deliveryRate * 100, lat, m.meanStorage * 100)
                sb.append(cell.padEnd(colW))
            }
            sb.append('\n')
        }
        sb.append("\nlegend: S+/R+ always online · R~ intermittent · S:early sender offline after round 15 · R:late recipient offline first half\n")
        sb.append("(main table ACK model = BREADCRUMB: ACK walks the reverse delivery path via crumbs)\n")

        val stress = environments.first { it.label == "ferry(4)   S:early R~" }
        val sparse = environments.first { it.label == "mix-sparse S+ R~" }
        fun cell(m: Metrics) = "%.0f%% | %.1f%%".format(m.deliveryRate * 100, m.meanStorage * 100)

        // ── (A) Spray-and-wait k-sweep — the delivery/storage knee as k varies ──
        sb.append("\n(A) spray-and-wait k-sweep (cell = delivery% | storage), BREADCRUMB ack:\n")
        sb.append("env".padEnd(22))
        val ks = listOf(1, 2, 3, 4, 6, 8)
        for (k in ks) sb.append("k=$k".padEnd(14))
        sb.append("k=∞(base)\n")
        for (env in listOf(stress, sparse, environments.first { it.label == "mix-dense  S+ R+" })) {
            sb.append(env.label.padEnd(22))
            for (k in ks) sb.append(cell(measure(env, Policy("spray-$k", serveK = k), AckModel.BREADCRUMB)).padEnd(14))
            sb.append(cell(measure(env, Policy("baseline"), AckModel.BREADCRUMB))).append('\n')
        }

        // ── (B) on-ack × after-X synergy matrix — does adding a TTL to on-ack
        //    buy storage without costing delivery, and where is the knee? ──
        sb.append("\n(B) after-X alone vs on-ack+after-X (cell = delivery% | storage), stressor + sparse, BREADCRUMB ack:\n")
        val xs = listOf(8, 16, 24, 40)
        sb.append("env / variant".padEnd(22))
        sb.append("ack-only".padEnd(14))
        for (x in xs) sb.append("X=$x".padEnd(14))
        sb.append('\n')
        for (env in listOf(stress, sparse)) {
            sb.append((env.label + " ttl-only").padEnd(22))
            sb.append("—".padEnd(14))
            for (x in xs) sb.append(cell(measure(env, Policy("after-$x", afterX = x), AckModel.BREADCRUMB)).padEnd(14))
            sb.append('\n')
            sb.append((env.label + " ack+ttl").padEnd(22))
            sb.append(cell(measure(env, Policy("on-ack", onAck = true), AckModel.BREADCRUMB)).padEnd(14))
            for (x in xs) sb.append(cell(measure(env, Policy("ack+$x", onAck = true, afterX = x), AckModel.BREADCRUMB)).padEnd(14))
            sb.append('\n')
        }

        // ── (C) ACK-model sensitivity — how much weaker is the deployed
        //    BREADCRUMB ACK vs the optimistic FLOOD, and does after-X recover it? ──
        sb.append("\n(C) ACK-model sensitivity (env = stressor; cell = delivery% | storage):\n")
        sb.append("policy".padEnd(16)).append("FLOOD".padEnd(18)).append("CARRIER".padEnd(18)).append("BREADCRUMB\n")
        for (p in listOf(Policy("on-ack", onAck = true), Policy("ack+after24", onAck = true, afterX = 24))) {
            sb.append(p.name.padEnd(16))
            for (am in listOf(AckModel.FLOOD, AckModel.CARRIER, AckModel.BREADCRUMB)) {
                sb.append(cell(measure(stress, p, am)).padEnd(18))
            }
            sb.append('\n')
        }

        // ── (D) SCALE sweep — does the headline conclusion survive as N grows?
        //    Storage is normalized (% of carrier·rounds), so scales compare directly.
        //    Small N = fewer nodes but same accurate breadcrumb model; large N is the
        //    "less-granular but bigger picture" end. Together they bracket the middle. ──
        sb.append("\n(D) scale sweep — headline policies across N (cell = delivery% | storage%), BREADCRUMB ack:\n")
        val scales = listOf(32, 96, 256)
        val scalePolicies = listOf(
            Policy("baseline"),
            Policy("on-relay", serveK = 1),
            Policy("spray4+ack+ttl24", serveK = 4, onAck = true, afterX = 24),
        )
        for (env in listOf(stress, sparse)) {
            sb.append("  ${env.label}\n")
            sb.append("    policy".padEnd(24))
            for (nn in scales) sb.append("N=$nn".padEnd(20))
            sb.append('\n')
            for (p in scalePolicies) {
                sb.append("    ${p.name}".padEnd(24))
                for (nn in scales) sb.append(cell(measure(env, p, AckModel.BREADCRUMB, nn)).padEnd(20))
                sb.append('\n')
            }
        }

        println(sb.toString())
        // Gradle swallows test stdout by default — persist the table so the numbers survive.
        runCatching {
            val out = java.io.File("build/o193-report.txt")
            out.parentFile?.mkdirs()
            out.writeText(sb.toString())
        }

        // ── Negative controls / teeth (SIMULATOR_TESTING §3) ──────────────────
        // The prints are the deliverable; these guard against a vacuous harness.

        // 1. Baseline (keep-forever) is the delivery ceiling: no eviction policy
        //    delivers MORE than baseline in any environment (small slack for RNG).
        for (env in environments) {
            val base = results[env.label to "baseline"]!!.deliveryRate
            for (p in policies.filter { it.name != "baseline" }) {
                val d = results[env.label to p.name]!!.deliveryRate
                assertTrue(
                    "${p.name} delivered MORE than baseline in ${env.label} ($d > $base) — impossible if the model is sound",
                    d <= base + 0.03,
                )
            }
        }

        // 2. Not-vacuous: baseline actually delivers in a well-connected env
        //    (the harness measures real delivery, not a constant zero/blackhole).
        val denseBase = results["mix-dense  S+ R+" to "baseline"]!!.deliveryRate
        assertTrue("dense baseline should deliver ~always, got $denseBase", denseBase > 0.95)

        // 3. The harness can SEE forward-then-forget's cost: on-relay delivers
        //    materially LESS than baseline in at least one sparse environment.
        val sawRelayCost = environments.any { env ->
            val base = results[env.label to "baseline"]!!.deliveryRate
            val relay = results[env.label to "on-relay"]!!.deliveryRate
            base - relay > 0.10
        }
        assertTrue("on-relay never showed a delivery cost — harness can't see the tradeoff it exists to measure", sawRelayCost)

        // 4. Eviction actually buys down storage: on-relay holds strictly less
        //    than baseline in the dense env (its whole point).
        val denseBaseStore = results["mix-dense  S+ R+" to "baseline"]!!.meanStorage
        val denseRelayStore = results["mix-dense  S+ R+" to "on-relay"]!!.meanStorage
        assertTrue("on-relay ($denseRelayStore) should hold less than baseline ($denseBaseStore)", denseRelayStore < denseBaseStore)

        // 5. on-ack preserves delivery better than forward-then-forget under
        //    stress: in the sparse/intermittent env, on-ack >= on-relay delivery.
        val ackD = results["mix-sparse S+ R~" to "on-ack"]!!.deliveryRate
        val relayD = results["mix-sparse S+ R~" to "on-relay"]!!.deliveryRate
        assertTrue("on-ack ($ackD) should not deliver worse than on-relay ($relayD) under stress", ackD >= relayD - 0.03)

        // 6. Sanity has teeth: claiming on-relay is costless everywhere MUST fail.
        var falseClaimFailed = false
        try {
            for (env in environments) {
                val base = results[env.label to "baseline"]!!.deliveryRate
                val relay = results[env.label to "on-relay"]!!.deliveryRate
                assertTrue(relay >= base - 0.001)   // deliberately false in sparse envs
            }
        } catch (e: AssertionError) {
            falseClaimFailed = true
        }
        assertTrue("the 'on-relay is costless' claim should have failed but didn't — assertions lack teeth", falseClaimFailed)
    }

    /**
     * Accuracy pass for the suspiciously-clean ~95%: decompose the undelivered
     * runs, and measure how much sender-reconnection ("retry until ACK") lifts
     * delivery — i.e. whether the ceiling is sender-side (retry fixes it) or
     * recipient-availability-bound (it doesn't).
     */
    @Test
    fun stressorAccuracyAndSenderRetry() {
        val sb = StringBuilder()
        val stress = environments.first { it.label == "ferry(4)   S:early R~" }
        val base = Policy("baseline")

        // (E1) What IS the ~95%? Split the ~5% failures by cause (exact, 0.1%).
        sb.append("\n(E1) stressor baseline delivery decomposition ($SEEDS seeds, BREADCRUMB ack, exact):\n")
        val d = decompose(stress, base, AckModel.BREADCRUMB)
        sb.append("  delivered     = %.1f%%\n".format(d.delivery * 100))
        sb.append("  never-left-S  = %.1f%%  (DM never escaped the sender before it went offline @ round 15)\n".format(d.neverLeftS * 100))
        sb.append("  stranded      = %.1f%%  (reached carriers, but no holder met R while R was online)\n".format(d.stranded * 100))

        // (E2) Sender-retry-on-reconnect: a sender that keeps re-offering whenever
        // it is online is exactly "retry until ACK". Sweep sender online-fraction.
        sb.append("\n(E2) sender presence -> delivery (ferry topology, R~ intermittent, baseline):\n")
        sb.append("  sender online-time     delivery%   (remaining failures)\n")
        val early: (Long) -> BooleanArray = { seed -> presenceSchedule(Presence.EARLY_ONLY, seed, 0x51_5eed) }
        val de = decompose(stress, base, AckModel.BREADCRUMB, sSchedOverride = early)
        sb.append("  early15 then gone      %.1f%%      (neverLeftS %.1f%% / stranded %.1f%%)\n"
            .format(de.delivery * 100, de.neverLeftS * 100, de.stranded * 100))
        for (f in listOf(0.10, 0.20, 0.30, 0.50, 1.0)) {
            val sched: (Long) -> BooleanArray = { seed -> fractionalPresence(f, seed, 0x51_5eed) }
            val df = decompose(stress, base, AckModel.BREADCRUMB, sSchedOverride = sched)
            sb.append("  intermittent %3.0f%%      %.1f%%      (neverLeftS %.1f%% / stranded %.1f%%)\n"
                .format(f * 100, df.delivery * 100, df.neverLeftS * 100, df.stranded * 100))
        }
        sb.append("\nReading: if delivery plateaus below 100%% even at 100%% sender-online, the residual\n")
        sb.append("is 'stranded' = recipient-availability-bound — retries can't fix it, only R coming online can.\n")
        println(sb.toString())
        runCatching { java.io.File("build/o193-retry.txt").writeText(sb.toString()) }

        // Teeth: more sender online-time must not REDUCE delivery (monotone lever).
        val d10 = decompose(stress, base, AckModel.BREADCRUMB) { seed -> fractionalPresence(0.10, seed, 0x51_5eed) }.delivery
        val d100 = decompose(stress, base, AckModel.BREADCRUMB) { seed -> fractionalPresence(1.0, seed, 0x51_5eed) }.delivery
        assertTrue("more sender uptime should not lower delivery ($d10 -> $d100)", d100 >= d10 - 0.01)
    }
}
