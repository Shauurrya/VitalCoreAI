package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY
// No android.* imports permitted in this package.

/**
 * Part 11 — Energy Bank Calculator
 *
 * Models "estimated available recovery capacity" as a 0-100 score.
 * Think of it as a battery: good sleep charges it, hard training drains it,
 * rest days let it recover, stress erodes it.
 *
 * Inputs (all optional — missing data uses neutral defaults):
 * - Sleep adequacy (duration vs personal need)
 * - Sleep consistency (bedtime variance)
 * - Rest day count over last 7 days
 * - Training load trend (acute vs chronic)
 * - Stress score (from RHR deviation)
 * - Soreness rating (from check-in)
 * - RHR deviation from baseline
 *
 * Output: ScoreResult with 0-100 score, contributing factors, and explanation.
 */
object EnergyBankCalculator {

    data class EnergyBankInput(
        val sleepDurationsLast7: List<Int>,          // sleep duration minutes, last 7 days
        val personalSleepNeedMinutes: Int = 480,     // 8h default
        val bedtimeMinutesOfDay: List<Int?> = emptyList(), // bedtimes for consistency calc
        val restDaysLast7: Int = 0,                  // days with no exercise
        val acwr: Float? = null,                     // Acute:Chronic Workload Ratio
        val stressScore: Float? = null,              // 0-100 stress score
        val sorenessRating: Int? = null,             // 1-10 from check-in
        val rhrDeviationBpm: Int? = null,            // bpm above/below baseline
        val recoveryScore: Float? = null,            // today's recovery score for cross-reference
        val trainingLoadNormalized: Float? = null     // today's training load
    )

    fun calculate(input: EnergyBankInput): ScoreResult {
        val factors = mutableListOf<ScoreFactor>()

        // 1. Sleep Adequacy (30% weight)
        val avgSleepMinutes = if (input.sleepDurationsLast7.isNotEmpty())
            input.sleepDurationsLast7.average() else input.personalSleepNeedMinutes.toDouble()
        val sleepRatio = (avgSleepMinutes / input.personalSleepNeedMinutes).coerceIn(0.5, 1.3)
        val sleepAdequacyScore = ((sleepRatio - 0.5) / 0.8 * 100).toFloat().coerceIn(0f, 100f)
        factors += ScoreFactor(
            name = "Sleep Adequacy",
            contribution = 30f,
            rawValue = "${avgSleepMinutes.toInt()} min avg",
            score = sleepAdequacyScore,
            description = "Average sleep vs ${input.personalSleepNeedMinutes / 60}h personal need",
            delta = if (avgSleepMinutes < input.personalSleepNeedMinutes)
                "deficit of ${((input.personalSleepNeedMinutes - avgSleepMinutes) / 60).toInt()}h"
            else null
        )

        // 2. Rest / Recovery Days (20% weight)
        val restDayScore = when {
            input.restDaysLast7 >= 3 -> 95f
            input.restDaysLast7 == 2 -> 80f
            input.restDaysLast7 == 1 -> 55f
            else -> 25f  // no rest days in a week = depleted
        }
        factors += ScoreFactor(
            name = "Rest Days",
            contribution = 20f,
            rawValue = "${input.restDaysLast7}/7 days",
            score = restDayScore,
            description = "Recovery days in the last week"
        )

        // 3. Training Load Balance (20% weight)
        val trainingScore = input.acwr?.let { acwr ->
            when {
                acwr in 0.8f..1.3f -> 85f  // optimal zone
                acwr < 0.6f -> 70f          // under-training (bank is full but not building fitness)
                acwr in 1.3f..1.5f -> 45f   // caution zone
                acwr > 1.5f -> 15f          // danger zone — bank is heavily depleted
                else -> 60f
            }
        } ?: 60f  // neutral if no data
        factors += ScoreFactor(
            name = "Training Balance",
            contribution = 20f,
            rawValue = input.acwr?.let { "ACWR %.2f".format(it) } ?: "—",
            score = trainingScore,
            description = "Acute-to-chronic workload balance"
        )

        // 4. Physiological Stress (15% weight)
        val stressImpactScore = input.stressScore?.let { stress ->
            // Stress score is 0-100 where higher = more stressed
            // Invert: low stress = high energy bank
            (100f - stress).coerceIn(0f, 100f)
        } ?: 60f
        factors += ScoreFactor(
            name = "Stress Level",
            contribution = 15f,
            rawValue = input.stressScore?.let { "${it.toInt()}/100" } ?: "—",
            score = stressImpactScore,
            description = "Physiological stress from RHR deviation"
        )

        // 5. Muscle Soreness (15% weight)
        val sorenessScore = input.sorenessRating?.let { rating ->
            // 1 = no soreness (good), 10 = extreme soreness (bad)
            ((10f - rating) / 9f * 100f).coerceIn(0f, 100f)
        } ?: 65f  // slightly above neutral if not reported
        factors += ScoreFactor(
            name = "Soreness",
            contribution = 15f,
            rawValue = input.sorenessRating?.let { "$it/10" } ?: "not reported",
            score = sorenessScore,
            description = "Self-reported muscle soreness"
        )

        // Weighted composite
        val totalScore = factors.sumOf { (it.contribution / 100.0) * it.score.toDouble() }
            .toFloat().coerceIn(0f, 100f)

        // Explanation
        val explanation = buildExplanation(totalScore, factors, input)

        // Confidence
        val dataPoints = listOfNotNull(
            input.sleepDurationsLast7.takeIf { it.isNotEmpty() },
            input.acwr,
            input.stressScore,
            input.sorenessRating
        ).size
        val confidence = when {
            dataPoints >= 3 -> Confidence.HIGH
            dataPoints >= 2 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return ScoreResult(
            score = totalScore,
            confidence = confidence,
            confidencePercent = (dataPoints * 25).coerceAtMost(100),
            explanation = explanation,
            breakdown = factors.sortedByDescending { it.contribution },
            dataQuality = DataQualityReport(
                level = confidence,
                confidencePercent = (dataPoints * 25).coerceAtMost(100),
                reasons = if (dataPoints < 3) listOf("More data sources needed for higher confidence") else emptyList(),
                insufficientData = dataPoints == 0
            )
        )
    }

    private fun buildExplanation(score: Float, factors: List<ScoreFactor>, input: EnergyBankInput): String {
        val level = when {
            score >= 80 -> "Your energy reserves are high"
            score >= 60 -> "Your energy bank is at a moderate level"
            score >= 40 -> "Your energy reserves are running low"
            else -> "Your energy bank is depleted"
        }

        val worst = factors.minByOrNull { it.score } ?: return level
        val worstNote = when (worst.name) {
            "Sleep Adequacy" -> "Sleep deficit is the main drain."
            "Rest Days" -> "You need more rest days."
            "Training Balance" -> "Training load is imbalanced."
            "Stress Level" -> "Elevated stress is depleting reserves."
            "Soreness" -> "Muscle soreness indicates incomplete recovery."
            else -> ""
        }

        val recommendation = when {
            score < 40 -> " Prioritize sleep and skip intense training today."
            score < 60 -> " Consider a lighter day with emphasis on recovery."
            score >= 80 -> " Great capacity for a quality training session."
            else -> ""
        }

        return "$level. $worstNote$recommendation"
    }
}
