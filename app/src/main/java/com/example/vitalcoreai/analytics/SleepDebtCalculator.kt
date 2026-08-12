package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.SleepData
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Part 10 — Sleep Need & Sleep Debt.
 *
 * CLASSIFICATION: Heuristic. The individual components lean on published sleep
 * research (adult sleep-need range, the partial-repayment behaviour of recovery
 * sleep), but the specific weights and decay constant here are not a validated
 * clinical instrument. Everything this produces must be presented as an estimate.
 *
 * Why this exists as its own engine: sleep debt was previously computed inline in
 * the repository by summing every deficit across the 14-day baseline window, while
 * ReadinessScoreCalculator documents and thresholds its input as a SEVEN-day
 * cumulative deficit (60/120/180-minute breakpoints). Feeding a 14-day sum into
 * 7-day thresholds roughly doubled the debt and dragged readiness down for anyone
 * who was even slightly short on sleep.
 *
 * Three things this model does that a naive "8 hours minus what you slept" does not:
 *
 *  1. Sleep need is personal. A fixed 8h target mislabels a genuine 7h sleeper as
 *     permanently indebted. Once enough nights exist we estimate need from the
 *     user's own longer, unrestricted nights.
 *  2. Surplus sleep repays debt only partially. You cannot bank sleep 1:1 — one
 *     10-hour night does not erase two short ones.
 *  3. Older deficits matter less. A deficit from six nights ago is largely resolved;
 *     last night's is not. Recent nights are weighted more heavily.
 */
object SleepDebtCalculator {

    /** Population baseline sleep need for adults, in minutes. */
    const val POPULATION_SLEEP_NEED_MINUTES = 480          // 8h 00m

    /** Personal need is clamped to a physiologically sensible adult band. */
    const val MIN_PERSONAL_NEED_MINUTES = 390              // 6h 30m
    const val MAX_PERSONAL_NEED_MINUTES = 570              // 9h 30m

    /** Extra sleep need attributed to a heavy training day. */
    const val HIGH_LOAD_EXTRA_MINUTES = 20

    /** Normalized training load above which a day counts as high-load. */
    const val HIGH_LOAD_THRESHOLD = 0.7f

    /** The debt window. Must stay aligned with ReadinessScoreCalculator's thresholds. */
    const val DEBT_WINDOW_NIGHTS = 7

    /** Per-night-older multiplier applied to a deficit. 6 nights back ≈ 38% weight. */
    private const val RECENCY_DECAY = 0.85

    /** Fraction of surplus sleep that actually repays accumulated debt. */
    private const val SURPLUS_REPAYMENT_EFFICIENCY = 0.5

    /** Debt is capped so a long gap in data cannot produce an absurd figure. */
    const val MAX_DEBT_MINUTES = 900                       // 15h

    /** Nights of history needed before we trust a personal need estimate at all. */
    private const val MIN_NIGHTS_FOR_PERSONAL_NEED = 7

    enum class Trend { IMPROVING, STABLE, WORSENING }

    enum class Confidence { HIGH, MEDIUM, LOW }

    /**
     * @param sleepNeedMinutes        estimated personal need for a normal day
     * @param adjustedNeedMinutes     tonight's need including training-load and debt payback
     * @param lastNightMinutes        actual sleep, null when the night is missing
     * @param lastNightDeficitMinutes positive = short, negative = surplus
     * @param debtMinutes             recency-weighted 7-night cumulative debt (never negative)
     * @param trend                   direction of debt over the window
     * @param nightsUsed              how many real nights fed the calculation
     * @param needIsPersonal          true once need is derived from the user rather than population
     * @param explanation             user-facing sentence
     */
    data class SleepDebtResult(
        val sleepNeedMinutes: Int,
        val adjustedNeedMinutes: Int,
        val lastNightMinutes: Int?,
        val lastNightDeficitMinutes: Int?,
        val debtMinutes: Int,
        val trend: Trend,
        val nightsUsed: Int,
        val needIsPersonal: Boolean,
        val confidence: Confidence,
        val explanation: String
    ) {
        val debtHoursText: String
            get() = "${debtMinutes / 60}h ${debtMinutes % 60}m"
    }

    /**
     * @param recentSleep        recent nights, any order; only the most recent
     *                           [DEBT_WINDOW_NIGHTS] are counted toward debt, but the
     *                           full list informs the personal need estimate
     * @param highLoadDays       epoch days on which training load exceeded
     *                           [HIGH_LOAD_THRESHOLD]
     * @param todayIsHighLoad    whether TODAY is expected to be a heavy day
     */
    fun calculate(
        recentSleep: List<SleepData>,
        highLoadDays: Set<Long> = emptySet(),
        todayIsHighLoad: Boolean = false
    ): SleepDebtResult {
        // Guard against duplicate rows for one night, which would double-count a deficit.
        val nights = recentSleep
            .filter { it.durationMinutes > 0 }
            .groupBy { it.dateEpochDay }
            .map { (_, sameDay) -> sameDay.maxByOrNull { it.durationMinutes }!! }
            .sortedByDescending { it.dateEpochDay }

        if (nights.isEmpty()) {
            return SleepDebtResult(
                sleepNeedMinutes = POPULATION_SLEEP_NEED_MINUTES,
                adjustedNeedMinutes = POPULATION_SLEEP_NEED_MINUTES,
                lastNightMinutes = null,
                lastNightDeficitMinutes = null,
                debtMinutes = 0,
                trend = Trend.STABLE,
                nightsUsed = 0,
                needIsPersonal = false,
                confidence = Confidence.LOW,
                explanation = "No sleep data yet — sleep debt will appear once nights are recorded."
            )
        }

        val need = estimatePersonalNeed(nights)
        val window = nights.take(DEBT_WINDOW_NIGHTS)

        // Recency-weighted running balance. Index 0 is the most recent night.
        var balance = 0.0
        window.forEachIndexed { index, night ->
            val nightNeed = need + if (night.dateEpochDay in highLoadDays) HIGH_LOAD_EXTRA_MINUTES else 0
            val delta = nightNeed - night.durationMinutes      // >0 short, <0 surplus
            val weight = RECENCY_DECAY.pow(index)
            balance += if (delta >= 0) {
                delta * weight
            } else {
                // A surplus only partially repays — you cannot bank sleep 1:1.
                delta * weight * SURPLUS_REPAYMENT_EFFICIENCY
            }
        }
        val debt = balance.coerceIn(0.0, MAX_DEBT_MINUTES.toDouble()).roundToInt()

        val lastNight = window.first()
        val lastNightNeed = need + if (lastNight.dateEpochDay in highLoadDays) HIGH_LOAD_EXTRA_MINUTES else 0
        val lastNightDeficit = lastNightNeed - lastNight.durationMinutes

        // Tonight's target: normal need, plus a heavy-day bump, plus a capped slice of
        // the outstanding debt. Recommending someone repay 4 hours in one night would be
        // useless advice, so payback is limited to an hour.
        val adjustedNeed = (
            need +
                (if (todayIsHighLoad) HIGH_LOAD_EXTRA_MINUTES else 0) +
                (debt / 3).coerceAtMost(60)
            ).coerceAtMost(MAX_PERSONAL_NEED_MINUTES + HIGH_LOAD_EXTRA_MINUTES + 60)

        val trend = computeTrend(window, need, highLoadDays)

        val confidence = when {
            window.size >= 6 -> Confidence.HIGH
            window.size >= 3 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return SleepDebtResult(
            sleepNeedMinutes = need,
            adjustedNeedMinutes = adjustedNeed,
            lastNightMinutes = lastNight.durationMinutes,
            lastNightDeficitMinutes = lastNightDeficit,
            debtMinutes = debt,
            trend = trend,
            nightsUsed = window.size,
            needIsPersonal = nights.size >= MIN_NIGHTS_FOR_PERSONAL_NEED,
            confidence = confidence,
            explanation = buildExplanation(debt, lastNightDeficit, trend, need, nights.size)
        )
    }

    /**
     * Estimate how much sleep this person actually needs.
     *
     * On nights without an alarm or an early start, people tend to sleep close to their
     * true need — so the upper part of someone's own distribution is a better estimate
     * than a population constant. We take the 75th percentile of recent nights and blend
     * it toward the population figure according to how much history exists, reusing
     * [BaselineManager.personalWeightForDays] so the personalisation ramp is identical
     * everywhere in the app.
     */
    fun estimatePersonalNeed(nights: List<SleepData>): Int {
        if (nights.size < MIN_NIGHTS_FOR_PERSONAL_NEED) return POPULATION_SLEEP_NEED_MINUTES

        val durations = nights.map { it.durationMinutes }.sorted()
        // Nearest-rank 75th percentile; index is clamped so a short list cannot overflow.
        val index = ((durations.size * 0.75).toInt()).coerceIn(0, durations.size - 1)
        val personalEstimate = durations[index].toDouble()

        val weight = BaselineManager.personalWeightForDays(nights.size)
        val blended = POPULATION_SLEEP_NEED_MINUTES * (1 - weight) + personalEstimate * weight

        return blended.roundToInt().coerceIn(MIN_PERSONAL_NEED_MINUTES, MAX_PERSONAL_NEED_MINUTES)
    }

    /**
     * Compare the recent half of the window against the older half. Fewer than four
     * nights cannot support a direction, so those report STABLE rather than inventing one.
     */
    private fun computeTrend(
        window: List<SleepData>,
        need: Int,
        highLoadDays: Set<Long>
    ): Trend {
        if (window.size < 4) return Trend.STABLE

        fun avgDeficit(nights: List<SleepData>): Double = nights
            .map { night ->
                val n = need + if (night.dateEpochDay in highLoadDays) HIGH_LOAD_EXTRA_MINUTES else 0
                (n - night.durationMinutes).toDouble()
            }
            .average()

        val half = window.size / 2
        val recent = avgDeficit(window.take(half))          // most recent nights
        val older = avgDeficit(window.drop(half))
        val change = recent - older

        return when {
            change < -15 -> Trend.IMPROVING                 // deficit shrinking
            change > 15 -> Trend.WORSENING
            else -> Trend.STABLE
        }
    }

    private fun buildExplanation(
        debt: Int,
        lastNightDeficit: Int,
        trend: Trend,
        need: Int,
        nightsAvailable: Int
    ): String {
        val needText = "${need / 60}h ${need % 60}m"
        val sourceText = if (nightsAvailable >= MIN_NIGHTS_FOR_PERSONAL_NEED) {
            "your own sleep pattern"
        } else {
            "a typical adult baseline (still learning yours)"
        }

        val debtText = when {
            debt <= 15 -> "Essentially no sleep debt."
            debt < 60 -> "You're carrying about ${debt}m of sleep debt — a minor shortfall."
            debt < 180 -> "You're carrying ${debt / 60}h ${debt % 60}m of sleep debt."
            else -> "You're carrying ${debt / 60}h ${debt % 60}m of sleep debt, a substantial shortfall."
        }

        val nightText = when {
            lastNightDeficit <= -30 -> " Last night ran ${abs(lastNightDeficit)}m above your target."
            lastNightDeficit in -29..15 -> " Last night was close to your target."
            else -> " Last night fell ${lastNightDeficit}m short."
        }

        val trendText = when (trend) {
            Trend.IMPROVING -> " The trend is improving over the past week."
            Trend.WORSENING -> " The shortfall has been growing over the past week."
            Trend.STABLE -> ""
        }

        return "$debtText Estimated need is $needText, based on $sourceText.$nightText$trendText"
    }
}
