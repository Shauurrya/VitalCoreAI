package com.example.vitalcoreai.ui.main

import com.example.vitalcoreai.analytics.BaselineUtils
import com.example.vitalcoreai.analytics.SleepScoreCalculator
import com.example.vitalcoreai.data.model.HRZone
import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.model.SleepData
import com.example.vitalcoreai.data.model.TrainingLoadData
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * VitalCore analytics smoke tests — replaces stale scaffold test.
 * These are pure-logic unit tests with zero Android dependencies.
 */
class AnalyticsSmokeTest {

    // ── BaselineUtils ──────────────────────────────────────────────────────────

    @Test
    fun baselineUtils_average_returnsCorrectMean() {
        val values = listOf(10.0, 20.0, 30.0)
        assertEquals(20.0, BaselineUtils.average(values), 0.001)
    }

    @Test
    fun baselineUtils_average_emptyList_returns0() {
        assertEquals(0.0, BaselineUtils.average(emptyList()), 0.001)
    }

    @Test
    fun baselineUtils_stdDev_returnsSampleStandardDeviation() {
        // Textbook set with mean 5.0 and summed squared deviations of 32.
        // BaselineUtils uses the SAMPLE standard deviation (n−1), which is the correct
        // estimator here: a user's recent nights are a sample of their habits, not the
        // whole population of them. Population SD (n) would be exactly 2.0; sample SD is
        // sqrt(32/7) ≈ 2.138. This test previously asserted the population value.
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        val std = BaselineUtils.stdDev(values)
        assertEquals(kotlin.math.sqrt(32.0 / 7.0), std, 0.001)
    }

    @Test
    fun baselineUtils_zScoreToScore_neutralWhenNoVariance() {
        val score = BaselineUtils.zScoreToScore(60.0, 60.0, 0.0, false)
        assertEquals(50f, score)
    }

    @Test
    fun baselineUtils_zScoreToScore_clampedAt100() {
        val score = BaselineUtils.zScoreToScore(40.0, 80.0, 5.0, true)
        assertEquals(100f, score)
    }

    @Test
    fun baselineUtils_zScoreToScore_clampedAt0() {
        val score = BaselineUtils.zScoreToScore(110.0, 60.0, 5.0, true)
        assertEquals(0f, score)
    }

    // ── SleepScoreCalculator ───────────────────────────────────────────────────

    private fun makeSleep(
        durationMinutes: Int,
        deepMinutes: Int = 90,
        remMinutes: Int = 100,
        efficiencyPercent: Double = 90.0
    ) = SleepData(
        dateEpochDay = 0L,
        durationMinutes = durationMinutes,
        efficiencyPercent = efficiencyPercent,
        bedtimeMinuteOfDay = 22 * 60,
        wakeTimeMinuteOfDay = 6 * 60,
        deepMinutes = deepMinutes,
        remMinutes = remMinutes,
        lightMinutes = durationMinutes - deepMinutes - remMinutes
    )

    @Test
    fun sleepScore_goodSleep_scoresAbove70() {
        val result = SleepScoreCalculator.calculate(
            todaySleep = makeSleep(480), // 8 hours
            last7Days = List(6) { makeSleep(480) },
            personalSleepNeedMinutes = 480
        )
        assertTrue("Good sleep should score >= 70, was ${result.score}", result.score >= 70f)
    }

    @Test
    fun sleepScore_poorSleep_scoresBelow50() {
        val result = SleepScoreCalculator.calculate(
            todaySleep = makeSleep(240, deepMinutes = 10, remMinutes = 15, efficiencyPercent = 50.0),
            last7Days = List(6) { makeSleep(240, deepMinutes = 10, remMinutes = 15, efficiencyPercent = 50.0) },
            personalSleepNeedMinutes = 480
        )
        assertTrue("Poor sleep should score < 50, was ${result.score}", result.score < 50f)
    }

    @Test
    fun sleepScore_hasBreakdown() {
        val result = SleepScoreCalculator.calculate(
            todaySleep = makeSleep(480),
            last7Days = List(6) { makeSleep(480) }
        )
        assertTrue("Breakdown should not be empty", result.breakdown.isNotEmpty())
    }

    @Test
    fun sleepScore_scoreInValidRange() {
        val result = SleepScoreCalculator.calculate(
            todaySleep = makeSleep(360),
            last7Days = emptyList()
        )
        assertTrue("Score must be 0–100", result.score in 0f..100f)
    }
}
