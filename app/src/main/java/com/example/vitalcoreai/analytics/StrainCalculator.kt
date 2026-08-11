package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.HeartRatePoint
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Strain — a 0–21 logarithmic daily cardiovascular load scale.
 *
 * Original formulation for VitalCore AI, assembled from published sports-science
 * literature. No proprietary algorithm is reproduced.
 *
 * ## Why logarithmic
 * Cardiovascular load accumulates near-multiplicatively: doubling duration at fixed
 * intensity roughly doubles TRIMP. Perceived effort and marginal recovery cost do not —
 * they follow the Weber–Fechner compression that Borg's 6–20 RPE scale was empirically
 * built around. On a linear 0–100 scale an easy 30-minute jog and a 3-hour ride land at
 * 6 and 100: the top saturates and every hard day looks identical. That is exactly what
 * the previous `normalizedLoad = load / 300` clamped to 1.0 already did — a 2-hour hard
 * ride and a 5-hour hard ride were indistinguishable, which destroyed the volume signal
 * ACWR exists to detect. A log map spends its resolution where users actually live and
 * leaves headroom that is hard but reachable.
 *
 * ## The four steps
 *
 * **1 — Intensity per sample, on heart-rate reserve (Karvonen), not %HRmax:**
 * ```
 * i(t) = clamp((HR(t) − RHR_base) / (HRmax − RHR_base), 0, 1)
 * ```
 * At RHR 58 / HRmax 190, quiet rest is 31% of HRmax — which the old zone table could not
 * represent at all — but 0.00 in HRR. Correct by construction.
 *
 * **2 — Continuous exertion weight (Banister-style):**
 * ```
 * w(i) = i · exp(K · i),   K = 1.92 (male) / 1.67 (female)
 * ```
 * w(0)=0, w(0.5)=1.31, w(0.75)=3.16, w(1.0)=6.82. A smooth generalisation of Edwards'
 * 5-zone step function: same ordering, no boundary cliffs — one beat either side of a
 * zone edge should not change the multiplier by a third.
 *
 * **3 — Integrate over the whole day's HR timeline, above a personal basal floor:**
 * ```
 * E_day = Σ max(0, w(i(t)) − w(i_basal)) · dt(t)      [exertion-minutes]
 * ```
 * `dt` is the gap to the next sample, **clamped to [MAX_SAMPLE_GAP_MIN]**. This is the
 * load-bearing detail for a Galaxy Watch Active 2: it emits roughly one sample per
 * 10 minutes at rest but one per second inside a tracked workout, so without dt
 * weighting a 30-minute workout's 1800 samples would swamp 23 hours of resting HR. The
 * clamp also stops a sensor dropout from being scored as sustained effort.
 *
 * Because this is one integral over the day, tracked sessions and untracked activity
 * compose automatically: multi-session days need no special rule, and overlapping or
 * adjacent sessions cannot double-count — a real risk under per-session summation.
 *
 * **4 — Logarithmic compression to 0–21:**
 * ```
 * Strain = 21 · ln(1 + E_day/E0) / ln(1 + Emax/E0)
 * E0 = 60, Emax = 900  →  ln(16) = 2.7726  →  Strain = 7.574 · ln(1 + E_day/60)
 * ```
 *
 * ## Properties
 * - **Rest is low but not zero (~1.0–1.5).** All-day HR contributes, so a stressful
 *   sedentary day with elevated HR scores measurably above a genuinely calm one. That is
 *   the whole reason to integrate 24 h rather than only sessions.
 * - **Diminishing returns.** 0→5 costs 42 exertion-minutes; 15→16 costs 219.
 * - **Multi-session:** additive in E, sub-additive in strain. Two hard sessions are more
 *   taxing than one and less than twice as taxing. Correct.
 * - **Invertible:** `E_day = 60·(exp(Strain/7.574) − 1)`, so the raw integral can always
 *   be recovered for explanation UI ("you accumulated 212 exertion-minutes").
 *
 * ## HRV
 * Not used anywhere, and nothing degrades from its absence. TRIMP was defined on heart
 * rate and duration alone.
 *
 * ## References
 * - Banister EW (1991) Modeling elite athletic performance. In: MacDougall JD et al. (eds).
 * - Edwards S (1993) The Heart Rate Monitor Book. Sacramento: Fleet Feet Press.
 * - Karvonen MJ et al. (1957) Ann Med Exp Biol Fenn 35(3):307-315.
 * - Borg GA (1982) Med Sci Sports Exerc 14(5):377-381.
 * - Tanaka H et al. (2001) J Am Coll Cardiol 37(1):153-156.
 */
object StrainCalculator {

    /**
     * Every tunable constant lives here so re-calibration is a one-file change.
     * [HealthRepository][com.example.vitalcoreai.data.repository.HealthRepository] also
     * persists the raw `E_day` integral, so these can be re-tuned later without
     * re-reading Health Connect.
     */
    object Constants {
        /** Banister exponential weighting coefficient. 1.92 male, 1.67 female. */
        const val K_BANISTER_MALE = 1.92
        const val K_BANISTER_FEMALE = 1.67

        /** Curvature constant of the log map, in exertion-minutes. */
        const val E0 = 60.0

        /** Exertion-minutes that map exactly to [SCALE]. ~3 h of continuous threshold work. */
        const val E_MAX = 900.0

        /** Top of the scale. */
        const val SCALE = 21.0

        /** A gap between HR samples counts for at most this many minutes. */
        const val MAX_SAMPLE_GAP_MIN = 5.0

        /**
         * Beyond this, a gap is a sensor dropout rather than sparse sampling.
         *
         * The Active 2 samples every few minutes at rest, so a gap of a few minutes is
         * ordinary and the intervening time can fairly be credited at the surrounding
         * intensity. A gap of hours means the watch was off the wrist, on the charger, or
         * not syncing — we know nothing about that time. Crediting it even at the clamped
         * five minutes let 25 scattered samples at 90% intensity accumulate a strain of
         * 18/21, an "All Out" day fabricated entirely out of missing data.
         */
        const val DROPOUT_GAP_MIN = 15.0

        /** What a sample speaks for when it stands alone: itself, and nothing more. */
        const val NOMINAL_SAMPLE_MIN = 1.0

        /** Below this many samples the HR path is not trustworthy; fall back to the proxy. */
        const val MIN_HR_SAMPLES = 20

        /** Proxy coefficients, used only when HR is unavailable. Always LOW confidence. */
        const val PROXY_STEP_COEF = 0.9          // per 1000 steps above the sedentary floor
        const val PROXY_SEDENTARY_STEPS = 2000
        const val PROXY_EXERCISE_COEF = 2.4      // per logged exercise minute

        /** Fallback basal intensity when the user has no personal median yet. */
        const val DEFAULT_BASAL_INTENSITY = 0.10

        /** Derived: 21 / ln(1 + 900/60) = 21 / 2.7726 = 7.5741 */
        val LOG_SCALE: Double get() = SCALE / ln(1.0 + E_MAX / E0)
    }

    /** Borg-style zones. Shared by analytics and UI so there is exactly one definition. */
    enum class StrainZone(val label: String, val minInclusive: Float, val maxExclusive: Float) {
        LIGHT("Light", 0f, 6f),
        MODERATE("Moderate", 6f, 10f),
        STRENUOUS("Strenuous", 10f, 14f),
        HARD("Hard", 14f, 18f),
        ALL_OUT("All Out", 18f, 21.001f);

        companion object {
            fun of(strain: Float): StrainZone = entries.first { strain < it.maxExclusive }
        }
    }

    /**
     * @param strain           0–21, one decimal for display. **Null means "no data"** —
     *                         a charging watch must never read as a rest day.
     * @param exertionMinutes  the raw `E_day` integral, persisted so the scale constants
     *                         stay re-tunable.
     * @param isProxyEstimate  true when derived from steps/exercise minutes rather than HR.
     */
    data class StrainResult(
        val strain: Float?,
        val exertionMinutes: Float?,
        val zone: StrainZone?,
        val confidence: Confidence,
        val confidencePercent: Int,
        val explanation: String,
        val breakdown: List<ScoreFactor>,
        val isProxyEstimate: Boolean,
        val hrSampleCount: Int,
        val basalIntensity: Double
    ) {
        companion object {
            /** No HR, no steps, no sessions — genuinely nothing to report. */
            val NO_DATA = StrainResult(
                strain = null,
                exertionMinutes = null,
                zone = null,
                confidence = Confidence.LOW,
                confidencePercent = 0,
                explanation = "No heart-rate or activity data for this day.",
                breakdown = emptyList(),
                isProxyEstimate = false,
                hrSampleCount = 0,
                basalIntensity = Constants.DEFAULT_BASAL_INTENSITY
            )
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Compute the day's strain from its full heart-rate timeline.
     *
     * @param hrPoints          every HR sample for the day, any order (sorted internally).
     * @param restingHRBaseline the 30-day blended resting-HR baseline (population 62.0 for
     *                          new users). Anchors the bottom of the reserve.
     * @param maxHR             the user's setting, else `208 − 0.7·age` (Tanaka).
     * @param basalIntensity    the user's own median daytime HRR intensity (typically
     *                          0.08–0.12). Only exertion *above* idle metabolism
     *                          accumulates, which keeps a rest day low without forcing it
     *                          to zero. Pass null to derive it from [hrPoints].
     * @param steps             for the no-HR proxy path only.
     * @param exerciseMinutes   for the no-HR proxy path only.
     * @param isFemale          selects the Banister coefficient.
     */
    fun calculate(
        hrPoints: List<HeartRatePoint>,
        restingHRBaseline: Double,
        maxHR: Int,
        basalIntensity: Double? = null,
        steps: Int? = null,
        exerciseMinutes: Int? = null,
        isFemale: Boolean = false,
        dataQuality: DataQualityReport? = null
    ): StrainResult {
        val k = if (isFemale) Constants.K_BANISTER_FEMALE else Constants.K_BANISTER_MALE
        val reserve = maxHR - restingHRBaseline

        // A non-positive reserve means the inputs are nonsense (max HR at or below
        // resting). Dividing by it would produce infinities, so degrade to the proxy.
        if (reserve <= 1.0 || hrPoints.size < Constants.MIN_HR_SAMPLES) {
            return proxyStrain(steps, exerciseMinutes, hrPoints.size)
        }

        val sorted = hrPoints.sortedBy { it.timestampMs }
        val basal = basalIntensity ?: deriveBasalIntensity(sorted, restingHRBaseline, maxHR)
        val basalWeight = exertionWeight(basal, k)

        var exertionMinutes = 0.0
        var activeMinutes = 0.0
        var peakIntensity = 0.0
        var weightedIntensitySum = 0.0
        var totalMinutes = 0.0

        for (index in sorted.indices) {
            val point = sorted[index]
            // dt = minutes to the NEXT sample, clamped. The final sample carries one
            // nominal minute rather than extrapolating to the end of the day.
            val dt = if (index < sorted.size - 1) {
                val gapMin = (sorted[index + 1].timestampMs - point.timestampMs) / 60_000.0
                when {
                    gapMin <= 0.0 -> 0.0
                    // Continuous enough to interpolate across.
                    gapMin <= Constants.MAX_SAMPLE_GAP_MIN -> gapMin
                    // Sparse, but still plausibly one unbroken wear period.
                    gapMin <= Constants.DROPOUT_GAP_MIN -> Constants.MAX_SAMPLE_GAP_MIN
                    // A dropout. The sample speaks only for itself.
                    else -> Constants.NOMINAL_SAMPLE_MIN
                }
            } else {
                Constants.NOMINAL_SAMPLE_MIN
            }
            if (dt <= 0.0) continue

            val intensity = ((point.bpm - restingHRBaseline) / reserve).coerceIn(0.0, 1.0)
            val netWeight = max(0.0, exertionWeight(intensity, k) - basalWeight)

            exertionMinutes += netWeight * dt
            totalMinutes += dt
            weightedIntensitySum += intensity * dt
            if (netWeight > 0.0) activeMinutes += dt
            if (intensity > peakIntensity) peakIntensity = intensity
        }

        val strain = strainFromExertion(exertionMinutes)
        val zone = StrainZone.of(strain)
        val avgIntensity = if (totalMinutes > 0) weightedIntensitySum / totalMinutes else 0.0

        // Confidence tracks sensor coverage, not the size of the number. A well-sampled
        // rest day is a HIGH-confidence 1.2, not a low-confidence anything.
        val coverageHours = totalMinutes / 60.0
        val confidence = dataQuality?.level ?: when {
            sorted.size >= 200 && coverageHours >= 12 -> Confidence.HIGH
            sorted.size >= 60 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val confidencePercent = dataQuality?.confidencePercent ?: when (confidence) {
            Confidence.HIGH -> 85
            Confidence.MEDIUM -> 60
            Confidence.LOW -> 35
        }

        return StrainResult(
            strain = strain,
            exertionMinutes = exertionMinutes.toFloat(),
            zone = zone,
            confidence = confidence,
            confidencePercent = confidencePercent,
            explanation = buildExplanation(strain, zone, exertionMinutes, activeMinutes, peakIntensity, false),
            breakdown = buildBreakdown(exertionMinutes, activeMinutes, peakIntensity, avgIntensity, sorted.size),
            isProxyEstimate = false,
            hrSampleCount = sorted.size,
            basalIntensity = basal
        )
    }

    /**
     * The no-HR degradation path — the watch does get charged.
     *
     * ```
     * E_proxy = 0.9 · max(0, steps − 2000)/1000 + 2.4 · exercise_minutes
     * ```
     * Never emits above LOW confidence, and returns [StrainResult.NO_DATA] rather than
     * 0.0 when there is neither HR nor steps nor a logged session.
     */
    fun proxyStrain(steps: Int?, exerciseMinutes: Int?, hrSampleCount: Int = 0): StrainResult {
        val hasSteps = steps != null && steps > 0
        val hasExercise = exerciseMinutes != null && exerciseMinutes > 0
        if (!hasSteps && !hasExercise) return StrainResult.NO_DATA.copy(hrSampleCount = hrSampleCount)

        val stepComponent = Constants.PROXY_STEP_COEF *
            max(0, (steps ?: 0) - Constants.PROXY_SEDENTARY_STEPS) / 1000.0
        val exerciseComponent = Constants.PROXY_EXERCISE_COEF * (exerciseMinutes ?: 0)
        val exertion = stepComponent + exerciseComponent
        val strain = strainFromExertion(exertion)

        return StrainResult(
            strain = strain,
            exertionMinutes = exertion.toFloat(),
            zone = StrainZone.of(strain),
            confidence = Confidence.LOW,
            confidencePercent = 30,
            explanation = buildString {
                append("Strain ${format1(strain)} estimated from steps and logged exercise — ")
                append("not enough heart-rate data for a measured value. ")
                append("Wear the watch through the day for a full reading.")
            },
            breakdown = listOfNotNull(
                steps?.takeIf { it > 0 }?.let {
                    ScoreFactor(
                        name = "Steps",
                        contribution = if (hasExercise) 50f else 100f,
                        rawValue = "$it steps",
                        score = (stepComponent / max(exertion, 0.001) * 100).toFloat().coerceIn(0f, 100f),
                        description = "Step count above a sedentary floor of ${Constants.PROXY_SEDENTARY_STEPS}."
                    )
                },
                exerciseMinutes?.takeIf { it > 0 }?.let {
                    ScoreFactor(
                        name = "Logged Exercise",
                        contribution = if (hasSteps) 50f else 100f,
                        rawValue = "$it min",
                        score = (exerciseComponent / max(exertion, 0.001) * 100).toFloat().coerceIn(0f, 100f),
                        description = "Duration of logged sessions, with intensity assumed moderate."
                    )
                }
            ),
            isProxyEstimate = true,
            hrSampleCount = hrSampleCount,
            basalIntensity = Constants.DEFAULT_BASAL_INTENSITY
        )
    }

    /** `Strain = 7.574 · ln(1 + E/60)`, clamped to [0, 21]. */
    fun strainFromExertion(exertionMinutes: Double): Float =
        (Constants.LOG_SCALE * ln(1.0 + max(0.0, exertionMinutes) / Constants.E0))
            .coerceIn(0.0, Constants.SCALE)
            .toFloat()

    /** Inverse of [strainFromExertion] — recovers the raw integral for explanation UI. */
    fun exertionFromStrain(strain: Float): Double =
        Constants.E0 * (exp(strain / Constants.LOG_SCALE) - 1.0)

    /** `w(i) = i · exp(K · i)` */
    fun exertionWeight(intensity: Double, k: Double): Double =
        intensity * exp(k * intensity)

    /**
     * The user's own basal HRR intensity: the median of daytime samples (06:00–22:00 is
     * not knowable from timestamps alone here, so the median of the whole day's lower
     * half is used, which is dominated by idle time for any realistic wear pattern).
     * Typically lands at 0.08–0.12.
     */
    fun deriveBasalIntensity(
        hrPoints: List<HeartRatePoint>,
        restingHRBaseline: Double,
        maxHR: Int
    ): Double {
        val reserve = maxHR - restingHRBaseline
        if (reserve <= 1.0 || hrPoints.isEmpty()) return Constants.DEFAULT_BASAL_INTENSITY
        val intensities = hrPoints
            .map { ((it.bpm - restingHRBaseline) / reserve).coerceIn(0.0, 1.0) }
            .sorted()
        val median = intensities[intensities.size / 2]
        // Guard against a day that was almost entirely exercise, which would otherwise
        // set a punishing floor and report a hard day as easy.
        return median.coerceIn(0.02, 0.20)
    }

    /**
     * Recommended strain band for today, given recovery. Used by the Recovery screen's
     * "Today's Target" and the Strain screen's target ticks.
     *
     * Higher recovery earns a higher ceiling. Deliberately wide (3.5 points) because this
     * is guidance, not a prescription.
     */
    fun recommendedBand(recoveryScore: Float?): Pair<Float, Float>? {
        val recovery = recoveryScore ?: return null
        val centre = when {
            recovery >= 85f -> 15.5f
            recovery >= 67f -> 13.5f
            recovery >= 50f -> 11.0f
            recovery >= 34f -> 8.5f
            else -> 6.0f
        }
        return (centre - 1.75f).coerceAtLeast(0f) to (centre + 1.75f).coerceAtMost(21f)
    }

    /** "Recovery 78 — aim for 12.0–15.5 today." */
    fun targetRationale(recoveryScore: Float?): String {
        val band = recommendedBand(recoveryScore)
            ?: return "Sync recovery data to get a strain target for today."
        val recovery = recoveryScore!!.toInt()
        val verdict = when {
            recoveryScore >= 67f -> "Recovery is strong"
            recoveryScore >= 34f -> "Recovery is moderate"
            else -> "Recovery is low"
        }
        return "$verdict at $recovery — aim for ${format1(band.first)}–${format1(band.second)} today."
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildBreakdown(
        exertionMinutes: Double,
        activeMinutes: Double,
        peakIntensity: Double,
        avgIntensity: Double,
        sampleCount: Int
    ): List<ScoreFactor> = listOf(
        ScoreFactor(
            name = "Exertion Volume",
            contribution = 60f,
            rawValue = "${exertionMinutes.toInt()} exertion-min",
            score = (StrainCalculator.strainFromExertion(exertionMinutes) / 21f * 100f).coerceIn(0f, 100f),
            description = "Heart-rate load integrated across the whole day, above your own resting baseline."
        ),
        ScoreFactor(
            name = "Time Above Rest",
            contribution = 25f,
            rawValue = "${activeMinutes.toInt()} min",
            score = (activeMinutes / 240.0 * 100.0).toFloat().coerceIn(0f, 100f),
            description = "Minutes spent with heart rate above your idle metabolic floor."
        ),
        ScoreFactor(
            name = "Peak Intensity",
            contribution = 15f,
            rawValue = "${(peakIntensity * 100).toInt()}% HR reserve",
            score = (peakIntensity * 100).toFloat().coerceIn(0f, 100f),
            description = "Highest sustained effort reached, as a share of your heart-rate reserve " +
                "(avg ${(avgIntensity * 100).toInt()}%, $sampleCount samples)."
        )
    )

    private fun buildExplanation(
        strain: Float,
        zone: StrainZone,
        exertionMinutes: Double,
        activeMinutes: Double,
        peakIntensity: Double,
        isProxy: Boolean
    ): String = buildString {
        append("Strain ${format1(strain)} of 21 — ${zone.label.lowercase()}. ")
        append("You accumulated ${exertionMinutes.toInt()} exertion-minutes")
        if (activeMinutes > 0) append(" across ${activeMinutes.toInt()} minutes above resting")
        append(". ")
        when {
            peakIntensity >= 0.85 -> append("Peak effort reached ${(peakIntensity * 100).toInt()}% of your heart-rate reserve.")
            strain < 3f -> append("A genuine rest day — background heart rate only.")
            else -> append("Effort peaked at ${(peakIntensity * 100).toInt()}% of your heart-rate reserve.")
        }
        if (isProxy) append(" Estimated without heart-rate data.")
    }

    /**
     * One-decimal formatting without `String.format`, which would need an explicit Locale
     * and is banned from this pure-Kotlin module by the project's lint config.
     */
    private fun format1(value: Float): String {
        val rounded = kotlin.math.round(value * 10f).toInt()
        return "${rounded / 10}.${rounded % 10}"
    }
}

/** Top-level alias so analytics and UI share one definition without importing the object. */
typealias StrainZone = StrainCalculator.StrainZone
