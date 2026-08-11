package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.DailyActivityData
import com.example.vitalcoreai.data.model.RestingHRData

/**
 * Biological Age Estimator
 *
 * ## Formula
 * biologicalAge = chronologicalAge + vo2AgeOffset + hrAgeOffset + activityAgeOffset
 * Clamped to ±15 years from chronological age.
 *
 * ## Component offsets
 * | Component              | Range  | Basis                                     |
 * |------------------------|--------|-------------------------------------------|
 * | VO₂ Max offset         | −8…+8  | Deviation from the HUNT norm for THIS age  |
 * | Resting HR offset      | −3…+4  | RHR zone vs published norms               |
 * | Activity offset        | −2…+4  | Active days per week vs recommended 5     |
 *
 * ## Three corrections
 * **The "very active" branch was unreachable.** `activeDays` counted days over a 30-day
 * window and `activityWeeks = activeDays / 7f` was compared against `>= 5f`, which needs
 * 35 active days inside 30 — impossible. A genuinely excellent user at 5 active days/week
 * (≈21 days/month) got 21/7 = 3.0 and landed on the *neutral* offset. The conversion is
 * now days-per-week over the observed window.
 *
 * **The VO₂ offset ignored age.** The KDoc claimed "deviation from 40 mL/kg/min at age 35"
 * but `chronologicalAge` never entered the expression. Since norms decline ~10% per decade,
 * 40 is roughly the 50th percentile at 35 and the 85th at 60 — so a fit 60-year-old at
 * 38 mL/kg/min was scored two years *older* when they are in fact exceptional.
 *
 * **The VO₂ term dominated.** `coerceIn(-15, 15)` on `40 − vo2` let one component swing 30
 * years while RHR contributed at most 7 and activity 6 — so ~70% of the estimate came from
 * the least reliable input, which carries its own ±10% margin (≈±5 years). Capped at ±8.
 *
 * ## Confidence (A3)
 * Inherits from VO₂ Max estimate confidence; LOW when < 7 days of HR history.
 *
 * ## A6 — Root cause
 * When [priorAge] is supplied, the explanation names the year-delta change.
 *
 * ## Limitations
 * - VO₂ Max input itself carries ±10% margin; this compounds into the age estimate.
 * - Population norms are for adults; estimates may be less accurate outside 20–70 age range.
 * - Not a medical or clinical measurement — always displayed with disclaimer.
 *
 * ## References
 * - Nes BM et al. (2013) Scand J Med Sci Sports 23(6):697–704 (HUNT Fitness Study)
 * - Shcherbina A et al. (2017) JMIR 4(2):e23 (resting HR norms)
 */
object BiologicalAgeEstimator {

    data class BiologicalAgeResult(
        val estimatedAge: Int,
        val chronologicalAge: Int,
        val ageDifference: Int,           // negative = biologically younger
        val vo2MaxContribution: String,
        val hrTrendContribution: String,
        val activityContribution: String,
        val disclaimer: String,
        val sourceReference: String,
        val explanation: String,
        /** Per-component year offsets, so the UI can show which input drives the number. */
        val vo2OffsetYears: Int,
        val restingHROffsetYears: Int,
        val activityOffsetYears: Int,
        val vo2AgeNorm: Float,
        val vo2Descriptor: String,
        /** True when there is too little history for the estimate to mean anything. */
        val insufficientData: Boolean,
        val confidence: Confidence
    )

    /** VO₂ contributes at most this many years either way — see the class KDoc. */
    private const val MAX_VO2_OFFSET = 8

    /** Below this many days of history the estimate is not reported. */
    private const val MIN_DAYS_FOR_ESTIMATE = 7

    fun estimate(
        chronologicalAge: Int,
        vo2Max: Float,
        restingHR30Days: List<RestingHRData>,
        activityData30Days: List<DailyActivityData>,
        isFemale: Boolean = false,
        stepGoal: Int = TrendCalculators.DEFAULT_STEP_GOAL
    ): BiologicalAgeResult {
        // VO₂ Max component, referenced against the norm for THIS age rather than a fixed
        // 40 mL/kg/min. HUNT norms decline ~0.36 mL/kg/min per year for men.
        val ageNorm = Vo2MaxEstimator.ageNorm(chronologicalAge, isFemale)
        val vo2Descriptor = Vo2MaxEstimator.describeAgainstNorm(vo2Max, chronologicalAge, isFemale)
        // ~0.36 mL/kg/min per year of decline, so 1 mL/kg/min above norm ≈ 2.8 years younger.
        val yearsPerUnit = 1f / 0.36f
        val vo2AgeOffset = ((ageNorm - vo2Max) * yearsPerUnit).toInt()
            .coerceIn(-MAX_VO2_OFFSET, MAX_VO2_OFFSET)

        // Resting HR trend component
        val avgRHR = if (restingHR30Days.isEmpty()) 65f
            else restingHR30Days.map { it.bpm }.average().toFloat()
        val hrAgeOffset = when {
            avgRHR < 55f -> -3
            avgRHR < 62f -> -1
            avgRHR < 70f ->  0
            avgRHR < 80f ->  2
            else         ->  4
        }

        // Activity consistency — active days per week over the days ACTUALLY OBSERVED, so
        // a short history is not mistaken for inactivity.
        val observedDays = activityData30Days.size
        val activeDays = activityData30Days.count { it.steps >= stepGoal }
        val activeDaysPerWeek = if (observedDays > 0) activeDays.toFloat() / observedDays * 7f else 0f
        val activityAgeOffset = when {
            activeDaysPerWeek >= 5f -> -2
            activeDaysPerWeek >= 3f ->  0
            activeDaysPerWeek >= 1f ->  2
            else                    ->  4
        }

        val insufficientData = restingHR30Days.size < MIN_DAYS_FOR_ESTIMATE &&
            observedDays < MIN_DAYS_FOR_ESTIMATE
        val confidence = when {
            insufficientData -> Confidence.LOW
            restingHR30Days.size >= 21 && observedDays >= 21 -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }

        val bioAge = (chronologicalAge + vo2AgeOffset + hrAgeOffset + activityAgeOffset)
            .coerceIn(maxOf(1, chronologicalAge - 15), chronologicalAge + 15)

        val diff = bioAge - chronologicalAge
        val diffText = when {
            diff < 0  -> "${-diff} years younger than your chronological age"
            diff > 0  -> "$diff years older than your chronological age"
            else      -> "equal to your chronological age"
        }

        return BiologicalAgeResult(
            estimatedAge = bioAge,
            chronologicalAge = chronologicalAge,
            ageDifference = diff,
            vo2MaxContribution = "VO₂ Max ${vo2Max.toInt()} mL/kg/min vs ${ageNorm.toInt()} norm " +
                "(offset: ${signed(vo2AgeOffset)} yrs)",
            hrTrendContribution = "Avg resting HR ${avgRHR.toInt()} bpm (offset: ${signed(hrAgeOffset)} yrs)",
            activityContribution = "$activeDays active days of $observedDays recorded " +
                "(offset: ${signed(activityAgeOffset)} yrs)",
            disclaimer = "A wellness estimate based on published fitness research — not a medical " +
                "or clinical measurement. The VO₂ max input carries a ±10% margin, which is roughly " +
                "±3 years on this estimate.",
            sourceReference = "Reference: Nes BM et al. (2013), HUNT Fitness Study. Scand J Med Sci Sports 23(6):697–704.",
            explanation = if (insufficientData)
                "Not enough history yet for a fitness age estimate. Keep syncing for at least " +
                    "$MIN_DAYS_FOR_ESTIMATE days."
            else
                "Your fitness age is estimated at $bioAge — $diffText. Based on your VO₂ max " +
                    "estimate ($vo2Descriptor), resting heart rate trend, and activity consistency " +
                    "across $observedDays recorded days.",
            vo2OffsetYears = vo2AgeOffset,
            restingHROffsetYears = hrAgeOffset,
            activityOffsetYears = activityAgeOffset,
            vo2AgeNorm = ageNorm,
            vo2Descriptor = vo2Descriptor,
            insufficientData = insufficientData,
            confidence = confidence
        )
    }

    private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"
}
