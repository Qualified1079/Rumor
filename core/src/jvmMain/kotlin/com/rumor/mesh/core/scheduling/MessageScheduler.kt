package com.rumor.mesh.core.scheduling

import com.rumor.mesh.core.data.ContactRepository
import com.rumor.mesh.core.data.ScheduledMessageRepository
import com.rumor.mesh.core.logging.RumorLog
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.ScheduledMessage
import com.rumor.mesh.core.protocol.GossipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rumor.mesh.core.platform.Uuid

private const val TAG = "MessageScheduler"

/**
 * Native delayed / recurring message scheduling (O22). Replaces the need
 * for plugin-tier outbound-message capability for the legitimate "status
 * bot" use cases — scheduled and recurring messages live as ordinary
 * local state and fire through the same compose path as any user-typed
 * message. No new wire format; the network can't even tell a scheduled
 * message from a typed one.
 *
 * Polls [scheduledRepo] on a [tickIntervalMs] cadence. On each tick, due
 * messages are pulled, fired in order, and either deleted (one-shot or
 * recurrence exhausted) or advanced (fireAtMs += intervalMs, remainingFires
 * decremented unless unlimited).
 *
 * Skewed clocks are not a concern at this layer — the schedule is in
 * wall-clock ms relative to the local device. If the user adjusts the
 * system clock backwards, scheduled messages may fire late by the skew.
 * If forward, they may fire in a burst. Either is acceptable for a feature
 * whose granularity is human-scale (minutes-to-days).
 */
class MessageScheduler(
    private val scheduledRepo: ScheduledMessageRepository,
    private val gossipEngine: GossipEngine,
    private val contactRepo: ContactRepository,
    private val tickIntervalMs: Long = 10_000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { fireDue(System.currentTimeMillis()) }
                    .onFailure { RumorLog.w(TAG, "tick failed", it) }
                delay(tickIntervalMs)
            }
        }
        RumorLog.i(TAG, "scheduler started (tick=${tickIntervalMs}ms)")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun fireDue(nowMs: Long) {
        val due = scheduledRepo.dueAt(nowMs)
        for (s in due) {
            fireOne(s, nowMs)
        }
    }

    /** Schedule a new one-shot broadcast at [fireAtMs]. Returns the assigned id. */
    suspend fun scheduleBroadcast(text: String, fireAtMs: Long): String {
        val id = Uuid.random()
        scheduledRepo.upsert(ScheduledMessage(
            id = id,
            type = MessageType.BROADCAST,
            contentText = text,
            fireAtMs = fireAtMs,
        ))
        return id
    }

    /** Schedule a recurring broadcast. [count] = -1 for unlimited. */
    suspend fun scheduleRecurringBroadcast(
        text: String,
        firstFireAtMs: Long,
        intervalMs: Long,
        count: Int = -1,
    ): String {
        require(intervalMs > 0) { "intervalMs must be positive" }
        val id = Uuid.random()
        scheduledRepo.upsert(ScheduledMessage(
            id = id,
            type = MessageType.BROADCAST,
            contentText = text,
            fireAtMs = firstFireAtMs,
            intervalMs = intervalMs,
            remainingFires = count,
        ))
        return id
    }

    /** Schedule a one-shot DM. Recipient must be in the contact repo at fire time. */
    suspend fun scheduleDirect(text: String, recipientUserId: String, fireAtMs: Long): String {
        val id = Uuid.random()
        scheduledRepo.upsert(ScheduledMessage(
            id = id,
            type = MessageType.DIRECT,
            contentText = text,
            fireAtMs = fireAtMs,
            recipientUserId = recipientUserId,
        ))
        return id
    }

    suspend fun cancel(id: String) {
        scheduledRepo.delete(id)
    }

    private suspend fun fireOne(s: ScheduledMessage, nowMs: Long) {
        when (s.type) {
            MessageType.BROADCAST -> {
                val composed = gossipEngine.composeBroadcast(s.contentText)
                if (composed == null) {
                    RumorLog.w(TAG, "scheduled broadcast ${s.id.take(8)}… aborted (no identity)")
                }
            }
            MessageType.DIRECT -> {
                val recipientId = s.recipientUserId
                if (recipientId == null) {
                    RumorLog.w(TAG, "scheduled DIRECT ${s.id.take(8)}… missing recipient — dropping")
                } else {
                    val contact = contactRepo.getById(recipientId)
                    val pubKey = contact?.publicKey
                    if (pubKey == null) {
                        RumorLog.w(TAG, "scheduled DM to ${recipientId.take(16)}… contact not on file — dropping")
                    } else {
                        gossipEngine.composeDirect(
                            recipientId = recipientId,
                            recipientPublicKey = com.rumor.mesh.core.crypto.CryptoManager.run {
                                pubKey.fromBase64()
                            },
                            text = s.contentText,
                        )
                    }
                }
            }
            else -> RumorLog.w(TAG, "unsupported scheduled type ${s.type} on ${s.id.take(8)}… — dropping")
        }
        // Advance or delete.
        val interval = s.intervalMs
        if (interval == null) {
            scheduledRepo.delete(s.id)
            return
        }
        val newRemaining = if (s.remainingFires < 0) -1 else s.remainingFires - 1
        if (newRemaining == 0) {
            scheduledRepo.delete(s.id)
        } else {
            // Skip past any fireAt values that are already in the past so a
            // long-paused device doesn't fire a burst on resume.
            var nextFire = s.fireAtMs + interval
            while (nextFire <= nowMs && (newRemaining > 1 || newRemaining < 0)) {
                nextFire += interval
            }
            scheduledRepo.upsert(s.copy(fireAtMs = nextFire, remainingFires = newRemaining))
        }
    }
}
