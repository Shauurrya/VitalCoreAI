package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.ExerciseSessionData
import com.example.vitalcoreai.data.model.HeartRatePoint
import com.example.vitalcoreai.data.model.HRZone
import com.example.vitalcoreai.data.model.TrainingLoadData

/**
 * Per-session training load.
 *
 * ## Scope
 * This produces a **per-session** normalised load, used for the session list and as the
 * per-day input to [AcwrCalculator]. The user-facing *daily* number is
 * [StrainCalculator]'s 0–21 scale, which integrates the whole day's HR timeline rather
 * than summing sessions.
 *
 * ## Formula
 * ```
 * load = duration (min) × weighted_intensity
 * weighted_intensity = Σ (zone_time_fraction × zone_multiplier)
 * normalised_load = load / MAX_SESSION_LOAD, clamped 0..1
 * ```
 *
 * ## Zone multipliers (TRIMP-variant)
 * | Zone | Label         | Multiplier |
 * |------|---------------|-----------|
 * | <1   | Rest          | 0.0       |
 * | 1    | Recovery      | 0.5       |
 * | 2    | Aerobic Base  | 1.0       |
 * | 3    | Aerobic       | 1.5       |
 * | 4    | Threshold     | 2.0       |
 * | 5    | VO₂ Max       | 2.5       |
 *
 * ## Two corrections that matter
 * **Zones are on heart-rate reserve, not %HRmax.** See [HRZone]. Sub-threshold heart rate
 * lands in [HRZone.BELOW_ZONE1] and contributes zero load, instead of being swept into
 * Zone 1 alongside genuine active recovery.
 *
 * **The distribution is time-weighted, not sample-counted.** Health Connect HR sampling is
 * wildly non-uniform — per-second inside a workout, one reading per 10 minutes at rest —
 * so counting samples reports whichever period happened to be sampled densely, not the
 * time actually spent in each zone.
 *
 * ## References
 * - Banister EW (1991) Modeling elite athletic performance. In: MacDougall JD et al. (eds).
 * - Foster C et al. (2001) Med Sci Sports Exerc 33(5):848-850 (session RPE method).
 * - Karvonen MJ et al. (1957) Ann Med Exp Biol Fenn 35(3):307-315.
 */
object TrainingLoadCalculator {

    private val zoneMultipliers = mapOf(
        HRZone.BELOW_ZONE1 to 0.0f,
        HRZone.ZONE1 to 0.5f,
        HRZone.ZONE2 to 1.0f,
        HRZone.ZONE3 to 1.5f,
        HRZone.ZONE4 to 2.0f,
        HRZone.ZONE5 to 2.5f
    )

    /** Normalisation ceiling in arbitrary units — a ceiling, not a prescription. */
    private const val MAX_SESSION_LOAD = 300f

    /** A gap between HR samples counts for at most this many minutes. */
    private const val MAX_SAMPLE_GAP_MIN = 5.0

    /**
     * Compute normalised training load for a single session.
     *
     * @param session   exercise session record
     * @param maxHR     the user's max HR (see [tanakaMaxHR] when unknown)
     * @param restingHR the user's resting HR, anchoring the bottom of the reserve
     */
    fun calculateForSession(
        session: ExerciseSessionData,
        maxHR: Int = 190,
        restingHR: Int = 60
    ): TrainingLoadData {
        val durationMinutes = ((session.endMs - session.startMs) / 60_000).toInt()
        val hasHR = session.heartRatePoints.isNotEmpty()
        val zoneDistribution = calculateZoneDistribution(session.heartRatePoints, maxHR, restingHR)

        val weightedIntensity = zoneDistribution.entries.sumOf { (zone, fraction) ->
            (fraction * (zoneMultipliers[zone] ?: 1.0f)).toDouble()
        }.toFloat()

        val normalizedLoad = ((durationMinutes * weightedIntensity) / MAX_SESSION_LOAD).coerceIn(0f, 1f)

        // Exclude the rest bucket when naming the dominant zone: a session whose HR was
        // mostly sub-threshold is a Zone 1 session, not a "Rest" session.
        val dominantZone = zoneDistribution
            .filterKeys { it != HRZone.BELOW_ZONE1 }
            .maxByOrNull { it.value }?.key
            ?: HRZone.ZONE1

        return TrainingLoadData(
            dateEpochDay = session.dateEpochDay,
            normalizedLoad = normalizedLoad,
            durationMinutes = durationMinutes,
            dominantZone = dominantZone,
            hasHeartRateData = hasHR
        )
    }

    /**
     * Distribute HR data across zones **by time**, using heart-rate reserve.
     *
     * Returns an empty map when there is no HR data. Callers must handle that explicitly:
     * the old `mapOf(ZONE2 to 1.0f)` fallback fabricated a plausible-looking zone
     * distribution that was indistinguishable from a real aerobic session.
     */
    fun calculateZoneDistribution(
        hrPoints: List<HeartRatePoint>,
        maxHR: Int,
        restingHR: Int = 60
    ): Map<HRZone, Float> {
        if (hrPoints.isEmpty()) return emptyMap()
        val reserve = (maxHR - restingHR).toDouble()
        if (reserve <= 1.0) return emptyMap()

        val sorted = hrPoints.sortedBy { it.timestampMs }
        val zoneMinutes = HRZone.entries.associateWith { 0.0 }.toMutableMap()

        for (index in sorted.indices) {
            val point = sorted[index]
            val dt = if (index < sorted.size - 1) {
                val gapMin = (sorted[index + 1].timestampMs - point.timestampMs) / 60_000.0
                gapMin.coerceIn(0.0, MAX_SAMPLE_GAP_MIN)
            } else {
                1.0
            }
            if (dt <= 0.0) continue
            val fraction = ((point.bpm - restingHR) / reserve).coerceIn(0.0, 1.0)
            val zone = HRZone.ofReserveFraction(fraction)
            zoneMinutes[zone] = (zoneMinutes[zone] ?: 0.0) + dt
        }

        val total = zoneMinutes.values.sum()
        if (total <= 0.0) return emptyMap()
        return zoneMinutes.mapValues { (_, minutes) -> (minutes / total).toFloat() }
    }

    /** Minutes spent in each zone, for the "Time in Zones" bar. */
    fun zoneMinutes(
        hrPoints: List<HeartRatePoint>,
        maxHR: Int,
        restingHR: Int = 60
    ): Map<HRZone, Int> {
        if (hrPoints.isEmpty()) return emptyMap()
        val reserve = (maxHR - restingHR).toDouble()
        if (reserve <= 1.0) return emptyMap()

        val sorted = hrPoints.sortedBy { it.timestampMs }
        val minutes = HRZone.entries.associateWith { 0.0 }.toMutableMap()
        for (index in sorted.indices) {
            val point = sorted[index]
            val dt = if (index < sorted.size - 1) {
                ((sorted[index + 1].timestampMs - point.timestampMs) / 60_000.0)
                    .coerceIn(0.0, MAX_SAMPLE_GAP_MIN)
            } else 1.0
            if (dt <= 0.0) continue
            val fraction = ((point.bpm - restingHR) / reserve).coerceIn(0.0, 1.0)
            val zone = HRZone.ofReserveFraction(fraction)
            minutes[zone] = (minutes[zone] ?: 0.0) + dt
        }
        return minutes.mapValues { (_, m) -> m.toInt() }
    }

    /**
     * Max HR from age, Tanaka et al. (2001): `208 − 0.7·age`.
     *
     * Materially more accurate above 40 than the folk `220 − age`, which the sync worker's
     * documentation still referenced.
     */
    fun tanakaMaxHR(age: Int): Int = (208.0 - 0.7 * age).toInt().coerceIn(120, 220)

    /**
     * Produce a [ScoreResult] summary for a single session.
     *
     * @param priorSessionLoad previous session's normalised load (enables the delta line)
     */
    fun summarizeResult(
        load: TrainingLoadData,
        priorSessionLoad: Float? = null
    ): ScoreResult {
        val loadScore = (load.normalizedLoad * 100f)
        val label = when {
            loadScore < 30f -> "Light"
            loadScore < 60f -> "Moderate"
            loadScore < 80f -> "Hard"
            else            -> "Very Hard"
        }

        val loadDelta = priorSessionLoad?.let {
            val diff = load.normalizedLoad - it
            when {
                diff >  0.15f -> "Training load up ${(diff * 100).toInt()}% vs prior session"
                diff < -0.15f -> "Training load down ${(-diff * 100).toInt()}% vs prior session"
                else          -> null
            }
        }

        // Data presence is now carried explicitly rather than inferred from the dominant
        // zone, which was also a legitimate outcome.
        val quality = DataQualityReport(
            level = if (load.hasHeartRateData) Confidence.HIGH else Confidence.MEDIUM,
            confidencePercent = if (load.hasHeartRateData) 85 else 45,
            reasons = if (load.hasHeartRateData) listOf("HR zone data present")
                      else listOf("No intraday HR for this session — intensity unknown"),
            insufficientData = false
        )

        val triggers = buildSet<CoachTrigger> {
            if (loadScore > 80f) add(CoachTrigger.TRAINING_LOAD_SPIKE)
        }

        return ScoreResult(
            score = loadScore,
            confidence = quality.level,
            confidencePercent = quality.confidencePercent,
            explanation = "$label training session. Load ${loadScore.toInt()}/100, " +
                "${load.durationMinutes} min, dominant zone ${load.dominantZone.label}." +
                (loadDelta?.let { " $it." } ?: "") +
                (if (!load.hasHeartRateData) " Intensity estimated — no heart-rate data recorded." else ""),
            breakdown = listOf(
                ScoreFactor(
                    name = "Duration",
                    contribution = 40f,
                    rawValue = "${load.durationMinutes} min",
                    score = (load.durationMinutes / 90f * 100f).coerceIn(0f, 100f),
                    description = "Time spent training",
                    delta = loadDelta
                ),
                ScoreFactor(
                    name = "Intensity",
                    contribution = 60f,
                    rawValue = load.dominantZone.label,
                    score = (zoneMultipliers[load.dominantZone] ?: 1f) / 2.5f * 100f,
                    description = if (load.hasHeartRateData) "HR zone distribution during session"
                                  else "No HR recorded — intensity not measured"
                )
            ),
            weights = mapOf("duration" to 0.40f, "intensity" to 0.60f),
            trendDirection = when {
                loadScore < 40f -> TrendDirection.DOWN
                loadScore > 70f -> TrendDirection.UP
                else            -> TrendDirection.NEUTRAL
            },
            coachTriggers = triggers,
            dataQuality = quality
        )
    }
}
