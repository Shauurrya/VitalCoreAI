package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.model.SleepData
import com.example.vitalcoreai.data.model.TrainingLoadData

/**
 * Recovery Score Calculator (0–100)
 *
 * ## Formula
 * Weighted composite of four physiological components:
 *
 * | Component           | Default Weight | Basis                                    |
 * |---------------------|----------------|------------------------------------------|
 * | Sleep quality       | 35%            | 60% duration vs personal need + 40% efficiency vs 14-day baseline |
 * | Resting heart rate  | 30%            | RHR vs 30-day personal baseline (z-score) |
 * | Sleep consistency   | 20%            | Circular bedtime variance over 14 days   |
 * | Training recovery   | 15%            | Prior-day normalised training load       |
 *
 * Optional modifier: SpO₂ ±5 pts (Watch Active 2 only; Fit 3 always null).
 *
 * ## Sleep duration is inside the score
 * The 35% component used to read efficiency *only* — duration was fetched purely to build
 * a display string. Four hours at 95% efficiency therefore scored higher on the largest
 * recovery component than eight hours at 88%, which is the most misleading behaviour a
 * recovery engine can have, and it silently disabled every sleep-duration suggestion in
 * [ImprovementSimulator] (the nudged score was bit-identical, so the delta was always 0).
 * The duration half reuses [SleepScoreCalculator.durationScoreFor] so the Recovery and
 * Sleep screens cannot disagree about what a good night is.
 *
 * ## Absent efficiency redistributes rather than substitutes
 * When Samsung Health supplies no stage detail, [SleepData.efficiencyPercent] is null and
 * the sleep component becomes duration-only at full weight. Substituting a constant (the
 * old `?: 80.0`) would be z-scored against the personal baseline and thereby become a
 * signal in its own right.
 *
 * ## Confidence
 * Computed by [DataQualityEngine] from actual data completeness.
 *
 * ## Root-cause explanations (A6)
 * When a prior-day score is supplied, the explanation names specific metric deltas
 * (e.g., "Resting HR ↑8 bpm", "Sleep ↓42 min") rather than stating the score dropped.
 *
 * ## Limitations
 * - HRV is excluded by design (not available from Galaxy Watch Active 2 / Fit 3 via Health Connect).
 * - SpO₂ modifier is bounded to ±5 pts to avoid over-weighting a single optional sensor.
 *
 * ## References
 * - Fullagar HHK et al. (2015) Sleep & Athletic Performance. Sports Med 45(2):161-186.
 * - Buchheit M (2014) Monitoring training status with HR measures. Front Physiol 5:62.
 */
object RecoveryScoreCalculator {

    /** Component weight configuration — all weights must sum to 1.0. */
    data class Weights(
        val sleepQuality: Float     = 0.35f,
        val restingHR: Float        = 0.30f,
        val sleepConsistency: Float = 0.20f,
        val trainingLoad: Float     = 0.15f
    ) {
        init {
            val sum = sleepQuality + restingHR + sleepConsistency + trainingLoad
            require(kotlin.math.abs(sum - 1.0f) < 0.001f) {
                "Recovery weights must sum to 1.0 but summed to $sum"
            }
        }
    }

    /** Split of the sleep-quality component between duration and efficiency. */
    private const val DURATION_SHARE = 0.60f
    private const val EFFICIENCY_SHARE = 0.40f

    /**
     * Calculate recovery score.
     *
     * @param todaySleep                today's sleep session
     * @param sleepBaseline14Days       14 most recent sleep sessions (for baseline)
     * @param todayRestingHR            today's resting HR measurement
     * @param restingHRBaseline30Days   30 most recent resting HR readings
     * @param priorDayTrainingLoad      yesterday's training session (null if rest day)
     * @param spO2Percent               blood oxygen % — Watch Active 2 only, null otherwise
     * @param hrv                       extension point — always null currently (not available from HC)
     * @param weights                   weight configuration (use default for standard calculation)
     * @param priorDayScore             yesterday's recovery score — enables A6 delta explanations
     * @param qualityInput              pre-computed quality signals from DataQualityEngine
     */
    fun calculate(
        todaySleep: SleepData?,
        sleepBaseline14Days: List<SleepData>,
        todayRestingHR: RestingHRData,
        restingHRBaseline30Days: List<RestingHRData>,
        priorDayTrainingLoad: TrainingLoadData?,
        spO2Percent: Float? = null,
        @Suppress("UNUSED_PARAMETER") hrv: Double? = null,
        weights: Weights = Weights(),
        priorDayScore: Float? = null,
        qualityInput: DataQualityEngine.QualityInput? = null,
        personalSleepNeedMinutes: Int = 480,
        /** Number of SpO₂ readings behind [spO2Percent]; below 3 the modifier is skipped. */
        spO2ReadingCount: Int = 0
    ): ScoreResult {

        // ── A3: Data quality ─────────────────────────────────────────────────
        val effectiveQualityInput = qualityInput ?: DataQualityEngine.QualityInput(
            hasSleepToday = todaySleep != null,
            hasHRToday = true,
            sleepHistoryDays = sleepBaseline14Days.size,
            hrHistoryDays = restingHRBaseline30Days.size
        )
        val quality = DataQualityEngine.evaluate(effectiveQualityInput)

        // ── A5: Blended baselines ────────────────────────────────────────────
        // mapNotNull, not a fabricated default: nights with no stage detail must not
        // enter the efficiency baseline at all.
        val sleepEffBaseline = BaselineManager.sleepEfficiencyBaseline(
            sleepBaseline14Days.mapNotNull { it.efficiencyPercent }
        )
        val hrBaseline = BaselineManager.restingHRBaseline(
            restingHRBaseline30Days.map { it.bpm.toDouble() }
        )

        // ── Sleep Quality (35%) — duration 60% + efficiency 40% ──────────────
        // When sleep is absent (Samsung Health hasn’t synced it yet), the 35% sleep
        // quality and 20% sleep consistency components are dropped, and the remaining
        // HR (30%) + training (15%) weight is renormalized to fill 100%. The score is
        // flagged LOW confidence so the UI can present it honestly.
        val sleepFactor: ScoreFactor?
        val sleepScore: Float
        val sleepDelta: String?
        val consistencyFactor: ScoreFactor?
        val consistencyScore: Float

        if (todaySleep != null) {
            val durationScore = SleepScoreCalculator.durationScoreFor(
                todaySleep.durationMinutes, personalSleepNeedMinutes
            )
            val efficiency = todaySleep.efficiencyPercent
            val efficiencyScore = efficiency?.let {
                BaselineUtils.zScoreToScore(it, sleepEffBaseline.mean, sleepEffBaseline.std, invertPolarity = false)
            }
            sleepScore = if (efficiencyScore != null) {
                durationScore * DURATION_SHARE + efficiencyScore * EFFICIENCY_SHARE
            } else {
                durationScore
            }
            sleepDelta = buildSleepDelta(todaySleep, sleepBaseline14Days)
            sleepFactor = ScoreFactor(
                name = "Sleep Quality",
                contribution = weights.sleepQuality * 100f,
                rawValue = if (efficiency != null)
                    "${todaySleep.durationMinutes.toHoursMin()}, ${efficiency.toInt()}% efficiency"
                else
                    "${todaySleep.durationMinutes.toHoursMin()}, efficiency unavailable",
                score = sleepScore,
                description = buildSleepDescription(
                    todaySleep.durationMinutes, personalSleepNeedMinutes, efficiency, sleepEffBaseline.mean
                ),
                delta = sleepDelta
            )
            // Sleep Consistency (20%)
            val allSleepSessions = sleepBaseline14Days + listOf(todaySleep)
            consistencyScore = calculateSleepConsistency(allSleepSessions)
            val bedtimeVariance = calcBedtimeVarianceMinutes(sleepBaseline14Days)
            consistencyFactor = ScoreFactor(
                name = "Sleep Consistency",
                contribution = weights.sleepConsistency * 100f,
                rawValue = "Bedtime variance: ${bedtimeVariance}min",
                score = consistencyScore,
                description = buildConsistencyDescription(consistencyScore)
            )
        } else {
            // Sleep unavailable — drop sleep quality + consistency components.
            sleepScore = 0f   // not used in composite below when sleep == null
            sleepDelta = null
            sleepFactor = null
            consistencyScore = 0f
            consistencyFactor = null
        }

        // ── Resting HR (30%) ─────────────────────────────────────────────────
        val hrScore = BaselineUtils.zScoreToScore(
            todayRestingHR.bpm.toDouble(),
            hrBaseline.mean,
            hrBaseline.std,
            invertPolarity = true
        )
        val hrDiff = todayRestingHR.bpm - hrBaseline.mean
        val hrDeltaText = buildHRDelta(hrDiff)
        val hrFactor = ScoreFactor(
            name = "Resting Heart Rate",
            contribution = weights.restingHR * 100f,
            rawValue = "${todayRestingHR.bpm} bpm (baseline ${hrBaseline.mean.toInt()} bpm)",
            score = hrScore,
            description = buildHRDescription(todayRestingHR.bpm, hrBaseline.mean),
            delta = hrDeltaText
        )

        // ── Sleep Consistency (20%) ──────────────────────────────────────────
        // (computed above inside the todaySleep != null branch)

        // ── Training Recovery (15%) ──────────────────────────────────────────
        val trainingScore = priorDayTrainingLoad?.let {
            (100f - it.normalizedLoad * 100f).coerceIn(0f, 100f)
        } ?: 75f
        val trainingFactor = ScoreFactor(
            name = "Training Recovery",
            contribution = weights.trainingLoad * 100f,
            rawValue = priorDayTrainingLoad?.let { "Yesterday load: ${(it.normalizedLoad * 100).toInt()}%" }
                ?: "No workout yesterday",
            score = trainingScore,
            description = if (priorDayTrainingLoad == null) "No prior training load — full recovery assumed."
            else buildTrainingDescription(trainingScore)
        )

        // ── Composite ────────────────────────────────────────────────────────
        // When sleep is absent, renormalize HR + training weights to fill 100%.
        val totalScore: Float
        val spO2Note: String
        if (todaySleep != null) {
            var raw = (sleepScore * weights.sleepQuality) +
                    (hrScore * weights.restingHR) +
                    (consistencyScore * weights.sleepConsistency) +
                    (trainingScore * weights.trainingLoad)
            if (spO2Percent != null && spO2ReadingCount >= MIN_SPO2_READINGS) {
                val mod = when {
                    spO2Percent >= 98f ->  3f
                    spO2Percent >= 95f ->  0f
                    spO2Percent >= 90f -> -3f
                    else               -> -5f
                }
                raw += mod
                spO2Note = " SpO₂ ${spO2Percent.toInt()}% (${if (mod >= 0) "+" else ""}${mod.toInt()} pts)."
            } else {
                spO2Note = ""
            }
            totalScore = raw.coerceIn(0f, 100f)
        } else {
            // Only HR (30%) + training (15%) available — renormalize to 100%.
            val hrWeight    = weights.restingHR    / (weights.restingHR + weights.trainingLoad)
            val trainWeight = weights.trainingLoad / (weights.restingHR + weights.trainingLoad)
            totalScore = (hrScore * hrWeight + trainingScore * trainWeight).coerceIn(0f, 100f)
            spO2Note = ""
        }

        // ── A6: Root-cause explanation ───────────────────────────────────────
        val explanation = buildExplanation(
            totalScore, priorDayScore,
            hrDiff, hrDeltaText, sleepDelta,
            consistencyScore, spO2Note,
            quality,
            sleepAvailable = todaySleep != null
        )

        // ── Trend direction ───────────────────────────────────────────────────
        val trendDirection = when {
            priorDayScore != null && totalScore > priorDayScore + 5f -> TrendDirection.UP
            priorDayScore != null && totalScore < priorDayScore - 5f -> TrendDirection.DOWN
            else                                                       -> TrendDirection.NEUTRAL
        }

        // ── Coach triggers ────────────────────────────────────────────────────
        val bedtimeVarianceForTrigger = if (todaySleep != null) calcBedtimeVarianceMinutes(sleepBaseline14Days) else 0
        val triggers = buildSet {
            if (totalScore < 40f) add(CoachTrigger.RECOVERY_LOW)
            if (totalScore > 85f) add(CoachTrigger.RECOVERY_EXCELLENT)
            if (hrDiff > 8)       add(CoachTrigger.HR_ELEVATED)
            if (bedtimeVarianceForTrigger > 90) add(CoachTrigger.SLEEP_DRIFTING)
        }

        // ── Historical comparison ─────────────────────────────────────────────
        val historicalComparison = priorDayScore?.let {
            HistoricalComparison(
                yesterdayScore = it,
                deltaFromYesterday = totalScore - it
            )
        }

        // ── Breakdown ranked highest-impact first ────────────────────────────
        val breakdown = listOfNotNull(sleepFactor, hrFactor, consistencyFactor, trainingFactor)
            .sortedByDescending { kotlin.math.abs(it.score - 50f) }   // highest deviation first

        return ScoreResult(
            score = totalScore,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = explanation,
            breakdown = breakdown,
            weights = mapOf(
                "sleepQuality"     to weights.sleepQuality,
                "restingHR"        to weights.restingHR,
                "sleepConsistency" to weights.sleepConsistency,
                "trainingLoad"     to weights.trainingLoad
            ),
            historicalComparison = historicalComparison,
            trendDirection = trendDirection,
            coachTriggers = triggers,
            dataQuality = quality
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Bedtime consistency on the **circular** clock scale — see
     * [BaselineUtils.circularStdDevMinutes]. A linear SD scores 23:50 and 00:10 as 1420
     * minutes apart, so a consistent sleeper drifting either side of midnight was handed a
     * catastrophic consistency score for a 20-minute spread.
     */
    private fun calculateSleepConsistency(sessions: List<SleepData>): Float {
        if (sessions.size < 3) return 60f
        val bedtimes = sessions.mapNotNull { it.bedtimeMinuteOfDay }
        if (bedtimes.size < 3) return 60f
        val variance = BaselineUtils.circularStdDevMinutes(bedtimes)
        return (100f - (variance / 120.0 * 100f)).toFloat().coerceIn(0f, 100f)
    }

    private fun calcBedtimeVarianceMinutes(sessions: List<SleepData>): Int {
        val bedtimes = sessions.mapNotNull { it.bedtimeMinuteOfDay }
        return BaselineUtils.circularStdDevMinutes(bedtimes).toInt()
    }

    /** A6: named delta for sleep vs. recent baseline. */
    private fun buildSleepDelta(today: SleepData, baseline: List<SleepData>): String? {
        if (baseline.isEmpty()) return null
        val avgDuration = baseline.map { it.durationMinutes }.average()
        val diffMin = today.durationMinutes - avgDuration.toInt()
        return when {
            diffMin > 10  -> "Sleep ↑${diffMin}min vs 14-day avg"
            diffMin < -10 -> "Sleep ↓${-diffMin}min vs 14-day avg"
            else          -> null
        }
    }

    /** A6: named delta for resting HR. */
    private fun buildHRDelta(hrDiff: Double): String? = when {
        hrDiff > 2.0  -> "Resting HR ↑${hrDiff.toInt()} bpm vs baseline"
        hrDiff < -2.0 -> "Resting HR ↓${(-hrDiff).toInt()} bpm vs baseline"
        else          -> null
    }

    private fun buildSleepDescription(
        durationMinutes: Int,
        needMinutes: Int,
        efficiency: Double?,
        efficiencyBaseline: Double
    ): String {
        val durationDeficit = needMinutes - durationMinutes
        val durationText = when {
            durationDeficit <= 0 -> "Met your ${needMinutes / 60}h sleep target."
            durationDeficit <= 30 -> "${durationDeficit}min below your target — close to ideal."
            else -> "${durationDeficit}min below your ${needMinutes / 60}h target."
        }
        if (efficiency == null) {
            return "$durationText Stage detail was not provided for this night, so the score " +
                "is based on duration alone."
        }
        val diff = efficiency - efficiencyBaseline
        val efficiencyText = when {
            diff > 5  -> "Sleep efficiency above your 14-day average — strong recovery signal."
            diff < -5 -> "Sleep efficiency below your 14-day average — recovery potential reduced."
            else      -> "Sleep efficiency within your normal range."
        }
        return "$durationText $efficiencyText"
    }

    private fun buildHRDescription(today: Int, baselineMean: Double): String {
        val diff = today - baselineMean
        return when {
            diff > 5  -> "Resting HR is ${diff.toInt()} bpm above your 30-day baseline — may indicate stress or under-recovery."
            diff < -5 -> "Resting HR is ${(-diff).toInt()} bpm below your 30-day baseline — a positive cardiovascular sign."
            else      -> "Resting HR within your normal range."
        }
    }

    private fun buildConsistencyDescription(score: Float): String = when {
        score > 80 -> "Consistent sleep schedule — your body clock is well-regulated."
        score > 60 -> "Moderate consistency. Try to keep bedtime within 30 minutes each night."
        else       -> "Irregular sleep schedule is reducing recovery quality."
    }

    private fun buildTrainingDescription(score: Float): String = when {
        score > 80 -> "Light training load yesterday — well-rested from exertion."
        score > 60 -> "Moderate training load. Another recovery day is beneficial."
        else       -> "Heavy training load yesterday is still taxing recovery systems."
    }

    /** A6: explanation names specific drivers when score changes. */
    private fun buildExplanation(
        total: Float,
        priorScore: Float?,
        hrDiff: Double,
        hrDeltaText: String?,
        sleepDeltaText: String?,
        consistencyScore: Float,
        spO2Note: String,
        quality: DataQualityReport,
        sleepAvailable: Boolean = true
    ): String {
        val scoreText = "Recovery score: ${total.toInt()}/100."

        // Named drivers (A6)
        val drivers = listOfNotNull(hrDeltaText, sleepDeltaText)

        return if (priorScore != null && kotlin.math.abs(total - priorScore) >= 5f) {
            val direction = if (total > priorScore) "improved" else "decreased"
            val driversText = if (drivers.isNotEmpty()) " Drivers: ${drivers.joinToString("; ")}." else ""
            "$scoreText Recovery $direction ${kotlin.math.abs(total - priorScore).toInt()} pts vs yesterday.$driversText$spO2Note"
        } else {
            val hrText = hrDeltaText ?: "Resting HR within normal range"
            if (!sleepAvailable) {
                "$scoreText $hrText. Sleep data not yet synced — score based on heart rate only.$spO2Note"
            } else {
                "$scoreText $hrText. Sleep consistency scored ${consistencyScore.toInt()}/100.$spO2Note"
            }
        }
    }

    private fun Int.toHoursMin(): String = "${this / 60}h ${this % 60}m"

    /** Minimum overnight SpO₂ samples before the ±5 pt modifier is applied at all. */
    private const val MIN_SPO2_READINGS = 3
}
