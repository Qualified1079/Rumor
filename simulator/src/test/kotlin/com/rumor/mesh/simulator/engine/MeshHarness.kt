package com.rumor.mesh.simulator.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Real-engine mesh harness. Every node is a [SimNode] — the actual Rumor
 * `GossipEngine` + `MessageStore` + `DuplicateFilter` + `BreadcrumbCache` +
 * relay path (the same `:core` code the desktop `:node` and the phone run).
 * DMs are composed via the real `GossipEngine.composeDirect` (X25519 ECDH +
 * AES-GCM) and delivery is measured by the ciphertext actually landing in the
 * recipient's real store after real relay/dedup.
 *
 * This is the "grow it into a real harness" deliverable: every [Config] field
 * is a slider you sweep to see a REAL delivery-rate trend — peer cap, per-node
 * duty cycle (online/offline), link loss, and dead/hostile relay fraction.
 *
 * Cost note (SIMULATOR_TESTING §4): live engines are heavy and the suite runs
 * in parallel, so this is the small-N/high-fidelity end (dozens of nodes), the
 * complement to the headless O193 set-algebra model (hundreds, abstract). Keep
 * node counts modest and always tear the scope down.
 */
class MeshHarness(private val cfg: Config) {

    data class Config(
        val nodes: Int = 24,
        val peerCap: Int = 4,            // max persistent neighbours per node (topology degree)
        val rounds: Int = 45,
        val dms: Int = 48,               // DMs originated (random sender→recipient), spread over the first rounds
        val nodeOnlineFraction: Double = 1.0,  // duty cycle: each node online with this prob per round
        val linkLoss: Double = 0.0,      // per-message drop on every edge
        val deadRelayFraction: Double = 0.0,   // nodes that refuse to relay (hostile/broken); never DM endpoints
        val seed: Long = 1,
    )

    data class Result(val sent: Int, val delivered: Int) {
        val deliveryRate: Double get() = if (sent == 0) 0.0 else delivered.toDouble() / sent
    }

    private val clock = SimClock(0L)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    suspend fun run(): Result {
        val rng = Random(cfg.seed)
        val nodes = (0 until cfg.nodes).map { SimNode(it, scope, useBreadcrumbs = true, clock = clock) }

        // Dead relays: a fixed set that won't participate in exchanges (hostile
        // or broken relay). Excluded from being DM endpoints so we measure the
        // honest mesh's ability to route AROUND them.
        val dead = HashSet<Int>()
        if (cfg.deadRelayFraction > 0) {
            (0 until cfg.nodes).shuffled(rng).take((cfg.deadRelayFraction * cfg.nodes).toInt()).forEach { dead.add(it) }
        }
        val honest = (0 until cfg.nodes).filter { it !in dead }

        // Topology: k-regular-ish neighbour graph, degree = peerCap.
        val edgeKeys = HashSet<String>()
        val edges = ArrayList<SimTransport>()
        for (node in nodes) {
            val candidates = nodes.filter { it.index != node.index && SimTransport.edgeKey(node.index, it.index) !in edgeKeys }
            for (peer in candidates.shuffled(rng).take(cfg.peerCap)) {
                val cond = NetworkConditioner().apply { lossRate = cfg.linkLoss }
                edges.add(SimTransport(node, peer, cond))
                edgeKeys.add(SimTransport.edgeKey(node.index, peer.index))
            }
        }

        // Delivery ledger: dmId -> recipient node index.
        val pending = HashMap<String, Int>()
        val dmRounds = (0 until cfg.dms).map { rng.nextInt(cfg.rounds / 2) }.groupingBy { it }.eachCount()

        for (t in 0 until cfg.rounds) {
            clock.nowMs = t * 1000L
            val online = BooleanArray(cfg.nodes) { cfg.nodeOnlineFraction >= 1.0 || rng.nextDouble() < cfg.nodeOnlineFraction }

            // Originate DMs scheduled for this round (honest online sender → honest recipient).
            repeat(dmRounds[t] ?: 0) {
                val onlineHonest = honest.filter { online[it] }
                if (onlineHonest.size >= 2) {
                    val s = onlineHonest[rng.nextInt(onlineHonest.size)]
                    var r = honest[rng.nextInt(honest.size)]
                    while (r == s) r = honest[rng.nextInt(honest.size)]
                    val recipId = nodes[r].identityProvider.identity.value ?: return@repeat
                    val msg = nodes[s].gossipEngine.composeDirect(
                        recipientId = nodes[r].userId,
                        recipientPublicKey = recipId.publicKeyBytes,
                        text = "dm-$t-$it",
                        hopsToLive = 12,
                    )
                    if (msg != null) pending[msg.id] = r
                }
            }

            nodes.forEach { it.flushSchedulerToRepo() }

            // Exchange on every edge whose endpoints are both online and neither is a dead relay.
            for (e in edges) {
                if (e.nodeA.index in dead || e.nodeB.index in dead) continue
                if (!online[e.nodeA.index] || !online[e.nodeB.index]) continue
                e.exchange(rng)
            }
            delay(5)  // let async ingest handlers catch up between rounds
        }

        // Settle, then count delivered (ciphertext present in the recipient's real store).
        delay(400)
        var delivered = 0
        for ((id, recipIdx) in pending) {
            if (nodes[recipIdx].messageRepo.getById(id) != null) delivered++
        }
        return Result(sent = pending.size, delivered = delivered)
    }

    fun close() { scope.cancel() }
}
