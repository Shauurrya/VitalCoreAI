package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.*
import org.junit.Assert.*
import org.junit.Test

// ─── DataQualityEngine tests (A3) ─────────────────────────────────────────────

class DataQualityEngineTest {

    @Test
    fun `full data yields HIGH confidence`() {
        val input = DataQualityEngine.QualityInput(
            hasSleepToday = true,
            hasHRToday = true,
            sleepHistoryDays = 14,
            hrHistoryDays = 30,
            hrPointsPerHour = 4.0,
            overnightHRGapHours = 0.0,
            dataSourceType = DataQualityEngine.DataSourceType.WATCH_SENSOR,
            partialDayFraction = 1.0
        )
        val report = DataQualityEngine.evaluate(input)
        assertEquals(Confidence.HIGH, report.level)
        assertFalse(report.insufficientData)
    }

    @Test
    fun `no data yields LOW confidence and insufficientData flag`() {
        val input = DataQualityEngine.QualityInput() // all defaults = no data
        val report = DataQualityEngine.evaluate(input)
        assertEquals(Confidence.LOW, report.level)
        assertTrue(report.insufficientData)
    }

    @Test
    fun `partial data yields MEDIUM confidence`() {
        val input = DataQualityEngine.QualityInput(
            hasSleepToday = true,
            hasHRToday = true,
            sleepHistoryDays = 5,
            hrHistoryDays = 5
        )
        val report = DataQualityEngine.evaluate(input)
        assertEquals(Confidence.MEDIUM, report.level)
    }

    @Test
    fun `confidence percent is within 0 to 100`() {
        val report = DataQualityEngine.evaluate(DataQualityEngine.QualityInput(hasSleepToday = true))
        assertTrue(report.confidencePercent in 0..100)
    }
}

// ─── WearDetector tests (A4) ──────────────────────────────────────────────────

class WearDetectorTest {

    @Test
    fun `continuous HR data returns worn = true`() {
        val hrPoints = (0..23).map { h -> Pair(h * 60, 65) }
        val status = WearDetector.detect(hrPoints)
        assertTrue(status.worn)
        assertEquals(WearDetector.WearConfidence.HIGH, status.confidence)
    }

    @Test
    fun `phone only steps no HR returns worn false phone flag`() {
        val status = WearDetector.detect(
            hrPointsWithMinuteOfDay = emptyList(),
            hasSteps = true,
            hasCalories = false
        )
        assertFalse(status.worn)
        assertTrue(status.phoneOnlyTracking)
    }

    @Test
    fun `no data at all returns worn = false low confidence`() {
        val status = WearDetector.detect(emptyList())
        assertFalse(status.worn)
        assertEquals(WearDetector.WearConfidence.LOW, status.confidence)
    }

    @Test
    fun `large overnight HR gap detected as watch removed`() {
        // Data from 07:00 onward only — gap from midnight to 07:00 = 420 min (7h)
        val hrPoints = (7..23).map { h -> Pair(h * 60, 65) }
        val status = WearDetector.detect(
            hrPointsWithMinuteOfDay = hrPoints,
            firstDataMinuteOfDay = 7 * 60
        )
        assertFalse(status.worn)
    }
}

// ─── BaselineManager tests (A5) ──────────────────────────────────────────────

class BaselineManagerTest {

    @Test
    fun `zero days returns population default mean`() {
        val baseline = BaselineManager.restingHRBaseline(emptyList())
        assertEquals(BaselineManager.PopulationDefaults.RESTING_HR_MEAN, baseline.mean, 1.0)
        assertEquals(0.0, baseline.personalWeight, 0.001)
    }

    @Test
    fun `30 days returns fully personal baseline`() {
        val values = (1..30).map { 55.0 + it * 0.1 }
        val baseline = BaselineManager.restingHRBaseline(values)
        assertEquals(1.0, baseline.personalWeight, 0.001)
        assertTrue(baseline.isFullyPersonal)
    }

    @Test
    fun `3 days returns low personal weight`() {
        val values = listOf(60.0, 61.0, 59.0)
        val baseline = BaselineManager.restingHRBaseline(values)
        assertTrue(baseline.personalWeight in 0.1..0.4)
    }

    @Test
    fun `std never collapses to zero even with identical values`() {
        val values = (1..30).map { 62.0 }
        val baseline = BaselineManager.restingHRBaseline(values)
        assertTrue(baseline.std > 0.0)
    }

    @Test
    fun `personalWeightForDays is monotonically increasing`() {
        val weights = (0..30).map { BaselineManager.personalWeightForDays(it) }
        for (i in 1 until weights.size) {
            assertTrue("Weight should not decrease at n=$i", weights[i] >= weights[i-1])
        }
    }
}

// ─── MomentumCalculator tests (B1) ───────────────────────────────────────────

class MomentumCalculatorTest {

    @Test
    fun `consistently rising scores yield IMPROVING direction`() {
        val scores = listOf(50f, 55f, 60f, 65f, 70f)
        val report = MomentumCalculator.calculate(scores, scores, scores)
        assertEquals(MomentumCalculator.MomentumDirection.IMPROVING, report.recovery.direction)
    }

    @Test
    fun `consistently falling scores yield DECLINING direction`() {
        val scores = listOf(80f, 75f, 70f, 65f, 60f)
        val report = MomentumCalculator.calculate(scores, scores, scores)
        assertEquals(MomentumCalculator.MomentumDirection.DECLINING, report.recovery.direction)
    }

    @Test
    fun `flat scores yield STABLE direction`() {
        val scores = listOf(60f, 61f, 60f, 61f, 60f)
        val report = MomentumCalculator.calculate(scores, scores, scores)
        assertEquals(MomentumCalculator.MomentumDirection.STABLE, report.recovery.direction)
    }

    @Test
    fun `single data point returns STABLE with LOW confidence`() {
        val report = MomentumCalculator.calculate(listOf(70f), emptyList(), emptyList())
        assertEquals(MomentumCalculator.MomentumDirection.STABLE, report.recovery.direction)
        assertEquals(Confidence.LOW, report.recovery.confidence)
    }
}

// ─── AchievementEngine tests (B7) ────────────────────────────────────────────

class AchievementEngineTest {

    private val today = 100L

    /** Builds a day-keyed map covering [days] consecutive days ending today. */
    private fun daysEndingToday(days: Int, value: Int): Map<Long, Int> =
        (0 until days).associate { (today - it) to value }

    private fun makeInput(
        sleepByDay: Map<Long, Int> = emptyMap(),
        stepsByDay: Map<Long, Int> = emptyMap(),
        todayRecovery: Float? = null,
        todayRestingHR: Int? = null,
        todaySleepScore: Float? = null,
        priorBestRecovery: Float? = null,
        priorLowestRestingHR: Int? = null,
        priorBestSleepScore: Float? = null,
        totalDays: Int = sleepByDay.size
    ) = AchievementEngine.AchievementInput(
        todayEpochDay = today,
        sleepByDay = sleepByDay,
        stepsByDay = stepsByDay,
        todayRecovery = todayRecovery,
        todayRestingHR = todayRestingHR,
        todaySleepScore = todaySleepScore,
        priorBestRecovery = priorBestRecovery,
        priorLowestRestingHR = priorLowestRestingHR,
        priorBestSleepScore = priorBestSleepScore,
        totalDaysOfData = totalDays
    )

    @Test
    fun `first sync earns FIRST_SYNC achievement`() {
        val earned = AchievementEngine.evaluateNewlyEarned(makeInput(totalDays = 1))
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.FIRST_SYNC })
    }

    @Test
    fun `7 night sleep streak earns SLEEP_STREAK_7`() {
        val earned = AchievementEngine.evaluateNewlyEarned(
            makeInput(sleepByDay = daysEndingToday(7, 490))   // all above the 480 need
        )
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.SLEEP_STREAK_7 })
    }

    /**
     * A day the watch spent on the charger must BREAK the streak. The old implementation
     * compacted the list with mapNotNull, which silently welded two separate streaks
     * together across a missing day.
     */
    @Test
    fun `a missing day breaks the sleep streak`() {
        val withGap = daysEndingToday(9, 490).toMutableMap()
        withGap.remove(today - 3)          // watch was charging that night
        val earned = AchievementEngine.evaluateNewlyEarned(makeInput(sleepByDay = withGap))
        assertFalse(
            "A missing day must break the streak, not be skipped over",
            earned.any { it.id == AchievementEngine.AchievementId.SLEEP_STREAK_7 }
        )
    }

    @Test
    fun `sleep streak honours a non-default sleep need`() {
        // 450 min beats a 7h need but misses the 8h default.
        val input = AchievementEngine.AchievementInput(
            todayEpochDay = today,
            personalSleepNeedMinutes = 420,
            sleepByDay = daysEndingToday(7, 450),
            priorBestRecovery = null, priorLowestRestingHR = null,
            priorBestSleepScore = null, totalDaysOfData = 7
        )
        assertTrue(
            AchievementEngine.evaluateNewlyEarned(input)
                .any { it.id == AchievementEngine.AchievementId.SLEEP_STREAK_7 }
        )
        val defaultNeed = makeInput(sleepByDay = daysEndingToday(7, 450))
        assertFalse(
            AchievementEngine.evaluateNewlyEarned(defaultNeed)
                .any { it.id == AchievementEngine.AchievementId.SLEEP_STREAK_7 }
        )
    }

    @Test
    fun `recovery score over 90 earns RECOVERY_90_PLUS`() {
        val earned = AchievementEngine.evaluateNewlyEarned(makeInput(todayRecovery = 91f))
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.RECOVERY_90_PLUS })
    }

    @Test
    fun `personal best recovery earns PERSONAL_BEST_RECOVERY`() {
        val earned = AchievementEngine.evaluateNewlyEarned(
            makeInput(todayRecovery = 85f, priorBestRecovery = 80f, totalDays = 5)
        )
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.PERSONAL_BEST_RECOVERY })
    }

    /**
     * Regression guard for the bug that made three achievements dead code: the prior
     * record was computed AFTER today's row was written, so `today > allTimeHigh` compared
     * a value against a maximum that already included it. Passing today's own score as the
     * prior record reproduces that state — it must NOT be earned.
     */
    @Test
    fun `personal best is not earned when today equals the prior record`() {
        val earned = AchievementEngine.evaluateNewlyEarned(
            makeInput(todayRecovery = 85f, priorBestRecovery = 85f)
        )
        assertFalse(earned.any { it.id == AchievementEngine.AchievementId.PERSONAL_BEST_RECOVERY })
    }

    @Test
    fun `lowest resting HR and best sleep score are reachable`() {
        val earned = AchievementEngine.evaluateNewlyEarned(
            makeInput(
                todayRestingHR = 48, priorLowestRestingHR = 52,
                todaySleepScore = 94f, priorBestSleepScore = 88f
            )
        )
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.LOWEST_RESTING_HR })
        assertTrue(earned.any { it.id == AchievementEngine.AchievementId.BEST_SLEEP_SCORE })
    }

    @Test
    fun `earned achievement has todayEpochDay set`() {
        val earned = AchievementEngine.evaluateNewlyEarned(makeInput(totalDays = 7))
        assertTrue(earned.isNotEmpty())
        earned.forEach { assertEquals(today, it.earnedEpochDay) }
    }
}
