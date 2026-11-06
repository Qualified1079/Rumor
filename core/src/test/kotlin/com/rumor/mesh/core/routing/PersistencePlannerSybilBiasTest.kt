package com.rumor.mesh.core.routing

import com.rumor.mesh.core.model.UserMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O173 — **measurement, not a fix.** Quantifies how far a sybil can bias
 * [PersistencePlanner]'s backbone selection by *declaring* `UserMode.FREE`, and
 * pins the current (vulnerable) numbers so any future mitigation has a baseline
 * to beat. The policy question — what to do about it — is deliberately left open;
 * see the structural obstacle recorded at the bottom of this file.
 *
 * The lever: `plan()` ranks candidate edges by `max(capacityFor(a), capacityFor(b))`
 * descending, and capacity comes straight from the node's own self-declared mode
 * (FREE=8, STATIC=4, MOBILE=2). A fresh keypair that beacons `FREE` therefore
 * sorts to the FRONT of the Kruskal pass on every honest node, and accretes
 * backbone links before any honest edge is considered. Honest MOBILE nodes have a
 * budget of 2, so each captured slot is half of a phone's persistent-link
 * capacity.
 *
 * Both attack shapes are measured, because they cost the attacker very different
 * things:
 *  - **mutual** — the sybil is genuinely adjacent (it was really nearby / really
 *    exchanged), and both sides name each other.
 *  - **unilateral** — the sybil merely *claims* adjacency to nodes it has never
 *    met. [MeshViewTracker.assembleView] keeps an edge whenever the *named*
 *    endpoint is a known (beaconing) node; it does not require the claim to be
 *    reciprocated. So this costs the attacker nothing but a beacon.
 *
 * **Population caveat — read before quoting these numbers.** The tracker is fed
 * ONLY by inbound SELF_PRESENCE beacons (`GossipEngine` ~1382 is its single
 * `record` call site), and `ModeEnvelope` gives MOBILE `presenceBeaconIntervalMs =
 * null` — a phone beacons on mode *change*, never periodically, and its entry
 * ages out after `freshWindowMs` (6 min). So an all-MOBILE view like the ring
 * below is a worst case for *population*, not a steady state: in a real mesh the
 * view is mostly anchors (STATIC 5 min / FREE 2 min beacons) plus whichever
 * phones pulsed recently. It is still the right shape for measuring the
 * mechanism, and the mechanism is what is being measured — but the honest
 * real-world framing is [a sybil competing with the handful of honest anchors],
 * which is what `a grindable userId wins the tie against an honest FREE anchor`
 * below covers.
 */
class PersistencePlannerSybilBiasTest {

    private val HONEST = 20

    private fun honestId(i: Int) = "honest-%02d".format(i)
    private fun sybilId(i: Int) = "sybil-%02d".format(i)

    /** Honest mesh: a 20-node ring of MOBILE phones (each adjacent to 2 neighbours). */
    private fun honestRing(): Pair<MutableMap<String, UserMode>, MutableSet<Pair<String, String>>> {
        val modes = linkedMapOf<String, UserMode>()
        val edges = linkedSetOf<Pair<String, String>>()
        for (i in 0 until HONEST) modes[honestId(i)] = UserMode.MOBILE
        for (i in 0 until HONEST) edges += honestId(i) to honestId((i + 1) % HONEST)
        return modes to edges
    }

    private fun withSybils(sybils: Int, mode: UserMode, mutual: Boolean): MeshView {
        val (modes, edges) = honestRing()
        for (s in 0 until sybils) {
            modes[sybilId(s)] = mode
            // The sybil claims adjacency to every honest node. `mutual` only
            // documents intent — the tracker keeps a one-sided claim either way,
            // which is the point of the unilateral case.
            for (i in 0 until HONEST) edges += sybilId(s) to honestId(i)
        }
        return MeshView(modes = modes, edges = edges)
    }

    private fun report(label: String, view: MeshView): Triple<Int, Int, Int> {
        val plan = PersistencePlanner.plan(view, redundancy = 2)
        val honest = (0 until HONEST).map { honestId(it) }.toSet()
        val toSybil = plan.links.count { (it.a in honest) != (it.b in honest) }
        val honestOnly = plan.links.count { it.a in honest && it.b in honest }
        // If every sybil defects at once, does the honest backbone still span?
        val honestOnlyPlan = BackbonePlan(plan.links.filter { it.a in honest && it.b in honest }.toSet())
        val components = honestOnlyPlan.componentCount(honest)
        println(
            "O173-BIAS[$label] links=${plan.links.size} honest-honest=$honestOnly " +
                "honest-sybil=$toSybil honestComponentsIfSybilsDefect=$components"
        )
        return Triple(honestOnly, toSybil, components)
    }

    @Test
    fun `one declared-FREE sybil captures honest backbone capacity and becomes a cut point`() {
        val clean = report("no-sybil", MeshView(honestRing().first, honestRing().second))
        assertEquals(1, clean.third, "baseline: a 20-ring of MOBILE phones spans as one component")
        assertEquals(0, clean.second, "baseline: no sybil links to find")

        val one = report("1-sybil-FREE", withSybils(1, UserMode.FREE, mutual = false))

        // The measurement. A single fake identity, costing one keypair and one
        // beacon, takes its full declared capacity (8) in honest link slots...
        assertEquals(
            8, one.second,
            "a single declared-FREE sybil fills its whole capacity from honest nodes",
        )
        // ...and because the Kruskal pass spent those slots FIRST, the honest-only
        // backbone no longer spans: if the sybil defects (or is simply switched
        // off), the honest mesh is left in pieces that were relying on it.
        assertTrue(
            one.third > 1,
            "honest backbone must have been fragmented by the sybil's hub position " +
                "(components=${one.third})",
        )
    }

    @Test
    fun `the bias scales with sybil count and MOBILE's budget of two is what makes it cheap`() {
        val one = report("1-sybil", withSybils(1, UserMode.FREE, mutual = false))
        val three = report("3-sybils", withSybils(3, UserMode.FREE, mutual = false))
        val eight = report("8-sybils", withSybils(8, UserMode.FREE, mutual = false))

        assertTrue(
            three.second > one.second && eight.second > three.second,
            "more sybils must capture strictly more honest capacity, or this is not " +
                "the amplification it looks like (1=${one.second} 3=${three.second} 8=${eight.second})",
        )
        // Discrimination control: the capture is driven by the DECLARED mode, not
        // by the sybils merely existing or by their claimed edges. Identical
        // topology, identical claimed adjacency, mode MOBILE instead of FREE.
        val asMobile = report("8-sybils-declaring-MOBILE", withSybils(8, UserMode.MOBILE, mutual = false))
        assertTrue(
            eight.second > asMobile.second,
            "declaring FREE must buy strictly more honest link slots than declaring " +
                "MOBILE with the same topology — otherwise the self-declared mode is " +
                "not the lever and this whole row is mis-aimed " +
                "(FREE=${eight.second} MOBILE=${asMobile.second})",
        )
    }

    @Test
    fun `a sybil anchor does not out-rank an honest anchor — it dilutes it`() {
        // The realistic shape: one honest anchor (anchors are the only nodes
        // reliably in the view at all — see the population caveat) plus a sybil
        // also declaring FREE, over the same ring of recently-pulsed phones.
        //
        // A hypothesis tested and REJECTED here: that an attacker would grind a
        // keypair whose userId sorts low, to win `plan()`'s lexicographic tie-break
        // (`thenBy { it.a }`) against the honest anchor. Measured, it buys nothing —
        // a high-sorting sybil id takes exactly as many links as a low-sorting one.
        // The reason is the more useful finding: the two FREE claimants are not
        // competing. Honest capacity (20 phones x 2) is not scarce enough for the
        // tie-break to bind, so BOTH anchors simply fill their declared capacity of
        // 8. The sybil doesn't have to displace the honest anchor; it just adds
        // itself beside it, and what it consumes is the honest nodes' own budget.
        fun run(sybilId: String): Triple<Int, Int, Int> {
            val modes = linkedMapOf<String, UserMode>()
            val edges = linkedSetOf<Pair<String, String>>()
            modes["mmm-honest-anchor"] = UserMode.FREE
            modes[sybilId] = UserMode.FREE
            for (i in 0 until HONEST) modes[honestId(i)] = UserMode.MOBILE
            for (i in 0 until HONEST) {
                edges += honestId(i) to honestId((i + 1) % HONEST)
                edges += "mmm-honest-anchor" to honestId(i)
                edges += sybilId to honestId(i)
            }
            val plan = PersistencePlanner.plan(MeshView(modes, edges), redundancy = 2)
            val honest = (0 until HONEST).map { honestId(it) }.toSet()
            val honestSlotsUsed = plan.links.count { it.a in honest || it.b in honest }
            val toSybil = plan.persistentPeersOf(sybilId).size
            val honestOnly = BackbonePlan(
                plan.links.filter { it.a in honest && it.b in honest }.toSet()
            ).componentCount(honest)
            println(
                "O173-BIAS[mixed:$sybilId] honestAnchor=${plan.persistentPeersOf("mmm-honest-anchor").size} " +
                    "sybil=$toSybil honestSlotsUsed=$honestSlotsUsed honestComponentsIfSybilDefects=$honestOnly"
            )
            return Triple(toSybil, honestSlotsUsed, honestOnly)
        }

        val low = run("aaa-ground-sybil")
        val high = run("zzz-late-sybil")

        assertEquals(
            low.first, high.first,
            "id-grinding buys nothing here: a low-sorting and a high-sorting sybil id " +
                "take the same number of links, because the tie-break only binds when " +
                "honest capacity is scarce",
        )
        assertEquals(
            8, low.first,
            "the sybil fills its full declared FREE capacity from honest nodes regardless",
        )
        assertTrue(
            low.third > 1,
            "and the honest-only backbone no longer spans — honest nodes spent budget " +
                "on the sybil hub instead of on each other (components=${low.third})",
        )
    }

    @Test
    fun `an unreciprocated adjacency claim is enough — the sybil need never have met anyone`() {
        // MeshViewTracker.assembleView keeps edge (x -> n) whenever n is a known
        // node; it never checks that n also named x. So "I am adjacent to all 20 of
        // you" is accepted from a node that has never been in radio range of any of
        // them, and the honest nodes then compute a plan that tells them to spend
        // real persistent-link capacity dialling it.
        val tracker = MeshViewTracker(clock = { 1_000L })
        for (i in 0 until HONEST) {
            // Honest nodes name only their ring neighbours — nobody names the sybil.
            tracker.record(
                peerUserId = honestId(i),
                mode = UserMode.MOBILE,
                neighbors = listOf(honestId((i + 1) % HONEST), honestId((i + HONEST - 1) % HONEST)),
                beaconAtMs = 1_000L,
            )
        }
        tracker.record(
            peerUserId = sybilId(0),
            mode = UserMode.FREE,
            neighbors = (0 until HONEST).map { honestId(it) },
            beaconAtMs = 1_000L,
        )
        val view = tracker.assembleView()
        val honest = (0 until HONEST).map { honestId(it) }.toSet()

        val survived = view.edges.count { (it.first in honest) != (it.second in honest) }
        assertEquals(
            HONEST, survived,
            "every one of the sybil's 20 unreciprocated claims survived into the view",
        )
        val plan = PersistencePlanner.plan(view, redundancy = 2)
        val planned = plan.links.count { (it.a in honest) != (it.b in honest) }
        println("O173-BIAS[unreciprocated-claims] survivedEdges=$survived plannedHonestSybilLinks=$planned")
        assertTrue(
            planned > 0,
            "and the planner turns them into real persistent-link assignments",
        )

        // Control: the tracker is not simply keeping everything. An edge naming a
        // node that never beaconed IS dropped, so the rule above is specifically
        // "no reciprocity required", not "no validation at all".
        tracker.record(
            peerUserId = sybilId(1),
            mode = UserMode.FREE,
            neighbors = listOf("ghost-never-beaconed"),
            beaconAtMs = 1_000L,
        )
        val view2 = tracker.assembleView()
        assertTrue(
            view2.edges.none { it.first == "ghost-never-beaconed" || it.second == "ghost-never-beaconed" },
            "an edge to a node that never beaconed must still be dropped",
        )
    }
}

/*
 * WHY NO FIX IS ATTEMPTED HERE (the part worth reading before building one).
 *
 * The obvious mitigation — rank by *observed* evidence (TopologyTracker's
 * sessionCount / bytesRelayed, or first-hand recent exchange) instead of by a
 * self-declared mode — breaks the one hard property PersistencePlanner is built
 * around: agreement without a coordinator. `plan()` must be a pure deterministic
 * function of a SHARED view, so that when the plan contains edge A—B both A and B
 * independently conclude "I hold this link". First-hand evidence is per-node by
 * definition: A and B would rank the same candidate edges differently and compute
 * different plans, which is precisely the self-election fragmentation the planner
 * exists to avoid (see its KDoc, and the O99 tombstone).
 *
 * That leaves only mitigations that keep the input shared, and every shared signal
 * is gossiped, i.e. forgeable by the same sybil — unless it is *attested* (signed
 * by the other endpoint). So the real design space is:
 *   (a) require reciprocity: an edge counts only if BOTH endpoints' signed beacons
 *       name each other. Cheap, kills the unilateral case outright, and does not
 *       disturb determinism. Does NOT stop a sybil that really is nearby.
 *   (b) cap aggregate trust in self-declared capacity: e.g. an honest node spends
 *       at most ⌊cap/2⌋ of its own budget on peers it has no first-hand history
 *       with. Needs care — the "first-hand" part is per-node again, so it has to
 *       be applied as a LOCAL clamp after the shared plan, not inside the ranking.
 *   (c) earned capacity: capacity derives from attested relay history rather than
 *       a declared mode — the biggest change, and it penalises a genuinely new
 *       honest anchor, which is exactly the node O54/O104 wants to recruit.
 * (a) is nearly free and (b) is a local clamp that cannot fragment the plan, so
 * they are the plausible pair; (c) is a policy decision about bootstrap trust and
 * belongs with O135/O136. Numbers above are the baseline to measure against.
 */
