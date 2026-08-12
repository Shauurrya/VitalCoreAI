package com.example.vitalcoreai.core.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// MODULE BOUNDARY — java.* ONLY.
// No android.* / androidx.* imports permitted, so the analytics package (which forbids
// them) may depend on this file.

/**
 * The single source of truth for "what day is it".
 *
 * ## Why this exists
 *
 * Before this file, `LocalDate.now()` and `ZoneId.systemDefault()` were called from 10
 * files and 58 separate sites — including inside ViewModel property initialisers, where
 * "today" was frozen at construction time and never advanced across midnight. Every day
 * key in the database is a *local* epoch day, so the zone used to derive it is part of the
 * data's meaning; scattering that decision across the codebase made it impossible to
 * reason about, impossible to test, and impossible to change when the user travels.
 *
 * Everything that needs a day boundary now goes through here.
 *
 * ## Day model
 *
 * A "day" is `[local midnight, next local midnight)` in [zone]. This is what Health
 * Connect is queried with and what `dateEpochDay` means in every Room table, so the three
 * stay consistent by construction.
 *
 * DST is handled by never doing `24 * 60 * 60 * 1000` arithmetic: boundaries are always
 * resolved through `ZonedDateTime`, so the 23-hour and 25-hour days are correct. Use
 * [lengthOfDayMinutes] rather than assuming 1440.
 *
 * ## Testability
 *
 * [clock] is swappable. Tests and the developer screen's synthetic-data generator set a
 * [FixedVitalClock] to pin "now" and to simulate travel across time zones without
 * touching the device clock. Production leaves it at [SystemVitalClock].
 */
object VitalTime {

    /**
     * Swap to a [FixedVitalClock] in tests / synthetic-data mode.
     *
     * `@Volatile` because the sync worker reads this from a background thread while the
     * debug screen may be writing it from the main thread.
     */
    @Volatile
    var clock: VitalClock = SystemVitalClock

    /** Restore real time. Always call this in a test `@After`. */
    fun reset() {
        clock = SystemVitalClock
    }

    // ── Now ──────────────────────────────────────────────────────────────────

    fun zone(): ZoneId = clock.zone()

    fun nowMs(): Long = clock.nowMs()

    fun nowInstant(): Instant = Instant.ofEpochMilli(clock.nowMs())

    fun nowZoned(): ZonedDateTime = nowInstant().atZone(zone())

    /** Today in the *current* zone. Recomputed on every call — never cache this. */
    fun today(): LocalDate = nowZoned().toLocalDate()

    fun todayEpochDay(): Long = today().toEpochDay()

    /** Minutes elapsed since local midnight. */
    fun nowMinuteOfDay(): Int = nowZoned().let { it.hour * 60 + it.minute }

    // ── Day arithmetic ───────────────────────────────────────────────────────

    fun dateOf(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    /**
     * The local epoch day an instant falls on.
     *
     * This is the inverse of [startOfDayMs] and the only correct way to turn a Health
     * Connect timestamp into a `dateEpochDay` key.
     */
    fun epochDayOf(instantMs: Long): Long =
        Instant.ofEpochMilli(instantMs).atZone(zone()).toLocalDate().toEpochDay()

    /** Minutes past local midnight for an instant. */
    fun minuteOfDay(instantMs: Long): Int =
        Instant.ofEpochMilli(instantMs).atZone(zone()).let { it.hour * 60 + it.minute }

    /**
     * An instant resolved in the current zone — the display-side counterpart of [epochDayOf].
     *
     * Screens formatting a workout's start and end time went through
     * `Instant.ofEpochMilli(x).atZone(ZoneId.systemDefault())` directly, which is the one
     * form of the call the timezone grep would otherwise still have to allow.
     */
    fun zonedOf(instantMs: Long): ZonedDateTime = Instant.ofEpochMilli(instantMs).atZone(zone())

    /**
     * First millisecond of a local day.
     *
     * Uses `atStartOfDay(zone)` rather than `atTime(0,0)`, because on a spring-forward
     * date in some zones local midnight does not exist; `atStartOfDay` resolves to the
     * first valid instant instead of throwing or silently shifting.
     */
    fun startOfDayMs(epochDay: Long): Long =
        dateOf(epochDay).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** First millisecond of the *next* local day — an exclusive upper bound. */
    fun endOfDayExclusiveMs(epochDay: Long): Long = startOfDayMs(epochDay + 1)

    /** Half-open `[start, end)` millisecond bounds of a local day. */
    fun dayBoundsMs(epochDay: Long): LongRange =
        startOfDayMs(epochDay) until endOfDayExclusiveMs(epochDay)

    /**
     * Real length of a local day in minutes — 1380 / 1440 / 1500 across DST transitions.
     *
     * Anything computing a per-day rate (HR points per hour, fraction of day covered)
     * must divide by this, not by a hardcoded 1440, or a DST day reports false coverage.
     */
    fun lengthOfDayMinutes(epochDay: Long): Int =
        Duration.between(
            dateOf(epochDay).atStartOfDay(zone()),
            dateOf(epochDay + 1).atStartOfDay(zone())
        ).toMinutes().toInt()

    fun lengthOfDayHours(epochDay: Long): Double = lengthOfDayMinutes(epochDay) / 60.0

    /** Inclusive count of days spanned, safe across DST because it is pure day arithmetic. */
    fun daysBetween(fromEpochDay: Long, toEpochDay: Long): Int =
        (toEpochDay - fromEpochDay).toInt()

    /** Monday of the week containing [epochDay]. */
    fun weekStartEpochDay(epochDay: Long): Long {
        val d = dateOf(epochDay)
        return d.minusDays((d.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    fun monthStartEpochDay(epochDay: Long): Long =
        dateOf(epochDay).withDayOfMonth(1).toEpochDay()

    fun dayOfWeek(epochDay: Long): DayOfWeek = dateOf(epochDay).dayOfWeek

    /** True for Saturday/Sunday — used by pattern discovery to control for weekend effects. */
    fun isWeekend(epochDay: Long): Boolean =
        dayOfWeek(epochDay).let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

    // ── Sleep-specific attribution ───────────────────────────────────────────

    /**
     * Which day a sleep session belongs to.
     *
     * A night is attributed to the day the user **woke up**, not the day they went to bed.
     * That is the convention the rest of the app assumes: "today's recovery" is scored
     * from the night that just ended, and `HealthConnectManager.readNightForDay` already
     * selects sessions on this basis. Stated here once so nothing re-derives it.
     *
     * @param wakeInstantMs end of the sleep session
     */
    fun sleepDayFor(wakeInstantMs: Long): Long = epochDayOf(wakeInstantMs)

    /**
     * Convert a bedtime expressed as minutes-past-midnight into the calendar day it
     * actually occurred on, relative to the wake day.
     *
     * A bedtime at or after noon belongs to the previous day; one before noon (a late
     * night that crossed into the small hours) belongs to the wake day itself. Noon is
     * the standard split point because no plausible bedtime sits near it.
     */
    fun bedtimeEpochDay(wakeEpochDay: Long, bedtimeMinuteOfDay: Int): Long =
        if (bedtimeMinuteOfDay >= NOON_MINUTE) wakeEpochDay - 1 else wakeEpochDay

    /**
     * Absolute instant of a bedtime, given the wake day and clock time.
     * Resolves the previous-day case via [bedtimeEpochDay], then goes through the zone
     * so DST is applied correctly.
     */
    fun bedtimeInstantMs(wakeEpochDay: Long, bedtimeMinuteOfDay: Int): Long {
        val bedDay = bedtimeEpochDay(wakeEpochDay, bedtimeMinuteOfDay)
        return dateOf(bedDay).atStartOfDay(zone())
            .plusMinutes(bedtimeMinuteOfDay.toLong())
            .toInstant().toEpochMilli()
    }

    /**
     * Signed difference between two clock times in minutes, taking the short way round
     * the 24-hour circle. `diff(23:50, 00:10) == +20`, not −1420.
     *
     * Sleep-consistency scoring depends on this; a plain subtraction reports a rock-steady
     * sleeper who drifts either side of midnight as maximally erratic.
     */
    fun circularMinuteDelta(fromMinuteOfDay: Int, toMinuteOfDay: Int): Int {
        var d = (toMinuteOfDay - fromMinuteOfDay) % MINUTES_PER_DAY
        if (d > MINUTES_PER_DAY / 2) d -= MINUTES_PER_DAY
        if (d < -MINUTES_PER_DAY / 2) d += MINUTES_PER_DAY
        return d
    }

    // ── Formatting helpers (no Locale dependency) ────────────────────────────

    /** "7h 52m" / "52m". Negative durations are rendered with a leading minus. */
    fun formatDurationMinutes(minutes: Int): String {
        val sign = if (minutes < 0) "-" else ""
        val m = kotlin.math.abs(minutes)
        return if (m >= 60) "$sign${m / 60}h ${m % 60}m" else "$sign${m}m"
    }

    /** "23:15" from minutes past midnight. */
    fun formatClock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val h = m / 60
        val mm = m % 60
        return "${if (h < 10) "0$h" else "$h"}:${if (mm < 10) "0$mm" else "$mm"}"
    }

    const val MINUTES_PER_DAY = 1440
    private const val NOON_MINUTE = 12 * 60
}

/** Abstracts "now" and "which zone" so both can be pinned in tests. */
interface VitalClock {
    fun nowMs(): Long
    fun zone(): ZoneId
}

/** Production clock — real time, device zone, re-read on every call so travel is picked up. */
object SystemVitalClock : VitalClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Deterministic clock for tests and the synthetic-data generator.
 *
 * @param nowMs      pinned instant
 * @param zone       pinned zone — set this to a different zone to simulate travel
 */
class FixedVitalClock(
    private var nowMs: Long,
    private var zone: ZoneId = ZoneId.of("UTC")
) : VitalClock {

    constructor(local: LocalDateTime, zone: ZoneId) : this(
        local.atZone(zone).toInstant().toEpochMilli(), zone
    )

    override fun nowMs(): Long = nowMs
    override fun zone(): ZoneId = zone

    fun advanceMinutes(minutes: Long) {
        nowMs += minutes * 60_000L
    }

    fun advanceDays(days: Long) {
        nowMs = Instant.ofEpochMilli(nowMs).atZone(zone).plusDays(days).toInstant().toEpochMilli()
    }

    /** Simulate the user landing in a new time zone without changing the instant. */
    fun travelTo(newZone: ZoneId) {
        zone = newZone
    }
}
