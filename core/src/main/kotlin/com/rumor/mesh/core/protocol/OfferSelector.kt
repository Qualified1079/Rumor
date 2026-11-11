package com.rumor.mesh.core.protocol

import com.rumor.mesh.core.identity.IdentityProvider
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.mode.ModeState
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrustLevel
import com.rumor.mesh.core.model.approxWireBytes
import com.rumor.mesh.core.model.floodedHops
import com.rumor.mesh.core.model.routedHops
import com.rumor.mesh.core.routing.BreadcrumbCache
import com.rumor.mesh.core.routing.OnlineStatusTracker
import com.rumor.mesh.core.routing.TopologyTracker
import com.rumor.mesh.core.scheduler.Scheduler

private const val TAG = "GossipEngine"
/**
 * O198 liveness window fallback: a breadcrumb candidate counts as "reachable now" only
 * if we exchanged with it within this long. ~3 LAN gossip rounds — a routing-cadence
 * threshold, deliberately NOT the 5-min presence-DISPLAY window.
 *
 * O205: this constant is now only the no-[ModeState] fallback. When a ModeState is
 * wired, the window is [ModeEnvelope.livenessWindowMs] for the live mode (longer under
 * MOBILE duty-cycling, shorter on a FREE anchor); [GossipEngine.livenessWindowOverrideMs]
 * beats both.
 */
private const val DEFAULT_LIVENESS_WINDOW_MS = 30_000L
/** Bound on the first-hand-exchange map so a sybil id-flood can't grow it unboundedly. */
private const val LIVENESS_MAX_PEERS = 512
/** Routed messages (DMs, receipts) may take up to 1/ROUTED_SHARE of a store-backfilled offer. */
private const val ROUTED_SHARE = 4

internal class OfferSelector(
    private val topologyTracker: TopologyTracker,
    private val scheduler: Scheduler,
    private val messageStore: MessageStore,
    private val identityProvider: IdentityProvider,
    private val breadcrumbs: BreadcrumbCache?,
    private val clock: com.rumor.mesh.core.Clock,
    private val modeState: ModeState?,
) {
    /**
     * O198/liveness routing (sim-validated at all three fidelity tiers —
     * `RoutingBandwidthSweepTest`, `SmartRoutingLivenessTest`, and real-wire
     * `LanLivenessValidationTest`; see docs/SIM_HARNESS_RESULTS.md "mid-path
     * dropout"). When ON, [messagesForExchange] floods a routed DM when NONE of its
     * breadcrumb candidates were exchanged-with in the last [livenessWindowMs],
     * instead of offering only to a stale (possibly dead) next-hop and stranding the
     * DM at a mid-path dropout.
     *
     * The "reachable now" signal is first-hand recent exchange ([isRecentlyExchanged]),
     * NOT the presence-display 5-minute window: routing needs a cadence-scaled
     * threshold (a few gossip rounds), and a gossip mesh has no persistent session,
     * so "connected now" is honestly "exchanged a round or two ago". The residual
     * downstream-cut case (next hop live, path behind it severed) still wants the
     * O202 ACK backstop.
     */
    @Volatile
    var livenessRouting: Boolean = true

    /**
     * O205 escape hatch: pin the [livenessWindowMs] to an exact value, ignoring the
     * mode. Null (the default) = derive it from the live mode. Tests and a future
     * user-facing override are the only intended writers.
     */
    @Volatile
    var livenessWindowOverrideMs: Long? = null

    /**
     * Recent-exchange window for [livenessRouting]. Explicit override first, else the
     * live mode's cadence-derived window (O205), else the fixed fallback. Read fresh on
     * every use so a mode transition takes effect without re-wiring anything.
     */
    val livenessWindowMs: Long
        get() = livenessWindowOverrideMs
            ?: modeState?.envelope?.livenessWindowMs
            ?: DEFAULT_LIVENESS_WINDOW_MS

    // O198: first-hand "I directly exchanged with this peer at time T", for the
    // liveness routing filter only. Distinct from OnlineStatusTracker, which also
    // merges second-hand peer-asserted presence and serves a 5-min DISPLAY window.
    // Bounded: prune stale on write so it stays ~= the current neighbour set even
    // under a sybil id-flood.
    private val directContactMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordDirectExchange(peerUserId: String) {
        val now = clock.now()
        directContactMs[peerUserId] = now
        if (directContactMs.size > LIVENESS_MAX_PEERS) {
            directContactMs.entries.removeAll { now - it.value > livenessWindowMs }
        }
    }

    private fun isRecentlyExchanged(peerUserId: String): Boolean {
        val t = directContactMs[peerUserId] ?: return false
        return clock.now() - t < livenessWindowMs
    }

    /**
     * Returns messages to offer to [peerUserId]. The batch size is shaped by the
     * peer's recent overlap history: a peer that already knew most of our last
     * offer gets a smaller, freshest-only batch; a peer with low overlap gets a
     * full batch. This is the protocol-layer expression of diversity-aware
     * routing — see [NeighborStore]. Peer identity comes from the post-HELLO
     * Ed25519 fingerprint, never from MAC or pre-handshake hints.
     */
    suspend fun messagesForExchange(peerUserId: String): List<RumorMessage> {
        val overlap = topologyTracker.overlapFor(peerUserId)
        val cap = when {
            overlap >= 0.8f -> 50    // peer is well-informed; send just the freshest
            overlap >= 0.5f -> 100
            else            -> 200   // novel peer or unknown — send the full batch
        }
        // Deeper O92: the scheduler is the priority-shaped head (class priority +
        // DRR fairness for fresh/contended traffic), but its take() is destructive
        // — whatever the previous peer drained would never be offered again this
        // run. Backfill from the durable store so every exchange offers what we
        // hold, not what happens to still sit in a volatile queue. The peer's
        // summary (bloom/RBSR) dedups on the wire, so re-offering is free.
        val head = scheduler.take(cap)
        val batch = if (head.size >= cap) head else {
            val seen = head.mapTo(HashSet()) { it.id }
            // Routed messages get their own slice. Ranked with everything else
            // by sender clock, a carried DM dropped out of the window as soon as
            // the store held a batch of fresher broadcasts (a flood, or just a
            // sender whose clock runs slow) and was never offered again.
            val routed = messageStore.offerable(cap / ROUTED_SHARE, com.rumor.mesh.core.data.ROUTED_OFFERABLE_TYPES)
            val flooded = messageStore.offerable(cap, com.rumor.mesh.core.data.FLOODED_OFFERABLE_TYPES)
            head + (routed + flooded).filter { it.id !in seen }.take(cap - head.size)
        }

        // O29 per-peer routing filter. A relayed DM marked with intendedPeers
        // (set at relay time when breadcrumbs named candidates) is only
        // offered to peers in that set — other peers see the batch without
        // it. Locally-composed DMs and broadcasts have null intendedPeers and
        // are offered to everyone. This is hard exclusion, not bias: the
        // hard ceiling on routedHops+floodedHops in relay() bounds worst-case
        // loops if a breadcrumb is stale.
        val localUserId = identityProvider.identity.value?.userId
        val routedFiltered = ArrayList<RumorMessage>(batch.size)
        for (msg in batch) {
            // Never offer unsigned bridge traffic to peers — the store-backfill
            // path (offerable) can surface a persisted BRIDGED message (O162)
            // that relay() would have refused; the serve path must refuse it too.
            if (msg.trustLevel == TrustLevel.BRIDGED) continue
            val intended = msg.intendedPeers
            if (intended != null) {
                if (peerUserId in intended) routedFiltered.add(msg)
                continue
            }
            // Deeper O92: a backfilled relayed DM has lost its relay-time
            // intendedPeers mark (that lives on the scheduler copy; the repo
            // copy predates the relay decision). Re-derive the same O29
            // restriction statelessly: offer a relayed DM only to its
            // recipient or a current breadcrumb candidate; flood when no
            // breadcrumbs exist (same fallback as relay()). Uses the
            // authoritative (repo-backed) suspend lookup, NOT the sync
            // snapshot — the snapshot can be stale by offer time, and this
            // filter must see the same view relay() saw when it decided.
            if (breadcrumbs != null && (msg.type == MessageType.DIRECT || msg.type == MessageType.DIRECT_ACK) &&
                msg.recipientId != null && msg.senderId != localUserId &&
                msg.recipientId != peerUserId
            ) {
                val candidates = breadcrumbs.candidatePeers(msg.recipientId)
                if (candidates.isNotEmpty()) {
                    if (livenessRouting) {
                        // O198/liveness (sim-backed, see docs/SIM_HARNESS_RESULTS.md
                        // "mid-path dropout"). A crumb whose next-hop we haven't
                        // exchanged with in the last [livenessWindowMs] is a dead
                        // route, not a valid target: restrict to live candidates and
                        // flood (don't skip) when NONE are live — instead of offering
                        // into a hole and stranding the DM at the break. Uses
                        // first-hand recent exchange, NOT the 5-min presence-display
                        // window: liveness needs a routing-cadence threshold (~a few
                        // gossip rounds), and a gossip mesh has no persistent session
                        // — "connected now" IS "exchanged a round or two ago".
                        val live = candidates.filter { isRecentlyExchanged(it) }
                        if (live.isNotEmpty() && peerUserId !in live) continue
                    } else if (peerUserId !in candidates) {
                        continue
                    }
                }
            }
            routedFiltered.add(msg)
        }
        if (breadcrumbs == null) return budgetOfferBatch(routedFiltered)

        // O29 ordering bias for messages that haven't been pre-routed
        // (locally-composed DMs and intended-peer-null relays): DMs whose
        // breadcrumbs include this peer float to the front of the batch.
        // Stable-partition preserves scheduler order within each class.
        val (preferred, rest) = routedFiltered.partition { msg ->
            msg.intendedPeers == null &&
                (msg.type == MessageType.DIRECT || msg.type == MessageType.DIRECT_ACK) &&
                msg.recipientId != null &&
                peerUserId in breadcrumbs.candidatePeersSync(msg.recipientId)
        }
        return budgetOfferBatch(preferred + rest)
    }

    /**
     * O132 head-of-line-block fix: bound an offer batch so its serialized frame
     * can't exceed the GossipSession 4 MB read guard. Skip any single message
     * over [MAX_OFFERABLE_MESSAGE_BYTES] entirely (a legacy/oversized message
     * that would otherwise reset the session every round and wedge ALL sync to
     * the peer), and stop adding once the cumulative budget is reached. Order is
     * preserved, so the freshest/priority-shaped head still goes first.
     */
    private fun budgetOfferBatch(msgs: List<RumorMessage>): List<RumorMessage> {
        var acc = 0
        val out = ArrayList<RumorMessage>(msgs.size)
        for (msg in msgs) {
            val sz = msg.approxWireBytes()
            if (sz > com.rumor.mesh.core.model.MAX_OFFERABLE_MESSAGE_BYTES) {
                RumorLog.w(TAG, "Skipping un-offerable ${msg.id.take(8)}… (${sz}B > frame budget) — never wedge the link")
                continue
            }
            if (acc + sz > com.rumor.mesh.core.model.MAX_OFFER_BATCH_BYTES) break
            acc += sz
            out.add(msg)
        }
        return out
    }
}
