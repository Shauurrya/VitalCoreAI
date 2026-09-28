package com.example.vitalcoreai.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.analytics.ScoreFactor
import com.example.vitalcoreai.analytics.TrendDirection
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.StressAccent
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.viewmodel.ScoreDetailUiState
import com.example.vitalcoreai.ui.viewmodel.TrainingUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** State-only detail checks: never sync Health Connect or open the user's database. */
class DetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var compact by mutableStateOf(false)

    @Test fun recovery_retainsScoreBreakdownAndBack_atBothTextSizes() {
        var backCount = 0
        render {
            ScoreDetailScreen("Recovery", recoveryFixture(), RecoveryAccent) { backCount++ }
        }

        for (isCompact in listOf(false, true)) {
            resize(isCompact)
            compose.onNodeWithContentDescription("Recovery 79 percent, Optimal").assertIsDisplayed()
            capture("recovery-${sizeName(isCompact)}")
            scrollTo("Score history")
            compose.onNodeWithText("Latest 7 recorded scores · out of 100").assertIsDisplayed()
            waitForChart(RecoveryAccent)
            capture("recovery-${sizeName(isCompact)}-history")
            scrollTo("Behind your score")
            compose.onNodeWithText("Sleep quality").assertIsDisplayed()
            capture("recovery-${sizeName(isCompact)}-factors")
        }

        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(1, backCount)
    }

    @Test fun stress_retainsLowStressMeaningAndTrend_atBothTextSizes() {
        var backCount = 0
        render {
            ScoreDetailScreen("Stress", stressFixture(), StressAccent) { backCount++ }
        }

        for (isCompact in listOf(false, true)) {
            resize(isCompact)
            compose.onNodeWithContentDescription("Stress 28 percent, Low").assertIsDisplayed()
            compose.onNodeWithText("Trending down").assertIsDisplayed()
            capture("stress-${sizeName(isCompact)}")
            scrollTo("What this means")
            compose.onNodeWithText(stressFixture().explanation).assertIsDisplayed()
            scrollTo("Score history")
            waitForChart(StressAccent)
            capture("stress-${sizeName(isCompact)}-history")
            scrollTo("Behind your score")
            compose.onNodeWithText("Resting heart rate").assertIsDisplayed()
            capture("stress-${sizeName(isCompact)}-factors")
        }

        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(1, backCount)
    }

    @Test fun strain_keepsNativeScaleWorkloadAndHistory_atBothTextSizes() {
        var backCount = 0
        render { TrainingDetailContent(trainingFixture()) { backCount++ } }

        for (isCompact in listOf(false, true)) {
            resize(isCompact)
            compose.onNodeWithContentDescription("STRAIN 12.4 out of 21, Strenuous").assertIsDisplayed()
            capture("strain-${sizeName(isCompact)}")
            scrollTo("WORKLOAD BALANCE")
            compose.onNodeWithText("Optimal zone").assertIsDisplayed()
            compose.onNodeWithText("1.12").assertIsDisplayed()
            capture("strain-${sizeName(isCompact)}-workload")
            scrollTo("Strain history")
            compose.onNodeWithText("Latest 7 recorded days · out of 21").assertIsDisplayed()
            waitForChart(StrainAccent)
            capture("strain-${sizeName(isCompact)}-history")
            scrollTo("About workload balance")
            compose.onNodeWithText("About workload balance").assertIsDisplayed()
        }

        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(1, backCount)
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            val testDensity = if (compact) Density(density.density * screenWidth / 320f, 1.3f) else density
            CompositionLocalProvider(LocalDensity provides testDensity) {
                VitalCoreTheme { content() }
            }
        }
    }

    private fun resize(isCompact: Boolean) {
        compose.runOnIdle { compact = isCompact }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun sizeName(isCompact: Boolean) = if (isCompact) "compact" else "normal"

    private fun waitForChart(accent: Color) {
        val chart = compose.onNode(hasContentDescription("Trend,", substring = true))
        // A visible history heading doesn't guarantee that its plot is in the viewport.
        // Pixel captures are clipped, so bring the chart itself fully into view first.
        chart.performScrollTo().assertIsDisplayed()
        // Vico builds its model on Dispatchers.Default, outside Compose's test clock.
        // Poll the chart itself so late model/animation work cannot produce a blank capture.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeByFrame()
            val pixels = chart.captureToImage().toPixelMap()
            var linePixels = 0
            for (y in 0 until pixels.height step 3) {
                for (x in 0 until pixels.width step 3) {
                    val pixel = pixels[x, y]
                    if (abs(pixel.red - accent.red) < 0.04f &&
                        abs(pixel.green - accent.green) < 0.04f &&
                        abs(pixel.blue - accent.blue) < 0.04f
                    ) {
                        linePixels++
                    }
                }
            }
            linePixels >= 12
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1600)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private fun recoveryFixture() = ScoreDetailUiState(
    score = 79f,
    confidence = Confidence.HIGH,
    explanation = "Your sleep and resting heart rate support a balanced training day. Keep some energy in reserve for tomorrow.",
    breakdown = listOf(
        ScoreFactor("Sleep quality", 40f, "7h 42m", 88f,
            "Restful sleep supports today's recovery.", "42 minutes above your recent average")
    ),
    trendDirection = TrendDirection.UP,
    chartValues = listOf(62f, 68f, 65f, 73f, 70f, 76f, 79f),
    isLoading = false
)

private fun stressFixture() = ScoreDetailUiState(
    score = 28f,
    confidence = Confidence.HIGH,
    explanation = "Low stress today. Your resting heart rate is close to your baseline and your sleep was restorative.",
    breakdown = listOf(
        ScoreFactor("Resting heart rate", 35f, "54 bpm", 22f,
            "Close to your usual resting range.", "2 bpm below your recent average")
    ),
    trendDirection = TrendDirection.DOWN,
    chartValues = listOf(48f, 44f, 46f, 38f, 35f, 31f, 28f),
    isLoading = false
)

private fun trainingFixture() = TrainingUiState(
    strain = 12.4f,
    confidence = Confidence.HIGH,
    exertionMinutes = 46f,
    explanation = "A sustained session built today's strain. Make time for a comfortable cooldown and recovery.",
    strainHistory = listOf(7.2f, 9.8f, 8.5f, 14.2f, 6.1f, 10.8f, 12.4f),
    acwr = 1.12f,
    acwrZone = "OPTIMAL",
    acwrIsMeaningful = true,
    acwrDaysOfHistory = 30,
    isLoading = false
)
