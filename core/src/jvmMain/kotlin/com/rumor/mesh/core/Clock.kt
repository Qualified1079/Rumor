package com.rumor.mesh.core

/**
 * JVM implementation of [Clock]. Returns wall-clock millis from
 * `System.currentTimeMillis()`. Wraps the only JVM-specific time call in
 * `:core` so the rest of the codebase can stay platform-neutral.
 *
 * In Phase 1c this should become `actual object SystemClock` paired with an
 * `expect object SystemClock : Clock` declaration in commonMain. Until then,
 * non-JVM targets need to provide their own equivalent.
 */
object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
