package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.repository.HealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * B13 — ViewModel unit tests (no Android framework dependency).
 *
 * Tests use a TestCoroutineScheduler / UnconfinedTestDispatcher so state flows
 * resolve synchronously. Hilt is NOT used; each ViewModel is constructed
 * manually with a mock repository so these run as plain JVM tests.
 *
 * Coverage:
 *  - RecoveryViewModel: emits loading → loaded state
 *  - InsightsViewModel: passes through coach insights + empty simulator when no sleep data
 *  - HistoryViewModel: populates scores list
 *  - WeeklyReportViewModel: propagates null state before report exists
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTests {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildScores(recovery: Float = 72f, readiness: Float = 68f) =
        ComputedScoresEntity(
            dateEpochDay           = 19900L,
            recoveryScore          = recovery,
            recoveryConfidence     = "HIGH",
            recoveryExplanation    = "Good rest",
            readinessScore         = readiness,
            sleepScore             = 74f,
            stressScore            = 30f,
            activityScore          = 65f,
            consistencyScore       = 70f,
            lifestyleScore         = null,
            trainingLoadNormalized = 40f,
            acwr                   = 0.9f,
            acwrZone               = "OPTIMAL",
            vo2MaxEstimate         = 42f,
            biologicalAge          = 28,
            weeklyHealthScore      = null,
            monthlyHealthScore     = null,
            recoveryMomentum       = "IMPROVING",
            sleepMomentum          = "STABLE",
            trainingMomentum       = "DECLINING",
            dataQualityLevel       = "HIGH",
            dataQualityPercent     = 92
        )

    /** Minimal Context mock so UserPrefs.age()/maxHR() resolve to sane defaults in JVM tests. */
    private fun mockContext(age: Int = 30): Context {
        val prefs: SharedPreferences = mock {
            on { getInt(any(), any()) } doReturn age
        }
        return mock {
            on { getSharedPreferences(any(), any()) } doReturn prefs
        }
    }

    private fun buildMetrics(sleep: Int = 450) =
        DailyMetricsEntity(
            dateEpochDay           = 19900L,
            restingHR              = 58,
            steps                  = 8000,
            distanceMeters         = 6000f,
            caloriesBurned         = 450,
            weightKg               = null,
            bodyFatPercent         = null,
            spO2Percent            = null,
            sleepDurationMinutes   = sleep,
            sleepEfficiencyPercent = 85.0,
            sleepDeepMinutes       = 80,
            sleepRemMinutes        = 110,
            sleepLightMinutes      = 260,
            sleepAwakeMinutes      = 0,
            bedtimeMinuteOfDay     = 1380,
            wakeTimeMinuteOfDay    = 420
        )

    // ── RecoveryViewModel ─────────────────────────────────────────────────────

    @Test
    fun `RecoveryViewModel starts loading then emits score from repository`() = runTest {
        val scores = listOf(buildScores(recovery = 77f))
        val repo: HealthRepository = mock {
            on { scoresFrom(any()) } doReturn flowOf(scores)
        }

        val vm = RecoveryViewModel(repo)

        // After collecting, loading should be false and score should be set
        val state = vm.state.value
        assertFalse("Should not be loading", state.isLoading)
        assertEquals(77f, state.score)
    }

    @Test
    fun `RecoveryViewModel chart has at most 14 values`() = runTest {
        // 20 scores — chart should only show last 14
        val scores = (1..20).map { buildScores(recovery = it.toFloat()) }
        val repo: HealthRepository = mock {
            on { scoresFrom(any()) } doReturn flowOf(scores)
        }

        val vm = RecoveryViewModel(repo)
        assertTrue("Chart should cap at 14", vm.state.value.chartValues.size <= 14)
    }

    // ── HistoryViewModel ──────────────────────────────────────────────────────

    @Test
    fun `HistoryViewModel exposes all scores from repository`() = runTest {
        val scoreList = listOf(buildScores(72f), buildScores(68f), buildScores(80f))
        val repo: HealthRepository = mock {
            on { scoresFrom(any()) } doReturn flowOf(scoreList)
        }

        val vm = HistoryViewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertEquals(3, vm.state.value.scores.size)
    }

    @Test
    fun `HistoryViewModel handles empty history gracefully`() = runTest {
        val repo: HealthRepository = mock {
            on { scoresFrom(any()) } doReturn flowOf(emptyList())
        }

        val vm = HistoryViewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.scores.isEmpty())
    }

    // ── WeeklyReportViewModel ─────────────────────────────────────────────────

    @Test
    fun `WeeklyReportViewModel emits null when no report exists`() = runTest {
        val repo: HealthRepository = mock {
            on { latestWeeklyReport() } doReturn flowOf(null)
        }

        val vm = WeeklyReportViewModel(repo)

        // Should be null (no report yet)
        assertNull(vm.state.value)
    }

    // ── InsightsViewModel ─────────────────────────────────────────────────────

    @Test
    fun `InsightsViewModel emits empty simulator suggestions when no sleep data`() = runTest {
        val scores = buildScores()
        val metricsNoSleep = buildMetrics(sleep = 0).copy(sleepDurationMinutes = null)

        val repo: HealthRepository = mock {
            on { latestScores() } doReturn flowOf(scores)
            on { metricsFrom(any()) } doReturn flowOf(listOf(metricsNoSleep))
            on { earnedAchievements() } doReturn flowOf(emptyList())
        }

        val vm = InsightsViewModel(repo, mockContext())
        val state = vm.state.value

        assertFalse(state.isLoading)
        // Simulator requires sleepDurationMinutes — should be empty with missing data
        assertTrue(
            "Simulator suggestions should be empty with no sleep data",
            state.simulatorSuggestions.isEmpty()
        )
    }

    @Test
    fun `InsightsViewModel surfaces coach insights when scores available`() = runTest {
        val scores = buildScores(recovery = 30f) // low recovery → coach should fire insights
        val metrics = buildMetrics()

        val repo: HealthRepository = mock {
            on { latestScores() } doReturn flowOf(scores)
            on { metricsFrom(any()) } doReturn flowOf(listOf(metrics))
            on { earnedAchievements() } doReturn flowOf(emptyList())
        }

        val vm = InsightsViewModel(repo, mockContext())
        val state = vm.state.value

        assertFalse(state.isLoading)
        // Low recovery score (30) should produce at least one coach insight
        assertTrue(
            "Coach should produce insights on low recovery",
            state.insights.isNotEmpty()
        )
    }
}
