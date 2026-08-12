package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust statistics for short, noisy, gap-ridden personal health series.
 *
 * ## Why not just mean and standard deviation
 *
 * [BaselineUtils] already provides mean/SD, and they remain correct for the score
 * normalisation they were written for. They are the wrong tools for *anomaly detection*
 * and *trend detection* on this data, for two reasons:
 *
 * 1. **The outlier contaminates its own detector.** A single 12 bpm resting-HR spike
 *    raises the mean and inflates the SD, so the z-score of the spike shrinks and it
 *    hides itself. With n = 7 (one week of history) one bad value moves the SD enough to
 *    mask a genuine 3-sigma event. The median and MAD have a 50% breakdown point — half
 *    the series would have to be corrupt before they move.
 *
 * 2. **A slope is not a trend.** Ordinary least squares on 7 noisy points will always
 *    return a non-zero slope, so "is my recovery improving?" answered with OLS is a coin
 *    flip dressed as an insight. Mann-Kendall tests whether the monotonic ordering itself
 *    is unlikely under chance, and Theil-Sen estimates the magnitude without letting one
 *    endpoint dominate.
 *
 * Series ordering convention throughout this file: **oldest first, newest last** — the
 * same contract [BaselineManager] uses.
 *
 * References:
 * - Rousseeuw PJ & Croux C (1993) J Am Stat Assoc 88(424):1273-1283 (MAD, robust scale)
 * - Sen PK (1968) J Am Stat Assoc 63(324):1379-1389 (Theil-Sen slope)
 * - Mann HB (1945) Econometrica 13(3):245-259; Kendall MG (1975) Rank Correlation Methods
 */
object RobustStats {

    /**
     * Scale factor making MAD a consistent estimator of sigma for normally distributed
     * data: `sigma ≈ 1.4826 · MAD`. Without it, MAD-based thresholds would be ~32%
     * tighter than the sigma thresholds people intuitively expect from "3 sigma".
     */
    const val MAD_TO_SIGMA = 1.4826

    // ── Location and scale ───────────────────────────────────────────────────

    /** Null for an empty list — callers must decide what "no baseline" means. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * Linear-interpolated percentile. [p] is 0..1.
     * Uses the same definition as NumPy's default so results are checkable by hand.
     */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        if (values.size == 1) return values[0]
        val s = values.sorted()
        val rank = p.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = kotlin.math.floor(rank).toInt()
        val hi = kotlin.math.ceil(rank).toInt()
        if (lo == hi) return s[lo]
        return s[lo] + (s[hi] - s[lo]) * (rank - lo)
    }

    /** Median absolute deviation from the median. Null when undefined. */
    fun mad(values: List<Double>): Double? {
        val m = median(values) ?: return null
        return median(values.map { abs(it - m) })
    }

    /**
     * MAD rescaled to be comparable with a standard deviation.
     *
     * Returns null when the series is too short to have a meaningful scale (n < 3).
     * Returns 0.0 for a genuinely constant series — callers must apply their own noise
     * floor (see [BaselineManager.MinStd]) rather than dividing by it.
     */
    fun robustSigma(values: List<Double>): Double? {
        if (values.size < 3) return null
        val d = mad(values) ?: return null
        return d * MAD_TO_SIGMA
    }

    /**
     * Robust z-score of [value] against the distribution of [reference].
     *
     * [minSigma] is the metric's physiological noise floor and is mandatory: without it a
     * user with a genuinely stable signal gets a near-zero denominator and every
     * millimetre of noise becomes a 10-sigma "anomaly".
     *
     * Returns null when [reference] is too short to support the estimate.
     */
    fun robustZ(value: Double, reference: List<Double>, minSigma: Double): Double? {
        if (reference.size < 3) return null
        val m = median(reference) ?: return null
        val sigma = max(robustSigma(reference) ?: 0.0, minSigma)
        if (sigma <= 0.0) return null
        return (value - m) / sigma
    }

    // ── Outlier handling ─────────────────────────────────────────────────────

    /**
     * Tukey fences: `[Q1 − k·IQR, Q3 + k·IQR]`, k = 1.5 by convention.
     * Null when the series is too short for quartiles to mean anything.
     */
    fun outlierBounds(values: List<Double>, k: Double = 1.5): ClosedFloatingPointRange<Double>? {
        if (values.size < 4) return null
        val q1 = percentile(values, 0.25) ?: return null
        val q3 = percentile(values, 0.75) ?: return null
        val iqr = q3 - q1
        if (iqr <= 0.0) return null
        return (q1 - k * iqr)..(q3 + k * iqr)
    }

    /**
     * Drop values outside the Tukey fences.
     *
     * Deliberately conservative: with fewer than 4 points, or a zero IQR, the input is
     * returned untouched. Removing points from a 3-day baseline would do more damage
     * than the outlier does.
     *
     * This is for *baseline construction* only. Never filter the series you are testing
     * for anomalies — that would delete the thing you are looking for.
     */
    fun withoutOutliers(values: List<Double>, k: Double = 1.5): List<Double> {
        val bounds = outlierBounds(values, k) ?: return values
        val kept = values.filter { it in bounds }
        // Never hand back an empty or near-empty series; if the fence rejected most of the
        // data the fence is wrong, not the data.
        return if (kept.size >= max(3, values.size / 2)) kept else values
    }

    /** Indices of values outside the Tukey fences, oldest-first ordering preserved. */
    fun outlierIndices(values: List<Double>, k: Double = 1.5): List<Int> {
        val bounds = outlierBounds(values, k) ?: return emptyList()
        return values.indices.filter { values[it] !in bounds }
    }

    // ── Recency weighting ────────────────────────────────────────────────────

    /**
     * Exponentially recency-weighted mean; [values] oldest first.
     *
     * Weight of a point [age] positions back is `0.5 ^ (age / halfLife)`. A 7-day
     * half-life over a 30-day window means last week carries about as much weight as the
     * preceding three combined — which is the correct shape for a baseline that must
     * track genuine fitness change without chasing a single bad night.
     *
     * @param halfLifeDays must be > 0; a non-positive value degrades to an unweighted mean.
     */
    fun recencyWeightedMean(values: List<Double>, halfLifeDays: Double = 7.0): Double? {
        if (values.isEmpty()) return null
        if (halfLifeDays <= 0.0) return values.average()
        val newestIndex = values.size - 1
        var num = 0.0
        var den = 0.0
        for (i in values.indices) {
            val age = (newestIndex - i).toDouble()
            val w = exp(-ln(2.0) * age / halfLifeDays)
            num += values[i] * w
            den += w
        }
        return if (den <= 0.0) null else num / den
    }

    // ── Trend ────────────────────────────────────────────────────────────────

    /**
     * Theil-Sen slope: the median of all pairwise slopes. Units are "per position",
     * i.e. per day for a daily series.
     *
     * Robust to up to ~29% contaminated points, unlike OLS which one outlier can flip.
     */
    fun theilSenSlope(values: List<Double>): Double? {
        if (values.size < 3) return null
        val slopes = ArrayList<Double>(values.size * (values.size - 1) / 2)
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                slopes += (values[j] - values[i]) / (j - i).toDouble()
            }
        }
        return median(slopes)
    }

    /** Classification returned by [trendOf]. */
    enum class Trend { IMPROVING, STABLE, DECLINING, VARIABLE, INSUFFICIENT_DATA }

    /**
     * @param direction        classified trend
     * @param slopePerDay      Theil-Sen slope in the metric's own units per day
     * @param changeOverWindow slope × (n − 1) — the total change the trend accounts for
     * @param significance     0..1, `1 − p` from the Mann-Kendall test; higher = less
     *                         likely to be chance
     * @param coefficientOfVariation  robust CV, used to detect VARIABLE
     */
    data class TrendResult(
        val direction: Trend,
        val slopePerDay: Double,
        val changeOverWindow: Double,
        val significance: Double,
        val coefficientOfVariation: Double,
        val sampleSize: Int
    ) {
        val isMeaningful: Boolean get() = direction != Trend.INSUFFICIENT_DATA
    }

    /**
     * Classify the trend of a series, oldest first.
     *
     * Order of decisions matters and is deliberate:
     * 1. Too few points → INSUFFICIENT_DATA. Never guess a direction from 3 days.
     * 2. Statistically significant monotonic movement → IMPROVING / DECLINING.
     * 3. Otherwise, if the series is unusually dispersed → VARIABLE. A user bouncing
     *    50→80→45→85 is not "stable", and telling them so is worse than saying nothing.
     * 4. Otherwise STABLE.
     *
     * @param higherIsBetter false for metrics where a rise is bad (resting HR, sleep debt),
     *                       so IMPROVING always means "good for the user".
     * @param minSlopePerDay  ignore movement smaller than this per day even if significant;
     *                        prevents a statistically real but clinically meaningless
     *                        0.05 bpm/day drift being announced as a trend.
     * @param variableCvThreshold robust CV above which the series is called VARIABLE.
     */
    fun trendOf(
        values: List<Double>,
        higherIsBetter: Boolean = true,
        minSlopePerDay: Double = 0.0,
        variableCvThreshold: Double = 0.25,
        minSamples: Int = 5
    ): TrendResult {
        val n = values.size
        if (n < minSamples) {
            return TrendResult(Trend.INSUFFICIENT_DATA, 0.0, 0.0, 0.0, 0.0, n)
        }

        val slope = theilSenSlope(values) ?: 0.0
        val change = slope * (n - 1)
        val significance = 1.0 - mannKendallPValue(values)

        val med = median(values) ?: 0.0
        val sigma = robustSigma(values) ?: 0.0
        val cv = if (abs(med) < 1e-9) 0.0 else sigma / abs(med)

        val significant = significance >= 0.90 && abs(slope) >= minSlopePerDay
        val improving = if (higherIsBetter) slope > 0 else slope < 0

        val direction = when {
            significant && improving -> Trend.IMPROVING
            significant && !improving -> Trend.DECLINING
            cv >= variableCvThreshold -> Trend.VARIABLE
            else -> Trend.STABLE
        }

        return TrendResult(direction, slope, change, significance, cv, n)
    }

    /**
     * Two-sided p-value of the Mann-Kendall test for monotonic trend.
     *
     * Uses the normal approximation with continuity correction and tie handling. Exact for
     * practical purposes at n ≥ 8; for smaller n it is mildly conservative, which is the
     * safe direction — it under-claims trends rather than inventing them.
     *
     * Returns 1.0 (no evidence) when n < 4.
     */
    fun mannKendallPValue(values: List<Double>): Double {
        val n = values.size
        if (n < 4) return 1.0

        var s = 0
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                s += when {
                    values[j] > values[i] -> 1
                    values[j] < values[i] -> -1
                    else -> 0
                }
            }
        }

        // Variance, corrected for tied groups.
        val tieCounts = values.groupBy { it }.values.map { it.size.toLong() }
        val tieTerm = tieCounts.sumOf { t -> t * (t - 1) * (2 * t + 5) }
        val nL = n.toLong()
        val variance = (nL * (nL - 1) * (2 * nL + 5) - tieTerm) / 18.0
        if (variance <= 0.0) return 1.0

        // Continuity correction: S is integer-valued, the normal approximation is not.
        val z = when {
            s > 0 -> (s - 1) / sqrt(variance)
            s < 0 -> (s + 1) / sqrt(variance)
            else -> 0.0
        }
        return (2.0 * (1.0 - standardNormalCdf(abs(z)))).coerceIn(0.0, 1.0)
    }

    // ── Association ──────────────────────────────────────────────────────────

    /**
     * Pearson correlation. Null when either series is constant or shorter than 3.
     *
     * Correlation is *association*, never causation — every caller that surfaces this to
     * a user must word its output accordingly.
     */
    fun pearson(x: List<Double>, y: List<Double>): Double? {
        val n = min(x.size, y.size)
        if (n < 3) return null
        val xs = x.take(n)
        val ys = y.take(n)
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        if (dx <= 1e-12 || dy <= 1e-12) return null
        return (num / sqrt(dx * dy)).coerceIn(-1.0, 1.0)
    }

    /**
     * Two-sided p-value for a Pearson r via the t-transform.
     * `t = r·sqrt((n−2)/(1−r²))` with n−2 degrees of freedom.
     */
    fun pearsonPValue(r: Double, n: Int): Double {
        if (n < 3) return 1.0
        val rr = r.coerceIn(-0.999999, 0.999999)
        val t = abs(rr) * sqrt((n - 2) / (1 - rr * rr))
        return (2.0 * (1.0 - studentTCdf(t, (n - 2).toDouble()))).coerceIn(0.0, 1.0)
    }

    /**
     * Welch's t-test for two independent samples of unequal variance.
     * Returns null when either group is too small to estimate a variance.
     *
     * Used by habit correlation: "sleep on nights after late caffeine" vs "all other
     * nights" are unpaired groups of different sizes, which is exactly Welch's case.
     */
    data class GroupComparison(
        val meanA: Double,
        val meanB: Double,
        val difference: Double,
        val pValue: Double,
        val nA: Int,
        val nB: Int
    ) {
        /** Conventional 0.05 threshold. Callers should also require a practical effect size. */
        val isSignificant: Boolean get() = pValue < 0.05
    }

    fun welchTTest(a: List<Double>, b: List<Double>): GroupComparison? {
        if (a.size < 2 || b.size < 2) return null
        val ma = a.average()
        val mb = b.average()
        val va = a.sumOf { (it - ma) * (it - ma) } / (a.size - 1)
        val vb = b.sumOf { (it - mb) * (it - mb) } / (b.size - 1)
        val sa = va / a.size
        val sb = vb / b.size
        val denom = sa + sb
        if (denom <= 1e-12) return null
        val t = (ma - mb) / sqrt(denom)
        // Welch–Satterthwaite degrees of freedom.
        val dfNum = denom * denom
        val dfDen = (sa * sa) / (a.size - 1) + (sb * sb) / (b.size - 1)
        val df = if (dfDen <= 1e-12) (a.size + b.size - 2).toDouble() else dfNum / dfDen
        val p = (2.0 * (1.0 - studentTCdf(abs(t), df))).coerceIn(0.0, 1.0)
        return GroupComparison(ma, mb, ma - mb, p, a.size, b.size)
    }

    // ── Distribution helpers ─────────────────────────────────────────────────

    /**
     * Standard normal CDF via the Abramowitz & Stegun 7.1.26 erf approximation.
     * Absolute error < 1.5e-7 — far tighter than anything this app's conclusions turn on.
     */
    fun standardNormalCdf(z: Double): Double = 0.5 * (1.0 + erf(z / sqrt(2.0)))

    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    /**
     * Student's t CDF via the regularised incomplete beta function.
     * Accurate to ~1e-10 for the degrees of freedom this app uses.
     */
    fun studentTCdf(t: Double, df: Double): Double {
        if (df <= 0.0) return 0.5
        val x = df / (df + t * t)
        val ib = regularisedIncompleteBeta(df / 2.0, 0.5, x)
        val tail = 0.5 * ib
        return if (t > 0) 1.0 - tail else tail
    }

    /** Continued-fraction evaluation of I_x(a,b). */
    private fun regularisedIncompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b)
        val front = exp(ln(x) * a + ln(1.0 - x) * b - lnBeta) / a
        // Lentz's algorithm.
        var f = 1.0
        var c = 1.0
        var d = 0.0
        for (i in 0..200) {
            val m = i / 2
            val numerator = when {
                i == 0 -> 1.0
                i % 2 == 0 -> (m * (b - m) * x) / ((a + 2.0 * m - 1.0) * (a + 2.0 * m))
                else -> -((a + m) * (a + b + m) * x) / ((a + 2.0 * m) * (a + 2.0 * m + 1.0))
            }
            d = 1.0 + numerator * d
            if (abs(d) < 1e-30) d = 1e-30
            d = 1.0 / d
            c = 1.0 + numerator / c
            if (abs(c) < 1e-30) c = 1e-30
            val cd = c * d
            f *= cd
            if (abs(1.0 - cd) < 1e-12) break
        }
        val result = front * (f - 1.0)
        // The continued fraction converges for x < (a+1)/(a+b+2); use the symmetry
        // relation outside that region.
        return if (x < (a + 1.0) / (a + b + 2.0)) result.coerceIn(0.0, 1.0)
        else (1.0 - regularisedIncompleteBetaDirect(b, a, 1.0 - x)).coerceIn(0.0, 1.0)
    }

    private fun regularisedIncompleteBetaDirect(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b)
        val front = exp(ln(x) * a + ln(1.0 - x) * b - lnBeta) / a
        var f = 1.0
        var c = 1.0
        var d = 0.0
        for (i in 0..200) {
            val m = i / 2
            val numerator = when {
                i == 0 -> 1.0
                i % 2 == 0 -> (m * (b - m) * x) / ((a + 2.0 * m - 1.0) * (a + 2.0 * m))
                else -> -((a + m) * (a + b + m) * x) / ((a + 2.0 * m) * (a + 2.0 * m + 1.0))
            }
            d = 1.0 + numerator * d
            if (abs(d) < 1e-30) d = 1e-30
            d = 1.0 / d
            c = 1.0 + numerator / c
            if (abs(c) < 1e-30) c = 1e-30
            val cd = c * d
            f *= cd
            if (abs(1.0 - cd) < 1e-12) break
        }
        return (front * (f - 1.0)).coerceIn(0.0, 1.0)
    }

    /** Lanczos approximation of ln Γ(x). */
    private fun lnGamma(x: Double): Double {
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7
        )
        if (x < 0.5) {
            return ln(Math.PI / kotlin.math.sin(Math.PI * x)) - lnGamma(1.0 - x)
        }
        val z = x - 1.0
        var a = 0.99999999999980993
        val t = z + 7.5
        for (i in g.indices) a += g[i] / (z + i + 1.0)
        return 0.5 * ln(2.0 * Math.PI) + (z + 0.5) * ln(t) - t + ln(a)
    }
}
