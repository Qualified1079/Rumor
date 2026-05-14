package com.rumor.mesh.core.logging

import android.util.Log

/** Routes RumorLog output to Android logcat. Install once at app startup. */
object AndroidLogSink : LogSink {
    private const val PREFIX = "Rumor/"

    override fun emit(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val ltag = "$PREFIX$tag"
        when (level) {
            LogLevel.VERBOSE -> Log.v(ltag, message, throwable)
            LogLevel.DEBUG   -> Log.d(ltag, message, throwable)
            LogLevel.INFO    -> Log.i(ltag, message, throwable)
            LogLevel.WARN    -> Log.w(ltag, message, throwable)
            LogLevel.ERROR   -> Log.e(ltag, message, throwable)
        }
    }
}
