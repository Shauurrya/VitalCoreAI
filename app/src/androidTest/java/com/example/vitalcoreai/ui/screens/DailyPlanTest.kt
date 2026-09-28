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
import com.example.vitalcoreai.coach.DailyPlanBuilder
import com.example.vitalcoreai.coach.PlanStatus
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.DailyPlanStore
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.HomeUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DailyPlanTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dailyPlan_canSaveCompleteAdjustAndOpenCoach_atCompactSize() {
        val today = VitalTime.todayEpochDay()
        var plan by mutableStateOf(DailyPlanBuilder.build(today, null, false, 450))
        var route = ""
        compose.setContent {
            val density = LocalDensity.current
            val screenWidth = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalDensity provides Density(density.density * screenWidth / 320f, 1.3f)) {
                VitalCoreTheme {
                    HomeDashboard(HomeUiState(isLoading = false, dailyPlan = plan, checkInCompleted = true), emptyList(), {}, { route = it },
                        onPlanStatus = { plan = plan.copy(activity = plan.activity.copy(status = it)) },
                        onPlanChoice = { plan = plan.copy(activity = it.copy(status = PlanStatus.SAVED)) },
                        onSleepPlan = { minutes, status -> plan = plan.copy(sleepMinutes = minutes, sleepStatus = status) })
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("daily-plan")
        capture("daily-plan-compact-start")
        compose.onNodeWithText("Why this today?").performScrollTo().performClick()
        compose.onNodeWithText("No score evidence yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review data sources").performScrollTo().performClick()
        assertEquals(Routes.DATA_SOURCES, route)
        compose.onNodeWithText("Save activity").performScrollTo().performClick()
        assertEquals(PlanStatus.SAVED, plan.activity.status)
        compose.onNodeWithText("Dismiss activity").performScrollTo().performClick()
        assertEquals(PlanStatus.DISMISSED, plan.activity.status)
        compose.onNodeWithText("Restore activity").performScrollTo().performClick()
        assertEquals(PlanStatus.SAVED, plan.activity.status)
        compose.onNodeWithText("Complete activity").performScrollTo().performClick()
        assertEquals(PlanStatus.COMPLETED, plan.activity.status)
        compose.onNodeWithText("Adjust activity").performScrollTo().performClick()
        capture("daily-plan-compact-choices")
        compose.onNodeWithText("Full Rest").performClick()
        assertEquals("Full Rest", plan.activity.title)
        assertEquals(PlanStatus.SAVED, plan.activity.status)
        compose.onNodeWithText("View/edit check-in").performScrollTo().performClick()
        assertEquals(Routes.CHECK_IN, route)
        compose.onNodeWithText("Ask Coach").performScrollTo().performClick()
        assertEquals(Routes.ASK_COACH, route)
        compose.onNodeWithText("Adjust sleep target").performScrollTo().performClick()
        compose.onNodeWithText("Save target").performClick()
        assertEquals(PlanStatus.SAVED, plan.sleepStatus)
        compose.onNodeWithText("Complete sleep plan").performScrollTo().performClick()
        assertEquals(PlanStatus.COMPLETED, plan.sleepStatus)
        capture("daily-plan-compact")
    }

    @Test fun persistedChoice_survivesStoreRecreation_andDoesNotLeakToNextDay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = -91000L
        val initial = DailyPlanBuilder.build(day, null, false, 450).activity.copy(status = PlanStatus.COMPLETED)
        // The QA runner can rerun this method after force-stop to verify disk persistence.
        if (InstrumentationRegistry.getArguments().getString("verifySavedPlanOnly") != "true") {
            DailyPlanStore(context).saveActivity(day, initial)
            DailyPlanStore(context).saveSleep(day, 435, PlanStatus.DISMISSED)
        }
        val reopened = DailyPlanStore(context)
        assertEquals(initial, reopened.read(day).activity)
        assertEquals(435, reopened.read(day).sleepMinutes)
        assertEquals(PlanStatus.DISMISSED, reopened.read(day).sleepStatus)
        assertEquals(DailyPlanBuilder.guidanceKey(initial), reopened.read(day).guidanceAtSave)
        assertNull(reopened.read(day + 1).activity)
        assertNull(reopened.read(day + 1).sleepMinutes)
    }

    @Test fun dailyPlan_hasActionAndReasons_atNormalSize() {
        val plan = DailyPlanBuilder.build(VitalTime.todayEpochDay(), null, false, 450)
        compose.setContent {
            VitalCoreTheme {
                HomeDashboard(HomeUiState(isLoading = false, dailyPlan = plan), emptyList(), {}, {})
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("daily-plan")
        compose.onNodeWithText("Start gently and check in").assertIsDisplayed()
        compose.onNodeWithText("Easy movement or rest").assertIsDisplayed()
        capture("daily-plan-normal")
    }

    @Test fun statusChanges_preserveSavedEvidenceUntilAnotherChoiceIsMade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val day = -91002L
        val store = DailyPlanStore(context)
        val initial = DailyPlanBuilder.build(day, null, false, 450)
        store.saveActivity(day, initial.activity.copy(status = PlanStatus.SAVED), initial.suggestedActivity)
        val originalGuidance = store.read(day).guidanceAtSave

        // A failed refresh changes the reasons, while completion and restoration only
        // record what the user did with their existing choice.
        listOf(PlanStatus.COMPLETED, PlanStatus.DISMISSED, PlanStatus.SAVED).forEach { status ->
            store.setActivityStatus(day, status)
            val saved = DailyPlanStore(context).read(day)
            assertEquals(status, saved.activity?.status)
            assertEquals(originalGuidance, saved.guidanceAtSave)
            assertTrue(DailyPlanBuilder.build(day, null, true, 450, saved).guidanceChanged)
        }

        val updated = DailyPlanBuilder.build(day, null, true, 450)
        store.saveActivity(day, updated.activity.copy(status = PlanStatus.SAVED), updated.suggestedActivity)
        assertFalse(DailyPlanBuilder.build(day, null, true, 450, store.read(day)).guidanceChanged)
    }

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
