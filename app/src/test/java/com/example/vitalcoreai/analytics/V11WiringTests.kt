package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.coach.CoachAnswerEngine
import com.example.vitalcoreai.coach.CoachContext
import com.example.vitalcoreai.coach.CoachIntent
import com.example.vitalcoreai.core.time.FixedVitalClock
import com.example.vitalcoreai.core.time.VitalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MuscleRecovery2Test {

    private fun session(day: Long, type: String, rpe: Int, endMinute: Int = 12 * 60) =
        MuscleRecoveryEngine.SessionInfo(
            dateEpochDay = day,
            exerciseType = type,
            rpe = rpe,
            muscleGroups = MuscleRecoveryEngine.exerciseToMuscleGroups(type),
            endMinuteOfDay = endMinute
        )

    /**
     * The regression this file exists for.
     *
     * A generic strength session maps to FULL_BODY, and FULL_BODY was excluded from the
     * output, so a punishing gym session left every muscle group reporting READY.
     */
    @Test
    fun `a generic full-body session actually fatigues muscle groups`() {
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = listOf(session(100, "STRENGTH_TRAINING", rpe = 9)),
            todayEpochDay = 100,
            nowMinuteOfDay = 20 * 60
        )
        val notReady = statuses.count { it.status != MuscleRecoveryEngine.RecoveryStatus.READY }
        assertTrue(
            "a full-body session at RPE 9 must fatigue groups, got $notReady of ${statuses.size}",
            notReady >= 8
        )
    }

    @Test
    fun `an unrecognised exercise type still loads the body`() {
        // Falls through exerciseToMuscleGroups' else branch to FULL_BODY.
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = listOf(session(100, "SOME_UNKNOWN_ACTIVITY", rpe = 8)),
            todayEpochDay = 100,
            nowMinuteOfDay = 20 * 60
        )
        assertTrue(statuses.any { it.status != MuscleRecoveryEngine.RecoveryStatus.READY })
    }

    @Test
    fun `FULL_BODY is never itself reported as a group`() {
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = listOf(session(100, "STRENGTH_TRAINING", rpe = 5)),
            todayEpochDay = 100
        )
        assertTrue(statuses.none { it.group == MuscleRecoveryEngine.MuscleGroup.FULL_BODY })
    }

    @Test
    fun `elapsed hours use the session end time not whole days`() {
        // Session finished 20:00 yesterday; it is 08:00 today -> 12 hours, not 24.
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = listOf(session(99, "SQUAT", rpe = 8, endMinute = 20 * 60)),
            todayEpochDay = 100,
            nowMinuteOfDay = 8 * 60
        )
        val quads = statuses.first { it.group == MuscleRecoveryEngine.MuscleGroup.QUADS }
        assertEquals(12, quads.hoursSinceTrained)
    }

    /**
     * A single global soreness figure previously forced every group to FATIGUED, including
     * ones untrained for a week — blaming the wrong muscle and hiding the real one.
     */
    @Test
    fun `soreness only applies to recently trained groups`() {
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = listOf(session(100, "BENCH_PRESS", rpe = 8)),
            todayEpochDay = 100,
            sorenessRating = 9,
            nowMinuteOfDay = 18 * 60
        )
        val chest = statuses.first { it.group == MuscleRecoveryEngine.MuscleGroup.CHEST }
        val calves = statuses.first { it.group == MuscleRecoveryEngine.MuscleGroup.CALVES }
        assertEquals(MuscleRecoveryEngine.RecoveryStatus.FATIGUED, chest.status)
        assertEquals(
            "an untrained group must not inherit today's soreness",
            MuscleRecoveryEngine.RecoveryStatus.READY, calves.status
        )
        assertNotNull(chest.sorenessRating)
        assertEquals(null, calves.sorenessRating)
    }

    @Test
    fun `learned recovery needs enough observations before it is trusted`() {
        val sparse = listOf(session(100, "SQUAT", 8), session(103, "SQUAT", 8))
        val learned = MuscleRecoveryEngine.learnRecoveryTimes(sparse)
        val quads = learned[MuscleRecoveryEngine.MuscleGroup.QUADS]
        assertTrue("one observed cycle must not be reliable", quads == null || !quads.isReliable)
    }

    @Test
    fun `learned recovery is derived from the users own training gaps`() {
        // Squats every 3 days, repeatedly.
        val sessions = (0..7).map { session(100L + it * 3, "SQUAT", 8) }
        val learned = MuscleRecoveryEngine.learnRecoveryTimes(sessions)
        val quads = learned[MuscleRecoveryEngine.MuscleGroup.QUADS]!!
        assertTrue(quads.isReliable)
        assertEquals(72, quads.hoursP50)
        assertTrue(MuscleRecoveryEngine.describeLearned(quads).contains("your own"))
    }

    @Test
    fun `gaps following a high soreness report are excluded from learning`() {
        val sessions = (0..7).map { session(100L + it * 3, "SQUAT", 8) }
        // Mark every return day as very sore — nothing should be learned from those cycles.
        val sore = sessions.associate { it.dateEpochDay to 9 }
        val learned = MuscleRecoveryEngine.learnRecoveryTimes(sessions, sore)
        assertTrue(learned[MuscleRecoveryEngine.MuscleGroup.QUADS] == null)
    }

    @Test
    fun `an empty history leaves everything ready`() {
        val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(emptyList(), 100)
        assertTrue(statuses.all { it.status == MuscleRecoveryEngine.RecoveryStatus.READY })
        assertTrue(statuses.isNotEmpty())
    }
}

class RecommendationEngineTest {

    private fun input(
        readiness: Float? = 75f,
        soreness: Int? = null,
        consecutive: Int = 0,
        acwrZone: String? = null,
        acwrMeaningful: Boolean = false,
        energy: Float? = null,
        rhrDev: Int? = null,
        debt: Int = 0,
        muscles: List<MuscleRecoveryEngine.MuscleStatus> = emptyList()
    ) = RecommendationEngine.RecommendationInput(
        readiness = readiness,
        recovery = readiness,
        energyBank = energy,
        acwrZone = acwrZone,
        acwrIsMeaningful = acwrMeaningful,
        muscleStatuses = muscles,
        consecutiveTrainingDays = consecutive,
        sleepDebtMinutes = debt,
        checkInSoreness = soreness,
        restingHRDeviationBpm = rhrDev,
        dataQuality = DataQualityReport(Confidence.HIGH, 90, emptyList(), false)
    )

    @Test
    fun `high readiness permits a hard session`() {
        val r = RecommendationEngine.recommend(input(readiness = 92f))
        assertEquals(RecommendationEngine.Intensity.HIGH, r.intensity)
        assertTrue(r.volumeAdjustmentPercent >= 0)
    }

    @Test
    fun `very low readiness produces rest`() {
        val r = RecommendationEngine.recommend(input(readiness = 20f))
        assertEquals(RecommendationEngine.WorkoutType.REST, r.primaryType)
        assertEquals(RecommendationEngine.Intensity.LOW, r.intensity)
        assertEquals(-100, r.volumeAdjustmentPercent)
    }

    /**
     * The safety cap: no combination of favourable signals may produce a hard session on a
     * day the recovery data says otherwise.
     */
    @Test
    fun `recovery signals can only ever downgrade intensity`() {
        val cases = listOf(
            input(readiness = 95f, soreness = 9),
            input(readiness = 95f, consecutive = 6),
            input(readiness = 95f, acwrZone = "DANGER", acwrMeaningful = true),
            input(readiness = 95f, energy = 20f),
            input(readiness = 95f, rhrDev = 9)
        )
        for (c in cases) {
            val r = RecommendationEngine.recommend(c)
            assertTrue(
                "a recovery signal must prevent HIGH intensity, got ${r.intensity} for $c",
                r.intensity != RecommendationEngine.Intensity.HIGH
            )
        }
    }

    @Test
    fun `a missing readiness score defaults conservatively rather than optimistically`() {
        val r = RecommendationEngine.recommend(input(readiness = null))
        assertEquals(RecommendationEngine.Intensity.LOW, r.intensity)
        assertTrue(r.rationale.any { it.contains("No readiness") })
    }

    @Test
    fun `muscle availability drives the region advice`() {
        val upperReady = listOf(
            MuscleRecoveryEngine.MuscleGroup.CHEST, MuscleRecoveryEngine.MuscleGroup.BACK,
            MuscleRecoveryEngine.MuscleGroup.SHOULDERS, MuscleRecoveryEngine.MuscleGroup.BICEPS
        ).map {
            MuscleRecoveryEngine.MuscleStatus(it, MuscleRecoveryEngine.RecoveryStatus.READY, 0, 100, 0, null)
        }
        val lowerFatigued = listOf(
            MuscleRecoveryEngine.MuscleGroup.QUADS, MuscleRecoveryEngine.MuscleGroup.HAMSTRINGS,
            MuscleRecoveryEngine.MuscleGroup.GLUTES, MuscleRecoveryEngine.MuscleGroup.CALVES
        ).map {
            MuscleRecoveryEngine.MuscleStatus(it, MuscleRecoveryEngine.RecoveryStatus.FATIGUED, 40, 8, 9, 8)
        }
        val r = RecommendationEngine.recommend(input(muscles = upperReady + lowerFatigued))
        assertTrue(r.readyRegions.contains(RecommendationEngine.BodyRegion.UPPER))
        assertTrue(r.avoidRegions.contains(RecommendationEngine.BodyRegion.LOWER))
        assertTrue(r.detail.contains("Upper Body"))
        assertTrue(r.detail.contains("Lower Body"))
    }

    @Test
    fun `every recommendation explains itself`() {
        val r = RecommendationEngine.recommend(input(readiness = 55f, debt = 300, consecutive = 3))
        assertTrue(r.rationale.isNotEmpty())
        assertTrue(r.rationale.any { it.contains("Readiness") })
        assertTrue(r.recoveryActions.isNotEmpty())
    }

    @Test
    fun `summary line is renderable for both rest and training days`() {
        val rest = RecommendationEngine.recommend(input(readiness = 15f))
        val train = RecommendationEngine.recommend(input(readiness = 85f))
        assertTrue(rest.summaryLine.isNotBlank())
        assertTrue(train.summaryLine.contains("intensity"))
        assertFalse("a rest day should not advertise an intensity", rest.summaryLine.contains("intensity"))
    }

    @Test
    fun `volume advice stays within sane bounds`() {
        val extremes = listOf(0f, 10f, 35f, 50f, 70f, 85f, 100f)
        for (v in extremes) {
            val r = RecommendationEngine.recommend(input(readiness = v, consecutive = 6, soreness = 9))
            assertTrue(r.volumeAdjustmentPercent in -100..20)
        }
    }
}

class InsightDiscoveryEngineTest {

    @Test
    fun `finds nothing in a short history`() {
        val days = (1L..6L).map { InsightDiscoveryEngine.DayRecord(it, readiness = 70f, sleepMinutes = 450) }
        assertTrue(InsightDiscoveryEngine.discover(days).isEmpty())
    }

    @Test
    fun `finds nothing in pure noise`() {
        // Deterministic pseudo-noise: no relationship between sleep and readiness.
        val days = (1L..60L).map { d ->
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = d,
                readiness = (60 + (d * 37 % 23)).toFloat(),
                sleepMinutes = (400 + (d * 53 % 120)).toInt(),
                restingHR = 60
            )
        }
        val found = InsightDiscoveryEngine.discover(days)
        assertTrue("noise must not produce insights, got ${found.map { it.id }}", found.isEmpty())
    }

    /**
     * Note the deterministic jitter. Groups of *identical* values have zero variance, and
     * Welch's t-test is undefined there — the engine correctly declines to report anything
     * rather than dividing by zero. Real measurements always vary, so the planted signal
     * has to as well or the test would be exercising a case that cannot occur.
     */
    @Test
    fun `finds a planted sleep-readiness relationship`() {
        val days = (1L..60L).map { d ->
            val longNight = d % 2 == 0L
            val jitter = (d % 5).toInt()
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = d,
                readiness = (if (longNight) 80 else 60).toFloat() + jitter,
                sleepMinutes = (if (longNight) 500 else 380) + jitter * 3
            )
        }
        val found = InsightDiscoveryEngine.discover(days)
        assertTrue(
            "expected the sleep/readiness pattern, got ${found.map { it.id }}",
            found.any { it.id == "sleep_readiness" }
        )
        val insight = found.first { it.id == "sleep_readiness" }
        assertTrue(insight.pValue < 0.01)
        assertTrue(insight.observations >= 40)
    }

    @Test
    fun `finds the consecutive high-load drop`() {
        // Blocks of 3 high-load days followed by a suppressed readiness day.
        val days = (1L..80L).map { d ->
            val inStreak = (d % 8L) in 1..3
            val dayAfterStreak = (d % 8L) == 4L
            val jitter = (d % 5).toInt()
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = d,
                readiness = (if (dayAfterStreak) 55 else 75).toFloat() + jitter,
                hadHighLoad = inStreak,
                hadWorkout = inStreak
            )
        }
        val found = InsightDiscoveryEngine.discover(days)
        assertTrue(
            "expected the consecutive-load pattern, got ${found.map { it.id }}",
            found.any { it.id == "consecutive_high_load" }
        )
    }

    @Test
    fun `no insight claims causation`() {
        val days = (1L..60L).map { d ->
            val longNight = d % 2 == 0L
            val jitter = (d % 5).toInt()
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = d,
                readiness = (if (longNight) 82 else 58).toFloat() + jitter,
                sleepMinutes = (if (longNight) 505 else 375) + jitter * 3,
                restingHR = (if (longNight) 58 else 66) + jitter
            )
        }
        val forbidden = listOf("causes", "caused by", "because of", "results in", "leads to", "proves")
        val found = InsightDiscoveryEngine.discover(days)
        assertTrue(found.isNotEmpty())
        for (i in found) {
            val text = "${i.title} ${i.body}".lowercase()
            for (w in forbidden) {
                assertFalse("causal phrase '$w' in: $text", text.contains(w))
            }
        }
    }

    @Test
    fun `caps the number of insights surfaced`() {
        val days = (1L..120L).map { d ->
            val good = d % 2 == 0L
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = d,
                readiness = if (good) 85f else 55f,
                sleepMinutes = if (good) 520 else 360,
                restingHR = if (good) 55 else 68,
                bedtimeMinuteOfDay = if (good) 22 * 60 + 30 else 2 * 60,
                strain = if (good) 12f else 6f,
                hadWorkout = true,
                hadHighLoad = !good,
                isRestDay = false
            )
        }
        assertTrue(InsightDiscoveryEngine.discover(days).size <= 5)
    }

    @Test
    fun `survives empty input`() {
        assertTrue(InsightDiscoveryEngine.discover(emptyList()).isEmpty())
    }
}

class CoachAnswerEngineTest {

    private fun context(
        readiness: Float? = 72f,
        breakdown: List<CoachContext.FactorBlock> = emptyList(),
        sleepDebt: Int = 0,
        recommendation: CoachContext.RecommendationBlock? = null,
        forecast: CoachContext.ForecastBlock? = null,
        trends: List<CoachContext.TrendBlock> = emptyList(),
        missing: List<String> = emptyList()
    ) = CoachContext(
        today = CoachContext.TodayBlock(20000, "MONDAY", "Sam", 30, false),
        readiness = readiness?.let {
            CoachContext.ScoreBlock(it, "Good", "HIGH", "Readiness ${it.toInt()}/100.", breakdown, null)
        },
        sleep = CoachContext.SleepBlock(430, 71f, sleepDebt, 78f, "Consistent", "23:10", "07:00", true),
        restingHR = CoachContext.MetricBlock("Resting HR", "71 BPM", "62 BPM", "+9 BPM", "Declining", "HIGH"),
        trainingLoad = CoachContext.TrainingLoadBlock(11.2f, "MODERATE", 1.1f, "OPTIMAL", true, 3, 0),
        muscleRecovery = CoachContext.MuscleBlock(listOf("Chest"), listOf("Quads"), emptyList(), emptyList()),
        energy = CoachContext.ScoreBlock(64f, "Moderate", "HIGH", null, emptyList(), null),
        journal = CoachContext.JournalBlock(emptyList(), emptyList(), 6, 7, 4, 6),
        trends = trends,
        anomalies = emptyList(),
        forecast = forecast,
        recommendation = recommendation,
        insights = emptyList(),
        confidence = CoachContext.ConfidenceBlock("HIGH", 88, listOf("sleep recorded"), missing, emptyMap())
    )

    @Test
    fun `why am I low names the actual dragging component`() {
        val ctx = context(
            readiness = 48f,
            breakdown = listOf(
                CoachContext.FactorBlock("Sleep Debt", 30f, "3h over 7 nights", 28f, "Sleep debt is reducing readiness."),
                CoachContext.FactorBlock("Recovery", 55f, "70/100", 70f, "Recovery is solid.")
            )
        )
        val a = CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_LOW, ctx)
        assertTrue(a.hasSufficientData)
        assertTrue(a.body.contains("sleep debt", true))
        assertTrue(a.body.contains("48"))
        assertTrue(a.citations.contains("readiness.breakdown"))
    }

    @Test
    fun `answers degrade honestly when readiness is missing`() {
        val a = CoachAnswerEngine.answer(
            CoachIntent.WHY_AM_I_LOW,
            context(readiness = null, missing = listOf("no sleep recorded"))
        )
        assertFalse(a.hasSufficientData)
        assertEquals("Not enough data yet", a.headline)
        assertTrue(a.body.contains("no sleep recorded"))
    }

    @Test
    fun `what should I train relays the deterministic recommendation verbatim`() {
        val rec = CoachContext.RecommendationBlock(
            type = "Hypertrophy", intensity = "Moderate", volumeAdjustmentPercent = -20,
            readyRegions = listOf("Upper Body"), avoidRegions = listOf("Lower Body"),
            rationale = listOf("Readiness 72/100", "3 consecutive training days"),
            recoveryActions = listOf("Extra 30 minutes of sleep tonight.")
        )
        val a = CoachAnswerEngine.answer(CoachIntent.WHAT_SHOULD_I_TRAIN, context(recommendation = rec))
        assertEquals("Hypertrophy", a.headline)
        assertTrue(a.body.contains("20%"))
        assertTrue(a.body.contains("Upper Body"))
        assertTrue(a.body.contains("Lower Body"))
    }

    @Test
    fun `tonight leads with the forecast range not a point estimate`() {
        val f = CoachContext.ForecastBlock(
            low = 76, high = 82, confidence = "MEDIUM",
            positiveDrivers = listOf("Sleep opportunity"),
            negativeDrivers = listOf("Training load elevated"),
            risks = listOf("Another high-intensity session tonight would lower tomorrow."),
            opportunities = listOf("Sleeping 8h tonight is the single biggest lever available.")
        )
        val a = CoachAnswerEngine.answer(CoachIntent.WHAT_TONIGHT, context(forecast = f, sleepDebt = 120))
        assertEquals("Tomorrow: 76–82", a.headline)
        assertTrue(a.body.contains("lever") || a.body.contains("earlier"))
    }

    @Test
    fun `why am I tired admits when it cannot find a cause`() {
        val ctx = CoachContext(
            today = CoachContext.TodayBlock(20000, "MONDAY", null, 30, false),
            readiness = CoachContext.ScoreBlock(78f, "Good", "HIGH", null, emptyList(), null),
            sleep = CoachContext.SleepBlock(470, 85f, 0, 90f, "Highly consistent", "23:00", "07:00", true),
            restingHR = CoachContext.MetricBlock("Resting HR", "60 BPM", "61 BPM", "-1 BPM", "Stable", "HIGH"),
            trainingLoad = CoachContext.TrainingLoadBlock(6f, "LIGHT", 1.0f, "OPTIMAL", true, 0, 2),
            muscleRecovery = null,
            energy = null,
            journal = CoachContext.JournalBlock(emptyList(), emptyList(), 8, 2, 2, 8),
            trends = emptyList(), anomalies = emptyList(), forecast = null,
            recommendation = null, insights = emptyList(),
            confidence = CoachContext.ConfidenceBlock("HIGH", 92, emptyList(), emptyList(), emptyMap())
        )
        val a = CoachAnswerEngine.answer(CoachIntent.WHY_AM_I_TIRED, ctx)
        assertTrue(a.body.contains("can't point to a cause") || a.body.contains("Nothing obvious"))
        assertTrue("must not diagnose", !a.body.lowercase().contains("you have"))
    }

    @Test
    fun `weekly review refuses to review a week without data`() {
        val a = CoachAnswerEngine.answer(CoachIntent.WEEKLY_REVIEW, context(trends = emptyList()))
        assertFalse(a.hasSufficientData)
    }

    @Test
    fun `weekly review is sectioned when trends exist`() {
        val trends = listOf(
            CoachContext.TrendBlock("7-day", "IMPROVING", 64f, 71f, listOf("Better sleep consistency"), "HIGH")
        )
        val a = CoachAnswerEngine.answer(CoachIntent.WEEKLY_REVIEW, context(trends = trends))
        assertTrue(a.hasSufficientData)
        assertTrue(a.body.contains("RECOVERY"))
        assertTrue(a.body.contains("NEXT WEEK"))
        assertTrue(a.body.contains("Better sleep consistency"))
    }

    @Test
    fun `intent classification routes the spec's five questions`() {
        assertEquals(CoachIntent.WHAT_SHOULD_I_TRAIN, CoachIntent.classify("What should I train?"))
        assertEquals(CoachIntent.WHY_AM_I_TIRED, CoachIntent.classify("Why am I so tired today?"))
        assertEquals(CoachIntent.WHAT_TONIGHT, CoachIntent.classify("What should I do tonight?"))
        assertEquals(CoachIntent.WEEKLY_REVIEW, CoachIntent.classify("How was my week?"))
        assertEquals(CoachIntent.WHY_AM_I_LOW, CoachIntent.classify("Why is my readiness low?"))
    }

    @Test
    fun `context serialises to parseable json carrying the spec's top-level keys`() {
        val json = context().toJson()
        for (key in listOf(
            "today", "readiness", "sleep", "rhr", "training_load", "muscle_recovery",
            "energy", "journal", "trends", "anomalies", "forecast", "confidence"
        )) {
            assertTrue("missing key '$key' in payload", json.contains("\"$key\":"))
        }
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        // Balanced braces is a cheap structural check that catches most writer bugs.
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun `system prompt forbids the model from computing or diagnosing`() {
        val p = CoachContext.buildSystemPrompt()
        assertTrue(p.contains("You do not calculate"))
        assertTrue(p.contains("Never diagnose"))
        assertTrue(p.contains("Never claim HRV"))
    }
}

class VitalTimeTest {

    @After
    fun tearDown() = VitalTime.reset()

    @Test
    fun `today follows the injected clock`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 3, 14, 9, 30), ZoneId.of("Asia/Kolkata")
        )
        assertEquals(2026, VitalTime.today().year)
        assertEquals(3, VitalTime.today().monthValue)
        assertEquals(14, VitalTime.today().dayOfMonth)
        assertEquals(9 * 60 + 30, VitalTime.nowMinuteOfDay())
    }

    @Test
    fun `day bounds are exactly one day apart in a normal week`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 15, 12, 0), ZoneId.of("Europe/London")
        )
        val day = VitalTime.todayEpochDay()
        assertEquals(1440, VitalTime.lengthOfDayMinutes(day))
        assertEquals(
            VitalTime.startOfDayMs(day + 1),
            VitalTime.endOfDayExclusiveMs(day)
        )
    }

    /**
     * The DST case that hardcoded `24 * 60 * 60 * 1000` arithmetic gets wrong.
     * 29 March 2026 is the spring-forward date in Europe/London: a 23-hour day.
     */
    @Test
    fun `dst days are not assumed to be 1440 minutes`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 3, 29, 12, 0), ZoneId.of("Europe/London")
        )
        val springForward = VitalTime.todayEpochDay()
        assertEquals(1380, VitalTime.lengthOfDayMinutes(springForward))

        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 10, 25, 12, 0), ZoneId.of("Europe/London")
        )
        val fallBack = VitalTime.todayEpochDay()
        assertEquals(1500, VitalTime.lengthOfDayMinutes(fallBack))
    }

    @Test
    fun `travel changes the local day without changing the instant`() {
        val clock = FixedVitalClock(
            LocalDateTime.of(2026, 5, 10, 22, 0), ZoneId.of("America/New_York")
        )
        VitalTime.clock = clock
        val instantBefore = VitalTime.nowMs()
        val dayInNewYork = VitalTime.todayEpochDay()

        clock.travelTo(ZoneId.of("Asia/Kolkata"))
        val dayInKolkata = VitalTime.todayEpochDay()

        assertEquals("the instant must not move", instantBefore, VitalTime.nowMs())
        assertEquals("22:00 in New York is the next day in Kolkata", dayInNewYork + 1, dayInKolkata)
    }

    @Test
    fun `bedtime in the evening is attributed to the previous day`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 15, 8, 0), ZoneId.of("UTC")
        )
        val wakeDay = VitalTime.todayEpochDay()
        // 23:10 bedtime -> previous calendar day
        assertEquals(wakeDay - 1, VitalTime.bedtimeEpochDay(wakeDay, 23 * 60 + 10))
        // 00:40 bedtime -> same calendar day as waking
        assertEquals(wakeDay, VitalTime.bedtimeEpochDay(wakeDay, 40))
    }

    @Test
    fun `circular minute delta takes the short way round midnight`() {
        assertEquals(20, VitalTime.circularMinuteDelta(23 * 60 + 50, 10))
        assertEquals(-20, VitalTime.circularMinuteDelta(10, 23 * 60 + 50))
        assertEquals(30, VitalTime.circularMinuteDelta(600, 630))
    }

    @Test
    fun `epoch day round trips through start of day`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 1, 5, 3, 0), ZoneId.of("Asia/Kolkata")
        )
        val day = VitalTime.todayEpochDay()
        assertEquals(day, VitalTime.epochDayOf(VitalTime.startOfDayMs(day)))
        assertEquals(day, VitalTime.epochDayOf(VitalTime.endOfDayExclusiveMs(day) - 1))
        assertEquals(day + 1, VitalTime.epochDayOf(VitalTime.endOfDayExclusiveMs(day)))
    }

    @Test
    fun `formatting helpers are locale independent`() {
        assertEquals("7h 52m", VitalTime.formatDurationMinutes(472))
        assertEquals("52m", VitalTime.formatDurationMinutes(52))
        assertEquals("-35m", VitalTime.formatDurationMinutes(-35))
        assertEquals("23:05", VitalTime.formatClock(23 * 60 + 5))
        assertEquals("00:00", VitalTime.formatClock(0))
    }

    @Test
    fun `week start is always monday`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 18, 12, 0), ZoneId.of("UTC")  // a Thursday
        )
        val weekStart = VitalTime.weekStartEpochDay(VitalTime.todayEpochDay())
        assertEquals(java.time.DayOfWeek.MONDAY, VitalTime.dayOfWeek(weekStart))
    }
}
