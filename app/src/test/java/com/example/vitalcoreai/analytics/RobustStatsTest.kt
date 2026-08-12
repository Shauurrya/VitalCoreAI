package com.example.vitalcoreai.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness tests for [RobustStats].
 *
 * Reference values are computed independently (by hand, or from the standard tables /
 * R equivalents named in each test) rather than by running this implementation and
 * pasting the answer — a test that mirrors the implementation cannot fail when the
 * implementation is wrong.
 */
class RobustStatsTest {

    private val eps = 1e-9

    // ── Location and scale ───────────────────────────────────────────────────

    @Test
    fun `median handles odd and even lengths`() {
        assertEquals(2.0, RobustStats.median(listOf(1.0, 3.0, 2.0))!!, eps)
        assertEquals(2.5, RobustStats.median(listOf(1.0, 2.0, 3.0, 4.0))!!, eps)
        assertNull(RobustStats.median(emptyList()))
    }

    @Test
    fun `percentile interpolates linearly`() {
        val v = listOf(1.0, 2.0, 3.0, 4.0)
        // rank = 0.25 * 3 = 0.75 -> between 1.0 and 2.0
        assertEquals(1.75, RobustStats.percentile(v, 0.25)!!, eps)
        assertEquals(4.0, RobustStats.percentile(v, 1.0)!!, eps)
        assertEquals(1.0, RobustStats.percentile(v, 0.0)!!, eps)
    }

    @Test
    fun `mad is the median of absolute deviations`() {
        // median = 3; deviations = 2,1,0,1,2 -> median 1
        assertEquals(1.0, RobustStats.mad(listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!, eps)
    }

    @Test
    fun `robustSigma applies the normal-consistency constant`() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(RobustStats.MAD_TO_SIGMA, RobustStats.robustSigma(v)!!, 1e-6)
    }

    @Test
    fun `robustSigma is null below three samples`() {
        assertNull(RobustStats.robustSigma(listOf(1.0, 2.0)))
    }

    /**
     * The whole reason this file exists: a contaminating point already sitting in the
     * baseline window must not blind the detector to the *next* elevated reading.
     *
     * History here holds six stable days plus one 75 bpm spike. That spike drags the mean
     * up and inflates the SD, so a genuine 70 bpm reading scores only ~1.3 sigma — under
     * any sane alarm threshold. The median and MAD are untouched by it, so the same
     * reading scores ~6 sigma.
     */
    @Test
    fun `robust z detects an elevation that a contaminated sd baseline would mask`() {
        val historyWithSpike = listOf(60.0, 61.0, 60.0, 62.0, 61.0, 60.0, 75.0)
        val todaysValue = 70.0

        val classicMean = historyWithSpike.average()
        val classicSd = BaselineUtils.stdDev(historyWithSpike)
        val classicZ = (todaysValue - classicMean) / classicSd

        val robustZ = RobustStats.robustZ(todaysValue, historyWithSpike, minSigma = 0.5)!!

        assertTrue("sd-based z is masked below the alarm threshold", classicZ < 2.0)
        assertTrue("robust z should exceed sd-based z", robustZ > classicZ)
        assertTrue("robust z should flag it clearly", robustZ > 3.0)
    }

    @Test
    fun `robust z respects the noise floor for a perfectly constant series`() {
        val constant = List(10) { 60.0 }
        // MAD is 0; without the floor this divides by zero and returns infinity.
        val z = RobustStats.robustZ(63.0, constant, minSigma = 2.5)!!
        assertEquals(1.2, z, 1e-9)
        assertTrue(z.isFinite())
    }

    @Test
    fun `robust z is null with insufficient reference data`() {
        assertNull(RobustStats.robustZ(70.0, listOf(60.0, 61.0), minSigma = 2.5))
    }

    // ── Outliers ─────────────────────────────────────────────────────────────

    @Test
    fun `withoutOutliers removes a tukey-fence violator`() {
        val v = listOf(60.0, 61.0, 62.0, 60.0, 61.0, 62.0, 61.0, 120.0)
        val cleaned = RobustStats.withoutOutliers(v)
        assertTrue("the 120 should be dropped", 120.0 !in cleaned)
        assertEquals(v.size - 1, cleaned.size)
    }

    @Test
    fun `withoutOutliers is a no-op on short series`() {
        val v = listOf(60.0, 120.0, 61.0)
        assertEquals(v, RobustStats.withoutOutliers(v))
    }

    @Test
    fun `withoutOutliers refuses to gut the series`() {
        // Bimodal data where the fence would reject most points: keep everything.
        val v = listOf(1.0, 1.0, 1.0, 100.0, 100.0, 100.0)
        assertEquals(v, RobustStats.withoutOutliers(v))
    }

    // ── Recency weighting ────────────────────────────────────────────────────

    @Test
    fun `recencyWeightedMean leans toward the newest values`() {
        // Oldest first: an old low block and a recent high block.
        val v = listOf(50.0, 50.0, 50.0, 50.0, 80.0, 80.0, 80.0, 80.0)
        val plain = v.average()
        val weighted = RobustStats.recencyWeightedMean(v, halfLifeDays = 3.0)!!
        assertTrue("weighted mean should exceed the plain mean", weighted > plain)
        assertTrue(weighted < 80.0)
    }

    @Test
    fun `recencyWeightedMean equals the mean for a constant series`() {
        val v = List(10) { 42.0 }
        assertEquals(42.0, RobustStats.recencyWeightedMean(v, 7.0)!!, 1e-9)
    }

    @Test
    fun `recencyWeightedMean degrades to a plain mean for a non-positive half life`() {
        val v = listOf(1.0, 2.0, 3.0)
        assertEquals(2.0, RobustStats.recencyWeightedMean(v, 0.0)!!, eps)
    }

    // ── Trend ────────────────────────────────────────────────────────────────

    @Test
    fun `theilSen recovers an exact linear slope`() {
        val v = (0..9).map { 10.0 + 2.0 * it }
        assertEquals(2.0, RobustStats.theilSenSlope(v)!!, 1e-9)
    }

    @Test
    fun `theilSen resists a single corrupted endpoint`() {
        val clean = (0..9).map { 10.0 + 2.0 * it }
        val corrupted = clean.toMutableList().also { it[9] = -500.0 }
        val slope = RobustStats.theilSenSlope(corrupted)!!
        assertTrue("Theil-Sen should stay positive despite the outlier", slope > 1.0)
    }

    /**
     * Mann-Kendall on a strictly increasing series of 10 points gives S = 45,
     * var(S) = 10*9*25/18 = 125, z = (45-1)/sqrt(125) = 3.936, p ≈ 8.3e-5.
     */
    @Test
    fun `mannKendall p-value matches the hand-computed normal approximation`() {
        val v = (1..10).map { it.toDouble() }
        val p = RobustStats.mannKendallPValue(v)
        assertEquals(8.3e-5, p, 5e-6)
    }

    @Test
    fun `mannKendall reports no evidence for a flat series`() {
        val v = List(10) { 50.0 }
        assertEquals(1.0, RobustStats.mannKendallPValue(v), 1e-9)
    }

    @Test
    fun `mannKendall returns no evidence below four points`() {
        assertEquals(1.0, RobustStats.mannKendallPValue(listOf(1.0, 2.0, 3.0)), eps)
    }

    @Test
    fun `trendOf reports INSUFFICIENT_DATA rather than guessing`() {
        val r = RobustStats.trendOf(listOf(50.0, 60.0, 70.0))
        assertEquals(RobustStats.Trend.INSUFFICIENT_DATA, r.direction)
        assertTrue(!r.isMeaningful)
    }

    @Test
    fun `trendOf detects a genuine improvement`() {
        val v = listOf(60.0, 62.0, 64.0, 66.0, 68.0, 70.0, 72.0, 74.0)
        val r = RobustStats.trendOf(v, higherIsBetter = true)
        assertEquals(RobustStats.Trend.IMPROVING, r.direction)
        assertTrue(r.significance > 0.95)
        assertEquals(14.0, r.changeOverWindow, 1e-6)
    }

    /**
     * The same rising series is a DECLINE for a metric where lower is better —
     * this is what stops the UI telling a user their rising resting HR is "improving".
     */
    @Test
    fun `trendOf inverts direction when lower is better`() {
        val risingRhr = listOf(58.0, 59.0, 61.0, 62.0, 64.0, 65.0, 67.0, 68.0)
        val r = RobustStats.trendOf(risingRhr, higherIsBetter = false)
        assertEquals(RobustStats.Trend.DECLINING, r.direction)
    }

    @Test
    fun `trendOf calls an erratic series VARIABLE rather than stable`() {
        val v = listOf(50.0, 85.0, 45.0, 90.0, 40.0, 88.0, 42.0, 91.0)
        val r = RobustStats.trendOf(v, higherIsBetter = true)
        assertEquals(RobustStats.Trend.VARIABLE, r.direction)
    }

    @Test
    fun `trendOf calls a tight series STABLE`() {
        val v = listOf(70.0, 71.0, 70.0, 69.0, 70.0, 71.0, 70.0, 69.0)
        val r = RobustStats.trendOf(v, higherIsBetter = true)
        assertEquals(RobustStats.Trend.STABLE, r.direction)
    }

    @Test
    fun `trendOf ignores a significant but trivially small drift`() {
        // Monotonic and significant, but only 0.01 units/day.
        val v = (0..19).map { 70.0 + 0.01 * it }
        val r = RobustStats.trendOf(v, higherIsBetter = true, minSlopePerDay = 0.5)
        assertTrue(
            "a 0.01/day drift must not be announced as a trend",
            r.direction == RobustStats.Trend.STABLE || r.direction == RobustStats.Trend.VARIABLE
        )
    }

    // ── Association ──────────────────────────────────────────────────────────

    @Test
    fun `pearson is exactly one for a perfect positive relationship`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(2.0, 4.0, 6.0, 8.0, 10.0)
        assertEquals(1.0, RobustStats.pearson(x, y)!!, 1e-9)
    }

    @Test
    fun `pearson is exactly minus one for a perfect inverse relationship`() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val y = listOf(10.0, 8.0, 6.0, 4.0, 2.0)
        assertEquals(-1.0, RobustStats.pearson(x, y)!!, 1e-9)
    }

    @Test
    fun `pearson is null for a constant series`() {
        assertNull(RobustStats.pearson(listOf(1.0, 2.0, 3.0), listOf(5.0, 5.0, 5.0)))
    }

    /** r = 0.5, n = 12 -> t = 1.8257, df = 10, two-sided p ≈ 0.0978 (R: 2*pt(-1.8257,10)). */
    @Test
    fun `pearson p-value matches the t-transform reference`() {
        assertEquals(0.0978, RobustStats.pearsonPValue(0.5, 12), 0.002)
    }

    @Test
    fun `standard normal cdf matches known quantiles`() {
        assertEquals(0.5, RobustStats.standardNormalCdf(0.0), 1e-7)
        assertEquals(0.8413447, RobustStats.standardNormalCdf(1.0), 1e-6)
        assertEquals(0.9772499, RobustStats.standardNormalCdf(2.0), 1e-6)
        assertEquals(0.9986501, RobustStats.standardNormalCdf(3.0), 1e-6)
    }

    @Test
    fun `student t cdf matches known critical values`() {
        // t(0.975, df=10) = 2.228; so CDF(2.228, 10) ~= 0.975
        assertEquals(0.975, RobustStats.studentTCdf(2.228, 10.0), 1e-3)
        // t(0.95, df=20) = 1.725
        assertEquals(0.95, RobustStats.studentTCdf(1.725, 20.0), 1e-3)
        assertEquals(0.5, RobustStats.studentTCdf(0.0, 5.0), 1e-9)
    }

    // ── Welch ────────────────────────────────────────────────────────────────

    @Test
    fun `welch detects a real difference between two groups`() {
        val noLateCaffeine = listOf(470.0, 480.0, 465.0, 475.0, 485.0, 472.0, 478.0, 468.0)
        val lateCaffeine = listOf(415.0, 420.0, 405.0, 425.0, 410.0, 418.0)
        val r = RobustStats.welchTTest(noLateCaffeine, lateCaffeine)!!
        assertTrue(r.isSignificant)
        assertTrue("no-caffeine nights should be longer", r.difference > 0)
        assertEquals(8, r.nA)
        assertEquals(6, r.nB)
    }

    @Test
    fun `welch finds nothing between two samples of the same distribution`() {
        val a = listOf(470.0, 480.0, 465.0, 475.0, 485.0, 472.0)
        val b = listOf(474.0, 468.0, 482.0, 471.0, 479.0, 466.0)
        val r = RobustStats.welchTTest(a, b)!!
        assertTrue("p should be large for identical distributions", r.pValue > 0.2)
        assertTrue(!r.isSignificant)
    }

    @Test
    fun `welch is null when a group is too small`() {
        assertNull(RobustStats.welchTTest(listOf(1.0), listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun `welch is null when both groups are identical constants`() {
        assertNull(RobustStats.welchTTest(List(5) { 7.0 }, List(5) { 7.0 }))
    }

    // ── Degenerate inputs ────────────────────────────────────────────────────

    @Test
    fun `every entry point survives empty input`() {
        assertNull(RobustStats.median(emptyList()))
        assertNull(RobustStats.mad(emptyList()))
        assertNull(RobustStats.robustSigma(emptyList()))
        assertNull(RobustStats.theilSenSlope(emptyList()))
        assertNull(RobustStats.recencyWeightedMean(emptyList()))
        assertNull(RobustStats.outlierBounds(emptyList()))
        assertEquals(emptyList<Double>(), RobustStats.withoutOutliers(emptyList()))
        assertEquals(emptyList<Int>(), RobustStats.outlierIndices(emptyList()))
        assertEquals(
            RobustStats.Trend.INSUFFICIENT_DATA,
            RobustStats.trendOf(emptyList()).direction
        )
        assertNotNull(RobustStats.mannKendallPValue(emptyList()))
    }
}
