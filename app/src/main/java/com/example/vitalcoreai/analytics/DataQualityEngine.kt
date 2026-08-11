package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * A3 — Data Quality Engine
 *
 * Evaluates data completeness for a given day and history window.
 * Returns a [DataQualityReport] that every calculator attaches to its [ScoreResult].
 *
 * Design:
 * - Each check is independent and contributes a score 0–1.
 * - Checks are weighted: sleep history weight > single-day checks.
 * - Total weighted score → maps to LOW / MEDIUM / HIGH + insufficientData flag.
 *
 * Reference: thresholds chosen based on minimum viable data for
 * statistically meaningful z-score calculation (N ≥ 3 for mean, N ≥ 7 for robust baseline).
 */
object DataQualityEngine {

    // ── Input container ──────────────────────────────────────────────────────

    /**
     * All data availability signals for a single day.
     * Every field is nullable/has a default — partial data is valid input.
     *
     * @param hasSleepToday       today's sleep session was recorded
     * @param hasHRToday          today's resting HR was recorded
     * @param hasWorkoutToday     at least one exercise session recorded
     * @param hrPointsPerHour     continuous HR density — phone-only typically < 1/h
     * @param sleepHistoryDays    how many days of sleep history are available
     * @param hrHistoryDays       how many days of resting HR history are available
     * @param overnightHRGapHours longest gap in overnight HR data (watch-off proxy)
     * @param dataSourceType      WATCH_SENSOR, PHONE_SENSOR, or MANUAL
     * @param partialDayFraction  fraction of the day covered (0.0–1.0); 1.0 = full day
     * @param duplicateRecordsDetected  true if duplicate HC records were found and dropped
     */
    data class QualityInput(
        val hasSleepToday: Boolean = false,
        val hasHRToday: Boolean = false,
        val hasWorkoutToday: Boolean = false,
        val hrPointsPerHour: Double = 0.0,
        val sleepHistoryDays: Int = 0,
        val hrHistoryDays: Int = 0,
        val overnightHRGapHours: Double = 0.0,
        val dataSourceType: DataSourceType = DataSourceType.UNKNOWN,
        val partialDayFraction: Double = 1.0,
        val duplicateRecordsDetected: Boolean = false,
        /** Activity signals — an activity score must be judged on these, not on sleep. */
        val hasStepsToday: Boolean = false,
        val activityHistoryDays: Int = 0
    )

    enum class DataSourceType { WATCH_SENSOR, PHONE_SENSOR, MANUAL, UNKNOWN }

    /**
     * Which checks are relevant to the metric being scored.
     *
     * One fixed weight vector for every metric was structurally unfair to three scores.
     * Sleep carried 0.45 of the total (0.25 history + 0.20 presence), so the Stress score —
     * which sets only HR fields — could reach at most 45% and therefore **could never be
     * HIGH confidence**, no matter how much perfect data the user had. The Activity score
     * fared worse: it set `hasHRToday = false` and `hasSleepToday = false`, capping it at
     * 30% (permanently LOW) while `insufficientData` evaluated to true unconditionally.
     *
     * Each profile selects the applicable checks and the weights are renormalised over
     * only those, so a score is judged on the data it actually consumes.
     */
    enum class MetricProfile {
        /** Sleep + HR + wear. The original full vector. */
        RECOVERY,
        /** Sleep depth and presence dominate; intraday HR is irrelevant. */
        SLEEP,
        /** Resting-HR history and presence only. */
        STRESS,
        /** Step/calorie availability and history; sleep is irrelevant. */
        ACTIVITY,
        /** Intraday HR density and day coverage dominate. */
        STRAIN
    }

    // ── Thresholds ───────────────────────────────────────────────────────────

    private const val MIN_DAYS_FOR_HIGH = 14      // ≥14 days → full personal baseline
    private const val MIN_DAYS_FOR_MEDIUM = 3     // 3–13 days → blended baseline
    private const val MIN_HR_POINTS_PER_HOUR = 2.0
    private const val MAX_OVERNIGHT_GAP_HOURS = 2.0   // > 2h gap suggests watch removed
    private const val MIN_DAY_FRACTION = 0.7           // < 70% of day → partial day

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Evaluate data quality for a metric.
     *
     * @param profile which checks apply — see [MetricProfile]. Defaults to [MetricProfile.RECOVERY],
     *                which preserves the original behaviour for existing callers.
     */
    fun evaluate(
        input: QualityInput,
        profile: MetricProfile = MetricProfile.RECOVERY
    ): DataQualityReport {
        val checks = mutableListOf<CheckResult>()

        // ── Checks relevant to this profile ──────────────────────────────────
        val usesSleep = profile in setOf(MetricProfile.RECOVERY, MetricProfile.SLEEP)
        val usesRestingHR = profile in setOf(MetricProfile.RECOVERY, MetricProfile.STRESS)
        val usesIntradayHR = profile in setOf(MetricProfile.RECOVERY, MetricProfile.STRAIN)
        val usesActivity = profile == MetricProfile.ACTIVITY

        if (usesActivity) {
            // Activity is scored on step availability and step history only.
            checks += if (input.hasStepsToday) {
                CheckResult(1.0, 0.45, "step data recorded today")
            } else {
                CheckResult(0.0, 0.45, "no step data today")
            }
            checks += when {
                input.activityHistoryDays >= MIN_DAYS_FOR_HIGH ->
                    CheckResult(1.0, 0.35, "${input.activityHistoryDays} days activity history")
                input.activityHistoryDays >= MIN_DAYS_FOR_MEDIUM ->
                    CheckResult(0.6, 0.35, "${input.activityHistoryDays} days activity history (building baseline)")
                input.activityHistoryDays >= 1 ->
                    CheckResult(0.3, 0.35, "only ${input.activityHistoryDays} day(s) activity history")
                else -> CheckResult(0.0, 0.35, "no activity history")
            }
            checks += sourceCheck(input, weight = 0.10)
            checks += partialDayCheck(input, weight = 0.10)
            return aggregate(checks, insufficient = !input.hasStepsToday && input.activityHistoryDays < MIN_DAYS_FOR_MEDIUM)
        }

        if (profile == MetricProfile.STRAIN) {
            // Strain is an integral over the day's HR timeline: density and coverage are
            // the whole story.
            checks += when {
                input.hrPointsPerHour >= MIN_HR_POINTS_PER_HOUR -> CheckResult(1.0, 0.40, "continuous HR data")
                input.hrPointsPerHour > 0 -> CheckResult(0.4, 0.40, "sparse HR data")
                else -> CheckResult(0.0, 0.40, "no intraday HR")
            }
            checks += partialDayCheck(input, weight = 0.30)
            checks += sourceCheck(input, weight = 0.15)
            checks += when {
                input.hrHistoryDays >= MIN_DAYS_FOR_HIGH -> CheckResult(1.0, 0.15, "${input.hrHistoryDays} days HR history")
                input.hrHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.15, "${input.hrHistoryDays} days HR history")
                else -> CheckResult(0.2, 0.15, "limited HR history for a resting baseline")
            }
            return aggregate(checks, insufficient = input.hrPointsPerHour <= 0.0 && !input.hasStepsToday)
        }

        // 1. Sleep history depth (weight 0.25)
        if (usesSleep) checks += when {
            input.sleepHistoryDays >= MIN_DAYS_FOR_HIGH  -> CheckResult(1.0, 0.25, "${input.sleepHistoryDays} days sleep history")
            input.sleepHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.25, "${input.sleepHistoryDays} days sleep history (building baseline)")
            input.sleepHistoryDays >= 1                  -> CheckResult(0.3, 0.25, "only ${input.sleepHistoryDays} day(s) sleep history")
            else                                          -> CheckResult(0.0, 0.25, "no sleep history")
        }

        // 2. HR history depth (weight 0.20)
        if (usesRestingHR) checks += when {
            input.hrHistoryDays >= MIN_DAYS_FOR_HIGH  -> CheckResult(1.0, 0.20, "${input.hrHistoryDays} days HR history")
            input.hrHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.20, "${input.hrHistoryDays} days HR history")
            input.hrHistoryDays >= 1                  -> CheckResult(0.3, 0.20, "only ${input.hrHistoryDays} day(s) HR history")
            else                                       -> CheckResult(0.0, 0.20, "no HR history")
        }

        // 3. Today's sleep present (weight 0.20)
        if (usesSleep) checks += if (input.hasSleepToday) {
            CheckResult(1.0, 0.20, "sleep recorded today")
        } else {
            CheckResult(0.0, 0.20, "missing sleep data")
        }

        // 4. Today's HR present (weight 0.15)
        if (usesRestingHR) checks += if (input.hasHRToday) {
            CheckResult(1.0, 0.15, "resting HR recorded")
        } else {
            CheckResult(0.0, 0.15, "missing HR data")
        }

        // 5. Continuous HR quality (weight 0.10)
        if (usesIntradayHR) checks += when {
            input.hrPointsPerHour >= MIN_HR_POINTS_PER_HOUR -> CheckResult(1.0, 0.10, "continuous HR data")
            input.hrPointsPerHour > 0                       -> CheckResult(0.5, 0.10, "sparse HR data")
            else                                             -> CheckResult(0.0, 0.10, "no intraday HR")
        }

        // 6. Watch worn check (weight 0.05)
        checks += sourceCheck(input, weight = 0.05)

        // 7. Partial day check (weight 0.05)
        checks += partialDayCheck(input, weight = 0.05)

        // Insufficient: missing both today's sleep AND HR with < 3 days history
        val insufficientData = !input.hasSleepToday &&
                !input.hasHRToday &&
                input.sleepHistoryDays < MIN_DAYS_FOR_MEDIUM

        return aggregate(checks, insufficientData)
    }

    // ── Shared checks ────────────────────────────────────────────────────────

    private fun sourceCheck(input: QualityInput, weight: Double): CheckResult = when {
        input.overnightHRGapHours > MAX_OVERNIGHT_GAP_HOURS ->
            CheckResult(0.2, weight, "possible overnight watch removal (${input.overnightHRGapHours.toInt()}h gap)")
        input.dataSourceType == DataSourceType.PHONE_SENSOR ->
            CheckResult(0.5, weight, "phone-only tracking")
        input.dataSourceType == DataSourceType.MANUAL ->
            CheckResult(0.4, weight, "manual entry")
        else -> CheckResult(1.0, weight, "watch sensor data")
    }

    private fun partialDayCheck(input: QualityInput, weight: Double): CheckResult =
        if (input.partialDayFraction >= MIN_DAY_FRACTION) {
            CheckResult(1.0, weight, "full day recorded")
        } else {
            CheckResult(input.partialDayFraction, weight, "partial day (${(input.partialDayFraction * 100).toInt()}% coverage)")
        }

    /** Weights are renormalised over whichever checks the profile actually selected. */
    private fun aggregate(checks: List<CheckResult>, insufficient: Boolean): DataQualityReport {
        if (checks.isEmpty()) return DataQualityReport.UNKNOWN
        val totalWeight = checks.sumOf { it.weight }
        val weightedScore = checks.sumOf { it.score * it.weight } / totalWeight
        val percentScore = (weightedScore * 100).toInt().coerceIn(0, 100)

        // Only genuine problems are listed. Emitting a fixed four "reasons" meant a user
        // with flawless data was shown four of them, as if something were wrong.
        val problems = checks.filter { it.score < 1.0 }.sortedBy { it.score }.map { it.reason }
        val reasons = if (problems.isEmpty()) listOf("All expected data present") else problems.take(4)

        return DataQualityReport(
            level = when {
                percentScore >= 75 -> Confidence.HIGH
                percentScore >= 40 -> Confidence.MEDIUM
                else               -> Confidence.LOW
            },
            confidencePercent = percentScore,
            reasons = reasons,
            insufficientData = insufficient
        )
    }

    /**
     * Lightweight check: is there enough data to generate *any* score?
     * Used as an early exit in calculators.
     */
    fun hasMinimumData(input: QualityInput): Boolean =
        input.hasSleepToday || input.hasHRToday || input.sleepHistoryDays >= MIN_DAYS_FOR_MEDIUM

    // ── Internal ─────────────────────────────────────────────────────────────

    private data class CheckResult(val score: Double, val weight: Double, val reason: String)
}
