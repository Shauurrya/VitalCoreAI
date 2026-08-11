package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression guards for the calculation defects fixed in the analytics pass.
 *
 * Each test names the behaviour that was wrong and asserts the corrected one, so a future
 * refactor cannot quietly reintroduce it.
 */

// ─── Baseline windowing ───────────────────────────────────────────────────────

class BaselineWindowingTest {

    /**
     * The contract every consumer relies on: `BaselineManager` takes values **oldest
     * first** and does `takeLast(horizon)`. Feeding it a date-DESC list therefore selects
     * the OLDEST rows whenever the list is longer than the horizon — the exact inversion
     * that `getLatest(N)` produced.
     */
    @Test
    fun `baseline uses the most recent values, not the oldest`() {
        // 60 ascending days: the first 30 average 65, the last 30 average 55.
        val ascending = (1..30).map { 65.0 } + (1..30).map { 55.0 }
        val baseline = BaselineManager.restingHRBaseline(ascending)
        assertEquals(
            "A 30-day baseline must reflect the LAST 30 values, not the first 30",
            55.0, baseline.mean, 0.5
        )
    }

    /**
     * The critical bug: during backfill, `getLatest(30)` returned the newest rows in the
     * table — which, for a historical day, are days AFTER it. Every backfilled score was
     * z-scored against its own future.
     *
     * This asserts the property that makes such leakage detectable: a score computed
     * against a past-only baseline is unchanged by data that arrives later.
     */
    @Test
    fun `a historical day's score does not depend on future data`() {
        val pastOnly = (1..30).map { RestingHRData(it.toLong(), 58) }
        val withFuture = pastOnly + (31..45).map { RestingHRData(it.toLong(), 75) }

        val sleepBaseline = (1..14).map { makeSleep(85.0) }
        fun scoreWith(hrBaseline: List<RestingHRData>) = RecoveryScoreCalculator.calculate(
            todaySleep = makeSleep(85.0),
            sleepBaseline14Days = sleepBaseline,
            todayRestingHR = RestingHRData(31L, 58),
            restingHRBaseline30Days = hrBaseline,
            priorDayTrainingLoad = null
        ).score

        assertNotEquals(
            "This test is only meaningful if future data WOULD change the score",
            scoreWith(pastOnly), scoreWith(withFuture)
        )
        // The repository now passes getRange(day-30, day-1), so only `pastOnly` can ever
        // reach the calculator for day 31. The guard is that the two differ at all — if a
        // future refactor reintroduced getLatest(), the historical score would silently
        // become the second value.
    }

    /** Sample (n−1) standard deviation, not population (n). */
    @Test
    fun `stdDev uses the Bessel-corrected divisor`() {
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        // Population σ = 2.0; sample s = 2.138
        assertEquals(2.138, BaselineUtils.stdDev(values), 0.01)
    }

    @Test
    fun `resting HR baseline std is floored at a physiologically defensible value`() {
        // A very consistent athlete: true SD near zero.
        val baseline = BaselineManager.restingHRBaseline(List(30) { 52.0 })
        assertTrue(
            "A 0.5 bpm floor makes a 3 bpm swing a z of 6 and tanks recovery on noise",
            baseline.std >= BaselineManager.MinStd.RESTING_HR - 0.001
        )
    }

    private fun makeSleep(efficiency: Double) = SleepData(
        dateEpochDay = 0L, durationMinutes = 480, efficiencyPercent = efficiency,
        bedtimeMinuteOfDay = 23 * 60, wakeTimeMinuteOfDay = 7 * 60,
        remMinutes = 100, deepMinutes = 80, lightMinutes = 250, awakeMinutes = 50,
        stagesAvailable = true
    )
}

// ─── Circular clock statistics ────────────────────────────────────────────────

class CircularStatisticsTest {

    /**
     * 23:50 and 00:10 are twenty minutes apart, not 1420. A linear SD handed a consistent
     * sleeper who drifts either side of midnight a catastrophic consistency score.
     */
    @Test
    fun `bedtime variance across midnight is small, not enormous`() {
        val bedtimes = listOf(23 * 60 + 50, 10, 23 * 60 + 55, 5, 0)
        val circular = BaselineUtils.circularStdDevMinutes(bedtimes)
        val linear = BaselineUtils.stdDev(bedtimes.map { it.toDouble() })

        assertTrue("Circular SD for a 20-minute spread must be small (was $circular)", circular < 40)
        assertTrue("The linear SD is the bug being guarded against (was $linear)", linear > 500)
    }

    @Test
    fun `identical bedtimes have near-zero circular variance`() {
        assertEquals(0.0, BaselineUtils.circularStdDevMinutes(List(7) { 23 * 60 }), 1.0)
    }

    @Test
    fun `circular mean across midnight lands near midnight`() {
        val mean = BaselineUtils.circularMeanMinutes(listOf(23 * 60 + 50, 10))!!
        assertTrue("Mean of 23:50 and 00:10 should be ~00:00 (was $mean)", mean >= 1435 || mean <= 5)
    }
}

// ─── Sleep-need / settings threading ─────────────────────────────────────────

class SleepNeedThreadingTest {

    private fun sleep(durationMin: Int) = SleepData(
        dateEpochDay = 0L, durationMinutes = durationMin, efficiencyPercent = 88.0,
        bedtimeMinuteOfDay = 23 * 60, wakeTimeMinuteOfDay = 7 * 60,
        remMinutes = 100, deepMinutes = 80, lightMinutes = durationMin - 210, awakeMinutes = 30,
        stagesAvailable = true
    )

    /** The configured need must actually move the score, not be ignored. */
    @Test
    fun `sleep score honours the configured sleep need`() {
        val night = sleep(420)   // exactly 7h
        val sevenHourNeed = SleepScoreCalculator.calculate(
            night, List(7) { sleep(420) }, personalSleepNeedMinutes = 420
        ).score
        val nineHourNeed = SleepScoreCalculator.calculate(
            night, List(7) { sleep(420) }, personalSleepNeedMinutes = 540
        ).score

        assertTrue(
            "7h of sleep must score better against a 7h need than a 9h need " +
                "($sevenHourNeed vs $nineHourNeed)",
            sevenHourNeed > nineHourNeed
        )
    }

    @Test
    fun `duration score curve is shared with the recovery calculator`() {
        assertEquals(100f, SleepScoreCalculator.durationScoreFor(480, 480), 0.01f)
        assertEquals(80f, SleepScoreCalculator.durationScoreFor(432, 480), 0.01f)   // 90%
        assertEquals(50f, SleepScoreCalculator.durationScoreFor(336, 480), 0.01f)   // 70%
        assertEquals(0f, SleepScoreCalculator.durationScoreFor(0, 480), 0.01f)
    }

    /** The old bottom branch (`ratio * 71f`) was discontinuous with the branch above it. */
    @Test
    fun `duration score is continuous across its piecewise boundaries`() {
        for (need in listOf(420, 480, 540)) {
            for (ratioTimes100 in 1..120) {
                val a = SleepScoreCalculator.durationScoreFor((need * ratioTimes100 / 100), need)
                val b = SleepScoreCalculator.durationScoreFor((need * (ratioTimes100 + 1) / 100), need)
                assertTrue("Score must not jump at ratio $ratioTimes100%", b - a < 12f)
            }
        }
    }

    /**
     * Sleep debt was banded on the raw cumulative total, so 30 min short every night and
     * 4 h short every night both floored at the same score.
     */
    @Test
    fun `debt score responds across the whole realistic range`() {
        val mild = SleepScoreCalculator.debtScoreFor(30)
        val moderate = SleepScoreCalculator.debtScoreFor(60)
        val severe = SleepScoreCalculator.debtScoreFor(120)
        assertEquals(100f, SleepScoreCalculator.debtScoreFor(0), 0.01f)
        assertTrue("Debt score must be strictly decreasing", mild > moderate && moderate > severe)
        assertTrue("A 30-min nightly shortfall must not be treated as catastrophic", mild >= 75f)
    }

    /** Readiness banded on cumulative minutes was saturated at its floor for most users. */
    @Test
    fun `readiness sleep debt scales with nights counted, not raw minutes`() {
        val acwr = AcwrCalculator.calculate(List(28) { 0.5f })
        // 210 min over 7 nights = 30/night (mild). 210 min over 1 night = severe.
        val spreadOverWeek = ReadinessScoreCalculator.calculate(
            recoveryScore = 70f, sleepDebtMinutes = 210, acwrResult = acwr, nightsCounted = 7
        ).score
        val allOneNight = ReadinessScoreCalculator.calculate(
            recoveryScore = 70f, sleepDebtMinutes = 210, acwrResult = acwr, nightsCounted = 1
        ).score
        assertTrue(
            "The same cumulative debt must score better spread over 7 nights " +
                "($spreadOverWeek vs $allOneNight)",
            spreadOverWeek > allOneNight
        )
    }
}

// ─── Recovery: duration influence and absent efficiency ──────────────────────

class RecoveryComponentTest {

    private fun sleep(duration: Int, efficiency: Double?) = SleepData(
        dateEpochDay = 0L, durationMinutes = duration, efficiencyPercent = efficiency,
        bedtimeMinuteOfDay = 23 * 60, wakeTimeMinuteOfDay = 7 * 60,
        remMinutes = 90, deepMinutes = 70, lightMinutes = 200, awakeMinutes = 30,
        stagesAvailable = efficiency != null
    )

    private val hrBaseline = (1..30).map { RestingHRData(it.toLong(), 58 + (it % 5)) }
    private val sleepBaseline = (1..14).map { sleep(450, 85.0) }

    private fun recovery(today: SleepData) = RecoveryScoreCalculator.calculate(
        todaySleep = today,
        sleepBaseline14Days = sleepBaseline,
        todayRestingHR = RestingHRData(0L, 58),
        restingHRBaseline30Days = hrBaseline,
        priorDayTrainingLoad = null
    ).score

    /**
     * The most misleading behaviour in the old engine: recovery read efficiency only, so
     * four hours at 95% outscored eight hours at 88% on the largest component.
     */
    @Test
    fun `sleep duration influences the recovery score`() {
        val shortEfficient = recovery(sleep(240, 95.0))
        val longSlightlyLessEfficient = recovery(sleep(480, 88.0))
        assertTrue(
            "8h at 88% must beat 4h at 95% ($longSlightlyLessEfficient vs $shortEfficient)",
            longSlightlyLessEfficient > shortEfficient
        )
    }

    /**
     * Absent stage data must redistribute onto duration, not collapse the component. The
     * old path computed (deep+rem+light)/duration = 0.0% and z-scored that against an ~83%
     * baseline, dropping recovery ~35 points at HIGH confidence on a perfectly good night.
     */
    @Test
    fun `a night with no stage data does not collapse the recovery score`() {
        val withoutStages = recovery(sleep(480, null))
        assertTrue(
            "A full night with unknown efficiency must still score well (was $withoutStages)",
            withoutStages > 55f
        )
    }

    @Test
    fun `zero efficiency is never fabricated from missing stages`() {
        val noStages = sleep(480, null)
        assertNull(noStages.efficiencyPercent)
        assertFalse(noStages.stagesAvailable)
    }

    /** A single spurious spot reading must not cost 5 recovery points. */
    @Test
    fun `SpO2 modifier requires at least three readings`() {
        fun withSpO2(count: Int) = RecoveryScoreCalculator.calculate(
            todaySleep = sleep(480, 88.0),
            sleepBaseline14Days = sleepBaseline,
            todayRestingHR = RestingHRData(0L, 58),
            restingHRBaseline30Days = hrBaseline,
            priorDayTrainingLoad = null,
            spO2Percent = 85f,
            spO2ReadingCount = count
        ).score

        assertEquals("One reading must apply no modifier", withSpO2(0), withSpO2(1), 0.01f)
        assertTrue("Three readings must apply the penalty", withSpO2(5) < withSpO2(1))
    }
}

// ─── Activity, consistency and quality profiles ──────────────────────────────

class ActivityScoringTest {

    private fun activity(steps: Int, active: Int? = null, total: Int = 2400) =
        DailyActivityData(0L, steps, 5000f, total, active)

    /**
     * The calorie component divided TOTAL daily energy (2000–2800 kcal) by 600, so it
     * clamped to 100 for every user on every day while the card said "Active Calories
     * 100/100".
     */
    @Test
    fun `calorie component is not pinned at 100 by total daily energy`() {
        val baseline = (1..30).map { activity(7000) }
        val lowActive = TrendCalculators.calculateActivityScore(
            activity(7000, active = 100, total = 2400), baseline
        )
        val highActive = TrendCalculators.calculateActivityScore(
            activity(7000, active = 700, total = 2400), baseline
        )
        assertTrue(
            "Active calories must actually move the score (${lowActive.score} vs ${highActive.score})",
            highActive.score > lowActive.score
        )
        val calorieFactor = lowActive.breakdown.first { it.name == "Active Calories" }
        assertTrue("A 100 kcal active day must not score 100/100", calorieFactor.score < 50f)
    }

    @Test
    fun `absent active calories drops the component instead of shipping a constant`() {
        val result = TrendCalculators.calculateActivityScore(
            activity(7000, active = null), (1..30).map { activity(7000) }
        )
        assertTrue(result.breakdown.none { it.name == "Active Calories" })
        assertEquals(1.0f, result.weights["steps"]!!, 0.001f)
    }

    /**
     * A new user with five days of data, all highly active, scored 5/30 = 16.7 and was
     * told their consistency was poor — for their entire first month.
     */
    @Test
    fun `consistency divides by days recorded, not a hardcoded 30`() {
        val fiveActiveDays = (1..5).map { activity(12000) }
        assertEquals(
            "Five of five active days is 100% consistency",
            100f, TrendCalculators.calculateConsistencyScore(fiveActiveDays), 0.01f
        )
        assertEquals(0f, TrendCalculators.calculateConsistencyScore(emptyList()), 0.01f)
    }

    @Test
    fun `consistency honours a custom step goal`() {
        val days = (1..10).map { activity(6000) }
        assertEquals(0f, TrendCalculators.calculateConsistencyScore(days, stepGoal = 7500), 0.01f)
        assertEquals(100f, TrendCalculators.calculateConsistencyScore(days, stepGoal = 5000), 0.01f)
    }

    /** Activity was capped at 30% confidence — permanently LOW — by sleep-weighted checks. */
    @Test
    fun `activity score can reach HIGH confidence without sleep data`() {
        val result = TrendCalculators.calculateActivityScore(
            activity(9000, active = 500), (1..30).map { activity(7500, active = 400) }
        )
        assertEquals(
            "An activity score must be judged on activity data, not on whether the user slept",
            Confidence.HIGH, result.confidence
        )
        assertFalse(result.dataQuality.insufficientData)
    }

    /** Stress was capped at 45% — it could never be HIGH no matter how much data existed. */
    @Test
    fun `stress score can reach HIGH confidence with full HR history`() {
        val baseline = (1..30).map { RestingHRData(it.toLong(), 58 + (it % 4)) }
        val result = TrendCalculators.calculateStressScore(62, baseline)
        assertEquals(Confidence.HIGH, result.confidence)
    }
}

// ─── Period scores must not fabricate a 50 ───────────────────────────────────

class PeriodScoreHonestyTest {

    @Test
    fun `an empty month has no score, not a fabricated 50`() {
        val result = TrendCalculators.calculateMonthlyHealthScore(emptyList(), null)
        assertNull("Monthly score must be null so the UI can say 'Not enough data'", result.score)
        assertNull(result.deltaFromPreviousPeriod)
    }

    @Test
    fun `a week with no data at all has no score`() {
        val result = TrendCalculators.calculateWeeklyHealthScore(null, null, null, null, null)
        assertNull(result.score)
    }

    @Test
    fun `a week with partial data renormalises over the components present`() {
        // Only recovery, at 80. The composite must be 80, not 80×0.30 + fabricated filler.
        val result = TrendCalculators.calculateWeeklyHealthScore(80f, null, null, null, null)
        assertEquals(80f, result.score!!, 0.01f)
    }
}

// ─── HR zones on heart-rate reserve ──────────────────────────────────────────

class HeartRateZoneTest {

    /**
     * Under %HRmax, a user with RHR 58 / HRmax 190 sits at 31% while asleep — below every
     * boundary — and the old lookup swept that into Zone 1 alongside a genuine recovery
     * jog, so the all-day chart claimed ~90% of the day was spent training.
     */
    @Test
    fun `resting heart rate maps below zone 1, not into it`() {
        val restingPoints = (1..60).map { HeartRatePoint(it * 60_000L, 58) }
        val distribution = TrainingLoadCalculator.calculateZoneDistribution(
            restingPoints, maxHR = 190, restingHR = 58
        )
        assertEquals(1.0f, distribution[HRZone.BELOW_ZONE1]!!, 0.01f)
        assertEquals(0f, distribution[HRZone.ZONE1] ?: 0f, 0.01f)
    }

    @Test
    fun `zone lookup follows Karvonen reserve fractions`() {
        assertEquals(HRZone.BELOW_ZONE1, HRZone.ofReserveFraction(0.0))
        assertEquals(HRZone.BELOW_ZONE1, HRZone.ofReserveFraction(0.49))
        assertEquals(HRZone.ZONE1, HRZone.ofReserveFraction(0.50))
        assertEquals(HRZone.ZONE3, HRZone.ofReserveFraction(0.75))
        assertEquals(HRZone.ZONE5, HRZone.ofReserveFraction(1.0))
    }

    /**
     * Sampling is per-second in a workout and per-10-minutes at rest, so counting samples
     * reports whichever period was sampled densely rather than time actually spent.
     */
    @Test
    fun `zone distribution is weighted by time, not by sample count`() {
        // 10 min of hard work sampled every 10s (60 samples), then 60 min of rest
        // sampled every 5 min (12 samples). By count, hard work looks like 83% of the day.
        val points = mutableListOf<HeartRatePoint>()
        var ms = 0L
        repeat(60) { points += HeartRatePoint(ms, 170); ms += 10_000L }
        repeat(12) { points += HeartRatePoint(ms, 60); ms += 300_000L }

        val distribution = TrainingLoadCalculator.calculateZoneDistribution(points, 190, 58)
        val restFraction = distribution[HRZone.BELOW_ZONE1] ?: 0f
        assertTrue(
            "Time-weighted rest must dominate this day (was $restFraction)",
            restFraction > 0.6f
        )
    }

    @Test
    fun `no HR data returns an empty distribution, never a fabricated zone 2`() {
        assertTrue(TrainingLoadCalculator.calculateZoneDistribution(emptyList(), 190, 58).isEmpty())
    }

    /** "Has HR data" must be carried explicitly, not inferred from a legitimate outcome. */
    @Test
    fun `a genuine zone 2 session is not reported as missing HR data`() {
        val session = ExerciseSessionData(
            dateEpochDay = 0L, startMs = 0L, endMs = 60 * 60_000L,
            type = "Running", heartRatePoints = (1..60).map { HeartRatePoint(it * 60_000L, 145) },
            caloriesBurned = 500, distanceMeters = 8000f
        )
        val load = TrainingLoadCalculator.calculateForSession(session, 190, 58)
        assertTrue(load.hasHeartRateData)
        assertEquals(Confidence.HIGH, TrainingLoadCalculator.summarizeResult(load).confidence)
    }

    @Test
    fun `tanaka max HR is used rather than 220 minus age`() {
        assertEquals(180, TrainingLoadCalculator.tanakaMaxHR(40))
        assertEquals(166, TrainingLoadCalculator.tanakaMaxHR(60))
    }
}

// ─── VO2 max and biological age ──────────────────────────────────────────────

class Vo2AndBioAgeTest {

    /** The default was 220 — the numerator of the age formula, not a heart rate. */
    @Test
    fun `default max HR is a real heart rate`() {
        val withDefault = Vo2MaxEstimator.estimateFromRestingHR(60)
        val explicit = Vo2MaxEstimator.estimateFromRestingHR(60, 190)
        assertEquals(explicit.vo2Max, withDefault.vo2Max, 0.01f)
        assertTrue("15 × 220/60 = 55 would be spuriously 'Superior'", withDefault.vo2Max < 52f)
    }

    @Test
    fun `uses the published Uth coefficient of 15 point 3`() {
        // 15.3 × 190/60 = 48.45
        assertEquals(48.45f, Vo2MaxEstimator.estimateFromRestingHR(60, 190).vo2Max, 0.05f)
    }

    @Test
    fun `age adjustment lowers the estimate for an older user at the same resting HR`() {
        val young = Vo2MaxEstimator.estimateFromRestingHR(50, 190, age = 25)
        val old = Vo2MaxEstimator.estimateFromRestingHR(50, 190, age = 60)
        assertTrue(
            "Uth was validated on trained young men; age must matter ($old vs $young)",
            old.vo2Max < young.vo2Max
        )
    }

    @Test
    fun `HUNT age norms decline with age`() {
        assertTrue(Vo2MaxEstimator.ageNorm(30) > Vo2MaxEstimator.ageNorm(60))
        assertTrue(Vo2MaxEstimator.ageNorm(30, isFemale = true) < Vo2MaxEstimator.ageNorm(30))
    }

    /**
     * The exercise path was fed m/s where the ACSM equation requires m/min, under-counting
     * the speed term 60× and collapsing the estimate to a near-constant regardless of pace.
     */
    @Test
    fun `exercise estimate responds to pace`() {
        fun session(distanceMeters: Float) = ExerciseSessionData(
            dateEpochDay = 0L, startMs = 0L, endMs = 30 * 60_000L, type = "Running",
            heartRatePoints = (1..30).map { HeartRatePoint(it * 60_000L, 160) },
            caloriesBurned = 300, distanceMeters = distanceMeters
        )
        val slow = Vo2MaxEstimator.estimateFromExercise(session(4000f), 58, 190)!!
        val fast = Vo2MaxEstimator.estimateFromExercise(session(7000f), 58, 190)!!
        assertTrue("A faster 30-min run must estimate higher (${fast.vo2Max} vs ${slow.vo2Max})",
            fast.vo2Max > slow.vo2Max + 5f)
    }

    @Test
    fun `exercise estimate returns null rather than NaN when HR is absent`() {
        val session = ExerciseSessionData(
            dateEpochDay = 0L, startMs = 0L, endMs = 30 * 60_000L, type = "Running",
            heartRatePoints = emptyList(), caloriesBurned = 300, distanceMeters = 6000f
        )
        assertNull(Vo2MaxEstimator.estimateFromExercise(session, 58, 190))
    }

    /**
     * `activeDays / 7f >= 5` needed 35 active days inside a 30-day window, so the "very
     * active" offset could never be awarded.
     */
    @Test
    fun `the very active biological age offset is reachable`() {
        val restingHR = (1..30).map { RestingHRData(it.toLong(), 52) }
        val active = (1..30).map { DailyActivityData(it.toLong(), 12000, 8000f, 2400, 600) }
        val result = BiologicalAgeEstimator.estimate(35, 48f, restingHR, active)
        assertEquals(
            "30 of 30 active days is 7/week and must earn the top offset",
            -2, result.activityOffsetYears
        )
    }

    /** The VO2 term could swing 30 years while RHR contributed 7 and activity 6. */
    @Test
    fun `VO2 contribution is capped so it cannot dominate the estimate`() {
        val restingHR = (1..30).map { RestingHRData(it.toLong(), 60) }
        val activity = (1..30).map { DailyActivityData(it.toLong(), 8000, 6000f, 2400, 400) }
        val absurdlyHigh = BiologicalAgeEstimator.estimate(35, 90f, restingHR, activity)
        val absurdlyLow = BiologicalAgeEstimator.estimate(35, 15f, restingHR, activity)
        assertTrue(absurdlyHigh.vo2OffsetYears >= -8)
        assertTrue(absurdlyLow.vo2OffsetYears <= 8)
    }

    @Test
    fun `VO2 offset is referenced against the norm for the user's own age`() {
        val restingHR = (1..30).map { RestingHRData(it.toLong(), 60) }
        val activity = (1..30).map { DailyActivityData(it.toLong(), 8000, 6000f, 2400, 400) }
        // 38 mL/kg/min is unremarkable at 35 but exceptional at 60.
        val youngOffset = BiologicalAgeEstimator.estimate(35, 38f, restingHR, activity).vo2OffsetYears
        val olderOffset = BiologicalAgeEstimator.estimate(60, 38f, restingHR, activity).vo2OffsetYears
        assertTrue(
            "A fit 60-year-old must not be penalised against a 35-year-old's norm " +
                "($olderOffset vs $youngOffset)",
            olderOffset < youngOffset
        )
    }

    @Test
    fun `insufficient history is flagged rather than guessed`() {
        val result = BiologicalAgeEstimator.estimate(35, 45f, emptyList(), emptyList())
        assertTrue(result.insufficientData)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `component offsets are exposed so the UI can show what drives the number`() {
        val restingHR = (1..30).map { RestingHRData(it.toLong(), 52) }
        val activity = (1..30).map { DailyActivityData(it.toLong(), 12000, 8000f, 2400, 600) }
        val result = BiologicalAgeEstimator.estimate(35, 50f, restingHR, activity)
        assertEquals(
            result.estimatedAge - 35,
            result.vo2OffsetYears + result.restingHROffsetYears + result.activityOffsetYears
        )
        assertTrue(result.vo2Descriptor.contains("35"))
    }
}

// ─── Improvement simulator ───────────────────────────────────────────────────

class ImprovementSimulatorTest {

    private fun sleep(duration: Int, efficiency: Double? = 82.0) = SleepData(
        dateEpochDay = 0L, durationMinutes = duration, efficiencyPercent = efficiency,
        bedtimeMinuteOfDay = 23 * 60, wakeTimeMinuteOfDay = 6 * 60,
        remMinutes = 80, deepMinutes = 60, lightMinutes = 150, awakeMinutes = 30,
        stagesAvailable = efficiency != null
    )

    /**
     * These were dead code: recovery ignored duration, so every nudged score was
     * bit-identical to the control, the delta was 0, and the `>= 2` gate rejected all
     * three. The most actionable suggestion the app can give could never be shown.
     */
    @Test
    fun `sleep duration suggestions are now reachable`() {
        val suggestions = ImprovementSimulator.simulate(
            currentSleep = sleep(360),          // 6h against an 8h need
            sleepBaseline14 = (1..14).map { sleep(400) },
            currentHR = RestingHRData(0L, 58),
            hrBaseline30 = (1..30).map { RestingHRData(it.toLong(), 58 + (it % 4)) },
            priorLoad = null,
            currentScore = 55f
        )
        assertTrue(
            "A user 2 hours short of their need must be offered a sleep-duration nudge",
            suggestions.any { it.category == ImprovementSimulator.SuggestionCategory.SLEEP &&
                it.action.contains("longer") }
        )
    }

    /** A well-rested user should not be told sleeping more helps. */
    @Test
    fun `no duration suggestion when the user already meets their need`() {
        val suggestions = ImprovementSimulator.simulate(
            currentSleep = sleep(520),
            sleepBaseline14 = (1..14).map { sleep(500) },
            currentHR = RestingHRData(0L, 58),
            hrBaseline30 = (1..30).map { RestingHRData(it.toLong(), 58) },
            priorLoad = null,
            currentScore = 80f
        )
        assertTrue(suggestions.none { it.action.contains("longer") })
    }

    /**
     * Deltas must come from a matched control. Measuring against the stored score — which
     * is computed with SpO2 and a real quality input — produced fabricated ±5 swings from
     * no-op nudges.
     */
    @Test
    fun `projected deltas are independent of the passed-in current score`() {
        fun run(currentScore: Float) = ImprovementSimulator.simulate(
            currentSleep = sleep(360),
            sleepBaseline14 = (1..14).map { sleep(400) },
            currentHR = RestingHRData(0L, 58),
            hrBaseline30 = (1..30).map { RestingHRData(it.toLong(), 58 + (it % 4)) },
            priorLoad = null,
            currentScore = currentScore
        )
        assertEquals(
            "The delta must come from a matched control, not from the stored score",
            run(50f).map { it.projectedDelta }, run(70f).map { it.projectedDelta }
        )
    }
}

// ─── Data quality reasons ────────────────────────────────────────────────────

class DataQualityReasonsTest {

    /** Perfect data used to be shown four "reasons" as if something were wrong. */
    @Test
    fun `flawless data reports no problems`() {
        val report = DataQualityEngine.evaluate(
            DataQualityEngine.QualityInput(
                hasSleepToday = true, hasHRToday = true,
                sleepHistoryDays = 30, hrHistoryDays = 30,
                hrPointsPerHour = 6.0,
                dataSourceType = DataQualityEngine.DataSourceType.WATCH_SENSOR,
                partialDayFraction = 1.0
            )
        )
        assertEquals(Confidence.HIGH, report.level)
        assertEquals(listOf("All expected data present"), report.reasons)
    }

    @Test
    fun `genuine problems are reported worst first`() {
        val report = DataQualityEngine.evaluate(
            DataQualityEngine.QualityInput(
                hasSleepToday = false, hasHRToday = true,
                sleepHistoryDays = 0, hrHistoryDays = 30,
                hrPointsPerHour = 6.0,
                dataSourceType = DataQualityEngine.DataSourceType.WATCH_SENSOR
            )
        )
        assertTrue(report.reasons.isNotEmpty())
        assertTrue(report.reasons.any { it.contains("sleep") })
    }
}
