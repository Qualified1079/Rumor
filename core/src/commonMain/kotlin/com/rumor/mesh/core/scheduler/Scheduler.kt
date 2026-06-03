package com.rumor.mesh.core.scheduler

import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrafficClass
import com.rumor.mesh.core.model.trafficClass
import com.rumor.mesh.core.policy.StaticMode

/**
 * Class-aware Deficit Round Robin scheduler.
 *
 * Two levels of structure:
 *  - **Traffic class** — messages are bucketed by [trafficClass] and drained in
 *    strict priority order: INFRASTRUCTURE first, then REALTIME, TRANSFER_SETUP,
 *    and BULK last. Routing chatter and texts never wait behind a flood of
 *    image/video chunks.
 *  - **Flow (DRR)** — within a class, every distinct [RumorMessage.senderId] is
 *    one flow. Each round a flow gets [quantumBytes] of byte-credit; messages
 *    are popped while the head-of-line fits the flow's deficit. Leftover deficit
 *    carries to the next round so bursty flows keep their turn, but an emptied
 *    flow drops its deficit (no banking across idle periods).
 *
 * Byte-based fairness means one 60 KB chunk costs the same as ~300 short texts,
 * so chunk-heavy senders cannot monopolise their class.
 *
 * Overflow is shed from the lowest-priority class downward — under pressure a
 * node sheds bulk payloads (which a recipient can re-request later via
 * CHUNK_REQUEST) rather than dropping texts or routing traffic.
 *
 * Locally composed messages share a flow with everything else under the local
 * user ID — your own traffic competes on equal footing with peers'.
 *
 * Thread-safe via a single synchronized lock over all state.
 */
class Scheduler(
    private val quantumBytes: Int = DEFAULT_QUANTUM_BYTES,
    private val perFlowCap: Int = DEFAULT_PER_FLOW_CAP,
    private val totalQueueCap: Int = DEFAULT_TOTAL_QUEUE_CAP,
    private val staticMode: StaticMode? = null,
) {
    private val lock = Any()

    /** One DRR group per traffic class. Iterated in [TrafficClass] declaration order. */
    private val groups: Map<TrafficClass, FlowGroup> =
        TrafficClass.entries.associateWith { FlowGroup() }

    /** DRR state for a single traffic class: one queue + deficit per sender flow. */
    private class FlowGroup {
        val queues = LinkedHashMap<String, ArrayDeque<RumorMessage>>()
        val deficits = HashMap<String, Int>()
        val size: Int get() = queues.values.sumOf { it.size }
    }

    // A static node is plugged in: it can afford bigger DRR rounds, deeper
    // per-flow queues, and a larger overall backlog.
    private val isStatic: Boolean get() = staticMode?.enabled?.value == true
    private val effectiveQuantum: Int get() = if (isStatic) quantumBytes * STATIC_BOOST else quantumBytes
    private val effectivePerFlowCap: Int get() = if (isStatic) perFlowCap * STATIC_BOOST else perFlowCap
    private val effectiveTotalCap: Int get() = if (isStatic) totalQueueCap * STATIC_BOOST else totalQueueCap

    fun enqueue(msg: RumorMessage) = synchronized(lock) {
        val group = groups.getValue(msg.trafficClass)
        val queue = group.queues.getOrPut(flowKey(msg)) { ArrayDeque() }
        // Per-flow drop-oldest so one misbehaving sender can't grow memory unbounded.
        while (queue.size >= effectivePerFlowCap) queue.removeFirst()
        queue.addLast(msg)
        shedIfOverCapacity()
    }

    /**
     * Drain up to [maxCount] messages. Classes are visited in strict priority
     * order; within each class, DRR loops over sender flows until the class is
     * exhausted or the cap is hit.
     */
    fun take(maxCount: Int = 200): List<RumorMessage> = synchronized(lock) {
        val result = ArrayList<RumorMessage>(minOf(maxCount, 64))
        for (cls in TrafficClass.entries) {
            if (result.size >= maxCount) break
            drain(groups.getValue(cls), result, maxCount)
        }
        result
    }

    val isEmpty: Boolean get() = synchronized(lock) { groups.values.all { it.queues.isEmpty() } }

    /** Total messages queued across every class and flow. */
    val queueDepth: Int get() = synchronized(lock) { groups.values.sumOf { it.size } }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** DRR-drain one class group into [result] until it is empty or [maxCount] is reached. */
    private fun drain(group: FlowGroup, result: MutableList<RumorMessage>, maxCount: Int) {
        if (group.queues.isEmpty()) return
        val drained = mutableListOf<String>()
        // Classic DRR may need multiple rounds before deficit covers a head
        // message that is larger than the quantum. If we exited the moment a
        // round drained nothing, a single flow with one oversized message in
        // its class would never make progress (saw this on the BULK class of
        // SchedulerTest.drr-shares-bandwidth-fairly). Instead, keep looping as
        // long as ANY queue is non-empty, and bound progress-free rounds to
        // avoid pathological inputs holding the lock forever.
        val maxProgressFreeRounds = 32   // each round = one quantum per flow
        var progressFreeRounds = 0
        while (result.size < maxCount) {
            var anyQueue = false
            var madeProgress = false
            for (key in group.queues.keys.toList()) {
                if (result.size >= maxCount) break
                val queue = group.queues[key] ?: continue
                if (queue.isEmpty()) continue
                anyQueue = true
                var deficit = (group.deficits[key] ?: 0) + effectiveQuantum
                while (queue.isNotEmpty() && result.size < maxCount) {
                    val head = queue.first()
                    val size = sizeOf(head)
                    if (size > deficit) break  // wait for next round
                    queue.removeFirst()
                    result.add(head)
                    deficit -= size
                    madeProgress = true
                }
                if (queue.isEmpty()) {
                    drained.add(key)
                    group.deficits.remove(key)
                } else {
                    group.deficits[key] = deficit
                }
            }
            if (!anyQueue) break
            if (madeProgress) {
                progressFreeRounds = 0
            } else {
                progressFreeRounds++
                if (progressFreeRounds >= maxProgressFreeRounds) break
            }
        }
        drained.forEach { group.queues.remove(it) }
    }

    /**
     * Shed oldest messages from the lowest-priority non-empty class downward
     * until the total backlog is back under [effectiveTotalCap]. Within a class,
     * the deepest flow is trimmed first so pressure comes off the worst offender.
     */
    private fun shedIfOverCapacity() {
        var total = groups.values.sumOf { it.size }
        if (total <= effectiveTotalCap) return
        for (cls in TrafficClass.entries.reversed()) {
            val group = groups.getValue(cls)
            while (total > effectiveTotalCap) {
                val victim = group.queues.entries.maxByOrNull { it.value.size }
                if (victim == null || victim.value.isEmpty()) break
                victim.value.removeFirst()
                total--
                if (victim.value.isEmpty()) {
                    group.queues.remove(victim.key)
                    group.deficits.remove(victim.key)
                }
            }
            if (total <= effectiveTotalCap) break
        }
    }

    /** One flow per sender. */
    private fun flowKey(msg: RumorMessage): String = msg.senderId

    /**
     * Approximate serialized byte cost. Cheap to compute and tracks the dominant
     * cost — payload content. Fixed 256 byte estimate for envelope (id, sig, keys).
     */
    private fun sizeOf(msg: RumorMessage): Int {
        val payloadBytes = msg.payload?.content?.length ?: 0
        val encryptedBytes = msg.encryptedPayload?.length ?: 0
        return 256 + payloadBytes + encryptedBytes
    }

    companion object {
        /** ~60 KB — one chunk's worth of credit per round. */
        const val DEFAULT_QUANTUM_BYTES = 60_000
        /** Per-flow queue cap; drop-oldest above this. */
        const val DEFAULT_PER_FLOW_CAP = 500
        /** Total backlog cap across all classes; overflow sheds bulk first. */
        const val DEFAULT_TOTAL_QUEUE_CAP = 4_000
        /** Multiplier applied to quantum and caps when [StaticMode] is on. */
        const val STATIC_BOOST = 3
    }
}
