package com.example.vitalcoreai.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the V1.1 intelligence engines.
 *
 * Emphasis is on the two failure modes that matter most for a health app:
 * **fabricating confidence from missing data**, and **saying something clinical**.
 */
class SleepConsistencyCalculatorTest {

    private fun night(day: Long, bed: Int?, wake: Int?, dur: Int?) =
        SleepConsistencyCalculator.NightTiming(day, bed, wake, dur)

    @Test
    fun `reports insufficient data below the minimum nights`() {
        val nights = (1L..4L).map { night(it, 23 * 60, 7 * 60, 480) }
        val r = SleepConsistencyCalculator.calculate(nights)
        assertTrue(r.insufficientData)
        assertNull(r.score)
        assertEquals(Confidence.LOW, r.confidence)
        assertEquals("Not enough data", r.label)
    }

    @Test
    fun `a perfectly regular sleeper scores at the top`() {
        val nights = (1L..14L).map { night(it, 23 * 60, 7 * 60, 480) }
        val r = SleepConsistencyCalculator.calculate(nights)
        assertFalse(r.insufficientData)
        assertEquals(100f, r.score!!, 0.5f)
        assertEquals("Highly consistent", r.label)
        assertEquals(Confidence.HIGH, r.confidence)
        assertEquals(0, r.bedtimeSdMinutes)
        assertEquals(0, r.wakeSdMinutes)
    }

    /**
     * The midnight-straddling case that plain arithmetic gets catastrophically wrong:
     * bedtimes alternating 23:50 / 00:10 are 20 minutes apart, not 23h 40m.
     */
    @Test
    fun `a sleeper straddling midnight is not punished`() {
        val nights = (1L..14L).map {
            night(it, if (it % 2 == 0L) 23 * 60 + 50 else 10, 7 * 60, 480)
        }
        val r = SleepConsistencyCalculator.calculate(nights)
        assertTrue(
            "bedtime spread should be ~20 min, was ${r.bedtimeSdMinutes}",
            r.bedtimeSdMinutes!! < 30
        )
        assertTrue("score should stay high, was ${r.score}", r.score!! > 80f)
    }

    @Test
    fun `an erratic sleeper scores low and the copy names the worst component`() {
        val beds = listOf(21 * 60, 2 * 60, 23 * 60, 4 * 60, 20 * 60, 1 * 60, 23 * 60, 3 * 60)
        val nights = beds.mapIndexed { i, b -> night(i.toLong(), b, 7 * 60 + (i % 5) * 40, 300 + i * 30) }
        val r = SleepConsistencyCalculator.calculate(nights)
        assertTrue("score should be low, was ${r.score}", r.score!! < 60f)
        assertTrue(r.explanation.contains("bedtime") || r.explanation.contains("wake time"))
    }

    @Test
    fun `renormalises weights when only duration is recorded`() {
        val nights = (1L..10L).map { night(it, null, null, 470) }
        val r = SleepConsistencyCalculator.calculate(nights)
        assertFalse(r.insufficientData)
        assertNotNull(r.score)
        assertNull(r.bedtimeSdMinutes)
        assertEquals(1, r.breakdown.size)
        assertEquals("Duration", r.breakdown.first().name)
    }

    @Test
    fun `sdToScore is monotonic decreasing and bounded`() {
        val scores = listOf(0.0, 15.0, 30.0, 60.0, 120.0, 240.0)
            .map { SleepConsistencyCalculator.sdToScore(it) }
        assertEquals(100f, scores.first(), 0.01f)
        assertEquals(50f, SleepConsistencyCalculator.sdToScore(60.0), 0.01f)
        for (i in 1 until scores.size) {
            assertTrue("must decrease monotonically", scores[i] < scores[i - 1])
        }
        assertTrue(scores.all { it in 0f..100f })
    }

    @Test
    fun `survives an empty input`() {
        val r = SleepConsistencyCalculator.calculate(emptyList())
        assertTrue(r.insufficientData)
        assertNull(r.score)
        assertEquals(0, r.nightsCounted)
    }
}

class RecoveryTrendEngineTest {

    private fun day(
        d: Long,
        readiness: Float?,
        sleep: Int? = 450,
        rhr: Int? = 60,
        highLoad: Boolean = false,
        bed: Int? = 23 * 60,
        wake: Int? = 7 * 60
    ) = RecoveryTrendEngine.DayInput(
        dateEpochDay = d,
        recoveryScore = readiness,
        readinessScore = readiness,
        sleepMinutes = sleep,
        bedtimeMinuteOfDay = bed,
        wakeTimeMinuteOfDay = wake,
        restingHR = rhr,
        strain = null,
        hadHighLoad = highLoad
    )

    @Test
    fun `refuses to declare a direction on thin coverage`() {
        val days = (1L..3L).map { day(it, 70f) }
        val r = RecoveryTrendEngine.analyse(days, RecoveryTrendEngine.Window.FORTNIGHT)
        assertEquals(RobustStats.Trend.INSUFFICIENT_DATA, r.direction)
        assertFalse(r.isMeaningful)
        assertEquals("—", r.arrow)
        assertTrue(r.summary.contains("Needs at least"))
    }

    @Test
    fun `detects a real improvement and reports the averages`() {
        val days = (0L..13L).map { day(it, (60 + it).toFloat()) }
        val r = RecoveryTrendEngine.analyse(days, RecoveryTrendEngine.Window.FORTNIGHT)
        assertEquals(RobustStats.Trend.IMPROVING, r.direction)
        assertEquals("↑", r.arrow)
        assertEquals("Improving", r.directionLabel)
        assertTrue(r.delta!! > 5f)
        assertTrue(r.summary.contains("trending up"))
    }

    @Test
    fun `attributes an improvement to more sleep and fewer high-load days`() {
        val days = (0L..13L).map { d ->
            val earlier = d < 7
            day(
                d = d,
                readiness = if (earlier) 62f else 74f,
                sleep = if (earlier) 380 else 470,
                highLoad = earlier && d % 2 == 0L
            )
        }
        val r = RecoveryTrendEngine.analyse(days, RecoveryTrendEngine.Window.FORTNIGHT)
        assertTrue(r.contributors.isNotEmpty())
        val names = r.contributors.map { it.name }
        assertTrue("expected sleep to be named, got $names", names.any { it.contains("sleep", true) })
        assertTrue("top contributor should agree with the trend", r.contributors.first().favourable)
    }

    @Test
    fun `an erratic series is reported as variable rather than stable`() {
        val values = listOf(45f, 88f, 40f, 90f, 42f, 86f, 38f, 91f, 44f, 89f, 41f, 87f, 39f, 92f)
        val days = values.mapIndexed { i, v -> day(i.toLong(), v) }
        val r = RecoveryTrendEngine.analyse(days, RecoveryTrendEngine.Window.FORTNIGHT)
        assertEquals(RobustStats.Trend.VARIABLE, r.direction)
        assertEquals("↕", r.arrow)
    }

    @Test
    fun `does not manufacture contributors from trivial differences`() {
        // Sleep differs by 5 minutes and RHR by 0 — nothing material.
        val days = (0L..13L).map { d -> day(d, 70f, sleep = if (d < 7) 448 else 453) }
        val r = RecoveryTrendEngine.analyse(days, RecoveryTrendEngine.Window.FORTNIGHT)
        assertTrue("no contributor should clear materiality", r.contributors.isEmpty())
    }

    @Test
    fun `all three windows are produced and labelled`() {
        val days = (0L..29L).map { day(it, (55 + it / 2).toFloat()) }
        val all = RecoveryTrendEngine.analyseAll(days)
        assertEquals(3, all.size)
        assertEquals(listOf("7-day", "14-day", "30-day"), all.map { it.window.label })
    }

    @Test
    fun `survives an empty input`() {
        val r = RecoveryTrendEngine.analyse(emptyList(), RecoveryTrendEngine.Window.WEEK)
        assertEquals(RobustStats.Trend.INSUFFICIENT_DATA, r.direction)
        assertEquals(0, r.daysCovered)
    }
}

class AnomalyDetectionEngineTest {

    private fun sample(d: Long, rhr: Double? = 60.0, sleep: Double? = 450.0) =
        AnomalyDetectionEngine.DaySample(dateEpochDay = d, restingHR = rhr, sleepMinutes = sleep)

    @Test
    fun `stays silent below the minimum history`() {
        val history = (1L..5L).map { sample(it) }
        val found = AnomalyDetectionEngine.detect(history, sample(6, rhr = 85.0))
        assertTrue("must not call anything unusual on 5 days of history", found.isEmpty())
    }

    @Test
    fun `flags a single-day spike once history is sufficient`() {
        val history = (1L..14L).map { sample(it, rhr = 60.0 + (it % 3)) }
        val found = AnomalyDetectionEngine.detect(history, sample(15, rhr = 78.0))
        val rhrAnomaly = found.firstOrNull {
            it.metric == PersonalBaselines.TrackedMetric.RESTING_HR
        }
        assertNotNull("a 78 bpm reading against a ~61 bpm baseline should register", rhrAnomaly)
        assertEquals(AnomalyDetectionEngine.Kind.SPIKE, rhrAnomaly!!.kind)
        assertTrue(rhrAnomaly.deviation > 0)
    }

    @Test
    fun `a sustained elevation outranks a one-day spike`() {
        val stable = (1L..14L).map { sample(it, rhr = 60.0) }
        val elevated = (15L..17L).map { sample(it, rhr = 69.0) }
        val found = AnomalyDetectionEngine.detect(stable + elevated, sample(18, rhr = 69.0))
        val rhr = found.first { it.metric == PersonalBaselines.TrackedMetric.RESTING_HR }
        assertEquals(AnomalyDetectionEngine.Kind.SUSTAINED, rhr.kind)
        assertTrue("streak should count the consecutive days", rhr.consecutiveDays >= 3)
        assertTrue(rhr.title.contains("days"))
    }

    /**
     * A resting HR *below* baseline is a good thing. Warning about it would be both wrong
     * and alarming, so only adverse deviations are surfaced.
     */
    @Test
    fun `favourable deviations are never reported as anomalies`() {
        val history = (1L..14L).map { sample(it, rhr = 65.0) }
        val found = AnomalyDetectionEngine.detect(history, sample(15, rhr = 48.0))
        assertTrue(
            "a resting HR well below baseline must not be flagged",
            found.none { it.metric == PersonalBaselines.TrackedMetric.RESTING_HR }
        )
    }

    @Test
    fun `unusually short sleep is flagged but unusually long sleep is not`() {
        val history = (1L..14L).map { sample(it, sleep = 450.0) }
        val short = AnomalyDetectionEngine.detect(history, sample(15, sleep = 250.0))
        assertTrue(short.any { it.metric == PersonalBaselines.TrackedMetric.SLEEP_DURATION })

        val long = AnomalyDetectionEngine.detect(history, sample(15, sleep = 620.0))
        assertTrue(long.none { it.metric == PersonalBaselines.TrackedMetric.SLEEP_DURATION })
    }

    /**
     * The medical-safety contract, asserted rather than merely documented.
     *
     * The fixed disclaimer ("…not a diagnosis") is stripped before scanning, so the check
     * targets *affirmative* clinical claims. Asserting on the raw string would flag the
     * app's own safety wording, which is the opposite of what this test is for — and the
     * disclaimer's presence is asserted separately below.
     */
    @Test
    fun `no anomaly copy contains diagnostic language`() {
        val disclaimer = "This is a pattern worth monitoring, not a diagnosis."
        val forbidden = listOf(
            "you are sick", "illness", "infection", "disease", "diagnose", "diagnosing",
            "overtraining syndrome", "heart problem", "heart condition",
            "you have", "symptom", "condition", "medical"
        )
        val history = (1L..14L).map { sample(it, rhr = 60.0, sleep = 450.0) }
        val today = AnomalyDetectionEngine.DaySample(
            dateEpochDay = 15,
            restingHR = 80.0,
            sleepMinutes = 240.0,
            checkInStress = 9.0,
            checkInSoreness = 9.0
        )
        val found = AnomalyDetectionEngine.detect(history, today)
        assertTrue("expected at least one anomaly to inspect", found.isNotEmpty())
        for (a in found) {
            assertTrue(
                "every anomaly must carry the disclaimer verbatim",
                a.body.contains(disclaimer)
            )
            val scanned = "${a.title} ${a.body.replace(disclaimer, "")} ${a.suggestion.orEmpty()}"
                .lowercase()
            for (word in forbidden) {
                assertFalse("forbidden phrase '$word' in: $scanned", scanned.contains(word))
            }
            // The permitted vocabulary must actually be used, not merely allowed.
            assertTrue(
                "anomaly copy should use hedged language",
                a.body.contains("unusual", true) ||
                        a.body.contains("your usual", true) ||
                        a.body.contains("normal range", true) ||
                        a.body.contains("pattern", true)
            )
        }
    }

    @Test
    fun `caps the number of anomalies surfaced at once`() {
        val history = (1L..14L).map {
            AnomalyDetectionEngine.DaySample(
                dateEpochDay = it,
                restingHR = 60.0, sleepMinutes = 450.0, sleepScore = 80.0,
                strain = 8.0, hrr1 = 30.0, steps = 9000.0, recovery = 70.0,
                energyBank = 70.0, checkInEnergy = 7.0, checkInStress = 3.0,
                checkInSoreness = 3.0
            )
        }
        val today = AnomalyDetectionEngine.DaySample(
            dateEpochDay = 15,
            restingHR = 85.0, sleepMinutes = 200.0, sleepScore = 30.0,
            strain = 19.0, hrr1 = 8.0, steps = 500.0, recovery = 25.0,
            energyBank = 20.0, checkInEnergy = 2.0, checkInStress = 9.0,
            checkInSoreness = 9.0
        )
        val found = AnomalyDetectionEngine.detect(history, today)
        assertTrue("must not spam the user, got ${found.size}", found.size <= 4)
        // Highest priority first.
        for (i in 1 until found.size) {
            assertTrue(found[i - 1].priority >= found[i].priority)
        }
    }

    @Test
    fun `a missing day breaks a streak instead of being skipped`() {
        val stable = (1L..14L).map { sample(it, rhr = 60.0) }
        // Days 15 and 17 elevated, day 16 absent entirely.
        val gapped = listOf(sample(15, rhr = 69.0), sample(17, rhr = 69.0))
        val found = AnomalyDetectionEngine.detect(stable + gapped, sample(18, rhr = 69.0))
        val rhr = found.firstOrNull { it.metric == PersonalBaselines.TrackedMetric.RESTING_HR }
        if (rhr != null && rhr.kind == AnomalyDetectionEngine.Kind.SUSTAINED) {
            assertTrue(
                "streak must not jump the missing day 16, got ${rhr.consecutiveDays}",
                rhr.consecutiveDays <= 2
            )
        }
    }

    @Test
    fun `survives empty history`() {
        assertTrue(AnomalyDetectionEngine.detect(emptyList(), sample(1)).isEmpty())
    }
}

class ReadinessForecastEngineTest {

    private fun baseInput(
        today: Float? = 70f,
        history: List<Float> = List(14) { 68f + (it % 5) }
    ) = ReadinessForecastEngine.ForecastInput(
        todayReadiness = today,
        readinessHistory = history,
        recentSleepMinutes = List(14) { 450 },
        personalSleepNeedMinutes = 480,
        dataQuality = DataQualityReport(Confidence.HIGH, 90, emptyList(), false)
    )

    @Test
    fun `is unavailable without today's readiness`() {
        val f = ReadinessForecastEngine.forecast(baseInput(today = null))
        assertFalse(f.available)
        assertEquals("—", f.range)
        assertTrue(f.explanation.contains("needs today's readiness"))
    }

    /** The core honesty guarantee: never a single number. */
    @Test
    fun `always produces an interval, never a point estimate`() {
        val f = ReadinessForecastEngine.forecast(baseInput())
        assertTrue(f.available)
        assertTrue("high must exceed low", f.high > f.low)
        assertTrue(f.range.contains("–"))
    }

    @Test
    fun `bounds stay inside zero to one hundred`() {
        val high = ReadinessForecastEngine.forecast(baseInput(today = 99f, history = List(14) { 98f }))
        assertTrue(high.high <= 100)
        assertTrue(high.low >= 0)

        val low = ReadinessForecastEngine.forecast(baseInput(today = 2f, history = List(14) { 3f }))
        assertTrue(low.low >= 0)
        assertTrue(low.high <= 100)
    }

    /**
     * A volatile user must get a visibly wider band than a metronomic one — this is what
     * stops the forecast flattering itself with false precision.
     */
    @Test
    fun `interval width tracks the user's own volatility`() {
        val steady = ReadinessForecastEngine.forecast(baseInput(history = List(14) { 70f }))
        val volatileHistory = List(14) { if (it % 2 == 0) 45f else 90f }
        val swingy = ReadinessForecastEngine.forecast(baseInput(history = volatileHistory))

        val steadyWidth = steady.high - steady.low
        val swingyWidth = swingy.high - swingy.low
        assertTrue(
            "volatile user should get a wider band ($swingyWidth vs $steadyWidth)",
            swingyWidth > steadyWidth
        )
    }

    @Test
    fun `missing inputs widen the interval and lower confidence`() {
        val rich = ReadinessForecastEngine.forecast(
            baseInput().copy(
                plannedSleepMinutes = 480,
                todayStrain = 9f,
                restingHR = 60,
                restingHRBaseline = 60.0
            )
        )
        val sparse = ReadinessForecastEngine.forecast(
            baseInput(history = emptyList()).copy(
                dataQuality = DataQualityReport(Confidence.LOW, 20, listOf("no sleep"), false)
            )
        )
        assertTrue((sparse.high - sparse.low) > (rich.high - rich.low))
        assertEquals(Confidence.LOW, sparse.confidence)
    }

    @Test
    fun `a planned hard session is reported as a risk that lowers the centre`() {
        val rested = ReadinessForecastEngine.forecast(
            baseInput().copy(todayStrain = 5f, strainHistory = List(7) { 8f })
        )
        val hammered = ReadinessForecastEngine.forecast(
            baseInput().copy(
                todayStrain = 5f,
                strainHistory = List(7) { 8f },
                plannedWorkoutLoad = 0.9f
            )
        )
        assertTrue("planned hard session should lower the projection", hammered.midpoint < rested.midpoint)
        assertTrue(hammered.risks.any { it.contains("high-intensity") })
    }

    @Test
    fun `short planned sleep lowers the projection and surfaces an opportunity`() {
        val good = ReadinessForecastEngine.forecast(baseInput().copy(plannedSleepMinutes = 490))
        val bad = ReadinessForecastEngine.forecast(baseInput().copy(plannedSleepMinutes = 330))
        assertTrue(bad.midpoint < good.midpoint)
        assertTrue(bad.opportunities.any { it.contains("Sleeping") })
    }

    @Test
    fun `consecutive training days accumulate a penalty and a risk`() {
        val fresh = ReadinessForecastEngine.forecast(baseInput().copy(consecutiveTrainingDays = 1))
        val grinding = ReadinessForecastEngine.forecast(baseInput().copy(consecutiveTrainingDays = 5))
        assertTrue(grinding.midpoint < fresh.midpoint)
        assertTrue(grinding.risks.any { it.contains("consecutive") })
    }

    @Test
    fun `regression pulls an unusually high day back toward the personal median`() {
        val spikeDay = ReadinessForecastEngine.forecast(
            baseInput(today = 95f, history = List(14) { 65f })
        )
        assertTrue(
            "a 95 against a 65 median should project downward, got ${spikeDay.midpoint}",
            spikeDay.midpoint < 95
        )
    }

    @Test
    fun `every driver carries a signed effect and a description`() {
        val f = ReadinessForecastEngine.forecast(
            baseInput().copy(
                plannedSleepMinutes = 360,
                todayStrain = 15f,
                strainHistory = List(7) { 7f },
                consecutiveTrainingDays = 4,
                checkInStress = 8,
                checkInSoreness = 8,
                sleepDebtMinutes = 240
            )
        )
        assertTrue(f.drivers.isNotEmpty())
        for (d in f.drivers) {
            assertTrue("driver '${d.name}' needs a description", d.description.isNotBlank())
        }
        // Sorted by absolute impact.
        for (i in 1 until f.drivers.size) {
            assertTrue(
                kotlin.math.abs(f.drivers[i - 1].points) >= kotlin.math.abs(f.drivers[i].points)
            )
        }
    }

    @Test
    fun `explanation states this is a projection rather than a prediction`() {
        val f = ReadinessForecastEngine.forecast(baseInput())
        assertTrue(f.explanation.contains("not a prediction"))
    }

    @Test
    fun `high confidence requires both history and a tight interval`() {
        val thin = ReadinessForecastEngine.forecast(
            baseInput(history = List(3) { 70f })
        )
        assertTrue(thin.confidence != Confidence.HIGH)
    }
}
