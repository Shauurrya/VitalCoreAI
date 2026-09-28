package com.example.vitalcoreai.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vitalcoreai.coach.CoachContext
import com.example.vitalcoreai.coach.CoachIntent
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.AskCoachUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AskCoachScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allQuestionsAndDataSources_workWithSparseData_atCompactLargeText() {
        exerciseQuestions(compact = true)
    }

    @Test fun allQuestionsAndDataSources_workWithSparseData_atNormalSize() {
        exerciseQuestions(compact = false)
    }

    private fun exerciseQuestions(compact: Boolean) {
        var state by mutableStateOf(AskCoachUiState(context = emptyContext(), isLoading = false))
        var route = ""
        var backCount = 0
        compose.setContent {
            val density = LocalDensity.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalDensity provides if (compact)
                Density(density.density * screenWidth / 320f, 1.3f) else density) {
                VitalCoreTheme {
                    AskCoachContent(state, { backCount++ }, { route = it }, { state = state.copy(selected = it) }, {})
                }
            }
        }
        val size = if (compact) "compact" else "normal"
        capture("ask-coach-$size-questions")
        CoachIntent.entries.filter { it != CoachIntent.FREEFORM }.forEach { intent ->
            // The choices share one lazy item; return to it before seeking a chip.
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onNodeWithText(intent.displayQuestion).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(intent, state.selected) }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
            compose.onNodeWithText("Not enough data yet").assertIsDisplayed()
            compose.onNodeWithText("Low confidence · More evidence needed").assertIsDisplayed()
            if (intent == CoachIntent.WHY_AM_I_LOW) capture("ask-coach-$size-sparse")
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("Data Sources and freshness").performScrollTo().performClick()
        assertEquals(Routes.DATA_SOURCES, route)
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backCount)
    }

    @Test fun staleReadings_showProvisionalAnswerAndSupportingLink_atCompactLargeText() {
        val context = emptyContext().copy(
            readiness = CoachContext.ScoreBlock(42f, "Low", "HIGH", "Saved score explanation.", emptyList(), null),
            evidence = CoachContext.EvidenceBlock(20000, 1728000000000L, listOf("Sleep Session"), 1)
        )
        var route = ""
        compose.setContent {
            val density = LocalDensity.current
            val width = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalDensity provides Density(density.density * width / 320f, 1.3f)) {
                VitalCoreTheme {
                    AskCoachContent(AskCoachUiState(context = context, isLoading = false), {}, { route = it }, {}, {})
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("Readiness 42/100").assertIsDisplayed()
        compose.onNodeWithText("Low confidence").assertIsDisplayed()
        compose.onNodeWithText("Some readings could not be refreshed:", substring = true).assertExists()
        capture("ask-coach-compact-stale")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("Readiness and score factors").performScrollTo().performClick()
        assertEquals(Routes.READINESS, route)
        compose.onNodeWithText("Latest cached measurement:", substring = true).assertExists()
        capture("ask-coach-compact-evidence")
    }

    @Test fun adequateHistory_showsSavedTrainingGuidanceAndEvidence_atCompactLargeText() {
        val context = emptyContext().copy(
            today = emptyContext().today.copy(daysOfHistory = 30, isCalibrating = false),
            readiness = CoachContext.ScoreBlock(66f, "Good", "HIGH", "Saved readiness explanation.", emptyList(), null),
            recommendation = CoachContext.RecommendationBlock(
                type = "Easy walk", intensity = "Low", volumeAdjustmentPercent = -20,
                readyRegions = emptyList(), avoidRegions = emptyList(),
                rationale = listOf("Recorded sleep was shorter than usual"),
                recoveryActions = listOf("Allow time for rest."), confidence = "MEDIUM"
            ),
            confidence = CoachContext.ConfidenceBlock("HIGH", 90, listOf("Current readings available"), emptyList(), emptyMap()),
            evidence = CoachContext.EvidenceBlock(20000, 1728000000000L, emptyList(), 7)
        )
        var state by mutableStateOf(AskCoachUiState(context = context, isLoading = false))
        var route = ""
        compose.setContent {
            val density = LocalDensity.current
            val width = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalDensity provides Density(density.density * width / 320f, 1.3f)) {
                VitalCoreTheme {
                    AskCoachContent(state, {}, { route = it }, { state = state.copy(selected = it) }, {})
                }
            }
        }
        compose.onNodeWithText(CoachIntent.WHAT_SHOULD_I_TRAIN.displayQuestion).performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithText("Easy walk").assertIsDisplayed()
        compose.onNodeWithText("Medium confidence").assertIsDisplayed()
        compose.onNodeWithText("recorded sleep was shorter than usual", substring = true).assertExists()
        compose.onNodeWithText("Trim volume by about 20%", substring = true).assertExists()
        capture("ask-coach-compact-training")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("Latest cached measurement:", substring = true).assertExists()
        compose.onNodeWithText("Today's action plan").performScrollTo().performClick()
        assertEquals(Routes.HOME, route)
        capture("ask-coach-compact-training-evidence")
    }

    private fun emptyContext() = CoachContext(
        today = CoachContext.TodayBlock(20000, "FRIDAY", null, 0, true),
        readiness = null, sleep = null, restingHR = null, trainingLoad = null,
        muscleRecovery = null, energy = null, journal = null, trends = emptyList(),
        anomalies = emptyList(), forecast = null, recommendation = null, insights = emptyList(),
        confidence = CoachContext.ConfidenceBlock("LOW", 0, emptyList(), listOf("No current readings"), emptyMap()),
        evidence = CoachContext.EvidenceBlock(20000, null)
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
