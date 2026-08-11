package com.example.vitalcoreai.data.model

/**
 * Domain models for passing health data to the analytics engine.
 * These are plain Kotlin data classes with NO Android framework dependencies.
 */

data class SleepData(
    val dateEpochDay: Long,
    val durationMinutes: Int,
    /**
     * 0–100, or **null when the source supplied no stage detail**.
     *
     * Null is load-bearing: Samsung Health regularly writes a sleep session with no
     * stages (naps, manual entries, and any night where the session envelope syncs
     * before the stage detail does). Computing (deep+rem+light)/duration in that case
     * yields a literal 0.0%, which the recovery engine then z-scores against an ~83%
     * baseline and reads as a catastrophic night. Efficiency is unknown, not zero, and
     * RecoveryScoreCalculator redistributes its weight rather than substituting a
     * fabricated constant.
     */
    val efficiencyPercent: Double?,
    val bedtimeMinuteOfDay: Int?,             // e.g., 23*60+30 = 1410 for 11:30pm
    val wakeTimeMinuteOfDay: Int?,
    val remMinutes: Int = 0,
    val deepMinutes: Int = 0,
    val lightMinutes: Int = 0,
    val awakeMinutes: Int = 0,
    /** False when the session carried no stage breakdown — the stage bar must not render. */
    val stagesAvailable: Boolean = false
)

data class RestingHRData(
    val dateEpochDay: Long,
    val bpm: Int
)

data class HeartRatePoint(
    val timestampMs: Long,
    val bpm: Int
)

data class TrainingLoadData(
    val dateEpochDay: Long,
    val normalizedLoad: Float,               // 0.0–1.0 where 1.0 = max effort
    val durationMinutes: Int,
    val dominantZone: HRZone,
    /**
     * True when the zone distribution came from real intraday HR samples.
     *
     * Previously "has HR data" was inferred as `dominantZone != ZONE2`, but ZONE2 is
     * also the no-data fallback *and* a completely legitimate outcome — so every
     * steady endurance session, the most common workout there is, was reported at
     * reduced confidence with the reason "no intraday HR" while HR was in fact present.
     */
    val hasHeartRateData: Boolean = true
)

data class DailyActivityData(
    val dateEpochDay: Long,
    val steps: Int,
    val distanceMeters: Float,
    /** Total daily energy (BMR + activity) — typically 2000–2800 kcal for an adult. */
    val caloriesBurned: Int,
    /**
     * Activity-only energy, null when Samsung Health did not write
     * ActiveCaloriesBurnedRecord. Never substitute [caloriesBurned] here: dividing a
     * ~2400 kcal total by a 600 kcal target pins the component at 100 every day.
     */
    val activeCalories: Int? = null
)

data class WeightData(
    val dateEpochDay: Long,
    val weightKg: Float,
    val bodyFatPercent: Float?
)

/**
 * Heart-rate zones expressed as percentages of **heart-rate reserve** (Karvonen),
 * not of max HR.
 *
 * `intensity = (HR − restingHR) / (maxHR − restingHR)`
 *
 * This matters for a 24-hour signal. Under %HRmax, a user with RHR 58 and HRmax 190
 * sits at 31% while asleep — below every zone boundary — and the old lookup
 * (`lastOrNull { pct >= it.minPercent } ?: ZONE1`) therefore bucketed *sleeping* heart
 * rate into Zone 1 alongside a genuine recovery jog. Under HRR quiet rest is 0.00 by
 * construction, and [BELOW_ZONE1] gives sub-threshold time somewhere honest to live.
 *
 * Reference: Karvonen MJ et al. (1957) Ann Med Exp Biol Fenn 35(3):307-315.
 */
enum class HRZone(val minPercent: Int, val maxPercent: Int, val label: String) {
    BELOW_ZONE1(0, 50, "Rest"),
    ZONE1(50, 60, "Recovery"),
    ZONE2(60, 70, "Aerobic Base"),
    ZONE3(70, 80, "Aerobic"),
    ZONE4(80, 90, "Threshold"),
    ZONE5(90, 100, "VO2 Max");

    companion object {
        /** Zones that represent actual training effort (excludes [BELOW_ZONE1]). */
        val TRAINING_ZONES: List<HRZone> = listOf(ZONE1, ZONE2, ZONE3, ZONE4, ZONE5)

        /** Map a 0–1 heart-rate-reserve intensity to its zone. */
        fun ofReserveFraction(fraction: Double): HRZone {
            val pct = (fraction * 100.0)
            return entries.last { pct >= it.minPercent }
        }
    }
}

data class ExerciseSessionData(
    val dateEpochDay: Long,
    val startMs: Long,
    val endMs: Long,
    /** Human-readable name resolved from the Health Connect int constant, e.g. "Running". */
    val type: String,
    /** The raw Health Connect EXERCISE_TYPE_* constant, so icon mapping keys off a stable id. */
    val exerciseTypeId: Int = 0,
    val heartRatePoints: List<HeartRatePoint>,
    val caloriesBurned: Int?,
    val distanceMeters: Float?
)

data class SpO2Reading(
    val timestampMs: Long,
    val percent: Float
)

/**
 * SpO₂ for a day, restricted to the overnight window.
 *
 * All-day averaging mixes continuous overnight monitoring with ad-hoc daytime spot
 * checks taken at variable wrist placement; the resulting number means nothing
 * physiologically but still moved recovery by ±5 points. [readingCount] lets callers
 * require a minimum sample count before applying any modifier.
 */
data class SpO2Summary(
    val averagePercent: Float,
    val readingCount: Int,
    val fromSleepWindow: Boolean
)
