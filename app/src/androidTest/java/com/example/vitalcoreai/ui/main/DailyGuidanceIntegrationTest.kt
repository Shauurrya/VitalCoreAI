package com.example.vitalcoreai.ui.main

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vitalcoreai.MainActivity
import com.example.vitalcoreai.coach.PlanStatus
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.DailyPlanStore
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Runs the real Hilt view models and navigation on an isolated QA installation with no
 * Health Connect grants. Keep the saved plan so a second instrumentation process can
 * verify it after force-stop with verifyGuidanceRestartOnly=true. No database is cleared.
 */
class DailyGuidanceIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedHomePlan_opensCoachAndLiveDataSources_andSurvivesRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val restartOnly = InstrumentationRegistry.getArguments()
            .getString("verifyGuidanceRestartOnly") == "true"
        val day = VitalTime.todayEpochDay()
        val previousOnboarding = UserPrefs.onboardingComplete(context)
        val previousLock = UserPrefs.biometricLockEnabled(context)
        val healthConnect = HealthConnectManager(context)
        val available = healthConnect.isAvailable()
        assertTrue("Use the isolated QA installation without Health Connect grants",
            runBlocking { healthConnect.grantedPermissions() }.isEmpty())

        UserPrefs.setOnboardingComplete(context, true)
        UserPrefs.setBiometricLockEnabled(context, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                scrollToHomePlan()
                if (!restartOnly) {
                    compose.onNodeWithText("Adjust activity").performScrollTo().performClick()
                    awaitText("Choose today's activity")
                    // The dialog can include the same title as an already-saved Home choice.
                    compose.onNode(hasText("Full Rest") and hasAnyAncestor(isDialog()))
                        .performScrollTo().performClick()
                    awaitText("Complete activity")
                    compose.onNodeWithText("Complete activity").performScrollTo().performClick()
                    awaitText("Undo activity completion")

                    compose.onNodeWithText("Adjust sleep target").performScrollTo().performClick()
                    awaitText("Tonight's sleep target")
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                        .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                            setProgress(435f)
                        }
                    compose.onNodeWithText("Save target").performClick()
                }

                // These assertions run before any navigation or saving in restart-only mode.
                compose.waitUntil(20_000) {
                    val saved = DailyPlanStore(context).read(day)
                    saved.activity?.title == "Full Rest" &&
                        saved.activity?.status == PlanStatus.COMPLETED &&
                        saved.sleepMinutes == 435 && saved.sleepStatus == PlanStatus.SAVED
                }
                compose.onNodeWithText("Full Rest").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Completed · Low intensity").assertExists()
                compose.onNodeWithText("Undo activity completion").assertExists()
                compose.onNodeWithText("Tonight · ${VitalTime.formatDurationMinutes(435)} sleep target")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Saved · based on your chosen sleep need").assertExists()
                // Wait for outstanding apply() writes before the runner force-stops this process.
                assertTrue(context.getSharedPreferences("daily_action_plans", Context.MODE_PRIVATE)
                    .edit().commit())

                compose.onNodeWithText("Ask Coach").performScrollTo().performClick()
                awaitText("Choose a question. Answers use your saved readings and work offline.")
                compose.onNode(hasText("What should I train today?") and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performScrollTo().performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("Reading your saved history…")
                        .fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().size == 1
                }
                compose.onNode(hasScrollToIndexAction())
                    .performScrollToNode(hasText("Data Sources and freshness"))
                compose.onNodeWithText("Data Sources and freshness").performScrollTo().performClick()

                awaitText("Data Sources")
                awaitText("Recheck access")
                compose.onNodeWithText("Recheck access").performScrollTo().performClick()
                awaitText("Recheck access")
                if (available) {
                    compose.onNodeWithText("0 of 16 metric permissions allowed").assertExists()
                } else {
                    compose.onAllNodesWithText("Health Connect unavailable").onFirst().assertExists()
                }
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                capture("daily-guidance-live-data-sources")
                compose.onNode(hasScrollToIndexAction()).performScrollToKey("SleepSessionRecord")
                compose.onNodeWithText("Sleep").assertIsDisplayed()
                compose.onAllNodesWithText(if (available) "Permission not granted" else "Health Connect unavailable")
                    .onFirst().assertExists()
                compose.onAllNodesWithText("Read successfully", substring = true).assertCountEquals(0)
                compose.onAllNodesWithText(if (available)
                    "Open Health Connect and allow this metric for VitalCore."
                    else "Install or update Health Connect, then return here.").onFirst().assertExists()
                capture("daily-guidance-live-sleep-source")
            }
        } finally {
            UserPrefs.setOnboardingComplete(context, previousOnboarding)
            UserPrefs.setBiometricLockEnabled(context, previousLock)
        }
    }

    private fun scrollToHomePlan() {
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNode(hasScrollToIndexAction()).performScrollToKey("daily-plan")
                compose.onNodeWithText("YOUR DAILY PLAN").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-screenshots")
            .apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
