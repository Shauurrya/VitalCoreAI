package com.example.vitalcoreai.coach

import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.AskCoachUiState
import com.example.vitalcoreai.ui.viewmodel.coachEvidenceLinks
import org.junit.Assert.*
import org.junit.Test

class AskCoachTest {
    private val questions = CoachIntent.entries.filter { it != CoachIntent.FREEFORM }

    private fun context() = CoachContext(
        today = CoachContext.TodayBlock(20000, "MONDAY", null, 30, false),
        readiness = CoachContext.ScoreBlock(42f, "Low", "HIGH", "Saved explanation.",
            listOf(CoachContext.FactorBlock("Sleep", 50f, "5h", 35f, "Short sleep recorded.")), null),
        sleep = CoachContext.SleepBlock(300, 40f, 180, 50f, "Variable", "23:00", "07:00", true),
        restingHR = CoachContext.MetricBlock("Resting HR", "65 BPM", "60 BPM", "+5 BPM", "UNKNOWN", "HIGH"),
        trainingLoad = CoachContext.TrainingLoadBlock(12f, "MODERATE", 1.1f, "OPTIMAL", true, 3, 0),
        muscleRecovery = null,
        energy = null,
        journal = null,
        trends = listOf(CoachContext.TrendBlock("7-day", "DECLINING", 70f, 50f, listOf("Shorter sleep"), "HIGH")),
        anomalies = emptyList(),
        forecast = CoachContext.ForecastBlock(50, 65, "MEDIUM", emptyList(), emptyList(), emptyList(), listOf("Keep a regular bedtime.")),
        recommendation = CoachContext.RecommendationBlock("Intervals", "High", 10, emptyList(), emptyList(),
            listOf("Saved recommendation"), listOf("Make room for rest."), "HIGH"),
        insights = emptyList(),
        confidence = CoachContext.ConfidenceBlock("HIGH", 90, listOf("Readings available"), emptyList(), emptyMap()),
        evidence = CoachContext.EvidenceBlock(20000, 1728000000000, emptyList(), 7)
    )

    @Test fun `all five supported questions answer current evidence and classify correctly`() {
        questions.forEach { question ->
            assertEquals(question, CoachIntent.classify(question.displayQuestion))
            val answer = CoachAnswerEngine.answer(question, context())
            assertEquals(question, answer.intent)
            assertTrue(question.name, answer.hasSufficientData)
            assertTrue(answer.body.isNotBlank())
            assertTrue(answer.citations.isNotEmpty())
            answer.followUps.forEach { assertTrue(CoachIntent.classify(it) in questions) }
        }
    }

    @Test fun `all questions explicitly explain an empty history`() {
        val empty = context().copy(
            readiness = null, sleep = null, restingHR = null, trainingLoad = null,
            forecast = null, recommendation = null, trends = emptyList(),
            today = context().today.copy(daysOfHistory = 0, isCalibrating = true),
            confidence = context().confidence.copy(overall = "LOW", missing = listOf("No current wearable readings")),
            evidence = CoachContext.EvidenceBlock(20000, null, emptyList(), 0)
        )
        questions.forEach { question ->
            val answer = CoachAnswerEngine.answer(question, empty)
            assertFalse(question.name, answer.hasSufficientData)
            assertTrue(answer.body.contains("No current wearable readings"))
            assertEquals("LOW", AskCoachUiState(selected = question, context = empty).confidence)
            assertEquals(listOf("confidence"), answer.citations)
        }
    }

    @Test fun `all questions label stale evidence and lower displayed confidence`() {
        val stale = context().copy(evidence = context().evidence!!.copy(staleMetrics = listOf("Sleep Session")))
        questions.forEach { question ->
            val answer = CoachAnswerEngine.answer(question, stale)
            assertTrue(question.name, answer.body.contains("could not be refreshed"))
            assertTrue(answer.body.contains("provisional"))
            assertEquals("LOW", AskCoachUiState(selected = question, context = stale).confidence)
        }
        val training = CoachAnswerEngine.answer(CoachIntent.WHAT_SHOULD_I_TRAIN, stale)
        assertFalse(training.hasSufficientData)
        assertFalse(training.body.contains("Intervals"))
        assertTrue(training.body.contains("rest or gentle movement"))
    }

    @Test fun `four scored days cannot support a weekly review`() {
        val sparse = context().copy(evidence = context().evidence!!.copy(scoredDaysThisWeek = 4))
        assertFalse(CoachAnswerEngine.answer(CoachIntent.WEEKLY_REVIEW, sparse).hasSufficientData)
    }

    @Test fun `overview cannot bypass stale training protection`() {
        val stale = context().copy(evidence = context().evidence!!.copy(staleMetrics = listOf("Sleep Session")))
        val answer = CoachAnswerEngine.answer(CoachIntent.FREEFORM, stale)
        assertTrue(answer.body.contains("rest or gentle movement"))
        assertFalse(answer.body.contains("Intervals"))
        assertFalse(answer.citations.contains("recommendation"))
    }

    @Test fun `score without factors uses its saved explanation`() {
        val saved = context().copy(readiness = context().readiness!!.copy(breakdown = emptyList()))
        val answer = CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_LOW, saved)
        assertTrue(answer.body.contains("42/100"))
        assertTrue(answer.body.contains("Saved explanation."))
        assertFalse(answer.body.contains("No single component"))
    }

    @Test fun `zero resting heart rate deviation is not a tiredness contributor`() {
        val equal = context().copy(
            readiness = null,
            sleep = context().sleep!!.copy(durationMinutes = 480, debtMinutes = 0, consistencyScore = 90f),
            trainingLoad = context().trainingLoad!!.copy(consecutiveTrainingDays = 0),
            restingHR = context().restingHR!!.copy(deviation = "+0 BPM")
        )
        val answer = CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_TIRED, equal)
        assertEquals("Nothing obvious in your data", answer.headline)
        assertTrue(answer.hasSufficientData)
        assertFalse(answer.body.contains("+0 BPM"))
    }

    @Test fun `missing resting heart rate baseline is not treated as a normal reading`() {
        val missingBaseline = context().copy(
            sleep = context().sleep!!.copy(durationMinutes = 480, debtMinutes = 0, consistencyScore = 90f),
            trainingLoad = context().trainingLoad!!.copy(consecutiveTrainingDays = 0),
            restingHR = context().restingHR!!.copy(baseline = "—", deviation = "—")
        )
        val answer = CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_TIRED, missingBaseline)
        assertFalse(answer.hasSufficientData)
        assertTrue(answer.body.contains("resting heart rate baseline"))
        assertEquals("LOW", AskCoachUiState(selected = CoachIntent.WHY_AM_I_TIRED, context = missingBaseline).confidence)
    }

    @Test fun `tonight cites the forecast range even without forecast actions`() {
        val noForecastActions = context().copy(forecast = context().forecast!!.copy(opportunities = emptyList(), risks = emptyList()))
        val answer = CoachAnswerEngine.answer(CoachIntent.WHAT_TONIGHT, noForecastActions)
        assertEquals("Tomorrow: 50–65", answer.headline)
        assertTrue(answer.citations.contains("forecast"))
    }

    @Test fun `weekly review links to the recovery action it cites`() {
        val answer = CoachAnswerEngine.answer(CoachIntent.WEEKLY_REVIEW, context())
        assertTrue(answer.body.contains("Make room for rest."))
        assertTrue(answer.citations.contains("recommendation"))
        assertTrue(coachEvidenceLinks(answer.citations).any { it.route == Routes.HOME })
    }

    @Test fun `supporting links are deduplicated registered local reading destinations`() {
        val links = coachEvidenceLinks(listOf("readiness", "readiness.breakdown", "sleep.debt", "rhr",
            "training_load", "muscle_recovery", "recommendation", "forecast.risks", "journal", "trends", "insights", "anomalies", "confidence"))
        assertEquals(links.size, links.map { it.route }.toSet().size)
        assertEquals(setOf(Routes.READINESS, Routes.SLEEP, Routes.HEART, Routes.TRAINING,
            Routes.MUSCLE_RECOVERY, Routes.HOME, Routes.FORECAST, Routes.CHECK_IN, Routes.HISTORY,
            Routes.INSIGHTS, Routes.DATA_SOURCES), links.map { it.route }.toSet())
        assertTrue(links.all { !it.route.contains("://") })
    }
}
