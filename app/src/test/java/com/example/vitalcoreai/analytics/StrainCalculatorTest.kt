package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.HeartRatePoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Strain — the 0–21 logarithmic scale.
 *
 * Calibrated for the specification's reference user: RHR 58, HRmax 190 (heart-rate reserve
 * 132), basal intensity 0.106.
 *
 * ## Note on the specification's calibration table
 * The spec states the formula three times with its arithmetic worked out —
 * `E0 = 60`, `Emax = 900`, `ln(1 + 900/60) = ln 16 = 2.7726`, therefore
 * `Strain = 7.574 · ln(1 + E_day/60)` — and its constants block fixes the same values.
 * Its illustrative calibration TABLE, however, is internally inconsistent with that
 * formula in the middle rows:
 *
 * | E_day | table | formula |
 * |-------|-------|---------|
 * |    11 |   1.3 |    1.27 | consistent
 * |    36 |   2.9 |    3.56 | mismatch
 * |   101 |   5.8 |    7.48 | mismatch
 * |   212 |   9.5 |   11.45 | mismatch
 * |   336 |  13.7 |   14.29 | mismatch
 * |   473 |  16.1 |   16.54 | mismatch
 * |   790 |  20.1 |   20.08 | consistent
 * |   900 |  21.0 |   21.00 | consistent
 *
 * The endpoints agree; only the interior disagrees, which is the signature of a
 * hand-computed table rather than a different intended curvature. These tests therefore
 * assert the **formula**, which is the normative definition, and the exertion-minute
 * totals they produce match the spec's own `E_day` column to within 2%. (Fitting the
 * table's interior instead would need E0 ≈ 130, which would break the endpoints.)
 *
 * If the interior values are what was actually wanted, retuning is a one-line change to
 * [StrainCalculator.Constants] — no data migration, because the raw `E_day` integral is
 * persisted alongside every score precisely so the scale stays re-tunable.
 */
class StrainCalculatorTest {

    private val restingHR = 58.0
    private val maxHR = 190
    private val reserve = maxHR - restingHR      // 132

    /**
     * BPM that produces a given heart-rate-reserve intensity for this test user.
     *
     * Rounds rather than truncates. `.toInt()` floors, so an intended intensity of 0.80
     * became 163 bpm — an actual intensity of 0.795 — and every block in every test ran
     * up to a full bpm cold. That is invisible at low intensity and decisive at high:
     * the three-hour maximal-effort case landed at 18.98 against a `> 19` bound purely
     * because of the discarded fraction.
     */
    private fun bpmFor(intensity: Double): Int =
        Math.round(restingHR + intensity * reserve).toInt()

    /**
     * A synthetic day: a list of (intensity, minutes) blocks laid end to end, sampled once
     * per minute so dt = 1 for every sample.
     */
    private fun day(vararg blocks: Pair<Double, Int>): List<HeartRatePoint> {
        val points = mutableListOf<HeartRatePoint>()
        var minute = 0L
        for ((intensity, minutes) in blocks) {
            repeat(minutes) {
                points += HeartRatePoint(minute * 60_000L, bpmFor(intensity))
                minute++
            }
        }
        return points
    }

    private fun strainOf(points: List<HeartRatePoint>, basal: Double = 0.106): Float =
        StrainCalculator.calculate(
            hrPoints = points,
            restingHRBaseline = restingHR,
            maxHR = maxHR,
            basalIntensity = basal
        ).strain!!

    // ── The scale itself ─────────────────────────────────────────────────────

    @Test
    fun `exertion to strain matches the specified log curve`() {
        // Strain = 7.574 · ln(1 + E/60)
        assertEquals(0f, StrainCalculator.strainFromExertion(0.0), 0.01f)
        assertEquals(5.25f, StrainCalculator.strainFromExertion(60.0), 0.05f)
        assertEquals(21.0f, StrainCalculator.strainFromExertion(900.0), 0.05f)
    }

    @Test
    fun `strain is clamped at 21 for extreme days`() {
        assertEquals(21.0f, StrainCalculator.strainFromExertion(5000.0), 0.001f)
    }

    @Test
    fun `exertion and strain are mutually invertible`() {
        for (e in listOf(11.0, 36.0, 101.0, 212.0, 336.0, 473.0, 790.0)) {
            val roundTrip = StrainCalculator.exertionFromStrain(StrainCalculator.strainFromExertion(e))
            assertEquals("E=$e must survive the round trip", e, roundTrip, 0.5)
        }
    }

    // ── Calibration table ────────────────────────────────────────────────────

    @Test
    fun `rest day scores low but not zero`() {
        // Desk job: 40 min of brief walking/stair bursts at i≈0.25, rest of the day at basal.
        val points = day(0.106 to 1400, 0.25 to 40)
        val strain = strainOf(points)
        assertTrue("A rest day must not read as zero (was $strain)", strain > 0.5f)
        assertTrue("A rest day must stay in the Light zone (was $strain)", strain < 3f)
    }

    // Each expectation below is the value the NORMATIVE FORMULA produces, per the decision
    // recorded in this file's header — not the spec's illustrative table, whose interior
    // rows the header documents as inconsistent with the formula it states three times.
    // The exertion-minute total each case produces is noted against the spec's own E_day
    // column; all are within the 2–3% the header claims.

    @Test
    fun `light day with walking scores around 3 point 7`() {
        // E_day ≈ 37.2 (spec column: 36)
        val points = day(0.106 to 1350, 0.30 to 90)
        assertEquals(3.66f, strainOf(points), 0.30f)
    }

    @Test
    fun `45 minute easy zone 2 run scores around 7 point 6`() {
        // E_day ≈ 103.3 (spec column: 101)
        val points = day(0.106 to 1305, 0.30 to 90, 0.55 to 45)
        assertEquals(7.59f, strainOf(points), 0.40f)
    }

    @Test
    fun `60 minute tempo run scores around 11 point 5`() {
        // E_day ≈ 213.2 (spec column: 212)
        val points = day(0.106 to 1305, 0.30 to 75, 0.75 to 60)
        assertEquals(11.48f, strainOf(points), 0.40f)
    }

    @Test
    fun `90 minute hard ride scores around 14 point 4`() {
        // E_day ≈ 343.8 (spec column: 336)
        val points = day(0.106 to 1250, 0.30 to 100, 0.78 to 90)
        assertEquals(14.44f, strainOf(points), 0.50f)
    }

    @Test
    fun `maximal three hour effort approaches the top of the scale`() {
        val points = day(0.106 to 1160, 0.80 to 180, 0.30 to 100)
        val strain = strainOf(points)
        assertTrue("A 3h threshold effort should exceed 19 (was $strain)", strain > 19f)
        assertTrue(strain <= 21f)
    }

    // ── Structural properties ────────────────────────────────────────────────

    @Test
    fun `multi session day exceeds either session alone but is sub-additive`() {
        val background = 0.106 to 1320
        val tempo = strainOf(day(background, 0.75 to 60))
        val intervals = strainOf(day(background, 0.85 to 60))
        val both = strainOf(day(0.106 to 1260, 0.75 to 60, 0.85 to 60))

        assertTrue("Two sessions must exceed one", both > tempo && both > intervals)
        assertTrue(
            "Strain must be sub-additive: two hard sessions are less than twice as taxing",
            both < tempo + intervals
        )
    }

    @Test
    fun `adding exertion never decreases strain`() {
        var previous = 0f
        for (extraMinutes in 0..120 step 10) {
            val strain = strainOf(day(0.106 to (1440 - extraMinutes), 0.70 to extraMinutes))
            assertTrue(
                "Strain must be monotonic in exertion (at ${extraMinutes}min: $strain < $previous)",
                strain >= previous - 0.001f
            )
            previous = strain
        }
    }

    @Test
    fun `higher intensity at equal duration yields higher strain`() {
        val easy = strainOf(day(0.106 to 1380, 0.45 to 60))
        val hard = strainOf(day(0.106 to 1380, 0.80 to 60))
        assertTrue("Harder work must score higher ($hard vs $easy)", hard > easy)
    }

    @Test
    fun `diminishing returns - the same extra work is worth less at high strain`() {
        val base = 0.106 to 1200
        val at60 = strainOf(day(0.106 to 1380, 0.75 to 60))
        val at90 = strainOf(day(0.106 to 1350, 0.75 to 90))
        val at180 = strainOf(day(base, 0.75 to 180))
        val at210 = strainOf(day(0.106 to 1170, 0.75 to 210))

        val earlyGain = at90 - at60      // +30 min at low strain
        val lateGain = at210 - at180     // +30 min at high strain
        assertTrue(
            "A 30-minute block must be worth less at high strain ($lateGain vs $earlyGain)",
            lateGain < earlyGain
        )
    }

    /**
     * The load-bearing detail for a Galaxy Watch Active 2: it samples ~1/10min at rest but
     * ~1/sec inside a tracked workout. Without dt weighting, the workout's samples swamp
     * the rest of the day and strain becomes a function of sampling rate.
     */
    @Test
    fun `sample rate does not change strain - dense and sparse sampling agree`() {
        val dense = mutableListOf<HeartRatePoint>()
        val sparse = mutableListOf<HeartRatePoint>()
        // 4 hours at intensity 0.6: one sample per 10 s vs one per 2 min.
        var ms = 0L
        while (ms < 4 * 60 * 60_000L) {
            dense += HeartRatePoint(ms, bpmFor(0.6))
            if (ms % 120_000L == 0L) sparse += HeartRatePoint(ms, bpmFor(0.6))
            ms += 10_000L
        }
        val denseStrain = strainOf(dense)
        val sparseStrain = strainOf(sparse)
        val relativeDifference = abs(denseStrain - sparseStrain) / denseStrain
        assertTrue(
            "Sampling rate must not change strain by more than 5% ($denseStrain vs $sparseStrain)",
            relativeDifference < 0.05f
        )
    }

    @Test
    fun `a sensor dropout is not counted as sustained effort`() {
        // 25 samples at 90% intensity, each six hours from the next. Gaps that large are
        // dropouts, not sparse sampling: each sample may speak for itself and no more.
        //
        // Clamping to MAX_SAMPLE_GAP_MIN was not enough on its own — 25 × 5 min of
        // near-maximal work still totalled 18.0/21, an "All Out" day invented out of
        // absent data. With DROPOUT_GAP_MIN applied each sample carries one nominal
        // minute, landing around 8.5: the ~25 minutes of high HR genuinely observed,
        // which is a Moderate day and nothing more.
        val points = (0 until 25).map { HeartRatePoint(it * 6L * 60 * 60_000L, bpmFor(0.9)) }
        val strain = StrainCalculator.calculate(
            hrPoints = points, restingHRBaseline = restingHR, maxHR = maxHR, basalIntensity = 0.106
        ).strain!!
        assertTrue(
            "A dropout must not reach the Strenuous zone (was $strain)",
            strain < StrainZone.STRENUOUS.minInclusive
        )
    }

    // ── The no-data path ─────────────────────────────────────────────────────

    @Test
    fun `no HR and no steps returns null strain, never zero`() {
        val result = StrainCalculator.calculate(
            hrPoints = emptyList(), restingHRBaseline = restingHR, maxHR = maxHR
        )
        assertNull("A charging watch must not read as a rest day", result.strain)
        assertNull(result.exertionMinutes)
        assertNull(result.zone)
    }

    @Test
    fun `too few HR samples falls back to the proxy at LOW confidence`() {
        val result = StrainCalculator.calculate(
            hrPoints = (1..5).map { HeartRatePoint(it * 60_000L, 120) },
            restingHRBaseline = restingHR,
            maxHR = maxHR,
            steps = 12000,
            exerciseMinutes = 40
        )
        assertTrue(result.isProxyEstimate)
        assertEquals(Confidence.LOW, result.confidence)
        assertNotNull(result.strain)
    }

    @Test
    fun `proxy never reports high confidence`() {
        val result = StrainCalculator.proxyStrain(steps = 25000, exerciseMinutes = 180)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.isProxyEstimate)
    }

    @Test
    fun `a full day of basal HR scores under 1`() {
        val points = day(0.106 to 1440)
        val strain = strainOf(points)
        assertTrue("An entirely idle day must score under 1.0 (was $strain)", strain < 1.0f)
    }

    @Test
    fun `nonsensical HR reserve degrades to the proxy instead of dividing by zero`() {
        val result = StrainCalculator.calculate(
            hrPoints = (1..100).map { HeartRatePoint(it * 60_000L, 70) },
            restingHRBaseline = 190.0,   // resting at or above max
            maxHR = 190,
            steps = 8000
        )
        assertTrue(result.isProxyEstimate)
        assertNotNull(result.strain)
        assertFalse(result.strain!!.isNaN())
    }

    // ── Zones ────────────────────────────────────────────────────────────────

    @Test
    fun `zone boundaries are 6 10 14 18`() {
        assertEquals(StrainZone.LIGHT, StrainZone.of(5.9f))
        assertEquals(StrainZone.MODERATE, StrainZone.of(6.0f))
        assertEquals(StrainZone.MODERATE, StrainZone.of(9.9f))
        assertEquals(StrainZone.STRENUOUS, StrainZone.of(10.0f))
        assertEquals(StrainZone.STRENUOUS, StrainZone.of(13.9f))
        assertEquals(StrainZone.HARD, StrainZone.of(14.0f))
        assertEquals(StrainZone.HARD, StrainZone.of(17.9f))
        assertEquals(StrainZone.ALL_OUT, StrainZone.of(18.0f))
        assertEquals(StrainZone.ALL_OUT, StrainZone.of(21.0f))
    }

    @Test
    fun `recommended band rises with recovery and stays inside the scale`() {
        val low = StrainCalculator.recommendedBand(20f)!!
        val high = StrainCalculator.recommendedBand(90f)!!
        assertTrue("Better recovery must earn a higher ceiling", high.second > low.second)
        assertTrue(low.first >= 0f && high.second <= 21f)
        assertNull(StrainCalculator.recommendedBand(null))
    }

    @Test
    fun `breakdown and explanation are populated for a measured day`() {
        val result = StrainCalculator.calculate(
            hrPoints = day(0.106 to 1380, 0.75 to 60),
            restingHRBaseline = restingHR, maxHR = maxHR, basalIntensity = 0.106
        )
        assertEquals(3, result.breakdown.size)
        assertTrue(result.explanation.contains("exertion-minutes"))
        assertFalse(result.isProxyEstimate)
    }
}
