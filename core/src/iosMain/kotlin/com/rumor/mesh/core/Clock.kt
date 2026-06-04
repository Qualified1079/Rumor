package com.rumor.mesh.core

import platform.Foundation.NSDate

/**
 * iOS `actual` for [SystemClock]. Returns wall-clock millis since the Unix
 * epoch from `NSDate().timeIntervalSince1970`.
 */
actual object SystemClock : Clock {
    override fun now(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}
