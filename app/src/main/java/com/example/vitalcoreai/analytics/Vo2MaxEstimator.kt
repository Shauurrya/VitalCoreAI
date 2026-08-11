package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.model.ExerciseSessionData
import com.example.vitalcoreai.data.model.HeartRatePoint

/**
 * VO₂ Max Estimator — Non-Proprietary Regression
 *
 * Method 1: Uth–Sørensen–Overgaard–Pedersen formula (non-exercise)
 *   `VO2Max ≈ 15.3 × (HRMax / HRRest)`
 *   Uth N et al. (2004) Scand J Med Sci Sports 14(1):22-6.
 *
 * Method 2: Steady-state pace/HR regression, from the ACSM running metabolic equation
 *   `VO2 (mL/kg/min) = 0.2 × velocity(m/MIN) + 3.5`
 *
 * All results are labelled "ESTIMATE" with a stated ±10% margin.
 *
 * ## Corrections
 * - The coefficient is **15.3**, not 15. The KDoc always wrote the formula correctly while
 *   the code used 15 — a 2% systematic underestimate.
 * - The default `maxHR` was **220**, the numerator of the age formula rather than a heart
 *   rate. Any caller omitting it got `15 × 220/RHR`: at RHR 60 that is 55 mL/kg/min,
 *   instantly "Superior". The default is now [UserPrefsDefaults.MAX_HR].
 * - The ACSM equation takes velocity in **metres per minute**; it was being fed metres per
 *   second, under-counting the speed term 60× and degenerating the estimate to a
 *   near-constant ~1.19 METs regardless of pace.
 * - The ratio method is age-blind by construction (Uth validated it on trained men), so
 *   [ageAdjusted] applies an age-referenced correction where an age is known.
 */
object Vo2MaxEstimator {

    /** Uth et al. (2004) coefficient. */
    private const val UTH_COEFFICIENT = 15.3f

    /** Mirrors `UserPrefs.DEFAULT_MAX_HR`; duplicated because this module takes no Android deps. */
    private object UserPrefsDefaults { const val MAX_HR = 190 }

    data class Vo2MaxEstimate(
        val vo2Max: Float,                    // mL/kg/min
        val lowerBound: Float,
        val upperBound: Float,
        val method: EstimationMethod,
        val category: FitnessCategory,
        val explanation: String
    )

    enum class EstimationMethod(val label: String) {
        RESTING_HR("Resting HR Ratio (Uth et al. 2004)"),
        EXERCISE_PACE_HR("Steady-State Pace/HR")
    }

    data class FitnessCategory(val label: String, val color: String)

    /**
     * @param age when supplied, an age-referenced correction is applied — the raw ratio
     *            method gives a 60-year-old with RHR 50 the same VO₂ as a 25-year-old with
     *            RHR 50, which is not physiologically defensible.
     */
    fun estimateFromRestingHR(
        restingHR: Int,
        maxHR: Int = UserPrefsDefaults.MAX_HR,
        age: Int? = null
    ): Vo2MaxEstimate {
        if (restingHR <= 0) return buildResult(0f, EstimationMethod.RESTING_HR)
        val raw = UTH_COEFFICIENT * (maxHR.toFloat() / restingHR.toFloat())
        val vo2 = if (age != null) ageAdjusted(raw, age) else raw
        return buildResult(vo2, EstimationMethod.RESTING_HR)
    }

    fun estimateFromExercise(
        session: ExerciseSessionData,
        restingHR: Int,
        maxHR: Int = UserPrefsDefaults.MAX_HR
    ): Vo2MaxEstimate? {
        val distance = session.distanceMeters ?: return null
        val durationSeconds = (session.endMs - session.startMs) / 1000.0
        if (durationSeconds < 300) return null           // need ≥5 min of steady state
        if (session.heartRatePoints.isEmpty()) return null // .average() would be NaN
        if (maxHR <= restingHR) return null

        // ACSM running equation takes METRES PER MINUTE, not per second.
        val velocityMetresPerMinute = (distance / durationSeconds * 60.0).toFloat()
        val avgHR = session.heartRatePoints.map { it.bpm }.average().toFloat()

        val vo2AtPace = 0.2f * velocityMetresPerMinute + 3.5f
        val hrReserveRatio = (avgHR - restingHR) / (maxHR - restingHR).toFloat()
        if (hrReserveRatio <= 0f) return null

        // Extrapolate the measured sub-maximal VO₂ to maximal effort.
        val vo2 = (vo2AtPace / hrReserveRatio).coerceIn(10f, 90f)
        return buildResult(vo2, EstimationMethod.EXERCISE_PACE_HR)
    }

    /**
     * HUNT Fitness Study age norms (Nes BM et al. 2013):
     * men ≈ `62 − 0.36·age`, women ≈ `51 − 0.30·age`.
     *
     * The Uth ratio is anchored on a young trained population, so it is nudged toward the
     * norm for the user's age. A 50/50 blend keeps the personal signal dominant while
     * stopping the estimate from being systematically flattering for older users.
     */
    fun ageAdjusted(rawVo2: Float, age: Int, isFemale: Boolean = false): Float {
        val norm = ageNorm(age, isFemale)
        val youngNorm = ageNorm(25, isFemale)
        val correction = norm - youngNorm     // negative for age > 25
        return (rawVo2 + correction * 0.5f).coerceAtLeast(10f)
    }

    /** Population VO₂ max norm for an age, mL/kg/min (HUNT). */
    fun ageNorm(age: Int, isFemale: Boolean = false): Float =
        if (isFemale) (51f - 0.30f * age).coerceAtLeast(15f)
        else (62f - 0.36f * age).coerceAtLeast(18f)

    /** "above average for 34" / "typical for 41" / "below average for 52". */
    fun describeAgainstNorm(vo2: Float, age: Int, isFemale: Boolean = false): String {
        val norm = ageNorm(age, isFemale)
        val ratio = vo2 / norm
        return when {
            ratio >= 1.15f -> "well above average for $age"
            ratio >= 1.05f -> "above average for $age"
            ratio >= 0.95f -> "typical for $age"
            ratio >= 0.85f -> "below average for $age"
            else           -> "well below average for $age"
        }
    }

    private fun buildResult(vo2: Float, method: EstimationMethod): Vo2MaxEstimate {
        val margin = vo2 * 0.10f  // ±10%
        val category = classifyVo2Max(vo2)
        return Vo2MaxEstimate(
            vo2Max = vo2,
            lowerBound = vo2 - margin,
            upperBound = vo2 + margin,
            method = method,
            category = category,
            explanation = "ESTIMATE: ${vo2.toInt()} mL/kg/min (±10% margin). Method: ${method.label}. This is a wellness estimate based on published fitness research — not a medical or clinical measurement."
        )
    }

    private fun classifyVo2Max(vo2: Float): FitnessCategory {
        // Reference: American Heart Association / ACSM norms (adult male/female combined approximation)
        return when {
            vo2 >= 55f -> FitnessCategory("Superior", "#00E5FF")
            vo2 >= 46f -> FitnessCategory("Excellent", "#69F0AE")
            vo2 >= 38f -> FitnessCategory("Good", "#B2FF59")
            vo2 >= 30f -> FitnessCategory("Fair", "#FFD740")
            else       -> FitnessCategory("Needs Improvement", "#FF6D00")
        }
    }
}
