package com.example.vitalcoreai.ui.main

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.screens.HomeDashboard
import com.example.vitalcoreai.ui.viewmodel.HomeUiState
import com.example.vitalcoreai.ui.viewmodel.RecommendationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the dashboard without touching Health Connect or the user's database. */
class MainScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun populatedDashboard_opensMetricDetails_andRenders() {
        var route = ""
        show(dashboardFixture()) { route = it }
        compose.onNodeWithText("Overview").assertIsSelected()
        capture("dashboard-populated")
        compose.onNodeWithContentDescription("RECOVERY 79 percent, Optimal. Open details.").assertExists().performClick()
        assertEquals(Routes.RECOVERY, route)
        compose.onNodeWithContentDescription("SLEEP 88 percent, Performance. Open details.").performClick()
        assertEquals(Routes.SLEEP, route)
        compose.onNodeWithContentDescription("STRAIN 12.4 out of 21, Strenuous. Open details.").performClick()
        assertEquals(Routes.TRAINING, route)
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("dashboard-title")
        compose.onNodeWithText("My dashboard").assertIsDisplayed()
        capture("dashboard-metrics")
    }

    @Test fun emptyDashboard_showsMissingData_andRefreshWorks() {
        var refreshCount = 0
        compose.setContent {
            VitalCoreTheme { HomeDashboard(HomeUiState(isLoading = false), emptyList(), { refreshCount++ }, {}) }
        }
        compose.onNodeWithContentDescription("RECOVERY, no data recorded yet. Open details.").assertExists()
        capture("dashboard-empty")
        compose.onNodeWithContentDescription("Sync health data").performClick()
        assertEquals(1, refreshCount)
    }

    @Test fun explore_keepsSecondaryScreensReachable() {
        var route = ""
        show(dashboardFixture()) { route = it }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Explore VitalCore").assertIsDisplayed()
        capture("dashboard-explore")
        compose.onNodeWithText("Readiness", useUnmergedTree = true).performClick()
        assertEquals(Routes.READINESS, route)
    }

    @Test fun compactDashboard_largeText_keepsNavigationAccessible() {
        var route = ""
        compose.setContent {
            val density = LocalDensity.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalDensity provides Density(density.density * screenWidth / 320f, 1.3f)) {
                VitalCoreTheme {
                    Box(Modifier.fillMaxSize()) {
                        HomeDashboard(dashboardFixture(), emptyList(), {}, { route = it })
                    }
                }
            }
        }
        compose.onNodeWithText("More").assertIsDisplayed()
        capture("dashboard-compact")
        compose.onNodeWithContentDescription("RECOVERY 79 percent, Optimal. Open details.").assertIsDisplayed().performClick()
        assertEquals(Routes.RECOVERY, route)
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("dashboard-title")
        compose.onNodeWithText("My dashboard").assertIsDisplayed()
        capture("dashboard-compact-metrics")
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("metrics")
        compose.onNodeWithText("Sleep duration", useUnmergedTree = true).assertIsDisplayed()
        capture("dashboard-compact-vitals")
    }

    @Test fun syncFeedback_disablesDuplicateRefresh_andAllowsRetry() {
        var refreshing by androidx.compose.runtime.mutableStateOf(true)
        var refreshCount = 0
        compose.setContent {
            VitalCoreTheme {
                HomeDashboard(
                    dashboardFixture().copy(syncError = if (refreshing) null else "Couldn't update health data."),
                    emptyList(), { refreshCount++ }, {}, isSyncing = refreshing
                )
            }
        }
        compose.onNodeWithContentDescription("Syncing health data").assertIsNotEnabled()
        compose.runOnIdle { refreshing = false }
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, refreshCount)
    }

    private fun show(state: HomeUiState, navigate: (String) -> Unit) {
        compose.setContent { VitalCoreTheme { HomeDashboard(state, emptyList(), {}, navigate) } }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        // Capture the whole window, including modal sheets rendered in a separate root.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Window rendering and native ripples settle after Compose's test clock is idle.
        android.os.SystemClock.sleep(350)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private fun dashboardFixture(): HomeUiState {
    val day = VitalTime.today().toEpochDay()
    return HomeUiState(
        latestScores = ComputedScoresEntity(
            dateEpochDay = day, recoveryScore = 79f, recoveryConfidence = "HIGH", recoveryExplanation = null,
            readinessScore = 82f, sleepScore = 88f, sleepConfidence = "HIGH", stressScore = 28f,
            activityScore = 65f, consistencyScore = 80f, lifestyleScore = 75f, trainingLoadNormalized = null,
            acwr = null, acwrZone = null, vo2MaxEstimate = null, biologicalAge = 26,
            weeklyHealthScore = null, monthlyHealthScore = null, strain = 12.4f, strainConfidence = "HIGH", energyBankScore = 76f
        ),
        latestMetrics = DailyMetricsEntity(
            dateEpochDay = day, restingHR = 54, steps = 8432, distanceMeters = null, caloriesBurned = null,
            weightKg = null, bodyFatPercent = null, spO2Percent = null, sleepDurationMinutes = 462,
            sleepEfficiencyPercent = null, sleepDeepMinutes = null, sleepRemMinutes = null, sleepLightMinutes = null,
            sleepAwakeMinutes = null, bedtimeMinuteOfDay = 1380, wakeTimeMinuteOfDay = 402, activeCalories = 486
        ),
        userName = "Alex",
        recommendation = RecommendationState("Find your steady pace.", "Moderate", null,
            "Your recovery supports a balanced session today. Keep your effort comfortable and leave room to recharge."),
        isLoading = false
    )
}
