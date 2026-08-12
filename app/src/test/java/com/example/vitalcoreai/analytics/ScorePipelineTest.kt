package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.core.time.FixedVitalClock
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.model.HeartRatePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T-15's payoff: the whole daily computation, exercised on the JVM with no Room, no Android
 * runtime and no instrumentation.
 *
 * The assertions here are the ones the handoff names as the integration test's contract —
 * idempotency, bounded forecasts, an anomaly count that matches the serialised list, a trend
 * direction drawn from the enum — but they run in milliseconds against the pure function
 * rather than against a device.
 */
class ScorePipelineTest {

    private val today = 20_000L

    @After
    fun tearDown() = VitalTime.reset()

    // ─── Fixtures ────────────────────────────────────────────────────────────

    private fun metrics(
        day: Long,
        restingHR: Int? = 60,
        sleepMinutes: Int? = 440,
        bedtime: Int? = 23 * 60,
        wake: Int? = 6 * 60 + 30,
        steps: Int? = 8000
    ) = ScorePipeline.DayMetrics(
        dateEpochDay = day,
        restingHR = restingHR,
        steps = steps,
        distanceMeters = steps?.let { it * 0.7f },
        caloriesBurned = 2200,
        activeCalories = 600,
        spO2Percent = 96f,
        spO2ReadingCount = 30,
        sleepDurationMinutes = sleepMinutes,
        sleepEfficiencyPercent = 88.0,
        sleepDeepMinutes = sleepMinutes?.let { it / 5 },
        sleepRemMinutes = sleepMinutes?.let { it / 5 },
        sleepLightMinutes = sleepMinutes?.let { it * 3 / 5 },
        sleepAwakeMinutes = 20,
        sleepStagesAvailable = true,
        bedtimeMinuteOfDay = bedtime,
        wakeTimeMinuteOfDay = wake,
        dataSourceType = "WATCH_SENSOR",
        hrPointsPerHour = 12.0,
        partialDayFraction = 0.95
    )

    private fun scores(day: Long, readiness: Float = 70f) = ScorePipeline.DayScores(
        dateEpochDay = day,
        recoveryScore = readiness + 2f,
        readinessScore = readiness,
        sleepScore = 72f,
        strain = 9f,
        energyBankScore = 65f,
        hrr1 = 22
    )

    /** Thirty days of ordinary history plus today. */
    private fun input(
        days: Int = 30,
        todayMetrics: ScorePipeline.DayMetrics = metrics(today),
        sessions: List<ScorePipeline.Session> = emptyList(),
        checkIns: List<ScorePipeline.CheckIn> = emptyList()
    ) = ScorePipeline.Input(
        todayEpochDay = today,
        nowMinuteOfDay = 18 * 60,
        userAge = 30,
        userMaxHR = 190,
        sleepNeedMinutes = 480,
        stepGoal = 8000,
        today = todayMetrics,
        history = (1..days).map { back ->
            // Deterministic wobble so nothing has zero variance, which several statistical
            // tests are legitimately undefined for.
            metrics(
                day = today - back,
                restingHR = 60 + (back % 3),
                sleepMinutes = 430 + (back % 5) * 6,
                bedtime = 23 * 60 + (back % 4) * 5,
                wake = 6 * 60 + 30 + (back % 3) * 4,
                steps = 7800 + (back % 7) * 120
            )
        },
        priorScores = (1..days).map { back -> scores(today - back, 68f + (back % 5)) },
        sessions = sessions,
        checkIns = checkIns,
        daysOfHistoryAvailable = days + 1
    )

    // ─── The handoff's integration-test contract ─────────────────────────────

    @Test
    fun `two runs over the same input produce an identical output`() {
        val i = input()
        assertEquals(ScorePipeline.compute(i), ScorePipeline.compute(i))
    }

    /**
     * The idempotency case that actually bit: `syncToday` re-scores the trailing three days,
     * so by the second pass the day's own row exists. Feeding it back in changed momentum and
     * the HRR trend. The pipeline takes prior scores strictly before today and appends the
     * value it just computed, so an extra row for today in the input is ignored.
     */
    @Test
    fun `a stale row for today in the input cannot change the result`() {
        val clean = input()
        val contaminated = clean.copy(
            priorScores = clean.priorScores + scores(today, readiness = 5f),
            history = clean.history + metrics(today, restingHR = 99)
        )
        assertEquals(ScorePipeline.compute(clean), ScorePipeline.compute(contaminated))
    }

    @Test
    fun `forecast bounds always sit inside 0 to 100 and low never exceeds high`() {
        val cases = listOf(
            input(),
            input(todayMetrics = metrics(today, restingHR = 95, sleepMinutes = 180)),
            input(todayMetrics = metrics(today, restingHR = 40, sleepMinutes = 700)),
            input(days = 5),
            input(days = 60)
        )
        for (case in cases) {
            val f = ScorePipeline.compute(case).forecast
            assertNotNull(f)
            if (f!!.available) {
                assertTrue("low=${f.low}", f.low in 0..100)
                assertTrue("high=${f.high}", f.high in 0..100)
                assertTrue("low ${f.low} > high ${f.high}", f.low <= f.high)
            }
        }
    }

    @Test
    fun `the persisted anomaly count matches the serialised list`() {
        val out = ScorePipeline.compute(
            // A resting HR far outside the personal range, sustained, is what the engine
            // exists to notice.
            input(todayMetrics = metrics(today, restingHR = 84))
        )
        assertEquals(out.anomalies.size, out.anomalyCount)
        val decoded = ScorePipeline.decodeAnomalies(out.anomaliesEncoded)
        assertEquals(out.anomalies.size, decoded.size)
    }

    @Test
    fun `every trend direction is a RobustStats Trend name`() {
        val valid = RobustStats.Trend.entries.map { it.name }.toSet()
        val out = ScorePipeline.compute(input())
        listOfNotNull(out.trend7Direction, out.trend14Direction, out.trend30Direction)
            .forEach { assertTrue("unexpected direction $it", it in valid) }
    }

    @Test
    fun `a recommendation is always produced and its volume stays in bounds`() {
        for (rhr in listOf(45, 60, 75, 95)) {
            val out = ScorePipeline.compute(input(todayMetrics = metrics(today, restingHR = rhr)))
            assertNotNull(out.recommendationType)
            assertNotNull(out.recommendationIntensity)
            assertTrue(out.recommendationVolumePct!! in -100..20)
        }
    }

    // ─── Degradation ─────────────────────────────────────────────────────────

    /**
     * A brand-new user gets a forecast, but a visibly less certain one.
     *
     * The engine deliberately does not refuse here, and that is the right call: the interval
     * width *is* the uncertainty, so a wide LOW-confidence band says "we barely know you"
     * more honestly than an empty card does. What must not happen is the band being as tight
     * on day one as it is after a month — that would be a fabricated precision.
     */
    @Test
    fun `a brand new user gets a wider, less confident forecast rather than a tight one`() {
        val fresh = ScorePipeline.compute(
            ScorePipeline.Input(
                todayEpochDay = today,
                today = metrics(today),
                history = emptyList(),
                priorScores = emptyList(),
                daysOfHistoryAvailable = 1
            )
        )
        val established = ScorePipeline.compute(input(days = 30))

        val freshForecast = fresh.forecast
        val establishedForecast = established.forecast
        assertNotNull(freshForecast)
        assertNotNull(establishedForecast)
        assertTrue("a forecast is still offered on day one", freshForecast!!.available)

        val freshWidth = freshForecast.high - freshForecast.low
        val establishedWidth = establishedForecast!!.high - establishedForecast.low
        assertTrue(
            "day-one band ($freshWidth) must not be tighter than a 30-day band ($establishedWidth)",
            freshWidth >= establishedWidth
        )
        assertEquals(
            "no history must not read as a confident projection",
            Confidence.LOW, freshForecast.confidence
        )
        assertTrue("ACWR must declare itself unusable", fresh.acwrIsMeaningful == false)
    }

    /** With no readiness to project *from*, the engine declines and nothing is persisted. */
    @Test
    fun `no readiness today means no forecast is persisted at all`() {
        val out = ScorePipeline.compute(
            input(todayMetrics = metrics(today, restingHR = null, sleepMinutes = null))
        )
        assertNull(out.readinessScore)
        assertNull("an unavailable forecast must not write a range", out.forecastLow)
        assertNull(out.forecastHigh)
        assertNull(out.forecastConfidence)
    }

    @Test
    fun `a day with no sleep and no heart rate still computes without throwing`() {
        val out = ScorePipeline.compute(
            input(todayMetrics = metrics(today, restingHR = null, sleepMinutes = null, steps = null))
        )
        assertNull(out.recoveryScore)
        assertNull(out.readinessScore)
        assertNull(out.sleepScore)
        // Strain has a steps/exercise proxy, so it is allowed to survive — but it must say so.
        assertNotNull(out.strainConfidence)
    }

    @Test
    fun `an entirely empty history does not divide by zero anywhere`() {
        val out = ScorePipeline.compute(
            ScorePipeline.Input(
                todayEpochDay = today,
                today = ScorePipeline.DayMetrics(dateEpochDay = today),
                daysOfHistoryAvailable = 0
            )
        )
        assertEquals(0, out.anomalyCount)
        assertTrue(out.insights.isEmpty())
        assertNotNull(out.recommendation)
    }

    @Test
    fun `identical values every day produce no spurious trend`() {
        val flat = ScorePipeline.Input(
            todayEpochDay = today,
            today = metrics(today),
            history = (1..30).map { metrics(today - it) },
            priorScores = (1..30).map { ScorePipeline.DayScores(today - it, readinessScore = 70f) },
            daysOfHistoryAvailable = 31
        )
        val out = ScorePipeline.compute(flat)
        assertTrue(
            "a perfectly flat series must not read as IMPROVING or DECLINING",
            out.trend30Direction in listOf(null, "STABLE", "INSUFFICIENT_DATA")
        )
    }

    // ─── Encoding round-trips ────────────────────────────────────────────────

    @Test
    fun `driver encoding survives a description containing the separators`() {
        val drivers = listOf(
            ReadinessForecastEngine.Driver(
                name = "Sleep opportunity",
                points = 4.5f,
                description = "Bedtime 23:10 | wake 07:00 — about 8h"
            ),
            ReadinessForecastEngine.Driver(
                name = "Training load",
                points = -3.25f,
                description = "Strain 14.2 vs a typical 9.1"
            )
        )
        val decoded = ScorePipeline.decodeDrivers(ScorePipeline.encodeDrivers(drivers))
        assertEquals(2, decoded.size)
        assertEquals("Sleep opportunity", decoded[0].name)
        assertTrue(decoded[0].points > 0)
        assertTrue(decoded[1].points < 0)
        // The pipe is the record separator, so it cannot survive verbatim — but the colons
        // in the clock times must, because only the leading fields are sanitised.
        assertTrue(decoded[0].description.contains("23:10"))
    }

    @Test
    fun `text list and quality factor encodings round-trip`() {
        val items = listOf("one", "two | three", "four")
        assertEquals(3, ScorePipeline.decodeTextList(ScorePipeline.encodeTextList(items)).size)

        val factors = mapOf("COMPLETENESS" to 0.85f, "FRESHNESS" to 0.4f)
        val decoded = ScorePipeline.decodeQualityFactors(ScorePipeline.encodeQualityFactors(factors))
        assertEquals(2, decoded.size)
        assertEquals(0.85f, decoded["COMPLETENESS"]!!, 0.01f)
    }

    @Test
    fun `empty encodings decode to empty rather than a blank entry`() {
        assertTrue(ScorePipeline.decodeTextList(null).isEmpty())
        assertTrue(ScorePipeline.decodeTextList("").isEmpty())
        assertTrue(ScorePipeline.decodeDrivers("nonsense").isEmpty())
        assertTrue(ScorePipeline.decodeAnomalies(null).isEmpty())
    }

    // ─── Consecutive training days ───────────────────────────────────────────

    @Test
    fun `a rest day today reports a zero streak`() {
        val trained = setOf(today - 1, today - 2, today - 3)
        assertEquals(0, ScorePipeline.countConsecutiveTrainingDays(trained, today))
    }

    @Test
    fun `a streak counts back from today and stops at the first gap`() {
        val trained = setOf(today, today - 1, today - 2, today - 4)
        assertEquals(3, ScorePipeline.countConsecutiveTrainingDays(trained, today))
    }

    // ─── Timezone safety (T-11) ──────────────────────────────────────────────

    @Test
    fun `the pipeline is independent of the ambient time zone`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 5, 10, 22, 0), ZoneId.of("America/New_York")
        )
        val fromNewYork = ScorePipeline.compute(input())

        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 5, 10, 22, 0), ZoneId.of("Asia/Kolkata")
        )
        val fromKolkata = ScorePipeline.compute(input())

        // The day key is an argument, not something the pipeline reads from a clock, so
        // travelling cannot change a day's scores after the fact.
        assertEquals(fromNewYork, fromKolkata)
    }

    @Test
    fun `hr points are consumed without reference to the wall clock`() {
        val points = (0 until 100).map { HeartRatePoint(timestampMs = it * 60_000L, bpm = 60 + it % 20) }
        val a = ScorePipeline.compute(input().copy(todayHrPoints = points))
        val b = ScorePipeline.compute(input().copy(todayHrPoints = points))
        assertEquals(a.strain, b.strain)
    }
}
