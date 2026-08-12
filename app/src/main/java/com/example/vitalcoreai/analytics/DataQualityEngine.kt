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
        val activityHistoryDays: Int = 0,

        // ── Priority 2: the two dimensions the spec requires that were missing ────
        /**
         * Age of the newest record backing this score, in hours.
         *
         * Completeness and freshness are genuinely different failures. A day can be
         * *complete* — sleep, HR, steps all present — and still be scored from data that
         * stopped arriving 30 hours ago because the watch has not synced. That score is
         * stale, not incomplete, and the user deserves to be told which.
         *
         * Null means unknown, which is scored as neutral rather than as a failure.
         */
        val dataAgeHours: Double? = null,

        /**
         * Measurement consistency, 0..1, where 1 is a rock-steady sensor signal.
         *
         * Computed upstream from the dispersion of the metric's own recent history
         * (see [consistencyFromSeries]). A signal thrashing between implausible values
         * carries less information than a stable one even when every field is populated,
         * and nothing previously captured that.
         *
         * Null means unknown → scored neutral.
         */
        val measurementConsistency: Double? = null
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

    /**
     * Data older than this is stale enough to mention. Set above 24h because Samsung
     * Health routinely delivers last night's sleep well into the following morning —
     * flagging that as stale would fire on almost every ordinary day.
     */
    private const val FRESH_HOURS = 12.0
    private const val STALE_HOURS = 36.0

    /** Weights for the two dimensions added in Priority 2. Deliberately small: they
     *  modulate a confidence number, they do not dominate it. */
    private const val W_FRESHNESS = 0.08
    private const val W_CONSISTENCY = 0.07

    /** The six dimensions the product spec names. Exposed for the debug screen. */
    enum class Factor {
        COMPLETENESS, FRESHNESS, SOURCE_RELIABILITY,
        SAMPLE_SIZE, MEASUREMENT_CONSISTENCY, HISTORICAL_COVERAGE
    }

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
                    CheckResult(1.0, 0.35, "${input.activityHistoryDays} days activity history", Factor.HISTORICAL_COVERAGE)
                input.activityHistoryDays >= MIN_DAYS_FOR_MEDIUM ->
                    CheckResult(0.6, 0.35, "${input.activityHistoryDays} days activity history (building baseline)", Factor.HISTORICAL_COVERAGE)
                input.activityHistoryDays >= 1 ->
                    CheckResult(0.3, 0.35, "only ${input.activityHistoryDays} day(s) activity history", Factor.SAMPLE_SIZE)
                else -> CheckResult(0.0, 0.35, "no activity history", Factor.SAMPLE_SIZE)
            }
            checks += sourceCheck(input, weight = 0.10)
            checks += partialDayCheck(input, weight = 0.10)
            checks += commonChecks(input)
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
                input.hrHistoryDays >= MIN_DAYS_FOR_HIGH -> CheckResult(1.0, 0.15, "${input.hrHistoryDays} days HR history", Factor.HISTORICAL_COVERAGE)
                input.hrHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.15, "${input.hrHistoryDays} days HR history", Factor.HISTORICAL_COVERAGE)
                else -> CheckResult(0.2, 0.15, "limited HR history for a resting baseline", Factor.SAMPLE_SIZE)
            }
            checks += commonChecks(input)
            return aggregate(checks, insufficient = input.hrPointsPerHour <= 0.0 && !input.hasStepsToday)
        }

        // 1. Sleep history depth (weight 0.25)
        if (usesSleep) checks += when {
            input.sleepHistoryDays >= MIN_DAYS_FOR_HIGH  -> CheckResult(1.0, 0.25, "${input.sleepHistoryDays} days sleep history", Factor.HISTORICAL_COVERAGE)
            input.sleepHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.25, "${input.sleepHistoryDays} days sleep history (building baseline)", Factor.HISTORICAL_COVERAGE)
            input.sleepHistoryDays >= 1                  -> CheckResult(0.3, 0.25, "only ${input.sleepHistoryDays} day(s) sleep history", Factor.SAMPLE_SIZE)
            else                                          -> CheckResult(0.0, 0.25, "no sleep history", Factor.SAMPLE_SIZE)
        }

        // 2. HR history depth (weight 0.20)
        if (usesRestingHR) checks += when {
            input.hrHistoryDays >= MIN_DAYS_FOR_HIGH  -> CheckResult(1.0, 0.20, "${input.hrHistoryDays} days HR history", Factor.HISTORICAL_COVERAGE)
            input.hrHistoryDays >= MIN_DAYS_FOR_MEDIUM -> CheckResult(0.6, 0.20, "${input.hrHistoryDays} days HR history", Factor.HISTORICAL_COVERAGE)
            input.hrHistoryDays >= 1                  -> CheckResult(0.3, 0.20, "only ${input.hrHistoryDays} day(s) HR history", Factor.SAMPLE_SIZE)
            else                                       -> CheckResult(0.0, 0.20, "no HR history", Factor.SAMPLE_SIZE)
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

        // 8/9. Freshness and measurement consistency (Priority 2)
        checks += commonChecks(input)

        // Insufficient: missing both today's sleep AND HR with < 3 days history
        val insufficientData = !input.hasSleepToday &&
                !input.hasHRToday &&
                input.sleepHistoryDays < MIN_DAYS_FOR_MEDIUM

        return aggregate(checks, insufficientData)
    }

    // ── Shared checks ────────────────────────────────────────────────────────

    private fun sourceCheck(input: QualityInput, weight: Double): CheckResult = when {
        input.overnightHRGapHours > MAX_OVERNIGHT_GAP_HOURS ->
            CheckResult(
                0.2, weight,
                "possible overnight watch removal (${input.overnightHRGapHours.toInt()}h gap)",
                Factor.SOURCE_RELIABILITY
            )
        input.dataSourceType == DataSourceType.PHONE_SENSOR ->
            CheckResult(0.5, weight, "phone-only tracking", Factor.SOURCE_RELIABILITY)
        input.dataSourceType == DataSourceType.MANUAL ->
            CheckResult(0.4, weight, "manual entry", Factor.SOURCE_RELIABILITY)
        else -> CheckResult(1.0, weight, "watch sensor data", Factor.SOURCE_RELIABILITY)
    }

    private fun partialDayCheck(input: QualityInput, weight: Double): CheckResult =
        if (input.partialDayFraction >= MIN_DAY_FRACTION) {
            CheckResult(1.0, weight, "full day recorded", Factor.COMPLETENESS)
        } else {
            CheckResult(
                input.partialDayFraction, weight,
                "partial day (${(input.partialDayFraction * 100).toInt()}% coverage)",
                Factor.COMPLETENESS
            )
        }

    /**
     * Checks that apply to every metric profile: how recently the data arrived, and how
     * steady the underlying signal is. Appended to whichever profile-specific checks ran,
     * and renormalised along with them by [aggregate].
     */
    private fun commonChecks(input: QualityInput): List<CheckResult> = buildList {
        // A null input means "not measured", which is different from "measured and bad".
        // Such a check is omitted entirely rather than scored at some partial value: the
        // weights renormalise over whatever ran, so an unsupplied dimension neither drags
        // the percentage down nor appears in the user-facing problem list. Scoring unknown
        // as a soft failure would have printed "data age unknown" on every score in the
        // app until every call site was updated — a caller's omission is not a data defect.
        val age = input.dataAgeHours
        if (age != null) {
            add(
                when {
                    age <= FRESH_HOURS ->
                        CheckResult(1.0, W_FRESHNESS, "data is current", Factor.FRESHNESS)
                    age >= STALE_HOURS ->
                        CheckResult(
                            0.2, W_FRESHNESS,
                            "last synced ${age.toInt()}h ago — scores may be out of date",
                            Factor.FRESHNESS
                        )
                    else -> {
                        // Linear decay between the two thresholds.
                        val t = (age - FRESH_HOURS) / (STALE_HOURS - FRESH_HOURS)
                        CheckResult(
                            1.0 - 0.8 * t, W_FRESHNESS,
                            "last synced ${age.toInt()}h ago",
                            Factor.FRESHNESS
                        )
                    }
                }
            )
        }

        val c = input.measurementConsistency
        if (c != null) {
            add(
                when {
                    c >= 0.75 -> CheckResult(1.0, W_CONSISTENCY, "stable sensor readings", Factor.MEASUREMENT_CONSISTENCY)
                    c >= 0.4 -> CheckResult(c, W_CONSISTENCY, "sensor readings vary more than usual", Factor.MEASUREMENT_CONSISTENCY)
                    else -> CheckResult(c.coerceAtLeast(0.0), W_CONSISTENCY, "sensor readings are erratic", Factor.MEASUREMENT_CONSISTENCY)
                }
            )
        }
    }

    /**
     * Turn a metric's recent history into a 0..1 consistency score.
     *
     * Uses the robust coefficient of variation (MAD-based, so one bad reading does not
     * define "typical variability"), mapped so that a CV at or below [goodCv] scores 1.0
     * and one at or above [badCv] scores 0.0.
     *
     * Returns null for series too short to judge — never a fabricated 1.0, which would
     * make a brand-new user look maximally reliable.
     */
    fun consistencyFromSeries(
        values: List<Double>,
        goodCv: Double = 0.08,
        badCv: Double = 0.35
    ): Double? {
        if (values.size < 4) return null
        val median = RobustStats.median(values) ?: return null
        if (kotlin.math.abs(median) < 1e-9) return null
        val sigma = RobustStats.robustSigma(values) ?: return null
        val cv = sigma / kotlin.math.abs(median)
        return when {
            cv <= goodCv -> 1.0
            cv >= badCv -> 0.0
            else -> 1.0 - (cv - goodCv) / (badCv - goodCv)
        }
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

        // Positives make the "Why?" block symmetrical: a MEDIUM score should say what it
        // *does* have ("sleep data available", "RHR available") alongside what it lacks.
        val positives = checks.filter { it.score >= 1.0 }.map { it.reason }.take(4)

        // Per-dimension roll-up, weight-averaged within each factor.
        val factors = checks
            .groupBy { it.factor }
            .mapValues { (_, group) ->
                val w = group.sumOf { it.weight }
                if (w <= 0.0) 0f else (group.sumOf { it.score * it.weight } / w).toFloat()
            }
            .mapKeys { it.key.name }

        return DataQualityReport(
            level = when {
                percentScore >= 75 -> Confidence.HIGH
                percentScore >= 40 -> Confidence.MEDIUM
                else               -> Confidence.LOW
            },
            confidencePercent = percentScore,
            reasons = reasons,
            insufficientData = insufficient,
            positives = positives,
            factors = factors
        )
    }

    /**
     * Lightweight check: is there enough data to generate *any* score?
     * Used as an early exit in calculators.
     */
    fun hasMinimumData(input: QualityInput): Boolean =
        input.hasSleepToday || input.hasHRToday || input.sleepHistoryDays >= MIN_DAYS_FOR_MEDIUM

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * @param factor which of the six spec dimensions this check reports on. Defaults to
     *               COMPLETENESS because most checks are presence checks; anything that is
     *               really about history depth, source, freshness or stability tags itself.
     */
    private data class CheckResult(
        val score: Double,
        val weight: Double,
        val reason: String,
        val factor: Factor = Factor.COMPLETENESS
    )
}
