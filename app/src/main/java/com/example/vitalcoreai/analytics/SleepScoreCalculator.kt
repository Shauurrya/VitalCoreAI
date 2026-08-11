package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.SleepData

/**
 * Sleep Score Calculator (0–100)
 *
 * ## Formula
 * | Component           | Weight | Basis                                         |
 * |---------------------|--------|-----------------------------------------------|
 * | Duration score      | 35%    | Total sleep vs personal sleep need            |
 * | Stage quality       | 30%    | Deep + REM fraction of total sleep time       |
 * | Consistency         | 20%    | Bedtime variance over last 7 sessions         |
 * | Sleep debt          | 15%    | 7-day cumulative deficit vs personal need     |
 *
 * ## Confidence (A3)
 * Computed from [DataQualityEngine] based on history depth and today's data presence.
 *
 * ## Root cause explanations (A6)
 * When [priorDaySleep] is supplied, the explanation includes specific deltas
 * (e.g., "Deep sleep ↓18 min", "Bedtime delayed 45 min").
 *
 * ## Limitations
 * - Stage data (deep/REM) requires a compatible wearable; absent on phone-only tracking.
 * - Consistency score requires ≥ 3 sessions; defaults to 60 (moderate) below this.
 *
 * ## References
 * - Hirshkowitz M et al. (2015) Sleep Health 1(1):6-19 (age-specific sleep need norms)
 * - Buman MP & King AC (2010) Am J Lifestyle Med 4(6):500-514 (stage quality targets)
 */
object SleepScoreCalculator {

    /** Healthy stage targets as fraction of total sleep (literature-derived). */
    private const val TARGET_DEEP_FRACTION = 0.15   // 15% deep sleep
    private const val TARGET_REM_FRACTION  = 0.22   // 22% REM sleep

    /** Component weights — declared once and used for both the composite and the factors. */
    private const val W_DURATION = 0.35f
    private const val W_STAGES = 0.30f
    private const val W_CONSISTENCY = 0.20f
    private const val W_DEBT = 0.15f

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param todaySleep                today's sleep session
     * @param last7Days                 7 most recent prior sleep sessions (empty if new user)
     * @param personalSleepNeedMinutes  user-configured target (default 480 = 8h)
     * @param priorDaySleep             yesterday's session — enables A6 delta explanations
     * @param qualityInput              pre-computed quality signals (optional override)
     */
    fun calculate(
        todaySleep: SleepData,
        last7Days: List<SleepData>,
        personalSleepNeedMinutes: Int = 480,
        priorDaySleep: SleepData? = null,
        qualityInput: DataQualityEngine.QualityInput? = null
    ): ScoreResult {

        // ── A3: Data quality ─────────────────────────────────────────────────
        val effectiveQualityInput = qualityInput ?: DataQualityEngine.QualityInput(
            hasSleepToday = true,
            sleepHistoryDays = last7Days.size,
            hrHistoryDays = 0
        )
        val quality = DataQualityEngine.evaluate(effectiveQualityInput)

        // ── Duration score (35%) ─────────────────────────────────────────────
        val durationScore = durationScoreFor(todaySleep.durationMinutes, personalSleepNeedMinutes)
        val durationDelta = priorDaySleep?.let { buildDurationDelta(todaySleep, it) }
        val durationFactor = ScoreFactor(
            name = "Sleep Duration",
            contribution = W_DURATION * 100f,
            rawValue = todaySleep.durationMinutes.toHoursMin(),
            score = durationScore,
            description = buildDurationDescription(todaySleep.durationMinutes, personalSleepNeedMinutes),
            delta = durationDelta
        )

        // ── Stage quality (30%) ──────────────────────────────────────────────
        val stageScore = calculateStageScore(todaySleep)
        val stageDelta = priorDaySleep?.let { buildStageDelta(todaySleep, it) }
        val stageFactor = ScoreFactor(
            name = "Sleep Stages",
            contribution = W_STAGES * 100f,
            rawValue = buildStageRawValue(todaySleep),
            score = stageScore,
            description = buildStageDescription(todaySleep),
            delta = stageDelta
        )

        // ── Consistency (20%) ────────────────────────────────────────────────
        val allSessions = last7Days + listOf(todaySleep)
        val consistencyScore = calculateConsistencyScore(allSessions)
        val consistencyFactor = ScoreFactor(
            name = "Sleep Consistency",
            contribution = W_CONSISTENCY * 100f,
            rawValue = "Bedtime variance: ${calcBedtimeVariance(allSessions)}min",
            score = consistencyScore,
            description = buildConsistencyDescription(consistencyScore)
        )

        // ── Sleep debt (15%) ─────────────────────────────────────────────────
        // Scored on the AVERAGE NIGHTLY deficit, not the raw cumulative total, so the
        // component stays responsive across the realistic range and is independent of the
        // window length. Banding on the cumulative figure meant 30 min short every night
        // and 4 h short every night both floored at the same value.
        val debtMinutes = calculateSleepDebt(last7Days, personalSleepNeedMinutes)
        val nightsCounted = last7Days.size
        val avgNightlyDeficit = if (nightsCounted > 0) debtMinutes / nightsCounted else 0
        val debtScore = debtScoreFor(avgNightlyDeficit)
        val debtFactor = ScoreFactor(
            name = "Sleep Debt",
            contribution = W_DEBT * 100f,
            rawValue = if (debtMinutes > 0)
                "${debtMinutes / 60}h ${debtMinutes % 60}m over $nightsCounted nights"
            else "No sleep debt",
            score = debtScore,
            description = buildDebtDescription(debtMinutes, nightsCounted, avgNightlyDeficit)
        )

        // ── Composite ────────────────────────────────────────────────────────
        val totalScore = (durationScore * W_DURATION + stageScore * W_STAGES +
                consistencyScore * W_CONSISTENCY + debtScore * W_DEBT).coerceIn(0f, 100f)

        // ── A6: Explanation ──────────────────────────────────────────────────
        val explanation = buildExplanation(totalScore, durationDelta, stageDelta, debtMinutes, nightsCounted)

        // ── Trend ────────────────────────────────────────────────────────────
        // last7Days is oldest-first by contract, so takeLast(3) really is the three most
        // recent prior nights and the slope over [.., today] is chronological.
        val trendHistory = (last7Days.takeLast(3) + listOf(todaySleep))
            .map { it.durationMinutes.toFloat() }
        val slope = BaselineUtils.linearSlope(trendHistory)
        val trendDirection = when {
            slope > 5f  -> TrendDirection.UP
            slope < -5f -> TrendDirection.DOWN
            else        -> TrendDirection.NEUTRAL
        }

        // ── Coach triggers ────────────────────────────────────────────────────
        val triggers = buildSet {
            val deficitMinutes = personalSleepNeedMinutes - todaySleep.durationMinutes
            if (deficitMinutes > 60) add(CoachTrigger.SLEEP_DEFICIT)
            if (totalScore > 85f)    add(CoachTrigger.SLEEP_EXCELLENT)
            if (calcBedtimeVariance(allSessions) > 90) add(CoachTrigger.SLEEP_DRIFTING)
        }

        // ── Breakdown sorted highest-deviation first ──────────────────────────
        val breakdown = listOf(durationFactor, stageFactor, consistencyFactor, debtFactor)
            .sortedByDescending { kotlin.math.abs(it.score - 50f) }

        return ScoreResult(
            score = totalScore,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = explanation,
            breakdown = breakdown,
            weights = mapOf(
                "duration" to W_DURATION, "stages" to W_STAGES,
                "consistency" to W_CONSISTENCY, "debt" to W_DEBT
            ),
            trendDirection = trendDirection,
            coachTriggers = triggers,
            dataQuality = quality
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Duration score as a continuous piecewise-linear curve of `duration / need`.
     *
     * Public and shared: [RecoveryScoreCalculator] calls this for the duration half of its
     * sleep component, so the Recovery and Sleep screens cannot disagree about what a good
     * night is.
     *
     * Anchors: 0.0 → 0, 0.7 → 50, 0.9 → 80, 1.0 → 100. The old bottom branch used a
     * magic `ratio * 71f` that was discontinuous with the branch above it.
     */
    fun durationScoreFor(durationMinutes: Int, needMinutes: Int): Float {
        if (needMinutes <= 0) return 50f
        val ratio = durationMinutes.toFloat() / needMinutes.toFloat()
        return when {
            ratio >= 1.0f -> 100f
            ratio >= 0.9f -> 80f + (ratio - 0.9f) * 200f    // 0.9→80 … 1.0→100
            ratio >= 0.7f -> 50f + (ratio - 0.7f) * 150f    // 0.7→50 … 0.9→80
            else          -> (ratio / 0.7f) * 50f           // 0.0→0  … 0.7→50
        }.coerceIn(0f, 100f)
    }

    /**
     * Sleep-debt score from the **average nightly** deficit in minutes.
     * 0 → 100, 30 → 80, 60 → 55, 90 → 35, 120+ → 15.
     */
    fun debtScoreFor(avgNightlyDeficitMinutes: Int): Float {
        val d = avgNightlyDeficitMinutes.coerceAtLeast(0)
        return when {
            d <= 0   -> 100f
            d <= 30  -> 100f - (d / 30f) * 20f          // 100 → 80
            d <= 60  -> 80f - ((d - 30) / 30f) * 25f    //  80 → 55
            d <= 90  -> 55f - ((d - 60) / 30f) * 20f    //  55 → 35
            d <= 120 -> 35f - ((d - 90) / 30f) * 20f    //  35 → 15
            else     -> 15f
        }.coerceIn(0f, 100f)
    }

    private fun calculateStageScore(sleep: SleepData): Float {
        val total = sleep.durationMinutes.toFloat()
        if (total <= 0f) return 50f
        val deepFraction = sleep.deepMinutes / total
        val remFraction  = sleep.remMinutes  / total
        val deepScore = (deepFraction / TARGET_DEEP_FRACTION.toFloat()).coerceIn(0f, 1f) * 50f
        val remScore  = (remFraction  / TARGET_REM_FRACTION.toFloat()).coerceIn(0f, 1f) * 50f
        return deepScore + remScore
    }

    /** Circular consistency — see [BaselineUtils.circularStdDevMinutes]. */
    private fun calculateConsistencyScore(sessions: List<SleepData>): Float {
        if (sessions.size < 3) return 60f
        val bedtimes = sessions.mapNotNull { it.bedtimeMinuteOfDay }
        if (bedtimes.size < 3) return 60f
        val variance = BaselineUtils.circularStdDevMinutes(bedtimes)
        return (100f - (variance / 120.0 * 100f)).toFloat().coerceIn(0f, 100f)
    }

    /**
     * Cumulative deficit across the supplied nights. No clamp: the raw total is reported
     * honestly and the *score* is derived from the per-night average by [debtScoreFor],
     * so a long window can no longer saturate the component at its floor.
     */
    fun calculateSleepDebt(nights: List<SleepData>, needMinutes: Int): Int {
        if (nights.isEmpty()) return 0
        return nights.sumOf { maxOf(0, needMinutes - it.durationMinutes) }
    }

    private fun calcBedtimeVariance(sessions: List<SleepData>): Int {
        val bedtimes = sessions.mapNotNull { it.bedtimeMinuteOfDay }
        return BaselineUtils.circularStdDevMinutes(bedtimes).toInt()
    }

    // A6 delta helpers
    private fun buildDurationDelta(today: SleepData, prior: SleepData): String? {
        val diff = today.durationMinutes - prior.durationMinutes
        return when {
            diff > 10  -> "Sleep duration ↑${diff}min vs yesterday"
            diff < -10 -> "Sleep duration ↓${-diff}min vs yesterday"
            else       -> null
        }
    }

    private fun buildStageDelta(today: SleepData, prior: SleepData): String? {
        val deepDiff = today.deepMinutes - prior.deepMinutes
        val remDiff  = today.remMinutes  - prior.remMinutes
        return when {
            deepDiff < -15 -> "Deep sleep ↓${-deepDiff}min vs yesterday"
            remDiff  < -15 -> "REM ↓${-remDiff}min vs yesterday"
            deepDiff >  15 -> "Deep sleep ↑${deepDiff}min vs yesterday"
            else           -> null
        }
    }

    private fun buildStageRawValue(sleep: SleepData): String {
        val total = sleep.durationMinutes.toFloat().coerceAtLeast(1f)
        val deepPct = (sleep.deepMinutes / total * 100).toInt()
        val remPct  = (sleep.remMinutes  / total * 100).toInt()
        return "Deep ${deepPct}% · REM ${remPct}%"
    }

    private fun buildDurationDescription(minutes: Int, need: Int): String {
        val diff = minutes - need
        return when {
            diff >= 0  -> "Met your ${need / 60}h sleep target."
            diff >= -30 -> "${(-diff)}min below your target — close to ideal."
            else        -> "${(-diff)}min below your ${need / 60}h target. Recovery may be impaired."
        }
    }

    private fun buildStageDescription(sleep: SleepData): String {
        val total = sleep.durationMinutes.toFloat().coerceAtLeast(1f)
        val deepPct = sleep.deepMinutes / total
        return when {
            deepPct >= TARGET_DEEP_FRACTION.toFloat() -> "Deep and REM sleep proportions are healthy."
            deepPct >= 0.10f -> "Deep sleep slightly below ideal — normal variation."
            else -> "Low deep sleep fraction — may affect physical recovery."
        }
    }

    private fun buildConsistencyDescription(score: Float): String = when {
        score > 80 -> "Consistent bedtime routine — your circadian rhythm is stable."
        score > 60 -> "Moderate consistency. Try to keep bedtime within 30 minutes each night."
        else       -> "Irregular bedtime is disrupting your sleep architecture."
    }

    /** Always states the window, so a cumulative figure never reads as one night's shortfall. */
    private fun buildDebtDescription(debtMinutes: Int, nights: Int, avgNightly: Int): String = when {
        nights == 0        -> "No prior nights recorded yet — debt will appear as history builds."
        debtMinutes == 0   -> "No cumulative sleep debt over the past $nights nights."
        avgNightly <= 30   -> "${debtMinutes / 60}h ${debtMinutes % 60}m over $nights nights — averaging ${avgNightly}min short per night. Catch up gradually."
        else               -> "${debtMinutes / 60}h ${debtMinutes % 60}m over $nights nights — averaging ${avgNightly}min short per night. Prioritise earlier bedtimes."
    }

    private fun buildExplanation(
        total: Float,
        durationDelta: String?,
        stageDelta: String?,
        debtMinutes: Int,
        nights: Int
    ): String {
        val drivers = listOfNotNull(durationDelta, stageDelta)
        val driversText = if (drivers.isNotEmpty()) " ${drivers.joinToString("; ")}." else ""
        val debtText = if (debtMinutes > 60 && nights > 0)
            " Sleep debt over the past $nights nights: ${debtMinutes / 60}h ${debtMinutes % 60}m." else ""
        return "Sleep score: ${total.toInt()}/100.$driversText$debtText"
    }

    private fun Int.toHoursMin(): String = "${this / 60}h ${this % 60}m"
}
