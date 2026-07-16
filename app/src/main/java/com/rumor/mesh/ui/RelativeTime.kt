package com.rumor.mesh.ui

/**
 * Human-readable "time since" label for a wall-clock delta in milliseconds.
 *
 * Rolls up s → m → h → d → w → mo → y so a legitimately old message in the
 * long-term-collapse threat model reads as "2mo ago", not "19000h ago" (O96).
 * A negative delta means the sender's clock is ahead of ours — expected in the
 * field where there is no NTP/GPS (O95) — and clamps to "just now" rather than
 * rendering a nonsense future timestamp.
 *
 * Single source of truth for every timestamp the UI shows (feed, thread,
 * message list); do not re-copy the tiers into a screen.
 */
fun formatElapsed(elapsedMs: Long): String {
    if (elapsedMs < 0) return "just now"
    val sec = elapsedMs / 1000
    return when {
        sec < 60         -> "${sec}s ago"
        sec < 3600       -> "${sec / 60}m ago"
        sec < 86_400     -> "${sec / 3600}h ago"
        sec < 604_800    -> "${sec / 86_400}d ago"
        sec < 2_592_000  -> "${sec / 604_800}w ago"
        sec < 31_536_000 -> "${sec / 2_592_000}mo ago"
        else             -> "${sec / 31_536_000}y ago"
    }
}
