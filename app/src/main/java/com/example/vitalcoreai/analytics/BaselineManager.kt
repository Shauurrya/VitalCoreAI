package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * A5 — Baseline Manager
 *
 * Maintains 7-day, 14-day, and 30-day rolling baselines for each metric.
 * New users start with population defaults and transition to personal baselines
 * as history accumulates. The blend weight is purely history-driven.
 *
 * Transition schedule:
 *   Days 1–2:   100% population default
 *   Days 3–6:   linear ramp from 20% → 50% personal
 *   Days 7–13:  linear ramp from 50% → 80% personal
 *   Days 14–29: linear ramp from 80% → 100% personal
 *   Day 30+:    100% personal baseline
 *
 * Population defaults are sourced from peer-reviewed sports science literature.
 * Extension point: [BaselineHorizon] enum — add DAYS_90 when enough user history exists.
 *
 * References:
 * - Resting HR norms: Shcherbina A et al. (2017) JMIR 4(2):e23
 * - Sleep efficiency norms: Ohayon M et al. (2004) Sleep 27(7):1255-1273
 * - Step norms: Tudor-Locke C et al. (2011) Int J Behav Nutr Phys Act 8:79
 */
object BaselineManager {

    // ── Extension point for future horizons ──────────────────────────────────
    /** Add DAYS_90 here when 90-day user history is sufficiently available. */
    enum class BaselineHorizon(val days: Int) {
        DAYS_7(7),
        DAYS_14(14),
        DAYS_30(30)
        // DAYS_90(90)  ← reserved, do not implement yet (see Improvements C1)
    }

    // ── Population defaults (literature-derived) ──────────────────────────────

    object PopulationDefaults {
        // Resting heart rate (bpm)
        const val RESTING_HR_MEAN = 62.0
        const val RESTING_HR_STD  = 8.0

        // Sleep efficiency (%)
        const val SLEEP_EFFICIENCY_MEAN = 83.0
        const val SLEEP_EFFICIENCY_STD  = 7.0

        // Sleep duration (minutes)
        const val SLEEP_DURATION_MEAN = 432.0   // 7.2 hours
        const val SLEEP_DURATION_STD  = 48.0    // ±48 min

        // Daily steps
        const val STEPS_MEAN = 7500.0
        const val STEPS_STD  = 2500.0

        // Recovery score (0–100)
        const val RECOVERY_MEAN = 55.0
        const val RECOVERY_STD  = 15.0
    }

    /**
     * Per-metric minimum standard deviation, in that metric's own units.
     *
     * A single uniform 0.5 floor was applied to bpm, percentage points and minutes alike —
     * three different units, so it was physiologically meaningful in none of them. The
     * hazard is real: `zScoreToScore` maps `50 − z·15`, so a user with a genuinely stable
     * resting HR (true 30-day SD near 1.0 — exactly the well-recovered athlete this app is
     * for) would see a 3 bpm rise become z=3, score 5/100, and the 30% RHR component would
     * tank their whole recovery score for ordinary day-to-day noise.
     *
     * These floors are set at roughly the measurement noise of each signal.
     */
    object MinStd {
        const val RESTING_HR = 2.5        // bpm — Watch Active 2 optical HR noise
        const val SLEEP_EFFICIENCY = 3.0  // percentage points
        const val SLEEP_DURATION = 15.0   // minutes
        const val STEPS = 500.0           // steps
        const val GENERIC = 0.5
    }

    // ── Baseline snapshot ─────────────────────────────────────────────────────

    /**
     * A computed baseline (mean + std) for a single metric over a specific horizon.
     *
     * @param horizon       which rolling window this baseline covers
     * @param mean          blended mean (personal + population)
     * @param std           blended standard deviation
     * @param personalWeight fraction from personal history (0.0 = all population, 1.0 = all personal)
     * @param sampleSize    number of personal data points used
     */
    data class Baseline(
        val horizon: BaselineHorizon,
        val mean: Double,
        val std: Double,
        val personalWeight: Double,
        val sampleSize: Int
    ) {
        /** True when the baseline is fully personal (no population blending). */
        val isFullyPersonal: Boolean get() = personalWeight >= 1.0
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compute a blended baseline for resting HR.
     *
     * @param historicalBpm ordered list of recent resting HR values (oldest first)
     * @param horizon       desired rolling window
     */
    fun restingHRBaseline(
        historicalBpm: List<Double>,
        horizon: BaselineHorizon = BaselineHorizon.DAYS_30
    ): Baseline = computeBaseline(
        values = historicalBpm.takeLast(horizon.days),
        populationMean = PopulationDefaults.RESTING_HR_MEAN,
        populationStd  = PopulationDefaults.RESTING_HR_STD,
        horizon = horizon,
        minStd = MinStd.RESTING_HR
    )

    /**
     * Compute a blended baseline for sleep efficiency.
     *
     * @param historicalEfficiency ordered list of sleep efficiency % values (oldest first)
     */
    fun sleepEfficiencyBaseline(
        historicalEfficiency: List<Double>,
        horizon: BaselineHorizon = BaselineHorizon.DAYS_14
    ): Baseline = computeBaseline(
        values = historicalEfficiency.takeLast(horizon.days),
        populationMean = PopulationDefaults.SLEEP_EFFICIENCY_MEAN,
        populationStd  = PopulationDefaults.SLEEP_EFFICIENCY_STD,
        horizon = horizon,
        minStd = MinStd.SLEEP_EFFICIENCY
    )

    /**
     * Compute a blended baseline for sleep duration (minutes).
     */
    fun sleepDurationBaseline(
        historicalMinutes: List<Double>,
        horizon: BaselineHorizon = BaselineHorizon.DAYS_14
    ): Baseline = computeBaseline(
        values = historicalMinutes.takeLast(horizon.days),
        populationMean = PopulationDefaults.SLEEP_DURATION_MEAN,
        populationStd  = PopulationDefaults.SLEEP_DURATION_STD,
        horizon = horizon,
        minStd = MinStd.SLEEP_DURATION
    )

    /**
     * Compute a blended baseline for daily step count.
     */
    fun stepsBaseline(
        historicalSteps: List<Double>,
        horizon: BaselineHorizon = BaselineHorizon.DAYS_30
    ): Baseline = computeBaseline(
        values = historicalSteps.takeLast(horizon.days),
        populationMean = PopulationDefaults.STEPS_MEAN,
        populationStd  = PopulationDefaults.STEPS_STD,
        horizon = horizon,
        minStd = MinStd.STEPS
    )

    /**
     * Generic baseline for any metric (e.g. recovery score history).
     */
    fun genericBaseline(
        values: List<Double>,
        populationMean: Double,
        populationStd: Double,
        horizon: BaselineHorizon = BaselineHorizon.DAYS_30
    ): Baseline = computeBaseline(
        values = values.takeLast(horizon.days),
        populationMean = populationMean,
        populationStd  = populationStd,
        horizon = horizon,
        minStd = MinStd.GENERIC
    )

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Core blending function. Personal weight is a function of [values.size].
     *
     * @param minStd physiological noise floor for this metric, in its own units — see [MinStd].
     */
    private fun computeBaseline(
        values: List<Double>,
        populationMean: Double,
        populationStd: Double,
        horizon: BaselineHorizon,
        minStd: Double = MinStd.GENERIC
    ): Baseline {
        val n = values.size
        val personalWeight = personalWeightForDays(n)

        val personalMean = if (n > 0) BaselineUtils.average(values) else populationMean
        val personalStd  = if (n >= 2) BaselineUtils.stdDev(values) else populationStd

        val blendedMean = BaselineUtils.blend(populationMean, personalMean, personalWeight)
        val blendedStd  = BaselineUtils.blend(populationStd, personalStd, personalWeight)
            .coerceAtLeast(minStd)   // never collapse std below this metric's noise floor

        return Baseline(
            horizon = horizon,
            mean = blendedMean,
            std = blendedStd,
            personalWeight = personalWeight,
            sampleSize = n
        )
    }

    /**
     * Personal weight schedule:
     *   n < 3:   0.0  (pure population default)
     *   n 3–6:   0.2–0.5 (linear)
     *   n 7–13:  0.5–0.8 (linear)
     *   n 14–29: 0.8–1.0 (linear)
     *   n ≥ 30:  1.0  (full personal)
     */
    internal fun personalWeightForDays(n: Int): Double = when {
        n < 3  -> 0.0
        n < 7  -> 0.2 + (n - 3) * (0.3 / 4.0)
        n < 14 -> 0.5 + (n - 7) * (0.3 / 7.0)
        n < 30 -> 0.8 + (n - 14) * (0.2 / 16.0)
        else   -> 1.0
    }
}
