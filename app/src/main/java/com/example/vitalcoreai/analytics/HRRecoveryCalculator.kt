package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY

/**
 * Part 7 — Heart Rate Recovery Calculator
 *
 * HRR (Heart Rate Recovery) measures how quickly the heart rate drops
 * after exercise cessation. It's a strong marker of cardiovascular fitness.
 *
 * - HRR1 = peak HR − HR at 1 minute post-exercise
 * - HRR2 = peak HR − HR at 2 minutes post-exercise
 *
 * Research benchmarks (Nishime et al., JAMA 2000):
 * - HRR1 < 12 bpm → abnormal (poor autonomic recovery)
 * - HRR1 12-25 bpm → normal
 * - HRR1 > 25 bpm → above average (good fitness)
 * - HRR1 > 40 bpm → excellent (very fit)
 *
 * Trend tracking: compare current HRR to personal 30-day baseline.
 * Improving HRR (getting larger) = improving cardiovascular fitness.
 */
object HRRecoveryCalculator {

    data class HRRecoveryResult(
        val hrr1: Int?,            // bpm drop after 1 min (null if insufficient data)
        val hrr2: Int?,            // bpm drop after 2 min
        val peakHR: Int,
        val oneMinHR: Int?,
        val twoMinHR: Int?,
        val assessment: String,    // "Excellent" | "Good" | "Normal" | "Below Normal" | "Insufficient data"
        val explanation: String,
        val percentile: Int?,      // estimated population percentile (null if insufficient data)
        val trend: TrendDirection  // vs personal baseline
    )

    /**
     * Calculate HRR from a list of post-workout HR samples.
     *
     * @param peakHR            max HR during the workout
     * @param postWorkoutSamples  list of (secondsAfterWorkoutEnd, bpm) sorted by time
     * @param historicalHrr1    past HRR1 values for trend analysis
     */
    fun calculate(
        peakHR: Int,
        postWorkoutSamples: List<Pair<Int, Int>>,  // (seconds after end, bpm)
        historicalHrr1: List<Int> = emptyList()
    ): HRRecoveryResult {
        if (postWorkoutSamples.isEmpty()) {
            return HRRecoveryResult(
                hrr1 = null, hrr2 = null,
                peakHR = peakHR,
                oneMinHR = null, twoMinHR = null,
                assessment = "Insufficient data",
                explanation = "No heart rate data available after workout to calculate recovery rate.",
                percentile = null,
                trend = TrendDirection.NEUTRAL
            )
        }

        // Find HR closest to 60 seconds post-workout
        val oneMinSample = postWorkoutSamples
            .minByOrNull { kotlin.math.abs(it.first - 60) }
            ?.takeIf { kotlin.math.abs(it.first - 60) < 30 }  // within 30s of the 1-min mark

        // Find HR closest to 120 seconds post-workout
        val twoMinSample = postWorkoutSamples
            .minByOrNull { kotlin.math.abs(it.first - 120) }
            ?.takeIf { kotlin.math.abs(it.first - 120) < 30 }

        val hrr1 = oneMinSample?.let { peakHR - it.second }
        val hrr2 = twoMinSample?.let { peakHR - it.second }

        val assessment = hrr1?.let { assessHrr1(it) } ?: "Insufficient data"
        val percentile = hrr1?.let { estimatePercentile(it) }

        // Trend: compare current HRR1 to rolling mean of historical
        val trend = if (hrr1 != null && historicalHrr1.size >= 3) {
            val baseline = historicalHrr1.average()
            when {
                hrr1 > baseline + 3 -> TrendDirection.UP       // improving
                hrr1 < baseline - 3 -> TrendDirection.DOWN     // declining
                else -> TrendDirection.NEUTRAL
            }
        } else TrendDirection.NEUTRAL

        val explanation = buildExplanation(hrr1, hrr2, peakHR, assessment, trend)

        return HRRecoveryResult(
            hrr1 = hrr1,
            hrr2 = hrr2,
            peakHR = peakHR,
            oneMinHR = oneMinSample?.second,
            twoMinHR = twoMinSample?.second,
            assessment = assessment,
            explanation = explanation,
            percentile = percentile,
            trend = trend
        )
    }

    private fun assessHrr1(hrr1: Int): String = when {
        hrr1 >= 40 -> "Excellent"
        hrr1 >= 25 -> "Good"
        hrr1 >= 12 -> "Normal"
        else -> "Below Normal"
    }

    private fun estimatePercentile(hrr1: Int): Int = when {
        hrr1 >= 50 -> 95
        hrr1 >= 40 -> 85
        hrr1 >= 30 -> 70
        hrr1 >= 25 -> 60
        hrr1 >= 20 -> 50
        hrr1 >= 15 -> 35
        hrr1 >= 12 -> 20
        else -> 10
    }

    private fun buildExplanation(
        hrr1: Int?,
        hrr2: Int?,
        peakHR: Int,
        assessment: String,
        trend: TrendDirection
    ): String = buildString {
        if (hrr1 == null) {
            append("Not enough post-workout HR data to compute recovery rate. ")
            append("Wear your watch for 2+ minutes after finishing exercise for accurate measurement.")
            return@buildString
        }

        append("Your heart rate dropped $hrr1 bpm in the first minute after your workout (peak ${peakHR} bpm). ")
        hrr2?.let { append("After 2 minutes it dropped $it bpm total. ") }

        append("Assessment: $assessment. ")

        when (assessment) {
            "Excellent" -> append("This indicates strong parasympathetic reactivation and excellent cardiovascular fitness.")
            "Good" -> append("Your autonomic recovery is healthy and above average.")
            "Normal" -> append("Your heart rate recovery is within the normal range.")
            "Below Normal" -> append("A drop under 12 bpm is below the usual range. Consistent exercise, better sleep, and stress management tend to raise it over time.")
        }

        when (trend) {
            TrendDirection.UP -> append(" Your HRR is improving compared to your recent baseline — a sign of increasing fitness.")
            TrendDirection.DOWN -> append(" Your HRR is below your recent baseline. Worth watching alongside sleep and training load.")
            TrendDirection.NEUTRAL -> { /* no trend note */ }
        }
    }
}
