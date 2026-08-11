package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.DailyActivityData
import com.example.vitalcoreai.data.model.HeartRatePoint
import com.example.vitalcoreai.data.model.HRZone
import com.example.vitalcoreai.data.model.RestingHRData

/**
 * Trend Calculators — rolling trend computations for all secondary metrics.
 *
 * Covers: resting HR trend, HR zones, stress, activity, consistency, lifestyle,
 * weekly and monthly health score rollups.
 *
 * All public functions return [ScoreResult] with the full A2 shape.
 * All private helpers are pure functions — no side effects, no Android deps.
 *
 * ## References
 * - Tudor-Locke C et al. (2011) Int J Behav Nutr Phys Act 8:79 (step targets)
 * - Buchheit M (2014) Front Physiol 5:62 (HR as stress proxy)
 */
object TrendCalculators {

    // ── Secondary data types ──────────────────────────────────────────────────

    data class HRTrend(
        val averageBpm: Float,
        val changeFromPrevPeriod: Float,    // positive = increased (worse for recovery)
        val trend: TrendDirection,
        val dayCount: Int
    )

    data class HRZonesSummary(
        val zone1Pct: Float,
        val zone2Pct: Float,
        val zone3Pct: Float,
        val zone4Pct: Float,
        val zone5Pct: Float,
        val dominantZone: HRZone
    )

    /**
     * [score] is nullable: a period with no underlying data has no health score, and
     * returning a literal 50 rendered a fabricated number that looked like a measurement.
     */
    data class PeriodHealthScore(
        val score: Float?,
        val deltaFromPreviousPeriod: Float?,
        val explanation: String,
        val highlights: List<String>
    )

    /** Population-norm daily step target — one definition, referenced everywhere. */
    const val DEFAULT_STEP_GOAL = 7500

    // ── Resting HR Trend ─────────────────────────────────────────────────────

    fun calculateRestingHRTrend(
        recent: List<RestingHRData>,
        previous: List<RestingHRData>
    ): HRTrend {
        val avgRecent = if (recent.isEmpty()) 0f else recent.map { it.bpm }.average().toFloat()
        val avgPrev   = if (previous.isEmpty()) avgRecent else previous.map { it.bpm }.average().toFloat()
        val change    = avgRecent - avgPrev
        return HRTrend(
            averageBpm = avgRecent,
            changeFromPrevPeriod = change,
            trend = when {
                change < -1.5f -> TrendDirection.DOWN    // lower RHR = improving
                change >  1.5f -> TrendDirection.UP
                else           -> TrendDirection.NEUTRAL
            },
            dayCount = recent.size
        )
    }

    // ── HR Zones Distribution ────────────────────────────────────────────────

    fun calculateHRZoneDistribution(
        hrPoints: List<HeartRatePoint>,
        maxHR: Int,
        restingHR: Int = 60
    ): HRZonesSummary {
        if (hrPoints.isEmpty()) return HRZonesSummary(0f, 0f, 0f, 0f, 0f, HRZone.BELOW_ZONE1)
        val zones = TrainingLoadCalculator.calculateZoneDistribution(hrPoints, maxHR, restingHR)
        val dominant = zones.maxByOrNull { it.value }?.key ?: HRZone.BELOW_ZONE1
        return HRZonesSummary(
            zone1Pct = (zones[HRZone.ZONE1] ?: 0f) * 100f,
            zone2Pct = (zones[HRZone.ZONE2] ?: 0f) * 100f,
            zone3Pct = (zones[HRZone.ZONE3] ?: 0f) * 100f,
            zone4Pct = (zones[HRZone.ZONE4] ?: 0f) * 100f,
            zone5Pct = (zones[HRZone.ZONE5] ?: 0f) * 100f,
            dominantZone = dominant
        )
    }

    // ── Stress Score ─────────────────────────────────────────────────────────
    // Derived from resting HR deviation from 30-day personal baseline.
    // Higher deviation = higher physiological stress. Pure HR-based proxy — no HRV.

    /**
     * ## Formula
     * z = (today_RHR − baseline_mean) / baseline_std
     * stressScore = 50 + (z × 20), clamped 0–100
     *
     * ## Confidence (A3)
     * HIGH when ≥ 21 days of HR history; MEDIUM for 7–20; LOW below 7.
     *
     * ## A6 — Root cause explanation
     * When [priorDayHR] is supplied, names the specific HR change.
     */
    fun calculateStressScore(
        todayRestingHR: Int,
        baselineHR30Days: List<RestingHRData>,
        priorDayHR: Int? = null,
        qualityInput: DataQualityEngine.QualityInput? = null
    ): ScoreResult {
        // Scored under the STRESS profile, which weights only the HR checks this metric
        // actually consumes. Under the old shared vector the maximum attainable score was
        // 45% — so a user with a year of perfect watch data could never see HIGH here.
        val effectiveQualityInput = qualityInput ?: DataQualityEngine.QualityInput(
            hasHRToday = true,
            hrHistoryDays = baselineHR30Days.size
        )
        val quality = DataQualityEngine.evaluate(
            effectiveQualityInput.copy(hasHRToday = true, hrHistoryDays = baselineHR30Days.size),
            DataQualityEngine.MetricProfile.STRESS
        )

        val baseline = BaselineManager.restingHRBaseline(baselineHR30Days.map { it.bpm.toDouble() })

        val z = if (baseline.std < 0.1) 0.0 else (todayRestingHR - baseline.mean) / baseline.std
        val stressScore = (50f + (z * 20f)).toFloat().coerceIn(0f, 100f)

        val level = when {
            stressScore < 30f -> "Low"
            stressScore < 60f -> "Moderate"
            stressScore < 80f -> "Elevated"
            else              -> "High"
        }

        // A6 delta
        val hrDelta = priorDayHR?.let {
            val diff = todayRestingHR - it
            when {
                diff > 2  -> "Resting HR ↑${diff} bpm vs yesterday"
                diff < -2 -> "Resting HR ↓${-diff} bpm vs yesterday"
                else      -> null
            }
        }

        val triggers = buildSet<CoachTrigger> {
            if (stressScore > 70f || (todayRestingHR - baseline.mean) > 8) add(CoachTrigger.HR_ELEVATED)
        }

        val explanation = buildString {
            append("$level physiological stress. Resting HR $todayRestingHR bpm vs ${baseline.mean.toInt()} bpm baseline.")
            hrDelta?.let { append(" $it.") }
        }

        return ScoreResult(
            score = stressScore,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = explanation,
            breakdown = listOf(
                ScoreFactor(
                    name = "HR Elevation",
                    contribution = 100f,
                    rawValue = "$todayRestingHR bpm (baseline ${baseline.mean.toInt()} bpm)",
                    score = stressScore,
                    description = "HR elevation above personal 30-day baseline as physiological stress proxy.",
                    delta = hrDelta
                )
            ),
            weights = mapOf("hrElevation" to 1.0f),
            trendDirection = when {
                stressScore < 35f -> TrendDirection.DOWN
                stressScore > 65f -> TrendDirection.UP
                else              -> TrendDirection.NEUTRAL
            },
            coachTriggers = triggers,
            dataQuality = quality
        )
    }

    // ── Activity Score ───────────────────────────────────────────────────────

    /**
     * ## Formula
     * Steps (60%) and **active** calories (40%) vs personal 30-day baseline.
     * When active calories are unavailable, steps take the full 100%.
     *
     * ## The calorie component used to be a constant
     * `caloriesBurned / 600 * 100` was fed *total* daily energy (BMR + activity, typically
     * 2000–2800 kcal), so the ratio was always well above 1 and clamped to 100 — for every
     * user, every day — while the breakdown card told them "Active Calories 100/100". The
     * activity score was therefore a pure step score with a fixed +40 offset, unable to
     * fall below 40 or rise above 100 on step count alone.
     *
     * The fix reads [DailyActivityData.activeCalories], which comes from
     * ActiveCaloriesBurnedRecord. If Samsung Health does not supply it the component is
     * dropped and its weight redistributed, rather than shipping a constant.
     */
    fun calculateActivityScore(
        today: DailyActivityData,
        last30: List<DailyActivityData>,
        priorDayActivity: DailyActivityData? = null,
        activeCalorieTarget: Int = DEFAULT_ACTIVE_CALORIE_TARGET,
        qualityInput: DataQualityEngine.QualityInput? = null
    ): ScoreResult {
        val effectiveQualityInput = (qualityInput ?: DataQualityEngine.QualityInput()).copy(
            hasStepsToday = today.steps > 0,
            activityHistoryDays = last30.size
        )
        val quality = DataQualityEngine.evaluate(
            effectiveQualityInput, DataQualityEngine.MetricProfile.ACTIVITY
        )

        val stepsBaseline = BaselineManager.stepsBaseline(last30.map { it.steps.toDouble() })
        val stepsScore = BaselineUtils.zScoreToScore(
            today.steps.toDouble(), stepsBaseline.mean, stepsBaseline.std, invertPolarity = false
        )

        val activeCalories = today.activeCalories
        val calScore = activeCalories?.let {
            (it / activeCalorieTarget.toFloat() * 100f).coerceIn(0f, 100f)
        }

        val total = if (calScore != null) {
            (stepsScore * 0.60f + calScore * 0.40f).coerceIn(0f, 100f)
        } else {
            stepsScore.coerceIn(0f, 100f)
        }

        // A6 deltas
        val stepDelta = priorDayActivity?.let {
            val diff = today.steps - it.steps
            when {
                diff >  500 -> "Steps up $diff vs yesterday"
                diff < -500 -> "Steps down ${-diff} vs yesterday"
                else        -> null
            }
        }

        val triggers = buildSet<CoachTrigger> {
            if (today.steps < 2000 && last30.size >= 3) add(CoachTrigger.NO_ACTIVITY_3_DAYS)
        }

        return ScoreResult(
            score = total,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = "${today.steps} steps today (30-day avg: ${stepsBaseline.mean.toInt()})." +
                    (stepDelta?.let { " $it." } ?: "") +
                    (if (calScore == null) " Active-calorie data is not available from this device." else ""),
            breakdown = listOfNotNull(
                ScoreFactor(
                    name = "Daily Steps",
                    contribution = if (calScore != null) 60f else 100f,
                    rawValue = "${today.steps}",
                    score = stepsScore,
                    description = "Steps vs personal 30-day average",
                    delta = stepDelta
                ),
                calScore?.let {
                    ScoreFactor(
                        name = "Active Calories",
                        contribution = 40f,
                        rawValue = "$activeCalories kcal",
                        score = it,
                        description = "Activity-only energy against a $activeCalorieTarget kcal target"
                    )
                }
            ),
            weights = if (calScore != null) mapOf("steps" to 0.60f, "calories" to 0.40f)
                      else mapOf("steps" to 1.0f),
            trendDirection = when {
                total > 65f -> TrendDirection.UP
                total < 40f -> TrendDirection.DOWN
                else        -> TrendDirection.NEUTRAL
            },
            coachTriggers = triggers,
            dataQuality = quality
        )
    }

    // ── Consistency Score ────────────────────────────────────────────────────

    /**
     * Share of *recorded* days that met the step goal.
     *
     * The denominator was a hardcoded 30 rather than the number of days actually
     * observed, so a brand-new user with five days of data — all of them highly active —
     * scored 5/30 = 16.7 and was told their consistency was poor. Since this feeds 40% of
     * the Lifestyle score, that was systematically wrong for the first month: exactly the
     * window in which a user decides whether to keep the app.
     *
     * Callers should also surface [daysCounted] so the coverage is visible rather than
     * silently compressed.
     */
    fun calculateConsistencyScore(
        recordedDays: List<DailyActivityData>,
        stepGoal: Int = DEFAULT_STEP_GOAL
    ): Float {
        if (recordedDays.isEmpty()) return 0f
        val activeDays = recordedDays.count { it.steps >= stepGoal }
        return (activeDays.toFloat() / recordedDays.size * 100f).coerceIn(0f, 100f)
    }

    // ── Lifestyle Score ──────────────────────────────────────────────────────

    /**
     * ## Formula
     * | Component          | Weight |
     * |--------------------|--------|
     * | Activity consistency | 40%  |
     * | Daily activity score | 35%  |
     * | Sleep score          | 25%  |
     */
    fun calculateLifestyleScore(
        consistencyScore: Float,
        activityScore: Float,
        sleepScore: Float
    ): ScoreResult {
        val total = (consistencyScore * 0.4f + activityScore * 0.35f + sleepScore * 0.25f)
            .coerceIn(0f, 100f)
        return ScoreResult(
            score = total,
            confidence = Confidence.HIGH,
            confidencePercent = 80,
            explanation = "Lifestyle score reflects activity consistency, daily activity level, and sleep quality.",
            breakdown = listOf(
                ScoreFactor("Activity Consistency", 40f, "${consistencyScore.toInt()}/100", consistencyScore, "How regularly you hit your step/activity goals"),
                ScoreFactor("Daily Activity",       35f, "${activityScore.toInt()}/100",    activityScore,    "Today's activity vs your personal average"),
                ScoreFactor("Sleep Quality",        25f, "${sleepScore.toInt()}/100",        sleepScore,       "Sleep score contribution")
            ).sortedByDescending { kotlin.math.abs(it.score - 50f) },
            weights = mapOf("consistency" to 0.40f, "activity" to 0.35f, "sleep" to 0.25f),
            trendDirection = when {
                total > 70f -> TrendDirection.UP
                total < 45f -> TrendDirection.DOWN
                else        -> TrendDirection.NEUTRAL
            },
            dataQuality = DataQualityReport(Confidence.HIGH, 80, listOf("Derived from sub-scores"), false)
        )
    }

    // ── Weekly & Monthly Health Score ────────────────────────────────────────

    /**
     * Averages are nullable: a week with no recovery data has no recovery average, and
     * substituting 50 fabricated a measurement. Present components are renormalised so
     * the composite is a genuine weighted mean of what was actually recorded.
     */
    fun calculateWeeklyHealthScore(
        weekRecovery: Float?,
        weekReadiness: Float?,
        weekSleep: Float?,
        weekActivity: Float?,
        previousWeekScore: Float?
    ): PeriodHealthScore {
        val components = listOfNotNull(
            weekRecovery?.let  { it to 0.30f },
            weekReadiness?.let { it to 0.25f },
            weekSleep?.let     { it to 0.25f },
            weekActivity?.let  { it to 0.20f }
        )
        if (components.isEmpty()) {
            return PeriodHealthScore(
                score = null,
                deltaFromPreviousPeriod = null,
                explanation = "Not enough data this week to compute a health score.",
                highlights = emptyList()
            )
        }
        val weightSum = components.sumOf { it.second.toDouble() }.toFloat()
        val score = (components.sumOf { (v, w) -> (v * w).toDouble() }.toFloat() / weightSum)
            .coerceIn(0f, 100f)
        val delta = previousWeekScore?.let { score - it }
        return PeriodHealthScore(
            score = score,
            deltaFromPreviousPeriod = delta,
            explanation = buildPeriodExplanation("week", score, delta,
                weekRecovery, weekSleep, weekActivity),
            highlights = buildWeekHighlights(weekRecovery, weekSleep, weekActivity)
        )
    }

    /** Returns a null score for an empty month rather than a fabricated 50. */
    fun calculateMonthlyHealthScore(
        weekScores: List<Float>,
        previousMonthScore: Float?
    ): PeriodHealthScore {
        if (weekScores.isEmpty()) {
            return PeriodHealthScore(
                score = null,
                deltaFromPreviousPeriod = null,
                explanation = "Not enough data this month to compute a health score.",
                highlights = emptyList()
            )
        }
        val score = weekScores.average().toFloat().coerceIn(0f, 100f)
        val delta = previousMonthScore?.let { score - it }
        return PeriodHealthScore(
            score = score,
            deltaFromPreviousPeriod = delta,
            explanation = buildPeriodExplanation("month", score, delta, null, null, null),
            highlights  = listOf("Average of ${weekScores.size} weeks of data.")
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildPeriodExplanation(
        period: String,
        score: Float,
        delta: Float?,
        recovery: Float?,
        sleep: Float?,
        activity: Float?
    ): String {
        val deltaText = when {
            delta == null   -> "no previous $period to compare against"
            delta > 3f      -> "up ${delta.toInt()} pts from last $period"
            delta < -3f     -> "down ${(-delta).toInt()} pts from last $period"
            else            -> "stable compared to last $period"
        }
        val details = buildString {
            if (recovery != null && recovery < 45f) append(" Recovery was below average.")
            if (sleep != null && sleep < 45f)       append(" Sleep quality needs attention.")
            if (activity != null && activity > 75f) append(" Strong activity levels.")
        }
        return "Your $period health score is ${score.toInt()} — $deltaText.$details"
    }

    private fun buildWeekHighlights(recovery: Float?, sleep: Float?, activity: Float?): List<String> {
        return buildList {
            if (recovery != null && recovery > 75f) add("Strong recovery this week.")
            if (recovery != null && recovery < 50f) add("Recovery below average — consider more rest.")
            if (sleep != null && sleep > 75f)       add("Excellent sleep quality this week.")
            if (sleep != null && sleep < 50f)       add("Sleep quality needs attention.")
            if (activity != null && activity > 75f) add("Consistently active week — great work.")
        }
    }

    /**
     * Default active-calorie target, roughly a 30-minute moderate session plus incidental
     * movement. Only ever compared against ActiveCaloriesBurnedRecord, never a daily total.
     */
    const val DEFAULT_ACTIVE_CALORIE_TARGET = 500
}
