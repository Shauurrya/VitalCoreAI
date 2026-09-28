package com.example.vitalcoreai.coach

import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import org.junit.Assert.*
import org.junit.Test

class DailyPlanTest {
    private val scores = ComputedScoresEntity(
        dateEpochDay = 100, recoveryScore = 80f, recoveryConfidence = "HIGH", recoveryExplanation = null,
        readinessScore = 85f, sleepScore = 80f, stressScore = 20f, activityScore = 70f,
        consistencyScore = null, lifestyleScore = null, trainingLoadNormalized = null,
        acwr = null, acwrZone = null, vo2MaxEstimate = null, biologicalAge = null,
        weeklyHealthScore = null, monthlyHealthScore = null, recommendationType = "Intervals",
        recommendationIntensity = "High", recommendationDetail = "Your recorded recommendation.",
        recommendationConfidence = "HIGH", recommendationAlternative = "Strength",
        recommendationRationale = ScorePipeline.encodeTextList(listOf("Readiness 85/100"))
    )

    @Test fun `current plan preserves persisted reasons and confidence`() {
        val plan = DailyPlanBuilder.build(100, scores, false, 450)
        assertEquals("Intervals", plan.activity.title)
        assertEquals(listOf("Readiness 85/100"), plan.activity.reasons)
        assertEquals("HIGH", plan.activity.confidence)
        assertEquals(450, plan.sleepMinutes)
        assertFalse(plan.activity.provisional)
    }

    @Test fun `yesterday high readiness cannot authorize todays intense plan`() {
        val plan = DailyPlanBuilder.build(101, scores, false, 480)
        assertEquals("Low", plan.activity.intensity)
        assertTrue(plan.activity.provisional)
        assertEquals(100L, plan.activity.evidenceDay)
    }

    @Test fun `stale readings make plan provisional despite persisted high readiness`() {
        val plan = DailyPlanBuilder.build(100, scores, true, 480)
        assertTrue(plan.activity.provisional)
        assertEquals("Low", plan.activity.intensity)
        assertTrue(plan.activity.reasons.single().contains("could not be refreshed"))
    }

    @Test fun `no scores still permits a gentle plan with configured sleep need`() {
        val plan = DailyPlanBuilder.build(100, null, false, 420)
        assertTrue(plan.activity.provisional)
        assertNull(plan.activity.evidenceDay)
        assertEquals(420, plan.sleepMinutes)
    }

    @Test fun `sync preserves completed saved choice and warns when guidance changes`() {
        val first = DailyPlanBuilder.build(100, scores, false, 480)
        val completed = first.activity.copy(status = PlanStatus.COMPLETED)
        val next = DailyPlanBuilder.build(100, scores, true, 480, SavedDailyPlan(activity = completed))
        assertEquals(completed, next.activity)
        assertTrue(next.guidanceChanged)
        assertTrue(next.suggestedActivity.provisional)
    }

    @Test fun `saved sleep adjustment and dismissal survive recommendation refresh`() {
        val plan = DailyPlanBuilder.build(100, scores, false, 480,
            SavedDailyPlan(sleepMinutes = 435, sleepStatus = PlanStatus.DISMISSED))
        assertEquals(435, plan.sleepMinutes)
        assertEquals(PlanStatus.DISMISSED, plan.sleepStatus)
    }

    @Test fun `legacy recommendation without confidence is visibly provisional`() {
        val plan = DailyPlanBuilder.build(100, scores.copy(recommendationConfidence = null), false, 480)
        assertTrue(plan.activity.provisional)
    }

    @Test fun `alternative does not increase intensity`() {
        val plan = DailyPlanBuilder.build(100, scores, false, 480)
        assertEquals("Low", plan.alternative?.intensity)
    }

    @Test fun `choosing an alternative does not claim guidance changed`() {
        val first = DailyPlanBuilder.build(100, scores, false, 480)
        val saved = SavedDailyPlan(activity = first.alternative!!.copy(status = PlanStatus.SAVED),
            guidanceAtSave = DailyPlanBuilder.guidanceKey(first.suggestedActivity))
        val refreshed = DailyPlanBuilder.build(100, scores, false, 480, saved)
        assertEquals("Strength", refreshed.activity.title)
        assertFalse(refreshed.guidanceChanged)
    }

    @Test fun `updated confidence and rationale prompt review even if activity stays the same`() {
        val first = DailyPlanBuilder.build(100, scores, false, 480)
        val saved = SavedDailyPlan(activity = first.activity.copy(status = PlanStatus.SAVED),
            guidanceAtSave = DailyPlanBuilder.guidanceKey(first.suggestedActivity))
        val refreshed = DailyPlanBuilder.build(100, scores.copy(recommendationConfidence = "MEDIUM",
            recommendationRationale = ScorePipeline.encodeTextList(listOf("More limited recent evidence"))), false, 480, saved)
        assertTrue(refreshed.guidanceChanged)
        assertEquals("HIGH", refreshed.activity.confidence)
        assertEquals("MEDIUM", refreshed.suggestedActivity.confidence)
    }
}
