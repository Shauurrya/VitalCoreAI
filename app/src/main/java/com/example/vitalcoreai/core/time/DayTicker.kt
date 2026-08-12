package com.example.vitalcoreai.core.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

// MODULE BOUNDARY — java.* and kotlinx.coroutines ONLY.
// No android.* / androidx.* imports permitted, matching VitalTime.kt.

/**
 * The current local day, re-emitted whenever it changes.
 *
 * ## The bug this exists to fix (F-03)
 *
 * Every ViewModel resolved "today" once, in the argument list of a Room `Flow` query built
 * inside `init`:
 *
 * ```
 * repository.scoresFrom(LocalDate.now().minusDays(30).toEpochDay()).collect { … }
 * ```
 *
 * The epoch day is evaluated exactly once, when the coroutine first runs, and then baked
 * into a query that collects forever. Nothing recomputed it: an app left open overnight kept
 * showing yesterday as today, a "last 30 days" window silently became 31 and then 32 days,
 * and — worst — `MuscleRecoveryViewModel` fed a stale `today` into the recovery decay for as
 * long as its collector lived. The same applied after travel, because the zone is part of
 * how a day key is derived.
 *
 * Collecting through [dayFlow] with `flatMapLatest` restarts the underlying query when the
 * day rolls over, and only then:
 *
 * ```
 * dayFlow().flatMapLatest { today -> repository.scoresFrom(today - 30) }
 * ```
 *
 * ## Why polling
 *
 * A timer scheduled to fire exactly at midnight is wrong twice over: it drifts when the
 * process is frozen by Doze, and it is scheduled against a zone that may itself change while
 * it is pending. Re-reading a cheap value on an interval is correct under both, and
 * [distinctUntilChanged] means a day that has not rolled over costs one comparison. The
 * emission is driven by [VitalTime.clock], so a test with a [FixedVitalClock] can advance
 * the day deterministically rather than waiting for a real minute to pass.
 */
fun dayFlow(pollIntervalMs: Long = DAY_POLL_INTERVAL_MS): Flow<Long> = flow {
    // The first value is emitted INLINE, on whatever dispatcher the caller collects on, so a
    // screen has its day before its first frame and a test sees loaded state without having
    // to pump a scheduler. Nothing is gained by making the value everyone already has wait
    // for a thread hop.
    emit(VitalTime.todayEpochDay())

    // Only the repeat is moved off, and that placement is load-bearing rather than tidiness.
    //
    // This is an unbounded `while (true)` whose sole pause is a `delay`. Left on the
    // caller's dispatcher it inherits the test scheduler in any `runTest`-based ViewModel
    // test — and there `delay` is *virtual*, so the loop never actually pauses. It spins,
    // advancing virtual time forever, and the test never reaches idle. That is not
    // hypothetical: it ran `ViewModelTests` for nine minutes before the run was killed.
    //
    // Pinning the loop to Dispatchers.Default makes the pause a real one. A wall-clock poll
    // has no business being fast-forwarded; a test that wants to drive the day boundary
    // moves the clock instead, which is what [FixedVitalClock] is for.
    emitAll(
        flow {
            while (true) {
                delay(pollIntervalMs)
                emit(VitalTime.todayEpochDay())
            }
        }.flowOn(Dispatchers.Default)
    )
}.distinctUntilChanged()

/**
 * One minute. Small enough that a card is never more than a minute stale across midnight,
 * large enough that the check is free: the body is two field reads and a comparison.
 */
const val DAY_POLL_INTERVAL_MS = 60_000L

/**
 * Build a flow from today's epoch day, rebuilding it when the day rolls over.
 *
 * The single place `flatMapLatest` is opted into, so every ViewModel gets the day-boundary
 * behaviour without each one repeating the annotation — and so there is one place to change
 * if the restart strategy ever needs to differ.
 *
 * ```
 * perDay { today -> repository.scoresFrom(today - 30) }.collect { … }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> perDay(block: (Long) -> Flow<T>): Flow<T> = dayFlow().flatMapLatest { block(it) }
