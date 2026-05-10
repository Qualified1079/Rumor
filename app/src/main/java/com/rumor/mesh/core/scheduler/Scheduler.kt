package com.rumor.mesh.core.scheduler

import com.rumor.mesh.core.model.RumorMessage

/**
 * Pure Deficit Round Robin scheduler.
 *
 * Every distinct [RumorMessage.senderId] is one flow. Each round, every active
 * flow gets [quantumBytes] of credit; messages are popped while head-of-line
 * fits in the flow's deficit. Leftover deficit carries to the next round, so
 * bursty flows don't lose their turn — but when a flow's queue empties, its
 * deficit is dropped (no credit banking across idle periods).
 *
 * Fairness is byte-based: one 60 KB chunk costs the same as ~300 short texts,
 * which prevents chunk-heavy senders from monopolizing the channel.
 *
 * Locally composed messages share a flow with everything else under the local
 * user ID — your own traffic competes for bandwidth on equal footing with peers.
 *
 * Thread-safe via a single synchronized lock over all state.
 */
class Scheduler(
    private val quantumBytes: Int = DEFAULT_QUANTUM_BYTES,
) {
    private val lock = Any()
    private val queues = LinkedHashMap<String, ArrayDeque<RumorMessage>>()
    private val deficits = HashMap<String, Int>()

    fun enqueue(msg: RumorMessage) = synchronized(lock) {
        queues.getOrPut(flowKey(msg)) { ArrayDeque() }.addLast(msg)
    }

    /**
     * Drain up to [maxCount] messages via DRR across all active flows.
     * Loops over flows until either the cap is hit or no flow made progress.
     */
    fun take(maxCount: Int = 200): List<RumorMessage> = synchronized(lock) {
        if (queues.isEmpty()) return emptyList()
        val result = ArrayList<RumorMessage>(minOf(maxCount, 64))
        val drained = mutableListOf<String>()

        var progress = true
        while (progress && result.size < maxCount) {
            progress = false
            for (key in queues.keys.toList()) {
                if (result.size >= maxCount) break
                val queue = queues[key] ?: continue
                var deficit = (deficits[key] ?: 0) + quantumBytes
                while (queue.isNotEmpty() && result.size < maxCount) {
                    val head = queue.first()
                    val size = sizeOf(head)
                    if (size > deficit) break  // wait for next round
                    queue.removeFirst()
                    result.add(head)
                    deficit -= size
                    progress = true
                }
                if (queue.isEmpty()) {
                    drained.add(key)
                    deficits.remove(key)
                } else {
                    deficits[key] = deficit
                }
            }
        }
        drained.forEach { queues.remove(it) }
        result
    }

    val isEmpty: Boolean get() = synchronized(lock) { queues.isEmpty() }

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
    }
}
