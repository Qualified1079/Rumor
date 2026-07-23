package com.rumor.mesh.ui

/**
 * O112: Compose `Text` hands the entire string to Android's native line-break
 * engine (`LineBreaker.nComputeLineBreaks`) on the main thread BEFORE `maxLines`
 * truncation applies — so a single large unbroken payload wedges the UI thread
 * into an ANR. Field-confirmed: one 500 KB signed broadcast made the feed
 * unopenable (the layout never returns). A cracked client could brick every
 * recipient's feed with one message.
 *
 * Cap what we ever hand to layout; the full content stays in the store. 5000
 * chars is far beyond any real message and keeps line-breaking bounded.
 */
const val MAX_DISPLAY_CHARS = 5000

fun String.capForDisplay(): String =
    if (length <= MAX_DISPLAY_CHARS) this else take(MAX_DISPLAY_CHARS) + "… (truncated)"
