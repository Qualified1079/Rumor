package com.rumor.mesh.core.model

/**
 * A user-scheduled message that fires at a specific local clock time and
 * optionally recurs at a fixed interval (O22). Stored locally — never
 * gossiped — until [MessageScheduler] picks it up and hands it to the
 * usual compose path on its fire tick.
 *
 * Recurrence model:
 *   - `intervalMs == null` — one-shot. Deleted from the repo after firing.
 *   - `intervalMs > 0` and `remainingFires > 0` — fires at [fireAtMs],
 *     advances [fireAtMs] by [intervalMs], decrements [remainingFires].
 *     Deleted when [remainingFires] reaches 0.
 *   - `intervalMs > 0` and `remainingFires == -1` — fires indefinitely.
 *     Useful for "hourly status broadcast" use cases that O22 surfaces as
 *     a legitimate alternative to a plugin needing outbound-message
 *     capability.
 *
 * DM scheduling (non-null [recipientUserId]) is supported but requires the
 * recipient's pubkey to be on file at fire time. If the contact is missing
 * the scheduled DM is silently dropped (logged) — the schedule entry is
 * still removed/advanced to keep the repo from growing unbounded.
 */
data class ScheduledMessage(
    /** Stable local ID. Used as the repo primary key. */
    val id: String,
    /** Either a BROADCAST or a DIRECT for now. Other types deferred. */
    val type: MessageType,
    val contentText: String,
    /** Wall-clock epoch ms the next fire is due. */
    val fireAtMs: Long,
    /** Recurrence period in ms; null = one-shot. */
    val intervalMs: Long? = null,
    /** Remaining fires when intervalMs is non-null. -1 = unlimited. */
    val remainingFires: Int = 0,
    /** Set for DM scheduling; null for broadcast. */
    val recipientUserId: String? = null,
    /** Wall-clock epoch ms when the user created the schedule. Diagnostic only. */
    val createdAtMs: Long = System.currentTimeMillis(),
)
