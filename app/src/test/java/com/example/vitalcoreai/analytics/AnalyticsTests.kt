package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryScoreCalculatorTest {

    private fun makeSleepData(efficiency: Double = 85.0, duration: Int = 480) = SleepData(
        dateEpochDay = 0L,
        durationMinutes = duration,
        efficiencyPercent = efficiency,
        bedtimeMinuteOfDay = 23 * 60,
        wakeTimeMinuteOfDay = 7 * 60,
        remMinutes = 100,
        deepMinutes = 80,
        lightMinutes = 250,
        awakeMinutes = 50
    )

    private fun makeRestingHR(bpm: Int) = RestingHRData(0L, bpm)

    @Test
    fun `score is within 0 to 100 range`() {
        val baseline14 = (1..14).map { makeSleepData(85.0) }
        val baseline30 = (1..30).map { makeRestingHR(58) }
        val result = RecoveryScoreCalculator.calculate(
            todaySleep = makeSleepData(88.0),
            sleepBaseline14Days = baseline14,
            todayRestingHR = makeRestingHR(56),
            restingHRBaseline30Days = baseline30,
            priorDayTrainingLoad = null
        )
        assertTrue("Score must be ≥ 0", result.score >= 0f)
        assertTrue("Score must be ≤ 100", result.score <= 100f)
    }

    @Test
    fun `elevated resting HR lowers recovery score`() {
        val baseline14 = (1..14).map { makeSleepData(85.0) }
        // Baseline with realistic variance (54–62 bpm) so stdDev > 0
        val baseline30 = (1..30).map { makeRestingHR(54 + (it % 9)) }

        val goodHR = RecoveryScoreCalculator.calculate(
            makeSleepData(), baseline14, makeRestingHR(56), baseline30, null
        )
        val highHR = RecoveryScoreCalculator.calculate(
            makeSleepData(), baseline14, makeRestingHR(75), baseline30, null
        )
        assertTrue("High resting HR should yield lower score (good=${goodHR.score}, high=${highHR.score})",
            goodHR.score > highHR.score)
    }

    @Test
    fun `score returns 4 breakdown factors`() {
        val baseline14 = (1..14).map { makeSleepData() }
        val baseline30 = (1..30).map { makeRestingHR(60) }
        val result = RecoveryScoreCalculator.calculate(
            makeSleepData(), baseline14, makeRestingHR(60), baseline30, null
        )
        assertEquals(4, result.breakdown.size)
    }

    @Test
    fun `confidence is HIGH with full baselines`() {
        val baseline14 = (1..14).map { makeSleepData() }
        val baseline30 = (1..30).map { makeRestingHR(60) }
        val result = RecoveryScoreCalculator.calculate(
            makeSleepData(), baseline14, makeRestingHR(60), baseline30, null
        )
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `confidence is not HIGH with minimal baselines`() {
        // Only 1 day of baseline history each — DataQualityEngine should yield MEDIUM confidence
        // (hasSleepToday=true and hasHRToday=true so it won't be LOW, but 1-day history ≠ HIGH)
        val result = RecoveryScoreCalculator.calculate(
            makeSleepData(),
            sleepBaseline14Days = listOf(makeSleepData()),
            todayRestingHR = makeRestingHR(60),
            restingHRBaseline30Days = listOf(makeRestingHR(60)),
            priorDayTrainingLoad = null
        )
        assertNotEquals(
            "Confidence should not be HIGH with only 1-day baselines",
            Confidence.HIGH, result.confidence
        )
    }

    @Test
    fun `spO2 modifier increases score when SpO2 is high`() {
        val baseline14 = (1..14).map { makeSleepData() }
        val baseline30 = (1..30).map { makeRestingHR(60) }
        val withoutSpO2 = RecoveryScoreCalculator.calculate(makeSleepData(), baseline14, makeRestingHR(60), baseline30, null, spO2Percent = null)
        val withHighSpO2 = RecoveryScoreCalculator.calculate(makeSleepData(), baseline14, makeRestingHR(60), baseline30, null, spO2Percent = 99f)
        assertTrue("High SpO2 should boost recovery score", withHighSpO2.score >= withoutSpO2.score)
    }
}

class SleepScoreCalculatorTest {

    /**
     * A night WITH stage detail.
     *
     * `stagesAvailable` must be passed explicitly: it defaults to false, and a fixture
     * carrying non-zero deep/REM minutes while claiming no stages were reported is a state
     * `HealthConnectManager` cannot produce — it sets `stagesAvailable = hasStages`, derived
     * from the record's own stage list. Leaving it false made these fixtures exercise the
     * stage-less path while reading as if they tested the stage path.
     */
    private fun sleep(durationMin: Int = 480, efficiency: Double = 85.0, deep: Int = 90, rem: Int = 100) =
        SleepData(
            0L, durationMin, efficiency, 23 * 60, 7 * 60,
            rem, deep, durationMin - deep - rem - 30, 30,
            stagesAvailable = true
        )

    /** A night the source recorded with no stage breakdown at all. */
    private fun sleepNoStages(durationMin: Int = 480, efficiency: Double = 85.0) =
        SleepData(
            0L, durationMin, efficiency, 23 * 60, 7 * 60,
            0, 0, 0, 0,
            stagesAvailable = false
        )

    @Test
    fun `score is in 0 to 100 range`() {
        val result = SleepScoreCalculator.calculate(sleep(), (1..7).map { sleep() })
        assertTrue(result.score in 0f..100f)
    }

    @Test
    fun `full sleep meets need returns higher score than short sleep`() {
        val full = SleepScoreCalculator.calculate(sleep(480), (1..7).map { sleep(480) })
        val short = SleepScoreCalculator.calculate(sleep(300), (1..7).map { sleep(300) })
        assertTrue("Full sleep should score higher than short sleep", full.score > short.score)
    }

    @Test
    fun `sleep with zero deep and rem scores lower`() {
        val noStages = SleepScoreCalculator.calculate(sleep(480, 85.0, 0, 0), (1..7).map { sleep() })
        val goodStages = SleepScoreCalculator.calculate(sleep(480, 85.0, 90, 100), (1..7).map { sleep() })
        assertTrue("Good stages should outscore zero stages", goodStages.score > noStages.score)
    }

    // ── Absent stages must renormalise, not penalise ─────────────────────────
    //
    // A source that writes a sleep session with no stage breakdown previously scored 0 on
    // the 30%-weighted stage component, costing a flat 30 points on an unknown. These pin
    // the corrected behaviour.

    @Test
    fun `a night with no stage detail is not penalised for the missing component`() {
        val reported = SleepScoreCalculator.calculate(sleep(480), (1..7).map { sleep() })
        val notReported = SleepScoreCalculator.calculate(sleepNoStages(480), (1..7).map { sleep() })
        assertTrue(
            "A night whose stages were never reported (${notReported.score}) must not score " +
                "far below the same night with good stages (${reported.score})",
            notReported.score > reported.score - 5f
        )
    }

    @Test
    fun `absent stages drop the component rather than scoring it zero`() {
        val result = SleepScoreCalculator.calculate(sleepNoStages(480), (1..7).map { sleep() })
        assertTrue(
            "Sleep Stages must be absent from the breakdown, not present with score 0",
            result.breakdown.none { it.name == "Sleep Stages" }
        )
        assertFalse(
            "the stages weight must not be published when the component did not run",
            result.weights.containsKey("stages")
        )
    }

    @Test
    fun `published weights always sum to one whether or not stages are present`() {
        listOf(
            SleepScoreCalculator.calculate(sleep(480), (1..7).map { sleep() }),
            SleepScoreCalculator.calculate(sleepNoStages(480), (1..7).map { sleep() })
        ).forEach { result ->
            val sum = result.weights.values.sum()
            assertTrue(
                "effective weights must sum to 1.0 but summed to $sum for ${result.weights}",
                kotlin.math.abs(sum - 1.0f) < 0.001f
            )
        }
    }

    @Test
    fun `a stage-less night says so rather than reporting poor stages`() {
        val result = SleepScoreCalculator.calculate(sleepNoStages(480), (1..7).map { sleep() })
        assertTrue(
            "the explanation must state that stage detail was not recorded: ${result.explanation}",
            result.explanation.contains("Stage detail was not recorded")
        )
    }
}

class TrainingLoadCalculatorTest {

    private val mockSession = ExerciseSessionData(
        dateEpochDay = 0L,
        startMs = 0L,
        endMs = 60 * 60 * 1000L, // 1 hour
        type = "running",
        heartRatePoints = (1..60).map { HeartRatePoint(it * 60_000L, 155) },
        caloriesBurned = 500,
        distanceMeters = 8000f
    )

    @Test
    fun `normalized load is between 0 and 1`() {
        val load = TrainingLoadCalculator.calculateForSession(mockSession, 190)
        assertTrue(load.normalizedLoad in 0f..1f)
    }

    @Test
    fun `longer session yields higher load`() {
        val short = mockSession.copy(endMs = 30 * 60 * 1000L,
            heartRatePoints = (1..30).map { HeartRatePoint(it * 60_000L, 155) })
        val long = mockSession

        val shortLoad = TrainingLoadCalculator.calculateForSession(short, 190)
        val longLoad = TrainingLoadCalculator.calculateForSession(long, 190)
        assertTrue(longLoad.normalizedLoad > shortLoad.normalizedLoad)
    }
}

class AcwrCalculatorTest {

    @Test
    fun `returns 1 when all daily loads equal`() {
        val result = AcwrCalculator.calculate(List(28) { 0.5f })
        assertEquals(1.0f, result.acwr!!, 0.05f)
    }

    @Test
    fun `spike in last 7 days yields ACWR over 1`() {
        val result = AcwrCalculator.calculate(List(21) { 0.3f } + List(7) { 0.7f })
        assertTrue(result.acwr!! > 1.3f)
        assertTrue(result.zone == AcwrCalculator.AcwrZone.CAUTION || result.zone == AcwrCalculator.AcwrZone.DANGER)
    }

    @Test
    fun `zero training for last 7 days yields under-training zone`() {
        val result = AcwrCalculator.calculate(List(21) { 0.5f } + List(7) { 0f })
        assertEquals(AcwrCalculator.AcwrZone.UNDER_TRAINING, result.zone)
    }

    /**
     * The defect that made ACWR meaningless: rest days must be in the denominator.
     * Fed only training *sessions*, both windows converged on the user's typical session
     * intensity and the ratio sat near 1.0 no matter what they did.
     */
    @Test
    fun `rest days count as zero load so extra sessions move the ratio`() {
        // Baseline: trains every 3rd day at 0.9 for 21 days, then trains DAILY for 7.
        val chronicPart = (0 until 21).map { if (it % 3 == 0) 0.9f else 0f }
        val acutePart = List(7) { 0.9f }
        val result = AcwrCalculator.calculate(chronicPart + acutePart)
        assertTrue(
            "Tripling training frequency must push ACWR well above 1 (was ${result.acwr})",
            result.acwr!! > 1.5f
        )
        assertEquals(AcwrCalculator.AcwrZone.DANGER, result.zone)
    }

    @Test
    fun `fewer than 14 days of history is INSUFFICIENT not OPTIMAL`() {
        val result = AcwrCalculator.calculate(List(10) { 0.5f })
        assertEquals(AcwrCalculator.AcwrZone.INSUFFICIENT, result.zone)
        assertNull("ACWR must not be reported without enough history", result.acwr)
        assertFalse(result.isMeaningful)
    }

    /** A user who has never trained is not "in the optimal zone". */
    @Test
    fun `no training history at all is INSUFFICIENT not OPTIMAL`() {
        val result = AcwrCalculator.calculate(List(28) { 0f })
        assertEquals(AcwrCalculator.AcwrZone.INSUFFICIENT, result.zone)
        assertNull(result.acwr)
        assertFalse(result.isMeaningful)
    }

    @Test
    fun `buildDailySeries zero-fills rest days`() {
        val series = AcwrCalculator.buildDailySeries(
            loadByDay = mapOf(100L to 0.8f, 103L to 0.6f),
            endDay = 104L,
            windowDays = 5
        )
        assertEquals(listOf(0.8f, 0f, 0f, 0.6f, 0f), series)
    }
}

class Vo2MaxEstimatorTest {

    @Test
    fun `resting HR estimate returns valid range`() {
        val result = Vo2MaxEstimator.estimateFromRestingHR(55, 190)
        assertTrue(result.vo2Max > 0f)
        assertTrue(result.upperBound > result.lowerBound)
        assertTrue(result.lowerBound > 0f)
    }

    @Test
    fun `lower resting HR yields higher VO2 max`() {
        val fit = Vo2MaxEstimator.estimateFromRestingHR(45, 190)
        val unfit = Vo2MaxEstimator.estimateFromRestingHR(75, 190)
        assertTrue(fit.vo2Max > unfit.vo2Max)
    }

    @Test
    fun `explanation contains ESTIMATE label`() {
        val result = Vo2MaxEstimator.estimateFromRestingHR(60, 190)
        assertTrue(result.explanation.contains("ESTIMATE"))
    }
}

class BiologicalAgeEstimatorTest {

    @Test
    fun `fit person gets lower biological age than unfit`() {
        val restingHR30 = (1..30).map { RestingHRData(it.toLong(), 52) }
        val activity30 = (1..30).map { DailyActivityData(it.toLong(), 10000, 7000f, 500) }
        val fitAge = BiologicalAgeEstimator.estimate(35, 55f, restingHR30, activity30)

        val restingHRHigh = (1..30).map { RestingHRData(it.toLong(), 80) }
        val activityLow = (1..30).map { DailyActivityData(it.toLong(), 3000, 1000f, 200) }
        val unfitAge = BiologicalAgeEstimator.estimate(35, 30f, restingHRHigh, activityLow)

        assertTrue("Fit person should have lower biological age", fitAge.estimatedAge < unfitAge.estimatedAge)
    }

    @Test
    fun `disclaimer is present in result`() {
        val result = BiologicalAgeEstimator.estimate(30, 45f, emptyList(), emptyList())
        assertTrue(result.disclaimer.isNotEmpty())
    }

    @Test
    fun `biological age is capped within 15 years of chronological age`() {
        val result = BiologicalAgeEstimator.estimate(35, 70f, emptyList(), emptyList())
        assertTrue(result.estimatedAge in 20..50)
    }
}

/**
 * ScoreFactor persistence encoding — round-trip correctness for the delimited-string
 * scheme used to store Readiness/Sleep/Stress/Recovery breakdowns in Room (see ScoreResult.kt).
 */
class ScoreFactorEncodingTest {

    @Test
    fun `encode then decode round-trips a single factor`() {
        val factors = listOf(
            ScoreFactor(
                name = "Resting Heart Rate",
                contribution = 30f,
                rawValue = "58 bpm (baseline 60 bpm)",
                score = 62f,
                description = "Resting HR within your normal range.",
                delta = "Resting HR ↓2 bpm vs baseline"
            )
        )
        val decoded = decodeScoreFactors(factors.encodeToString())
        assertEquals(1, decoded.size)
        assertEquals(factors[0], decoded[0])
    }

    @Test
    fun `encode then decode round-trips multiple factors and preserves order`() {
        val factors = listOf(
            ScoreFactor("Sleep Quality", 35f, "88% efficiency, 7h 30m", 80f, "Above your 14-day average."),
            ScoreFactor("Resting Heart Rate", 30f, "58 bpm", 62f, "Within your normal range.", delta = null),
            ScoreFactor("Sleep Consistency", 20f, "Bedtime variance: 12min", 91f, "Consistent bedtime routine.")
        )
        val decoded = decodeScoreFactors(factors.encodeToString())
        assertEquals(factors, decoded)
    }

    @Test
    fun `decoding null or blank returns empty list`() {
        assertTrue(decodeScoreFactors(null).isEmpty())
        assertTrue(decodeScoreFactors("").isEmpty())
    }

    @Test
    fun `null delta round-trips as null not empty string`() {
        val factors = listOf(ScoreFactor("Training Recovery", 15f, "No workout yesterday", 75f, "Full recovery assumed.", delta = null))
        val decoded = decodeScoreFactors(factors.encodeToString())
        assertNull(decoded[0].delta)
    }
}
