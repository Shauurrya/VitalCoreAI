package com.example.vitalcoreai.coach

import android.content.Context
import android.content.SharedPreferences
import com.example.vitalcoreai.analytics.ScoreFactor
import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.analytics.encodeToString
import com.example.vitalcoreai.data.db.dao.CheckInDao
import com.example.vitalcoreai.data.db.dao.DailyMetricsDao
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.repository.HealthRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class CoachContextFactoryTest {
    private val today = 20000L
    private fun scores(day: Long = today, readiness: Float? = 42f) = ComputedScoresEntity(
        dateEpochDay = day, recoveryScore = 80f, recoveryConfidence = "HIGH", recoveryExplanation = null,
        readinessScore = readiness, readinessConfidence = "MEDIUM", readinessExplanation = "Saved explanation",
        sleepScore = 40f, stressScore = null, activityScore = null, consistencyScore = null,
        lifestyleScore = null, trainingLoadNormalized = null, acwr = null, acwrZone = null,
        vo2MaxEstimate = null, biologicalAge = null, weeklyHealthScore = null, monthlyHealthScore = null,
        readinessBreakdown = listOf(ScoreFactor("Sleep", 50f, "5h", 35f, "Recorded sleep")).encodeToString(),
        recommendationType = "FULL_REST", recommendationIntensity = "LOW", recommendationConfidence = "MEDIUM",
        recommendationRationale = ScorePipeline.encodeTextList(listOf("Saved rationale")),
        forecastLow = 50, forecastHigh = 60, forecastConfidence = "MEDIUM", dataQualityLevel = "MEDIUM"
    )

    private fun metrics(stale: String? = null) = DailyMetricsEntity(
        dateEpochDay = today, restingHR = 65, steps = null, distanceMeters = null, caloriesBurned = null,
        weightKg = null, bodyFatPercent = null, spO2Percent = null, sleepDurationMinutes = 300,
        sleepEfficiencyPercent = null, sleepDeepMinutes = null, sleepRemMinutes = null, sleepLightMinutes = null,
        sleepAwakeMinutes = null, bedtimeMinuteOfDay = 1380, wakeTimeMinuteOfDay = 240,
        staleRecordTypes = stale, newestRecordTimestampMs = 1728000000000L
    )

    private suspend fun build(rows: List<ComputedScoresEntity>, cached: DailyMetricsEntity? = metrics()): CoachContext {
        val prefs: SharedPreferences = mock {
            on { getInt(any(), any()) } doAnswer { it.getArgument<Int>(1) }
        }
        val androidContext: Context = mock { on { getSharedPreferences(any(), any()) } doReturn prefs }
        val repo: HealthRepository = mock()
        whenever(repo.scoresFrom(any())).thenReturn(flowOf(rows))
        whenever(repo.runPipeline(any(), any(), any(), any(), any())).thenReturn(
            ScorePipeline.Output(readinessScore = 99f, sleepScore = 99f, forecastLow = 90, forecastHigh = 99)
        )
        whenever(repo.daysOfHistory()).thenReturn(30)
        whenever(repo.getJournalForDay(any())).thenReturn(emptyList())
        whenever(repo.getAllTrackedHabitIds()).thenReturn(emptyList())
        val metricDao: DailyMetricsDao = mock()
        whenever(metricDao.getForDay(today)).thenReturn(cached)
        whenever(metricDao.getRange(any(), any())).thenReturn(listOfNotNull(cached))
        val checkIns: CheckInDao = mock()
        return CoachContextFactory(repo, metricDao, checkIns, androidContext).build(today)
    }

    @Test fun `coach values and reasons use persisted scores rather than ephemeral recomputation`() = runTest {
        val context = build(listOf(scores()))
        assertEquals(42f, context.readiness?.value)
        assertEquals("Saved explanation", context.readiness?.explanation)
        assertEquals(35f, context.readiness?.breakdown?.single()?.subScore)
        assertEquals(40f, context.sleep?.score)
        assertEquals(50, context.forecast?.low)
        assertEquals(60, context.forecast?.high)
        assertEquals(listOf("Saved rationale"), context.recommendation?.rationale)
        assertEquals("MEDIUM", context.recommendation?.confidence)
    }

    @Test fun `week averages come from the persisted readiness scores and their actual dates`() = runTest {
        val rows = (0..6).map { scores(today - it, 70f) } + scores(today + 1, 1f)
        val context = build(rows)
        val week = context.trends.first { it.window == "7-day" }
        assertEquals(70f, week.averageRecent)
        assertEquals(70f, week.averageEarlier)
        assertEquals(7, context.evidence?.scoredDaysThisWeek)
        assertEquals(today, context.evidence?.dateEpochDay)
    }

    @Test fun `older scores and recovery-only days cannot fill this weeks readiness gaps`() = runTest {
        val rows = (0..3).map { scores(today - it, 70f) } + scores(today - 4, null) +
            (7..12).map { scores(today - it, 70f) }
        val context = build(rows)
        assertEquals(4, context.evidence?.scoredDaysThisWeek)
        assertFalse(CoachAnswerEngine.answer(CoachIntent.WEEKLY_REVIEW, context).hasSufficientData)
    }

    @Test fun `failed sleep read keeps evidence metadata but cannot cite cached sleep`() = runTest {
        val context = build(listOf(scores()), metrics("SleepSessionRecord"))
        assertNull(context.sleep)
        assertEquals(listOf("Sleep Session"), context.evidence?.staleMetrics)
        assertEquals(1728000000000L, context.evidence?.latestMeasurementMs)
        assertFalse(CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_TIRED, context).citations.any { it.startsWith("sleep") })
    }

    @Test fun `yesterdays score is not presented as todays readiness`() = runTest {
        val context = build(listOf(scores(today - 1)), null)
        assertNull(context.readiness)
        assertNull(context.recommendation)
        assertNull(context.forecast)
        assertFalse(CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_LOW, context).hasSufficientData)
    }
}
