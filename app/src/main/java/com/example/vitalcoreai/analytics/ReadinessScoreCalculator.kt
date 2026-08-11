package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * Readiness Score Calculator (0–100)
 *
 * ## Formula
 * | Component           | Weight | Basis                                   |
 * |---------------------|--------|-----------------------------------------|
 * | Recovery score      | 55%    | Output of RecoveryScoreCalculator       |
 * | Sleep debt          | 30%    | 7-day cumulative deficit vs personal need |
 * | Training load trend | 15%    | ACWR zone (Optimal=100, Danger=30)      |
 *
 * ## Confidence (A3)
 * Inherits confidence from the upstream recovery score; also checks sleep debt data presence.
 *
 * ## Root cause explanations (A6)
 * When [priorDayReadiness] is provided, names specific drivers of change.
 *
 * ## Limitations
 * - Readiness is a second-order score built on recovery; it inherits recovery's data quality.
 * - ACWR component is optional — when training history is absent, a neutral 75 is used.
 *
 * ## References
 * - Gabbett TJ (2016) Br J Sports Med 50(5):273-280 (ACWR and injury risk)
 * - Fullagar HHK et al. (2015) Sleep & Athletic Performance. Sports Med 45(2):161-186.
 */
object ReadinessScoreCalculator {

    /**
     * @param recoveryScore             today's output from [RecoveryScoreCalculator]
     * @param sleepDebtMinutes          7-day cumulative sleep deficit (minutes)
     * @param acwrResult                training load ratio result from [AcwrCalculator]
     * @param recoveryQuality           data quality from the upstream recovery calculation (A3)
     * @param priorDayReadiness         yesterday's readiness score — enables A6 delta explanations
     */
    /**
     * @param nightsCounted how many nights [sleepDebtMinutes] accumulated over. Used to
     *                      normalise to a per-night deficit — banding on the raw
     *                      cumulative figure meant 30 min short every night (210) and
     *                      4 h short every night (1680) both floored at the same score,
     *                      so the 30% component was a near-constant for most real users.
     */
    fun calculate(
        recoveryScore: Float,
        sleepDebtMinutes: Int,
        acwrResult: AcwrCalculator.AcwrResult,
        recoveryQuality: DataQualityReport = DataQualityReport.UNKNOWN,
        priorDayReadiness: Float? = null,
        nightsCounted: Int = 7
    ): ScoreResult {

        // ── Sleep debt component (30%) — banded on the per-night average ──────
        val avgNightlyDeficit = if (nightsCounted > 0) sleepDebtMinutes / nightsCounted else 0
        val sleepDebtScore = SleepScoreCalculator.debtScoreFor(avgNightlyDeficit)

        // ── ACWR component (15%) ──────────────────────────────────────────────
        // When the ratio is not meaningful its weight is redistributed across the two
        // components that ARE measured, rather than scoring a fabricated zone. The old
        // code handed a user who had never trained a 100/100 OPTIMAL.
        val acwrMeaningful = acwrResult.isMeaningful
        val acwrScore = when (acwrResult.zone) {
            AcwrCalculator.AcwrZone.OPTIMAL        -> 100f
            AcwrCalculator.AcwrZone.UNDER_TRAINING -> 75f
            AcwrCalculator.AcwrZone.CAUTION        -> 55f
            AcwrCalculator.AcwrZone.DANGER         -> 30f
            AcwrCalculator.AcwrZone.INSUFFICIENT   -> 0f     // never actually weighted
        }

        // ── Composite ────────────────────────────────────────────────────────
        val total = if (acwrMeaningful) {
            (recoveryScore * W_RECOVERY) + (sleepDebtScore * W_SLEEP_DEBT) + (acwrScore * W_ACWR)
        } else {
            // Rescale the surviving weights to sum to 1.0.
            val surviving = W_RECOVERY + W_SLEEP_DEBT
            (recoveryScore * (W_RECOVERY / surviving)) + (sleepDebtScore * (W_SLEEP_DEBT / surviving))
        }.coerceIn(0f, 100f)

        // ── Trend ────────────────────────────────────────────────────────────
        val trendDirection = when {
            priorDayReadiness != null && total > priorDayReadiness + 5f -> TrendDirection.UP
            priorDayReadiness != null && total < priorDayReadiness - 5f -> TrendDirection.DOWN
            total > 75f -> TrendDirection.UP
            total < 45f -> TrendDirection.DOWN
            else        -> TrendDirection.NEUTRAL
        }

        // ── Coach triggers ────────────────────────────────────────────────────
        val triggers = buildSet {
            if (total > 80f)                           add(CoachTrigger.READINESS_HIGH)
            if (acwrResult.zone == AcwrCalculator.AcwrZone.DANGER)  add(CoachTrigger.ACWR_DANGER)
            if (acwrResult.zone == AcwrCalculator.AcwrZone.UNDER_TRAINING) add(CoachTrigger.ACWR_UNDER_TRAINING)
        }

        // ── A6: Explanation ───────────────────────────────────────────────────
        val explanation = buildExplanation(
            total, priorDayReadiness, recoveryScore, sleepDebtMinutes, nightsCounted, acwrResult
        )

        // Effective weights, after any ACWR redistribution.
        val surviving = W_RECOVERY + W_SLEEP_DEBT
        val effRecovery = if (acwrMeaningful) W_RECOVERY else W_RECOVERY / surviving
        val effDebt = if (acwrMeaningful) W_SLEEP_DEBT else W_SLEEP_DEBT / surviving

        // ── Breakdown ranked by deviation ────────────────────────────────────
        val breakdown = listOfNotNull(
            ScoreFactor(
                name = "Recovery",
                contribution = effRecovery * 100f,
                rawValue = "${recoveryScore.toInt()}/100",
                score = recoveryScore,
                description = "Today's recovery score — primary readiness driver",
                delta = priorDayReadiness?.let {
                    "Readiness ${if (total > it) "up" else "down"} ${kotlin.math.abs(total - it).toInt()} vs yesterday"
                }
            ),
            ScoreFactor(
                name = "Sleep Debt",
                contribution = effDebt * 100f,
                rawValue = if (sleepDebtMinutes > 0)
                    "${sleepDebtMinutes / 60}h ${sleepDebtMinutes % 60}m over $nightsCounted nights"
                else "No deficit",
                score = sleepDebtScore,
                description = buildDebtDescription(sleepDebtMinutes, nightsCounted, avgNightlyDeficit)
            ),
            // Omitted entirely rather than shown with a fabricated score.
            if (acwrMeaningful) ScoreFactor(
                name = "Training Load Trend",
                contribution = W_ACWR * 100f,
                rawValue = "ACWR ${format2(acwrResult.acwr ?: 0f)} (${acwrResult.zone.name})",
                score = acwrScore,
                description = acwrResult.explanation
            ) else null
        ).sortedByDescending { kotlin.math.abs(it.score - 50f) }

        return ScoreResult(
            score = total,
            confidence = recoveryQuality.level,
            confidencePercent = recoveryQuality.confidencePercent,
            explanation = explanation,
            breakdown = breakdown,
            weights = mapOf(
                "recovery" to effRecovery,
                "sleepDebt" to effDebt,
                "acwr" to (if (acwrMeaningful) W_ACWR else 0f)
            ),
            trendDirection = trendDirection,
            coachTriggers = triggers,
            dataQuality = recoveryQuality
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private const val W_RECOVERY = 0.55f
    private const val W_SLEEP_DEBT = 0.30f
    private const val W_ACWR = 0.15f

    /** Always names the window so a cumulative total never reads as one night's shortfall. */
    private fun buildDebtDescription(debtMinutes: Int, nights: Int, avgNightly: Int): String = when {
        debtMinutes <= 0 -> "No accumulated sleep debt over the past $nights nights — fully rested."
        avgNightly <= 20 -> "${debtMinutes}min total over $nights nights (${avgNightly}min per night) — nearly fully recovered."
        avgNightly <= 60 -> "${debtMinutes / 60}h ${debtMinutes % 60}m over $nights nights (${avgNightly}min per night) is reducing readiness."
        else -> "${debtMinutes / 60}h ${debtMinutes % 60}m over $nights nights (${avgNightly}min per night) — prioritise an early night."
    }

    private fun buildExplanation(
        total: Float,
        priorDay: Float?,
        recovery: Float,
        debtMin: Int,
        nights: Int,
        acwr: AcwrCalculator.AcwrResult
    ): String = buildString {
        append("Readiness: ${total.toInt()}/100. ")
        if (priorDay != null && kotlin.math.abs(total - priorDay) >= 5f) {
            val dir = if (total > priorDay) "improved" else "decreased"
            append("Readiness $dir ${kotlin.math.abs(total - priorDay).toInt()} pts vs yesterday. ")
        }
        append("Recovery score of ${recovery.toInt()} is the primary driver. ")
        if (debtMin > 60 && nights > 0) {
            append("${debtMin / 60}h sleep debt over $nights nights is reducing readiness. ")
        }
        when (acwr.zone) {
            AcwrCalculator.AcwrZone.DANGER ->
                append("Training load spike (ACWR ${format2(acwr.acwr ?: 0f)}) — consider a rest day.")
            AcwrCalculator.AcwrZone.UNDER_TRAINING ->
                append("Training load below optimal — consider a moderate session.")
            AcwrCalculator.AcwrZone.OPTIMAL ->
                append("Training load is in the optimal zone.")
            AcwrCalculator.AcwrZone.CAUTION ->
                append("Recent training is elevated against your 28-day baseline.")
            AcwrCalculator.AcwrZone.INSUFFICIENT ->
                append("Training-load trend needs ${AcwrCalculator.MIN_DAYS_FOR_MEANINGFUL} days of history — not yet included.")
        }
    }

    /** Two-decimal formatting without String.format — this module takes no Locale. */
    private fun format2(value: Float): String {
        val rounded = kotlin.math.round(value * 100f).toInt()
        val whole = rounded / 100
        val frac = kotlin.math.abs(rounded % 100)
        return "$whole.${if (frac < 10) "0$frac" else "$frac"}"
    }
}
