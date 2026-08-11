package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * B1 — Momentum Indicators
 *
 * Computes the rolling directional trend (Improving / Stable / Declining) for
 * Recovery, Training, and Sleep over the past 3 days.
 *
 * ## Method
 * Linear slope of the last 3 daily scores. Threshold ±3 points/day distinguishes
 * Improving from Stable from Declining.
 *
 * ## Design
 * - Pure function — takes lists of recent scores, returns [MomentumReport]
 * - Requires ≥ 2 data points; returns STABLE with LOW confidence below that
 */
object MomentumCalculator {

    enum class MomentumDirection(val label: String, val symbol: String) {
        IMPROVING("Improving", "↑"),
        STABLE("Stable", "→"),
        DECLINING("Declining", "↓")
    }

    data class MomentumIndicator(
        val direction: MomentumDirection,
        val slopePerDay: Float,       // score points per day (positive = improving)
        val confidence: Confidence,
        val dataPoints: Int
    )

    data class MomentumReport(
        val recovery: MomentumIndicator,
        val sleep: MomentumIndicator,
        val training: MomentumIndicator
    )

    private const val SLOPE_THRESHOLD = 3f   // points/day — above this = improving

    /**
     * Compute all three momentum indicators.
     *
     * @param recentRecoveryScores  up to last 5 daily recovery scores (newest last)
     * @param recentSleepScores     up to last 5 daily sleep scores (newest last)
     * @param recentTrainingScores  up to last 5 daily training load scores (newest last)
     */
    fun calculate(
        recentRecoveryScores: List<Float>,
        recentSleepScores: List<Float>,
        recentTrainingScores: List<Float>
    ): MomentumReport = MomentumReport(
        recovery = computeMomentum(recentRecoveryScores),
        sleep    = computeMomentum(recentSleepScores),
        training = computeMomentum(recentTrainingScores)
    )

    private fun computeMomentum(scores: List<Float>): MomentumIndicator {
        val n = scores.size
        if (n < 2) {
            return MomentumIndicator(
                direction = MomentumDirection.STABLE,
                slopePerDay = 0f,
                confidence = Confidence.LOW,
                dataPoints = n
            )
        }
        val slope = BaselineUtils.linearSlope(scores.takeLast(5))
        val direction = when {
            slope >  SLOPE_THRESHOLD -> MomentumDirection.IMPROVING
            slope < -SLOPE_THRESHOLD -> MomentumDirection.DECLINING
            else                     -> MomentumDirection.STABLE
        }
        val confidence = when {
            n >= 5 -> Confidence.HIGH
            n >= 3 -> Confidence.MEDIUM
            else   -> Confidence.LOW
        }
        return MomentumIndicator(
            direction = direction,
            slopePerDay = slope,
            confidence = confidence,
            dataPoints = n
        )
    }
}
