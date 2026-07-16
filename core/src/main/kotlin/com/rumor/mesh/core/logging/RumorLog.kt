package com.rumor.mesh.core.logging

import com.rumor.mesh.core.SystemClock
import com.rumor.mesh.core.platform.RwLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Standard severity levels, ordered from chattiest (VERBOSE) to most
 * severe (ERROR). Below INFO is gated by `RumorLog.debugMode` — keeps
 * release builds quiet without requiring scattered `if (BuildConfig
 * .DEBUG)` checks.
 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/**
 * One structured log line. Held in the in-memory ring buffer for the
 * UI log viewer; also fed to the sink at emit time. `timestampMs`
 * comes from `SystemClock.now()` so simulator scenarios with a
 * fake clock produce reproducible log timing.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timestampMs: Long = SystemClock.now(),
)

/**
 * Pluggable output: where each emitted log line goes. The Android
 * app installs an `AndroidLogSink` (wraps `android.util.Log`); the
 * simulator and unit tests use [ConsoleSink]; tests that don't want
 * any output set a null-sink.
 */
fun interface LogSink {
    fun emit(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/** Prints to stdout. Used by the simulator and unit tests. */
object ConsoleSink : LogSink {
    override fun emit(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        println("[${level.name}] $tag: $message")
        throwable?.let { println(it.stackTraceToString()) }
    }
}

/**
 * Structured in-memory ring-buffer logger with a pluggable output sink.
 *
 * The Android app installs an AndroidLogSink (wraps android.util.Log) at startup.
 * The simulator and JVM tests use the default ConsoleSink.
 */
object RumorLog {
    private const val RING_CAPACITY = 1000

    @kotlin.concurrent.Volatile var debugMode: Boolean = false
    @kotlin.concurrent.Volatile var sink: LogSink = ConsoleSink

    private val ring = ArrayDeque<LogEntry>()
    private val lock = RwLock()

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
            if (ring.size >= RING_CAPACITY) ring.removeFirstOrNull()
            ring.addLast(entry)
            _entries.value = ring.toList()
        }

        sink.emit(level, tag, message, throwable)
    }

    fun recent(count: Int = 100): List<LogEntry> = lock.read {
        ring.toList().takeLast(count)
    }

    fun clear() = lock.write {
        ring.clear()
        _entries.value = emptyList()
    }
}
