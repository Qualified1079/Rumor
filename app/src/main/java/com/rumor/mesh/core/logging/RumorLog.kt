package com.rumor.mesh.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Structured in-memory ring buffer logger. Zero dependencies outside stdlib.
 * Debug mode forwards to Android logcat; release mode suppresses VERBOSE/DEBUG.
 */
object RumorLog {
    private const val RING_CAPACITY = 1000
    private const val LOGCAT_TAG_PREFIX = "Rumor/"

    @Volatile var debugMode: Boolean = false

    private val ring = ArrayDeque<LogEntry>(RING_CAPACITY)
    private val lock = ReentrantReadWriteLock()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun v(tag: String, msg: String) = log(LogLevel.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        if (!debugMode && level < LogLevel.INFO) return

        val entry = LogEntry(level, tag, message, throwable)

        lock.write {
            if (ring.size >= RING_CAPACITY) ring.poll()
            ring.offer(entry)
            _entries.value = ring.toList()
        }

        if (debugMode) {
            val ltag = "$LOGCAT_TAG_PREFIX$tag"
            when (level) {
                LogLevel.VERBOSE -> Log.v(ltag, message, throwable)
                LogLevel.DEBUG   -> Log.d(ltag, message, throwable)
                LogLevel.INFO    -> Log.i(ltag, message, throwable)
                LogLevel.WARN    -> Log.w(ltag, message, throwable)
                LogLevel.ERROR   -> Log.e(ltag, message, throwable)
            }
        }
    }

    fun recent(count: Int = 100): List<LogEntry> = lock.read {
        ring.toList().takeLast(count)
    }

    fun clear() = lock.write {
        ring.clear()
        _entries.value = emptyList()
    }
}
