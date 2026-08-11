package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * Acute:Chronic Workload Ratio (ACWR) Calculator
 *
 * ## Formula
 * ACWR = (7-day rolling mean of DAILY load) / (28-day rolling mean of DAILY load)
 *
 * ## The input must be a daily series, zero-filled
 * This calculator takes `List<Float>` of **daily** loads, oldest first, with rest days
 * present as `0f`. It previously took a list of exercise *sessions*, which broke it
 * completely: a user training 3×/week has ~12 sessions in 28 days, so `takeLast(7)` was
 * the last 7 *sessions* spanning perhaps 16 days, and the chronic mean averaged only
 * training days. Both numerator and denominator converged on "this user's typical session
 * intensity", pinning ACWR near 1.0 and OPTIMAL regardless of behaviour — while the one
 * thing ACWR exists to detect, a spike in *volume*, stayed invisible, because adding four
 * extra sessions in a week barely moves a ratio of per-session means.
 *
 * Gabbett defines acute as a 7-day rolling sum or mean of *daily* load and chronic as the
 * 28-day equivalent, with non-training days contributing zero.
 *
 * ## Zones
 * | ACWR Range | Zone           | Implication                      |
 * |-----------|----------------|----------------------------------|
 * | < 0.8     | Under-training | Fitness stimulus insufficient    |
 * | 0.8–1.3   | Optimal        | Ideal adaptation zone            |
 * | 1.3–1.5   | Caution        | Elevated injury risk             |
 * | > 1.5     | Danger         | High injury risk — reduce load   |
 *
 * ## Meaningfulness gate
 * Below [MIN_DAYS_FOR_MEANINGFUL] days of history the ratio is noise, and the zone is
 * [AcwrZone.INSUFFICIENT] rather than a fabricated OPTIMAL. The previous
 * `if (chronicAvg < 0.01f) 1.0f` fallback landed every user with no training history in
 * OPTIMAL, handing them 100/100 on the readiness ACWR component and printing "training
 * load is in the optimal zone" to someone who had never trained.
 *
 * ## References
 * - Gabbett TJ (2016) Br J Sports Med 50(5):273-280
 * - Hulin BT et al. (2016) Br J Sports Med 50(4):231-236
 */
object AcwrCalculator {

    /** Below this many days of daily history, ACWR is not reported as a zone. */
    const val MIN_DAYS_FOR_MEANINGFUL = 14

    data class AcwrResult(
        val acwr: Float?,
        val acuteLoad: Float,
        val chronicLoad: Float,
        val zone: AcwrZone,
        val explanation: String,
        val daysOfHistory: Int
    ) {
        /** False → render "Not enough history", never a zone label or a component score. */
        val isMeaningful: Boolean get() = zone != AcwrZone.INSUFFICIENT && acwr != null

        companion object {
            fun insufficient(daysOfHistory: Int) = AcwrResult(
                acwr = null,
                acuteLoad = 0f,
                chronicLoad = 0f,
                zone = AcwrZone.INSUFFICIENT,
                explanation = "Needs $MIN_DAYS_FOR_MEANINGFUL days of training history — " +
                    "$daysOfHistory recorded so far.",
                daysOfHistory = daysOfHistory
            )
        }
    }

    enum class AcwrZone(val label: String) {
        INSUFFICIENT("Not enough history"),
        UNDER_TRAINING("Under-training"),
        OPTIMAL("Optimal Zone"),
        CAUTION("Caution"),
        DANGER("Danger Zone")
    }

    /**
     * @param dailyLoads  **daily** load values, oldest first, rest days included as 0f.
     *                    Up to 28 entries; the last 7 form the acute window.
     * @param priorAcwr   yesterday's ACWR — enables spike-detection wording.
     */
    fun calculate(
        dailyLoads: List<Float>,
        priorAcwr: Float? = null
    ): AcwrResult {
        val days = dailyLoads.size
        if (days < MIN_DAYS_FOR_MEANINGFUL) return AcwrResult.insufficient(days)

        val chronic28 = dailyLoads.takeLast(28)
        val acute7 = dailyLoads.takeLast(7)

        val acuteAvg = acute7.average().toFloat()
        val chronicAvg = chronic28.average().toFloat()

        // A genuinely untrained user has a chronic load at or near zero. That is not an
        // "optimal" ratio, it is an undefined one.
        if (chronicAvg < 0.01f) {
            return AcwrResult(
                acwr = null,
                acuteLoad = acuteAvg,
                chronicLoad = chronicAvg,
                zone = AcwrZone.INSUFFICIENT,
                explanation = "No meaningful training load recorded over the last 28 days.",
                daysOfHistory = days
            )
        }

        val acwr = acuteAvg / chronicAvg
        val zone = when {
            acwr < 0.8f  -> AcwrZone.UNDER_TRAINING
            acwr <= 1.3f -> AcwrZone.OPTIMAL
            acwr <= 1.5f -> AcwrZone.CAUTION
            else         -> AcwrZone.DANGER
        }

        return AcwrResult(
            acwr = acwr,
            acuteLoad = acuteAvg,
            chronicLoad = chronicAvg,
            zone = zone,
            explanation = buildExplanation(acwr, zone, priorAcwr),
            daysOfHistory = days
        )
    }

    /**
     * Build the zero-filled daily series ACWR requires from per-day load totals.
     *
     * @param loadByDay  epoch day → summed load for that day (absent = rest day)
     * @param endDay     the day being scored (inclusive)
     * @param windowDays how many days back to cover
     */
    fun buildDailySeries(
        loadByDay: Map<Long, Float>,
        endDay: Long,
        windowDays: Int = 28
    ): List<Float> = ((endDay - windowDays + 1)..endDay).map { day -> loadByDay[day] ?: 0f }

    private fun buildExplanation(acwr: Float, zone: AcwrZone, priorAcwr: Float?): String {
        val acwrStr = format2(acwr)
        val spikeNote = if (priorAcwr != null && acwr > priorAcwr + 0.2f)
            " Training load increased ${format2(acwr - priorAcwr)} vs yesterday." else ""
        return when (zone) {
            AcwrZone.INSUFFICIENT ->
                "Not enough training history to compute a meaningful ratio."
            AcwrZone.UNDER_TRAINING ->
                "ACWR $acwrStr — training below your baseline. Consider a moderate session to maintain fitness.$spikeNote"
            AcwrZone.OPTIMAL ->
                "ACWR $acwrStr — in the optimal training zone. Workload is well-balanced relative to your baseline.$spikeNote"
            AcwrZone.CAUTION ->
                "ACWR $acwrStr — recent training elevated vs 28-day baseline. Consider easing up or adding a recovery day.$spikeNote"
            AcwrZone.DANGER ->
                "ACWR $acwrStr — workload spike detected. Ratios above 1.5 are linked to elevated injury risk. A rest day is recommended.$spikeNote"
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
