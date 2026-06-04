package com.rumor.mesh.core

/**
 * JVM `actual` for [SystemClock]. Returns wall-clock millis from
 * `System.currentTimeMillis()`.
 */
actual object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
