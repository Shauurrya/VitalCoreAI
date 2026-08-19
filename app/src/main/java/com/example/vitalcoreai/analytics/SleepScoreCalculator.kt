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
 * ## Absent stages redistribute rather than score zero
 * The stage component runs only when [SleepData.stagesAvailable] is true, and the
 * remaining weights are renormalised over the components that did run:
 *
 * | Component   | Stages present | Stages absent |
 * |-------------|----------------|---------------|
 * | Duration    | 0.35           | 0.50          |
 * | Stages      | 0.30           | dropped       |
 * | Consistency | 0.20           | 0.29          |
 * | Debt        | 0.15           | 0.21          |
 *
 * Previously the composite applied `W_STAGES` unconditionally. A source that writes a
 * sleep session with no stage breakdown — routine for naps, manual entries, phone-only
 * tracking, and any night whose stage detail syncs later than the session envelope —
 * produced `deepMinutes = remMinutes = 0`, so [calculateStageScore] returned a literal
 * `0.0f` and the night took a flat −30 penalty at unchanged confidence. Stage quality is
 * *unknown* on such a night, not bad. This matches the efficiency handling in
 * [RecoveryScoreCalculator], which had the same reasoning applied to it already.
 *
 * The dropped component is omitted from `breakdown` and from the published `weights` map
 * rather than reported as a component that scored zero.
 *
 * ## Confidence (A3)
 * Computed from [DataQualityEngine] under [DataQualityEngine.MetricProfile.SLEEP], so the
 * score is judged on the data it actually consumes. Under the default RECOVERY profile it
 * was being dragged down by resting-HR history, today's resting HR and intraday HR density
 * — 45% of the confidence weight coming from signals the sleep score never reads.
 *
 * ## Root cause explanations (A6)
 * When [priorDaySleep] is supplied, the explanation includes specific deltas
 * (e.g., "Deep sleep ↓18 min", "Bedtime delayed 45 min").
 *
 * ## Limitations
 * - Stage data (deep/REM) requires a source that writes it; handled by renormalisation above.
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
        val quality = DataQualityEngine.evaluate(effectiveQualityInput, DataQualityEngine.MetricProfile.SLEEP)

        // ── Which components can run at all ──────────────────────────────────
        //
        // Absent stages redistribute rather than score zero. [SleepData.stagesAvailable]
        // is false whenever the source wrote a session envelope with no stage breakdown —
        // routine for naps, manual entries, phone-only tracking, and any night where the
        // stage detail syncs later than the session itself. Computing (deep+rem)/duration
        // in that case yields a literal 0.0f, which the composite then applied at the full
        // 30% weight: a flat −30 penalty on an unknown, at unchanged confidence.
        //
        // This mirrors the efficiency handling in [RecoveryScoreCalculator] — see its
        // "Absent efficiency redistributes rather than substitutes" note. Stage quality is
        // unknown on such a night, not bad, and the surviving components carry the score.
        //
        // Weights are renormalised over whichever components actually ran, so the composite
        // stays on a 0–100 scale and every published contribution is truthful.
        val hasStages = todaySleep.stagesAvailable
        val survivingWeight = if (hasStages) 1f else 1f - W_STAGES
        val wDuration    = W_DURATION / survivingWeight
        val wStages      = if (hasStages) W_STAGES / survivingWeight else 0f
        val wConsistency = W_CONSISTENCY / survivingWeight
        val wDebt        = W_DEBT / survivingWeight

        // ── Duration score ───────────────────────────────────────────────────
        val durationScore = durationScoreFor(todaySleep.durationMinutes, personalSleepNeedMinutes)
        val durationDelta = priorDaySleep?.let { buildDurationDelta(todaySleep, it) }
        val durationFactor = ScoreFactor(
            name = "Sleep Duration",
            contribution = wDuration * 100f,
            rawValue = todaySleep.durationMinutes.toHoursMin(),
            score = durationScore,
            description = buildDurationDescription(todaySleep.durationMinutes, personalSleepNeedMinutes),
            delta = durationDelta
        )

        // ── Stage quality ────────────────────────────────────────────────────
        val stageScore = if (hasStages) calculateStageScore(todaySleep) else null
        val stageDelta = if (hasStages) priorDaySleep?.let { buildStageDelta(todaySleep, it) } else null

        val stageFactor = stageScore?.let {
            ScoreFactor(
                name = "Sleep Stages",
                contribution = wStages * 100f,
                rawValue = buildStageRawValue(todaySleep),
                score = it,
                description = buildStageDescription(todaySleep),
                delta = stageDelta
            )
        }

        // ── Consistency (20%) ────────────────────────────────────────────────
        val allSessions = last7Days + listOf(todaySleep)
        val consistencyScore = calculateConsistencyScore(allSessions)
        val consistencyFactor = ScoreFactor(
            name = "Sleep Consistency",
            contribution = wConsistency * 100f,
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
            contribution = wDebt * 100f,
            rawValue = if (debtMinutes > 0)
                "${debtMinutes / 60}h ${debtMinutes % 60}m over $nightsCounted nights"
            else "No sleep debt",
            score = debtScore,
            description = buildDebtDescription(debtMinutes, nightsCounted, avgNightlyDeficit)
        )

        // ── Composite ────────────────────────────────────────────────────────
        // Renormalised weights, so a night without stage detail is scored on what the
        // source actually reported rather than penalised for what it did not.
        val totalScore = (durationScore * wDuration +
                (stageScore ?: 0f) * wStages +
                consistencyScore * wConsistency +
                debtScore * wDebt).coerceIn(0f, 100f)

        // ── A6: Explanation ──────────────────────────────────────────────────
        val explanation = buildExplanation(
            totalScore, durationDelta, stageDelta, debtMinutes, nightsCounted, hasStages
        )

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
        // listOfNotNull, so a dropped component is absent from the breakdown rather than
        // rendering a fabricated 0 — the UI iterates this list directly.
        val breakdown = listOfNotNull(durationFactor, stageFactor, consistencyFactor, debtFactor)
            .sortedByDescending { kotlin.math.abs(it.score - 50f) }

        return ScoreResult(
            score = totalScore,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = explanation,
            breakdown = breakdown,
            // The EFFECTIVE weights, not the nominal ones: when stages are absent the
            // key is omitted entirely rather than published as a component worth 30%
            // that scored zero.
            weights = buildMap {
                put("duration", wDuration)
                if (hasStages) put("stages", wStages)
                put("consistency", wConsistency)
                put("debt", wDebt)
            },
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
        nights: Int,
        hasStages: Boolean
    ): String {
        val drivers = listOfNotNull(durationDelta, stageDelta)
        val driversText = if (drivers.isNotEmpty()) " ${drivers.joinToString("; ")}." else ""
        val debtText = if (debtMinutes > 60 && nights > 0)
            " Sleep debt over the past $nights nights: ${debtMinutes / 60}h ${debtMinutes % 60}m." else ""
        // Said plainly, as the Recovery engine does for absent efficiency: the score is
        // built from fewer components, which is different from having scored badly on one.
        val stagesText = if (!hasStages)
            " Stage detail was not recorded for this night, so the score is based on " +
                "duration, consistency and debt alone." else ""
        return "Sleep score: ${total.toInt()}/100.$driversText$debtText$stagesText"
    }

    private fun Int.toHoursMin(): String = "${this / 60}h ${this % 60}m"
}
