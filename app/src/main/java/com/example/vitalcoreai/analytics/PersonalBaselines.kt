package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Personal Baselines 2.0 — the "Current / Baseline / Deviation / Trend / Confidence"
 * view the product spec asks for, computed once and reused by anomaly detection, the
 * readiness forecast, the trend engine and the coach context.
 *
 * ## Relationship to [BaselineManager]
 *
 * [BaselineManager] is unchanged and still owns *score normalisation*: it blends a
 * personal mean/SD with a population prior so a brand-new user gets a usable z-score on
 * day one. That blending is right for scoring and wrong for anomaly detection — you
 * cannot call a value "unusual **for you**" against a distribution that is 80% population
 * average.
 *
 * So this file deliberately does something different:
 *
 * | | [BaselineManager] | [PersonalBaselines] |
 * |---|---|---|
 * | Centre | blended mean | **median of the user's own data** |
 * | Scale | blended SD | **MAD × 1.4826**, floored at the metric's noise |
 * | Population prior | yes, weighted by history | **no** — reports low confidence instead |
 * | Outliers | included | **excluded from the baseline**, kept in the test value |
 * | Purpose | 0–100 scores | deviation, trend, anomalies, forecast |
 *
 * Both are correct for their own job. Nothing here replaces the scoring path.
 */
object PersonalBaselines {

    /**
     * A metric that can be baselined.
     *
     * @param higherIsBetter drives whether a positive deviation reads as good or bad.
     * @param minSigma physiological noise floor in the metric's own units. Mandatory —
     *                 see [RobustStats.robustZ] for why a missing floor manufactures
     *                 anomalies out of a stable signal.
     * @param decimals display precision. Health data does not deserve more.
     */
    enum class TrackedMetric(
        val displayName: String,
        val unit: String,
        val higherIsBetter: Boolean,
        val minSigma: Double,
        val decimals: Int = 0
    ) {
        RESTING_HR("Resting HR", "BPM", higherIsBetter = false, minSigma = 2.5),
        SLEEP_DURATION("Sleep Duration", "min", higherIsBetter = true, minSigma = 15.0),
        SLEEP_EFFICIENCY("Sleep Efficiency", "%", higherIsBetter = true, minSigma = 3.0),
        BEDTIME("Bedtime", "", higherIsBetter = false, minSigma = 20.0),
        WAKE_TIME("Wake Time", "", higherIsBetter = false, minSigma = 20.0),
        STEPS("Steps", "steps", higherIsBetter = true, minSigma = 500.0),
        RECOVERY("Recovery", "/100", higherIsBetter = true, minSigma = 4.0),
        READINESS("Readiness", "/100", higherIsBetter = true, minSigma = 4.0),
        SLEEP_SCORE("Sleep Score", "/100", higherIsBetter = true, minSigma = 4.0),
        STRAIN("Strain", "", higherIsBetter = true, minSigma = 1.0, decimals = 1),
        HRR1("1-Min HR Recovery", "BPM", higherIsBetter = true, minSigma = 3.0),
        ENERGY_BANK("Energy Bank", "/100", higherIsBetter = true, minSigma = 4.0),
        CHECKIN_ENERGY("Energy", "/10", higherIsBetter = true, minSigma = 1.0),
        CHECKIN_STRESS("Stress", "/10", higherIsBetter = false, minSigma = 1.0),
        CHECKIN_SORENESS("Soreness", "/10", higherIsBetter = false, minSigma = 1.0)
    }

    /** How a value sits relative to the personal baseline. */
    enum class DeviationBand(val label: String) {
        WELL_BELOW("Well below your normal range"),
        BELOW("Below your normal range"),
        NORMAL("Within your normal range"),
        ABOVE("Above your normal range"),
        WELL_ABOVE("Well above your normal range")
    }

    /**
     * Everything known about one metric today.
     *
     * @param deviationSigma robust z-score; null when history is too thin to support one.
     *                       Callers must treat null as "unknown", never as zero.
     */
    data class MetricSnapshot(
        val metric: TrackedMetric,
        val current: Double?,
        val baseline: Double?,
        val deviation: Double?,
        val deviationSigma: Double?,
        val band: DeviationBand,
        val trend: RobustStats.TrendResult,
        val confidence: Confidence,
        val sampleSize: Int,
        val outliersExcluded: Int
    ) {
        val hasBaseline: Boolean get() = baseline != null

        /** "71 BPM" */
        val displayCurrent: String get() = format(current)

        /** "62 BPM" */
        val displayBaseline: String get() = format(baseline)

        /** "+9 BPM" — always signed, so the direction is unmissable. */
        val displayDeviation: String
            get() {
                val d = deviation ?: return "—"
                val sign = if (d > 0) "+" else if (d < 0) "-" else ""
                return "$sign${formatNumber(abs(d))}${unitSuffix()}"
            }

        /** "Elevated" / "Improving" / "Stable" — user-facing, never clinical. */
        val trendLabel: String
            get() = when (trend.direction) {
                RobustStats.Trend.IMPROVING -> "Improving"
                RobustStats.Trend.DECLINING -> "Declining"
                RobustStats.Trend.VARIABLE -> "Highly variable"
                RobustStats.Trend.STABLE -> "Stable"
                RobustStats.Trend.INSUFFICIENT_DATA -> "Not enough history"
            }

        /**
         * The compact block the spec asks for:
         * ```
         * RHR
         * Today: 71 BPM
         * Baseline: 62 BPM
         * Deviation: +9 BPM
         * Trend: Elevated
         * Confidence: High
         * ```
         */
        fun describe(): String = buildString {
            appendLine(metric.displayName)
            appendLine("Today: $displayCurrent")
            appendLine("Baseline: $displayBaseline")
            appendLine("Deviation: $displayDeviation")
            appendLine("Trend: $trendLabel")
            append("Confidence: ${confidence.name.lowercase().replaceFirstChar { it.uppercase() }}")
        }

        private fun unitSuffix(): String =
            if (metric.unit.isBlank()) "" else " ${metric.unit}"

        private fun format(v: Double?): String =
            if (v == null) "—" else "${formatNumber(v)}${unitSuffix()}"

        private fun formatNumber(v: Double): String =
            if (metric.decimals == 0) v.roundToInt().toString()
            else {
                val scale = when (metric.decimals) {
                    1 -> 10.0; 2 -> 100.0; else -> 1.0
                }
                val r = (v * scale).roundToInt()
                "${r / scale.toInt()}.${abs(r % scale.toInt())}"
            }
    }

    // ── Thresholds ───────────────────────────────────────────────────────────

    /**
     * Minimum personal observations before a baseline is reported at all.
     *
     * Three is the floor at which a median and a MAD are defined. It is *not* enough for
     * confidence — see [confidenceFor] — but reporting "we need more data" while silently
     * holding three usable points would be its own kind of dishonesty.
     */
    const val MIN_SAMPLES = 3
    const val SAMPLES_FOR_MEDIUM = 7
    const val SAMPLES_FOR_HIGH = 21

    private const val BAND_MILD = 1.0
    private const val BAND_STRONG = 2.0

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Build a snapshot for one metric.
     *
     * @param history the metric's own recent values, **oldest first, excluding [current]**.
     *                Including today would compare a value against a distribution that
     *                contains it, which pulls the median toward the anomaly and inflates
     *                the scale — systematically hiding the deviations that matter most.
     * @param current today's value; null is valid and yields a snapshot with no deviation.
     * @param trendWindow how many trailing points feed the trend test.
     */
    fun snapshot(
        metric: TrackedMetric,
        current: Double?,
        history: List<Double>,
        trendWindow: Int = 14
    ): MetricSnapshot {
        val clean = RobustStats.withoutOutliers(history)
        val excluded = history.size - clean.size

        val baseline = if (clean.size >= MIN_SAMPLES) RobustStats.median(clean) else null
        val deviation = if (baseline != null && current != null) current - baseline else null

        val sigma = if (current != null && clean.size >= MIN_SAMPLES) {
            RobustStats.robustZ(current, clean, metric.minSigma)
        } else null

        // The trend runs over history *plus* today, because a trend is about the series
        // as a whole — unlike the baseline, which must exclude the point being judged.
        val trendSeries = (history + listOfNotNull(current)).takeLast(trendWindow)
        val trend = RobustStats.trendOf(
            values = trendSeries,
            higherIsBetter = metric.higherIsBetter,
            minSlopePerDay = metric.minSigma / 20.0,
            minSamples = 5
        )

        return MetricSnapshot(
            metric = metric,
            current = current,
            baseline = baseline,
            deviation = deviation,
            deviationSigma = sigma,
            band = bandFor(sigma),
            trend = trend,
            confidence = confidenceFor(clean.size),
            sampleSize = clean.size,
            outliersExcluded = excluded
        )
    }

    /**
     * Confidence in the *baseline itself*, from sample size alone.
     *
     * This is intentionally narrower than [DataQualityEngine], which judges a whole
     * score's inputs. A baseline can be perfectly trustworthy on a day the user's sleep
     * did not record, and vice versa; conflating the two produced confidence badges that
     * moved for reasons the user could not connect to anything.
     */
    fun confidenceFor(sampleSize: Int): Confidence = when {
        sampleSize >= SAMPLES_FOR_HIGH -> Confidence.HIGH
        sampleSize >= SAMPLES_FOR_MEDIUM -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /** Signed sigma → band. Null sigma is NORMAL, because unknown must not read as alarming. */
    fun bandFor(sigma: Double?): DeviationBand {
        if (sigma == null) return DeviationBand.NORMAL
        return when {
            sigma <= -BAND_STRONG -> DeviationBand.WELL_BELOW
            sigma <= -BAND_MILD -> DeviationBand.BELOW
            sigma >= BAND_STRONG -> DeviationBand.WELL_ABOVE
            sigma >= BAND_MILD -> DeviationBand.ABOVE
            else -> DeviationBand.NORMAL
        }
    }

    /**
     * Is this deviation in the direction that is bad for the user?
     * Used by the anomaly engine to decide whether a deviation is worth surfacing at all —
     * a resting HR 8 bpm *below* baseline is not something to warn anyone about.
     */
    fun isAdverse(snapshot: MetricSnapshot): Boolean {
        val sigma = snapshot.deviationSigma ?: return false
        return if (snapshot.metric.higherIsBetter) sigma < 0 else sigma > 0
    }
}
