package com.rumor.mesh.core.scheduler

import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.core.model.TrafficClass

/**
 * Priority-aware outbound scheduler with Deficit Round Robin for bulk traffic.
 *
 * Priority order (higher always drains before lower):
 *   INFRASTRUCTURE → REALTIME → TRANSFER_SETUP → BULK
 *
 * Within BULK, DRR across source keys (senderId) ensures no single sender's
 * chunks monopolize the channel across multiple gossip sessions.
 *
 * Thread-safe via a single synchronized lock over all state.
 */
class Scheduler {

    private val lock = Any()

    // Simple FIFOs for the top three tiers.
    private val infraQueue = ArrayDeque<RumorMessage>()
    private val realtimeQueue = ArrayDeque<RumorMessage>()
    private val setupQueue = ArrayDeque<RumorMessage>()

    // DRR state for BULK.
    private val bulkDrr = DeficitRoundRobin(quantum = 1)

    fun enqueue(msg: RumorMessage) = synchronized(lock) {
        when (classify(msg)) {
            TrafficClass.INFRASTRUCTURE -> infraQueue.addLast(msg)
            TrafficClass.REALTIME       -> realtimeQueue.addLast(msg)
            TrafficClass.TRANSFER_SETUP -> setupQueue.addLast(msg)
            TrafficClass.BULK           -> bulkDrr.enqueue(msg, sourceKey = msg.senderId)
        }
    }

    /**
     * Drain up to [maxCount] messages in priority order.
     * INFRASTRUCTURE and REALTIME are fully drained first; then TRANSFER_SETUP;
     * then BULK via DRR.
     */
    fun take(maxCount: Int = 200): List<RumorMessage> = synchronized(lock) {
        val result = ArrayList<RumorMessage>(maxCount)
        fun drain(queue: ArrayDeque<RumorMessage>) {
            while (result.size < maxCount && queue.isNotEmpty()) {
                result.add(queue.removeFirst())
            }
        }
        drain(infraQueue)
        drain(realtimeQueue)
        drain(setupQueue)
        if (result.size < maxCount) {
            result.addAll(bulkDrr.take(maxCount - result.size))
        }
        result
    }

    val isEmpty: Boolean get() = synchronized(lock) {
        infraQueue.isEmpty() && realtimeQueue.isEmpty() &&
            setupQueue.isEmpty() && bulkDrr.isEmpty
    }

    private fun classify(msg: RumorMessage): TrafficClass = when (msg.type) {
        MessageType.PING,
        MessageType.PONG              -> TrafficClass.INFRASTRUCTURE
        MessageType.BROADCAST,
        MessageType.DIRECT            -> TrafficClass.REALTIME
        MessageType.TRANSFER_METADATA,
        MessageType.CHUNK_REQUEST     -> TrafficClass.TRANSFER_SETUP
        MessageType.CHUNK             -> TrafficClass.BULK
    }
}

/**
 * Deficit Round Robin scheduler for messages sharing a tier.
 *
 * Each [sourceKey] gets [quantum] message credits per round. Unused credits
 * carry over (deficit), so bursty senders don't permanently lose their turn.
 * When a source's queue empties its deficit resets to zero (no credit banking
 * across idle periods — prevents a returning sender from dumping a backlog).
 */
internal class DeficitRoundRobin(private val quantum: Int) {

    private val queues = LinkedHashMap<String, ArrayDeque<RumorMessage>>()
    private val deficits = HashMap<String, Int>()

    val isEmpty: Boolean get() = queues.isEmpty()

    fun enqueue(msg: RumorMessage, sourceKey: String) {
        queues.getOrPut(sourceKey) { ArrayDeque() }.addLast(msg)
    }

    fun take(maxCount: Int): List<RumorMessage> {
        if (queues.isEmpty()) return emptyList()
        val result = ArrayList<RumorMessage>(maxCount)
        val exhausted = mutableListOf<String>()

        // One full DRR pass — keep cycling until maxCount is reached or all queues drained.
        var progress = true
        while (progress && result.size < maxCount) {
            progress = false
            val keys = queues.keys.toList()
            for (key in keys) {
                val queue = queues[key] ?: continue
                val credits = (deficits[key] ?: 0) + quantum
                var remaining = credits
                while (remaining > 0 && queue.isNotEmpty() && result.size < maxCount) {
                    result.add(queue.removeFirst())
                    remaining--
                    progress = true
                }
                if (queue.isEmpty()) {
                    exhausted.add(key)
                    deficits.remove(key)
                } else {
                    deficits[key] = remaining
                }
            }
        }
        exhausted.forEach { queues.remove(it) }
        return result
    }
}
