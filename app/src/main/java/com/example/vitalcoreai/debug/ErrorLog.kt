package com.example.vitalcoreai.debug

import com.example.vitalcoreai.core.time.VitalTime

/**
 * The last few things that went wrong, so the debug screen can show them.
 *
 * ## Why this exists
 *
 * Almost every failure path in the sync layer is a `runCatching { }` that deliberately
 * swallows its exception, because one declined Health Connect permission must not abort a
 * month of backfill. That is the right behaviour and it is also why a user reporting
 * "nothing synced" left nothing behind to look at: the errors were correctly contained and
 * then correctly forgotten.
 *
 * Recording them here changes nothing about control flow — a caller still swallows — but
 * the debug screen can now answer "what actually failed".
 *
 * In-memory and bounded on purpose. Health-adjacent failure text can carry record detail,
 * and this app's whole posture is that such data does not get written anywhere it was not
 * asked to go; a process restart clearing the log is the correct trade.
 */
object ErrorLog {

    const val CAPACITY = 20

    data class Entry(val timestampMs: Long, val source: String, val message: String)

    private val entries = ArrayDeque<Entry>(CAPACITY)

    @Synchronized
    fun record(source: String, throwable: Throwable) {
        record(source, throwable::class.java.simpleName + ": " + (throwable.message ?: "no message"))
    }

    @Synchronized
    fun record(source: String, message: String) {
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(VitalTime.nowMs(), source, message))
    }

    /** Newest first — the order a human reads a log in. */
    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * `runCatching`, but the failure is remembered.
 *
 * Drop-in for the bare `runCatching { }` calls in the sync path: same swallowing, same
 * return type, one line in [ErrorLog] when it does swallow something.
 */
inline fun <T> loggingFailures(source: String, block: () -> T): Result<T> =
    runCatching(block).onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        ErrorLog.record(source, it)
    }
