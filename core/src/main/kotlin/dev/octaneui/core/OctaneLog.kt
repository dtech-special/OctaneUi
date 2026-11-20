package dev.octaneui.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * A log entry stores the message KEY and arguments, not ready-made text:
 * the UI picks the `log_<key>` string in the device language when displaying it.
 */
data class LogEntry(
    val timeMs: Long, val level: LogLevel, val tag: String, val key: String,
    val args: List<String> = emptyList(),
    /** Exception stack trace (if the entry was created from an exception). */
    val trace: String? = null,
) {
    /** Technical form without translation (fallback, logcat, tests). */
    val message: String get() = if (args.isEmpty()) key else "$key ${args.joinToString(" ")}"
}

/** The app's real log: the last 1000 entries in memory + logcat. */
object OctaneLog {
    private const val MAX = 1000
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** DEBUG entries are recorded only while verbose logging is enabled (Developer Settings). */
    @Volatile var verbose: Boolean = false

    fun log(level: LogLevel, tag: String, key: String, args: List<String> = emptyList(), trace: String? = null) {
        if (level == LogLevel.DEBUG && !verbose) return
        val e = LogEntry(System.currentTimeMillis(), level, tag, key, args, trace)
        _entries.value = (_entries.value + e).takeLast(MAX)
        runCatching {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, e.message)
                LogLevel.INFO -> Log.i(tag, e.message)
                LogLevel.WARN -> Log.w(tag, e.message)
                LogLevel.ERROR -> Log.e(tag, e.message)
            }
        }
    }

    fun d(tag: String, key: String, vararg args: Any?) = log(LogLevel.DEBUG, tag, key, args.map { it.toString() })
    fun i(tag: String, key: String, vararg args: Any?) = log(LogLevel.INFO, tag, key, args.map { it.toString() })
    fun w(tag: String, key: String, vararg args: Any?) = log(LogLevel.WARN, tag, key, args.map { it.toString() })
    fun e(tag: String, key: String, vararg args: Any?) = log(LogLevel.ERROR, tag, key, args.map { it.toString() })

    /** Error from an exception: message + full stack trace (shown in the log viewer). */
    fun ex(tag: String, t: Throwable) = log(LogLevel.ERROR, tag, "error_detail", listOf(t.message ?: t.javaClass.simpleName), t.stackTraceToString())

    fun clear() { _entries.value = emptyList() }
}
