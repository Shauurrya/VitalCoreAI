package com.example.vitalcoreai.debug

import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.db.entity.CheckInEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.data.db.entity.HeartRateSampleEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Thirty-day scenarios for exercising the engines without waiting a month for real data.
 *
 * ## Why this is deterministic
 *
 * Every value comes from a seeded linear-congruential generator, never from `Math.random()`
 * or the wall clock. A scenario loaded twice therefore produces byte-identical rows, which
 * is what makes it usable as a regression fixture: "the forecast moved" means the *engine*
 * changed, not that the fixture rolled different dice. It is also what lets the same
 * generator back both the debug screen and the JVM tests.
 *
 * ## What these are not
 *
 * Plausible-looking numbers, not physiology. They exist to drive code paths — sparse
 * history, a DST day, a zone change, an anomaly, a 100-samples-per-hour stress case — and
 * nothing here should ever be presented to a user as a measurement.
 */
object SyntheticDataGenerator {

    /** Everything one scenario writes, so the loader can insert it in one transaction. */
    data class Scenario(
        val id: String,
        val title: String,
        val description: String,
        val days: List<DailyMetricsEntity>,
        val sessions: List<ExerciseSessionEntity> = emptyList(),
        val heartRateSamples: List<HeartRateSampleEntity> = emptyList(),
        val checkIns: List<CheckInEntity> = emptyList(),
        /**
         * Zone the scenario expects to be in while it is loaded, if it is testing zone
         * handling. The debug screen pins [VitalTime.clock] to it rather than asking the
         * user to change their device settings.
         */
        val pinnedZone: ZoneId? = null
    )

    const val DAYS = 30

    /** Just enough to render the picker, without building nine 30-day fixtures to do it. */
    data class Descriptor(val id: String, val title: String, val description: String)

    /**
     * The nine scenarios' labels.
     *
     * Deliberately separate from [all]: the debug screen lists them on every refresh, and
     * `all()` materialises every fixture — including the 2,400-sample high-volume day. Paying
     * that cost to draw nine buttons would make the screen that exists to measure performance
     * the slowest one in the app.
     */
    val DESCRIPTORS: List<Descriptor> = listOf(
        Descriptor("healthy_improving", "Healthy, improving",
            "Resting HR falling 70 → 60, sleep lengthening, tight bedtimes. Nothing should be flagged."),
        Descriptor("recovering_from_stress", "Recovering from stress",
            "RHR elevated ~12 bpm for ten days, then a slow return. The 14- and 30-day trends should read IMPROVING."),
        Descriptor("overtraining", "Overtraining",
            "Training load ramping all month, sleep shortening, RHR stepping up in the final three days."),
        Descriptor("new_user", "New user (5 days)",
            "Five days only. Confidence must read LOW, ACWR must say it needs more history, and the forecast band must be visibly wider than a settled one."),
        Descriptor("timezone_travel", "Time-zone travel",
            "A nine-hour eastward move a week ago. Day keys must not shift and sleep consistency should drop without the bedtime SD wrapping around midnight."),
        Descriptor("dst_transition", "DST transition",
            "Ends on 29 March 2026, a 23-hour day in Europe/London. Day coverage must be judged against 1380 minutes, not 1440."),
        Descriptor("no_data", "No data / permissions refused",
            "No rows at all. Every card must render its own empty state rather than a zero, and nothing may crash."),
        Descriptor("high_volume", "High-volume HR",
            "2,400 intraday samples on the latest day. Analytics for the day must still complete well under 100 ms."),
        Descriptor("weekend_pattern", "Weekend shift",
            "Late Friday and Saturday nights against early weekdays. Bedtime SD must come out around an hour, not twenty.")
    )

    /** Every scenario the product spec names, in spec order. Builds all nine fixtures. */
    fun all(todayEpochDay: Long): List<Scenario> = listOf(
        healthyImproving(todayEpochDay),
        recoveringFromStress(todayEpochDay),
        overtraining(todayEpochDay),
        newUser(todayEpochDay),
        timezoneTravel(todayEpochDay),
        dstTransition(todayEpochDay),
        noData(todayEpochDay),
        highVolumeData(todayEpochDay),
        weekendPattern(todayEpochDay)
    )

    /** Build exactly one scenario. Named rather than filtered, so only one is materialised. */
    fun byId(id: String, todayEpochDay: Long): Scenario? = when (id) {
        "healthy_improving" -> healthyImproving(todayEpochDay)
        "recovering_from_stress" -> recoveringFromStress(todayEpochDay)
        "overtraining" -> overtraining(todayEpochDay)
        "new_user" -> newUser(todayEpochDay)
        "timezone_travel" -> timezoneTravel(todayEpochDay)
        "dst_transition" -> dstTransition(todayEpochDay)
        "no_data" -> noData(todayEpochDay)
        "high_volume" -> highVolumeData(todayEpochDay)
        "weekend_pattern" -> weekendPattern(todayEpochDay)
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1 — readiness trending up, sleep consistent, no anomalies
    // ─────────────────────────────────────────────────────────────────────────

    fun healthyImproving(today: Long): Scenario {
        val rng = Rng(1)
        val days = window(today).map { (offset, day) ->
            val progress = offset / (DAYS - 1).toFloat()          // 0 → 1
            metrics(
                day = day,
                restingHR = (70 - 10 * progress).roundToInt() + rng.jitter(1),
                sleepMinutes = 420 + (30 * progress).roundToInt() + rng.jitter(12),
                bedtime = 22 * 60 + 45 + rng.jitter(10),
                wake = 6 * 60 + 45 + rng.jitter(10),
                steps = 8000 + rng.jitter(900)
            )
        }
        return Scenario(
            id = "healthy_improving",
            title = "Healthy, improving",
            description = "Resting HR falling 70 → 60, sleep lengthening, tight bedtimes. Nothing should be flagged.",
            days = days,
            sessions = weeklySessions(today, rng, perWeek = 3, type = "RUNNING", load = 0.5f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2 — low readiness, elevated RHR, recovering
    // ─────────────────────────────────────────────────────────────────────────

    fun recoveringFromStress(today: Long): Scenario {
        val rng = Rng(2)
        val days = window(today).map { (offset, day) ->
            // Spikes in the first third, then settles back toward baseline.
            val stress = when {
                offset < 10 -> 1f
                offset < 20 -> 1f - (offset - 10) / 10f
                else -> 0f
            }
            metrics(
                day = day,
                restingHR = (58 + 12 * stress).roundToInt() + rng.jitter(1),
                sleepMinutes = (400 - 40 * stress).roundToInt() + rng.jitter(20),
                bedtime = 23 * 60 + 30 + rng.jitter(25),
                wake = 6 * 60 + 30 + rng.jitter(20),
                steps = (5200 - 1500 * stress).roundToInt() + rng.jitter(700)
            )
        }
        return Scenario(
            id = "recovering_from_stress",
            title = "Recovering from stress",
            description = "RHR elevated ~12 bpm for ten days, then a slow return. The 14- and 30-day trends should read IMPROVING.",
            days = days,
            checkIns = (0 until 10).map { i ->
                checkIn(today - DAYS + 1 + i, energy = 3, stress = 8, soreness = 5)
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3 — high strain, declining recovery, anomaly expected
    // ─────────────────────────────────────────────────────────────────────────

    fun overtraining(today: Long): Scenario {
        val rng = Rng(3)
        val days = window(today).map { (offset, day) ->
            val load = (offset / (DAYS - 1).toFloat())            // ramps all month
            metrics(
                day = day,
                // The last three days step well outside the personal range, which is what
                // AnomalyDetectionEngine's sustained-deviation rule is looking for.
                restingHR = (56 + 8 * load).roundToInt() + if (offset >= DAYS - 3) 9 else 0 + rng.jitter(1),
                sleepMinutes = (450 - 70 * load).roundToInt() + rng.jitter(15),
                bedtime = 23 * 60 + rng.jitter(30),
                wake = 6 * 60 + rng.jitter(20),
                steps = 11000 + rng.jitter(1200)
            )
        }
        return Scenario(
            id = "overtraining",
            title = "Overtraining",
            description = "Training load ramping all month, sleep shortening, RHR stepping up in the final three days.",
            days = days,
            sessions = weeklySessions(today, rng, perWeek = 6, type = "STRENGTH_TRAINING", load = 0.85f),
            checkIns = (DAYS - 5 until DAYS).map { i ->
                checkIn(today - DAYS + 1 + i, energy = 3, stress = 7, soreness = 8)
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4 — sparse history, "building baseline"
    // ─────────────────────────────────────────────────────────────────────────

    fun newUser(today: Long): Scenario {
        val rng = Rng(4)
        val days = (4 downTo 0).map { back ->
            metrics(
                day = today - back,
                restingHR = 64 + rng.jitter(2),
                sleepMinutes = 430 + rng.jitter(25),
                bedtime = 23 * 60 + 15 + rng.jitter(20),
                wake = 6 * 60 + 30 + rng.jitter(15),
                steps = 7200 + rng.jitter(800)
            )
        }
        return Scenario(
            id = "new_user",
            title = "New user (5 days)",
            description = "Five days only. Confidence must read LOW, ACWR must say it needs more history, and the forecast band must be visibly wider than a settled one.",
            days = days
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5 — overnight zone change
    // ─────────────────────────────────────────────────────────────────────────

    fun timezoneTravel(today: Long): Scenario {
        val rng = Rng(5)
        val days = window(today).map { (offset, day) ->
            // From the travel day on, bedtime and wake shift by the 9-hour zone change.
            val travelled = offset >= DAYS - 7
            val shift = if (travelled) 9 * 60 else 0
            metrics(
                day = day,
                restingHR = 61 + (if (travelled) 5 else 0) + rng.jitter(2),
                sleepMinutes = (if (travelled) 350 else 445) + rng.jitter(20),
                bedtime = ((23 * 60 + shift) % 1440) + rng.jitter(20),
                wake = ((6 * 60 + 30 + shift) % 1440) + rng.jitter(20),
                steps = 6800 + rng.jitter(900)
            )
        }
        return Scenario(
            id = "timezone_travel",
            title = "Time-zone travel",
            description = "A nine-hour eastward move a week ago. Day keys must not shift and sleep consistency should drop without the bedtime SD wrapping around midnight.",
            days = days,
            pinnedZone = ZoneId.of("Asia/Kolkata")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6 — spring forward
    // ─────────────────────────────────────────────────────────────────────────

    fun dstTransition(today: Long): Scenario {
        val rng = Rng(6)
        // Anchor on a real spring-forward date in Europe/London so lengthOfDayMinutes
        // genuinely returns 1380 for the transition day.
        val springForward = LocalDate.of(2026, 3, 29).toEpochDay()
        val days = (0 until DAYS).map { offset ->
            val day = springForward - (DAYS - 1 - offset)
            metrics(
                day = day,
                restingHR = 60 + rng.jitter(2),
                sleepMinutes = if (day == springForward) 380 else 440 + rng.jitter(15),
                bedtime = 23 * 60 + rng.jitter(15),
                wake = 6 * 60 + 45 + rng.jitter(15),
                steps = 7400 + rng.jitter(800)
            )
        }
        return Scenario(
            id = "dst_transition",
            title = "DST transition",
            description = "Ends on 29 March 2026, a 23-hour day in Europe/London. Day coverage must be judged against 1380 minutes, not 1440.",
            days = days,
            pinnedZone = ZoneId.of("Europe/London")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7 — permissions refused
    // ─────────────────────────────────────────────────────────────────────────

    fun noData(today: Long): Scenario = Scenario(
        id = "no_data",
        title = "No data / permissions refused",
        description = "No rows at all. Every card must render its own empty state rather than a zero, and nothing may crash.",
        days = emptyList()
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 8 — 100 HR samples per hour
    // ─────────────────────────────────────────────────────────────────────────

    fun highVolumeData(today: Long): Scenario {
        val rng = Rng(8)
        val days = window(today).map { (_, day) ->
            metrics(
                day = day,
                restingHR = 59 + rng.jitter(2),
                sleepMinutes = 445 + rng.jitter(15),
                bedtime = 22 * 60 + 50 + rng.jitter(12),
                wake = 6 * 60 + 20 + rng.jitter(12),
                steps = 9100 + rng.jitter(700),
                hrPointsPerHour = 100.0
            )
        }
        // 100 samples an hour for 24 hours on the most recent day only — 2,400 rows is
        // enough to catch an O(n²) scan without making the debug load take a minute.
        val samples = (0 until 24).flatMap { hour ->
            (0 until 100).map { i ->
                val ms = VitalTime.startOfDayMs(today) + hour * 3_600_000L + i * 36_000L
                HeartRateSampleEntity(
                    dateEpochDay = today,
                    timestampMs = ms,
                    bpm = 58 + rng.jitter(14)
                )
            }
        }
        return Scenario(
            id = "high_volume",
            title = "High-volume HR",
            description = "2,400 intraday samples on the latest day. Analytics for the day must still complete well under 100 ms.",
            days = days,
            heartRateSamples = samples
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9 — late on Fri/Sat, early on weekdays
    // ─────────────────────────────────────────────────────────────────────────

    fun weekendPattern(today: Long): Scenario {
        val rng = Rng(9)
        val days = window(today).map { (_, day) ->
            val dow = VitalTime.dayOfWeek(day)
            val isLateNight = dow == java.time.DayOfWeek.FRIDAY || dow == java.time.DayOfWeek.SATURDAY
            metrics(
                day = day,
                restingHR = (if (isLateNight) 65 else 59) + rng.jitter(2),
                sleepMinutes = (if (isLateNight) 400 else 455) + rng.jitter(15),
                // 02:10 on the weekend, 22:40 on a weekday — the pair straddles midnight,
                // which is exactly the case a non-circular SD gets catastrophically wrong.
                bedtime = if (isLateNight) 2 * 60 + 10 + rng.jitter(20) else 22 * 60 + 40 + rng.jitter(12),
                wake = if (isLateNight) 9 * 60 + 30 + rng.jitter(20) else 6 * 60 + 30 + rng.jitter(10),
                steps = 7600 + rng.jitter(900)
            )
        }
        return Scenario(
            id = "weekend_pattern",
            title = "Weekend shift",
            description = "Late Friday and Saturday nights against early weekdays. Bedtime SD must come out around an hour, not twenty.",
            days = days
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────────────────

    /** `(offset, epochDay)` for the 30-day window ending today, oldest first. */
    private fun window(today: Long): List<Pair<Int, Long>> =
        (0 until DAYS).map { offset -> offset to (today - (DAYS - 1 - offset)) }

    private fun metrics(
        day: Long,
        restingHR: Int,
        sleepMinutes: Int,
        bedtime: Int,
        wake: Int,
        steps: Int,
        hrPointsPerHour: Double = 12.0
    ): DailyMetricsEntity {
        val bed = ((bedtime % 1440) + 1440) % 1440
        val wk = ((wake % 1440) + 1440) % 1440
        val deep = (sleepMinutes * 0.20).roundToInt()
        val rem = (sleepMinutes * 0.22).roundToInt()
        val awake = (sleepMinutes * 0.06).roundToInt()
        return DailyMetricsEntity(
            dateEpochDay = day,
            restingHR = restingHR,
            steps = steps,
            distanceMeters = steps * 0.72f,
            caloriesBurned = 1900 + steps / 20,
            weightKg = 74.5f,
            bodyFatPercent = 18.2f,
            spO2Percent = 96.5f,
            sleepDurationMinutes = sleepMinutes,
            sleepEfficiencyPercent = 88.0,
            sleepDeepMinutes = deep,
            sleepRemMinutes = rem,
            sleepLightMinutes = sleepMinutes - deep - rem - awake,
            sleepAwakeMinutes = awake,
            bedtimeMinuteOfDay = bed,
            wakeTimeMinuteOfDay = wk,
            // Fixed rather than "now", so reloading a scenario does not change its rows.
            lastSyncTimestampMs = VitalTime.startOfDayMs(day),
            dataSourceType = "WATCH_SENSOR",
            hrPointsPerHour = hrPointsPerHour,
            partialDayFraction = 0.95,
            restingHRDerived = true,
            spO2ReadingCount = 42,
            spO2FromSleepWindow = true,
            sleepStagesAvailable = true,
            activeCalories = steps / 12,
            hasData = true,
            newestRecordTimestampMs = VitalTime.endOfDayExclusiveMs(day) - 60_000L
        )
    }

    private fun weeklySessions(
        today: Long,
        rng: Rng,
        perWeek: Int,
        type: String,
        load: Float
    ): List<ExerciseSessionEntity> = window(today).mapNotNull { (offset, day) ->
        if (offset % 7 >= perWeek) return@mapNotNull null
        val startMinute = 17 * 60 + rng.jitter(45)
        val duration = 45 + rng.jitter(15)
        val start = VitalTime.startOfDayMs(day) + startMinute * 60_000L
        ExerciseSessionEntity(
            startMs = start,
            dateEpochDay = day,
            endMs = start + duration * 60_000L,
            exerciseType = type,
            exerciseTypeId = 0,
            durationMinutes = duration,
            caloriesBurned = duration * 9,
            distanceMeters = if (type == "RUNNING") duration * 170f else null,
            avgHR = 142 + rng.jitter(8),
            maxHR = 171 + rng.jitter(6),
            trainingLoadNormalized = load,
            dominantZone = "ZONE3",
            hasHeartRateData = true,
            belowZone1Pct = 5f,
            zone1Pct = 10f,
            zone2Pct = 25f,
            zone3Pct = 40f,
            zone4Pct = 15f,
            zone5Pct = 5f,
            rpe = (load * 10).roundToInt().coerceIn(1, 10)
        )
    }

    private fun checkIn(day: Long, energy: Int, stress: Int, soreness: Int) = CheckInEntity(
        dateEpochDay = day,
        energy = energy,
        stress = stress,
        soreness = soreness,
        sleepQuality = 5,
        mood = 5,
        motivation = 5,
        illnessFlag = false,
        notes = null,
        createdAtMs = VitalTime.startOfDayMs(day)
    )

    /**
     * A seeded LCG (Numerical Recipes constants).
     *
     * `Math.random()` would make every load of a scenario produce different rows, which
     * defeats the point of a fixture — and the workflow-script rule against non-determinism
     * exists for the same reason.
     */
    private class Rng(seed: Int) {
        private var state: Long = seed.toLong() * 2_654_435_761L + 1L

        private fun next(): Int {
            state = (state * 1_664_525L + 1_013_904_223L) and 0xFFFFFFFFL
            return (state ushr 16).toInt()
        }

        /** Uniform in `[-magnitude, +magnitude]`. */
        fun jitter(magnitude: Int): Int =
            if (magnitude <= 0) 0 else (next() % (2 * magnitude + 1)) - magnitude
    }
}
