package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY
// No android.* imports permitted in this package.
// No androidx.* imports permitted in this package.
// No Context, Activity, or Fragment references permitted.
// Enforced by lint rule: AnalyticsModuleBoundaryDetector.

/**
 * Universal result container returned by every scoring function.
 *
 * Fields:
 * - [score]                0-100 composite score
 * - [confidence]           HIGH / MEDIUM / LOW based on data completeness
 * - [confidencePercent]    numeric confidence 0-100
 * - [explanation]          plain-language root-cause summary (A6: names specific deltas)
 * - [breakdown]            contributing factors ranked by impact (highest first)
 * - [weights]              component weights used in this calculation
 * - [historicalComparison] today vs. yesterday/7-day/30-day
 * - [trendDirection]       3-day rolling direction
 * - [coachTriggers]        flags consumed by CoachEngine rule tree
 * - [dataQuality]          completeness report from DataQualityEngine (A3)
 */
data class ScoreResult(
    val score: Float,
    val confidence: Confidence,
    val confidencePercent: Int,
    val explanation: String,
    val breakdown: List<ScoreFactor>,
    val weights: Map<String, Float> = emptyMap(),
    val historicalComparison: HistoricalComparison? = null,
    val trendDirection: TrendDirection = TrendDirection.NEUTRAL,
    val coachTriggers: Set<CoachTrigger> = emptySet(),
    val dataQuality: DataQualityReport = DataQualityReport.UNKNOWN
)

/**
 * A single contributing factor, ranked highest-impact first inside [ScoreResult.breakdown].
 */
data class ScoreFactor(
    val name: String,
    val contribution: Float,      // weight percentage (35f = 35%)
    val rawValue: String,         // human-readable raw value
    val score: Float,             // 0-100 sub-score
    val description: String,
    val delta: String? = null     // A6: e.g. "up 8 bpm vs baseline" — null if no prior data
)

/** How confident the engine is in this score, based on data completeness (A3). */
enum class Confidence { HIGH, MEDIUM, LOW }

// ScoreFactor persistence encoding — a plain delimited string (matching the
// project's existing convention for storing lists in a single Room TEXT
// column, e.g. WeeklyReportEntity.highlights via joinToString("|")) rather
// than pulling in a JSON serialization dependency for one small list.
// These multi-character delimiters are distinctive enough that they will
// not collide with any of the calculators' generated name/description text.

private const val FACTOR_FIELD_SEP = "###"
private const val FACTOR_RECORD_SEP = "@@@"

fun List<ScoreFactor>.encodeToString(): String =
    joinToString(FACTOR_RECORD_SEP) { f ->
        listOf(f.name, f.contribution.toString(), f.rawValue, f.score.toString(), f.description, f.delta ?: "")
            .joinToString(FACTOR_FIELD_SEP)
    }

fun decodeScoreFactors(encoded: String?): List<ScoreFactor> {
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(FACTOR_RECORD_SEP).mapNotNull { record ->
        val parts = record.split(FACTOR_FIELD_SEP)
        if (parts.size < 6) return@mapNotNull null
        ScoreFactor(
            name = parts[0],
            contribution = parts[1].toFloatOrNull() ?: 0f,
            rawValue = parts[2],
            score = parts[3].toFloatOrNull() ?: 0f,
            description = parts[4],
            delta = parts[5].ifBlank { null }
        )
    }
}

/** Three-day rolling direction of the score. */
enum class TrendDirection { UP, DOWN, NEUTRAL }

/**
 * Flags used by CoachEngine to fire rule-based insights.
 * Add new triggers here as new rules are added — existing rules are unaffected.
 */
enum class CoachTrigger {
    RECOVERY_LOW,           // recovery < 40
    RECOVERY_EXCELLENT,     // recovery > 85
    SLEEP_DEFICIT,          // total sleep < personal need − 60 min
    SLEEP_DRIFTING,         // bedtime variance > 90 min over 7 days
    HR_ELEVATED,            // resting HR > baseline mean + 1.5 sigma
    HR_DECLINING_TREND,     // resting HR rising 3+ days
    ACWR_DANGER,            // ACWR > 1.5
    ACWR_UNDER_TRAINING,    // ACWR < 0.6
    NO_ACTIVITY_3_DAYS,     // no logged workout or step goal in 3 days
    TRAINING_LOAD_SPIKE,    // single-session load > 2x 7-day avg
    SLEEP_EXCELLENT,        // sleep score > 85
    READINESS_HIGH,         // readiness score > 80
}

/**
 * Today vs. prior-period comparison values.
 * All fields nullable — comparison requires at least one prior data point.
 */
data class HistoricalComparison(
    val yesterdayScore: Float? = null,
    val avg7DayScore: Float? = null,
    val avg30DayScore: Float? = null,
    val deltaFromYesterday: Float? = null,
    val deltaFrom7Day: Float? = null,
    val personalBest: Float? = null
)

/**
 * Data quality / completeness report produced by DataQualityEngine (A3).
 * Consumed by UI to decide whether to render a score or "Not enough data."
 *
 * @param reasons   what is *missing or degraded* — the negatives.
 * @param positives what is *present and reliable*. Added so a confidence badge can answer
 *                  "why?" symmetrically. Showing only the negatives made every score look
 *                  faintly broken even at 95% confidence, because the card had nothing
 *                  good to say about itself.
 * @param factors   per-dimension sub-scores (0–1), keyed by [DataQualityEngine.Factor]
 *                  name, so the debug screen and coach context can inspect the shape of a
 *                  confidence number rather than just its value.
 */
data class DataQualityReport(
    val level: Confidence,
    val confidencePercent: Int,
    val reasons: List<String>,            // short human-readable explanations
    val insufficientData: Boolean,        // true → UI shows "Not enough data"
    val positives: List<String> = emptyList(),
    val factors: Map<String, Float> = emptyMap()
) {
    /**
     * The "Why?" block the product spec asks for — positives first, then gaps, each
     * prefixed so the list reads correctly to a screen reader without relying on colour.
     */
    fun whyLines(maxLines: Int = 5): List<String> =
        (positives.map { "✓ $it" } + reasons.map { "• $it" }).take(maxLines)

    companion object {
        /** Sentinel used before DataQualityEngine has run. */
        val UNKNOWN = DataQualityReport(
            level = Confidence.LOW,
            confidencePercent = 0,
            reasons = listOf("Data not yet evaluated"),
            insufficientData = true
        )
    }
}

// Shared statistical utilities — pure math, no Android deps

/**
 * Rolling-baseline utilities used by all calculators.
 * All functions are pure — they depend only on their arguments.
 */
object BaselineUtils {

    /**
     * Maps a raw value to a 0-100 score using z-score normalisation against
     * the provided [baselineMean] and [baselineStd].
     *
     * When [baselineStd] < 0.01 (no variance), returns 50 (neutral) rather
     * than dividing by zero.
     *
     * @param invertPolarity true when a *lower* raw value is better (e.g. resting HR).
     */
    fun zScoreToScore(
        value: Double,
        baselineMean: Double,
        baselineStd: Double,
        invertPolarity: Boolean
    ): Float {
        if (baselineStd < 0.01) return 50f
        val z = (value - baselineMean) / baselineStd
        val raw = if (invertPolarity) 50f - (z * 15f) else 50f + (z * 15f)
        return raw.toFloat().coerceIn(0f, 100f)
    }

    fun average(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    /**
     * **Sample** standard deviation (Bessel-corrected, divisor n−1).
     *
     * The population divisor understates the spread by ~18% at n=3, which is exactly the
     * small-sample case where the estimate is least trustworthy — and understating the
     * std inflates every z-score built on it.
     */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = average(values)
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    /**
     * Circular standard deviation for clock times, in minutes.
     *
     * A plain std treats 23:50 (1430) and 00:10 (10) as 1420 minutes apart when they are
     * 20 minutes apart, so a consistent sleeper who drifts either side of midnight was
     * handed a catastrophic consistency score. Map each time to an angle, average the
     * unit vectors, and take the circular SD from the resultant length:
     * `SD = sqrt(−2·ln(R))`.
     *
     * Reference: Mardia KV & Jupp PE (2000) Directional Statistics. Wiley.
     */
    fun circularStdDevMinutes(minutesOfDay: List<Int>): Double {
        if (minutesOfDay.size < 2) return 0.0
        val radiansPerMinute = 2.0 * Math.PI / 1440.0
        var sumSin = 0.0
        var sumCos = 0.0
        for (m in minutesOfDay) {
            val angle = m * radiansPerMinute
            sumSin += kotlin.math.sin(angle)
            sumCos += kotlin.math.cos(angle)
        }
        val n = minutesOfDay.size
        val resultantLength = kotlin.math.sqrt(sumSin * sumSin + sumCos * sumCos) / n
        // R == 0 means perfectly dispersed; R == 1 means identical. Clamp away from both
        // so the log stays finite.
        val r = resultantLength.coerceIn(1e-9, 1.0)
        val circularSdRadians = kotlin.math.sqrt(-2.0 * kotlin.math.ln(r))
        return circularSdRadians / radiansPerMinute
    }

    /**
     * Circular mean of clock times, in minutes past midnight.
     * Returns null for an empty list or a perfectly dispersed set with no meaningful mean.
     */
    fun circularMeanMinutes(minutesOfDay: List<Int>): Int? {
        if (minutesOfDay.isEmpty()) return null
        val radiansPerMinute = 2.0 * Math.PI / 1440.0
        val sumSin = minutesOfDay.sumOf { kotlin.math.sin(it * radiansPerMinute) }
        val sumCos = minutesOfDay.sumOf { kotlin.math.cos(it * radiansPerMinute) }
        if (kotlin.math.abs(sumSin) < 1e-9 && kotlin.math.abs(sumCos) < 1e-9) return null
        var angle = kotlin.math.atan2(sumSin, sumCos)
        if (angle < 0) angle += 2.0 * Math.PI
        return ((angle / radiansPerMinute).toInt()) % 1440
    }

    /** Linear slope of a series — positive = trending up, negative = trending down. */
    fun linearSlope(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val n = values.size
        val xMean = (n - 1) / 2.0
        val yMean = values.average()
        val num = values.indices.sumOf { i -> (i - xMean) * (values[i] - yMean) }
        val den = values.indices.sumOf { i -> (i - xMean) * (i - xMean) }
        return if (den < 0.001) 0f else (num / den).toFloat()
    }

    /** Weighted blend between two values. weight = 0.0 → all [a], 1.0 → all [b]. */
    fun blend(a: Double, b: Double, weight: Double): Double =
        a * (1.0 - weight) + b * weight
}
