package com.example.vitalcoreai.coach

/**
 * Rule-Based Coach Engine
 *
 * NOT generative AI — this is a decision tree + template library.
 * UI describes it as "personalized insights", never "AI model".
 *
 * Decision logic: keyed on which score(s) changed and by how much.
 * Template slots are filled with actual values from today's data.
 */
object CoachEngine {

    data class CoachInsight(
        val title: String,
        val body: String,
        val type: InsightType,
        val actionable: Boolean = true
    )

    enum class InsightType {
        RECOVERY, SLEEP, TRAINING, ACTIVITY, READINESS, GENERAL, WARNING
    }

    data class CoachInput(
        val recoveryScore: Float?,
        val readinessScore: Float?,
        val sleepScore: Float?,
        val stressScore: Float?,
        val activityScore: Float?,
        val acwrZone: String?,
        val restingHR: Int?,
        val hrBaseline: Int?,        // 30-day avg
        val sleepDebtMinutes: Int?,
        val daysWithoutTraining: Int?,
        val todaySleepHours: Float?,
        val vo2Max: Float?,
        val biologicalAge: Int?,
        val chronologicalAge: Int?,
        // Part 11 — Energy Bank
        val energyBankScore: Float? = null,
        // Part 9 — Muscle Recovery
        val musclesFatigued: Int = 0,
        val sorenessRating: Int? = null
    )

    fun getDailyInsights(input: CoachInput): List<CoachInsight> {
        val insights = mutableListOf<CoachInsight>()

        // --- Recovery insights ---
        when {
            input.recoveryScore != null && input.recoveryScore < 35f ->
                insights += CoachInsight(
                    title = "Prioritize Recovery Today",
                    body = recoveryLow(input),
                    type = InsightType.RECOVERY,
                    actionable = true
                )
            input.recoveryScore != null && input.recoveryScore > 80f ->
                insights += CoachInsight(
                    title = "You're Well-Recovered 💚",
                    body = recoveryHigh(input),
                    type = InsightType.RECOVERY
                )
            input.recoveryScore != null ->
                insights += CoachInsight(
                    title = "Moderate Recovery",
                    body = recoveryMid(input),
                    type = InsightType.RECOVERY
                )
        }

        // --- Resting HR elevation ---
        if (input.restingHR != null && input.hrBaseline != null) {
            val diff = input.restingHR - input.hrBaseline
            if (diff >= 6) {
                insights += CoachInsight(
                    title = "Resting HR Elevated",
                    body = "Your resting HR is ${input.restingHR} bpm — ${diff} bpm above your ${input.hrBaseline} bpm baseline. " +
                            "This can indicate stress, dehydration, or incomplete recovery. Consider a lighter day and early sleep.",
                    type = InsightType.WARNING
                )
            }
        }

        // --- Sleep debt ---
        if (input.sleepDebtMinutes != null && input.sleepDebtMinutes > 120) {
            insights += CoachInsight(
                title = "Sleep Debt Accumulating",
                body = "You've built up ${input.sleepDebtMinutes / 60}h ${input.sleepDebtMinutes % 60}m of sleep debt this week. " +
                        "Even 20–30 extra minutes tonight can start reducing that deficit. Aim to be in bed by 10pm.",
                type = InsightType.SLEEP
            )
        }

        // --- Sleep quality ---
        when {
            input.sleepScore != null && input.sleepScore < 50f ->
                insights += CoachInsight(
                    title = "Sleep Needs Attention",
                    body = buildSleepLow(input),
                    type = InsightType.SLEEP
                )
            input.sleepScore != null && input.todaySleepHours != null && input.todaySleepHours >= 7.5f ->
                insights += CoachInsight(
                    title = "Solid Night's Sleep",
                    body = "You got ${input.todaySleepHours}h of sleep — well within your recovery window. Keep the bedtime consistent.",
                    type = InsightType.SLEEP,
                    actionable = false
                )
        }

        // --- Training load / ACWR ---
        when (input.acwrZone) {
            "DANGER" -> insights += CoachInsight(
                title = "Training Spike — Rest Recommended",
                body = "Your workload has jumped sharply relative to your 28-day average. Research links ACWR > 1.5 to elevated injury risk. " +
                        "Consider a full rest day or only light movement (walk, yoga, stretching).",
                type = InsightType.WARNING
            )
            "CAUTION" -> insights += CoachInsight(
                title = "Training Load Getting High",
                body = "Your recent training is climbing above your chronic baseline. This is the optimal range's upper edge — " +
                        "one more hard session could push into the danger zone. Listen to your body.",
                type = InsightType.TRAINING
            )
            "UNDER_TRAINING" ->
                if (input.daysWithoutTraining != null && input.daysWithoutTraining >= 3) {
                    insights += CoachInsight(
                        title = "Time for a Workout",
                        body = "It's been ${input.daysWithoutTraining} days since your last session. Your recovery is solid — " +
                                "a moderate workout today would maintain your fitness baseline without overloading.",
                        type = InsightType.TRAINING
                    )
                }
            "OPTIMAL" ->
                if (input.readinessScore != null && input.readinessScore > 75f) {
                    insights += CoachInsight(
                        title = "Ready for a Quality Session",
                        body = "Your readiness score is ${input.readinessScore.toInt()} and training load is in the optimal zone. " +
                                "This is a good day for a quality workout — threshold intervals or a long run would both work well.",
                        type = InsightType.TRAINING
                    )
                }
        }

        // --- Activity ---
        if (input.activityScore != null && input.activityScore < 40f) {
            insights += CoachInsight(
                title = "Move More Today",
                body = "Your activity today is below your personal average. Even a 20-minute walk adds significant cardiovascular benefit. " +
                        "Try breaking up sitting with short movement breaks every hour.",
                type = InsightType.ACTIVITY
            )
        }

        // --- Part 11: Energy Bank insights ---
        when {
            input.energyBankScore != null && input.energyBankScore < 30f ->
                insights += CoachInsight(
                    title = "Energy Reserves Depleted ⚡",
                    body = "Your energy bank is at ${input.energyBankScore.toInt()}/100 — critically low. " +
                            "Skip intense training today and focus on sleep, light movement, and nutrition. " +
                            "Your body needs to recharge before your next quality session.",
                    type = InsightType.WARNING
                )
            input.energyBankScore != null && input.energyBankScore < 50f ->
                insights += CoachInsight(
                    title = "Energy Bank Running Low",
                    body = "Energy reserves at ${input.energyBankScore.toInt()}/100. Consider a light recovery session instead of " +
                            "high intensity. Prioritize an extra 30 minutes of sleep tonight.",
                    type = InsightType.RECOVERY
                )
            input.energyBankScore != null && input.energyBankScore >= 85f ->
                insights += CoachInsight(
                    title = "Energy Reserves Full ⚡",
                    body = "Energy bank at ${input.energyBankScore.toInt()}/100 — enough for a demanding session today " +
                            "without risking overtraining.",
                    type = InsightType.TRAINING,
                    actionable = false
                )
        }

        // --- Part 9: Muscle Recovery insights ---
        if (input.musclesFatigued >= 3) {
            insights += CoachInsight(
                title = "Multiple Muscle Groups Fatigued",
                body = "${input.musclesFatigued} muscle groups are still recovering. " +
                        "Avoid training those areas today. An active recovery session targeting unaffected groups, " +
                        "or light cardio, would be a good choice.",
                type = InsightType.TRAINING
            )
        }

        if (input.sorenessRating != null && input.sorenessRating >= 7) {
            insights += CoachInsight(
                title = "High Soreness Reported",
                body = "You rated soreness ${input.sorenessRating}/10 in your check-in. " +
                        "This level of muscle discomfort warrants a rest or very light recovery day. " +
                        "Foam rolling, gentle stretching, and adequate protein intake will help.",
                type = InsightType.WARNING
            )
        }

        // --- Biological age (shown weekly, not daily) ---
        if (input.biologicalAge != null && input.chronologicalAge != null) {
            val diff = input.biologicalAge - input.chronologicalAge
            if (diff <= -2) {
                insights += CoachInsight(
                    title = "Fitness Age Younger Than Calendar Age",
                    body = "Your fitness age is estimated at ${input.biologicalAge} — ${-diff} years younger than your actual age. " +
                            "Keep up the consistent training and sleep habits.",
                    type = InsightType.GENERAL,
                    actionable = false
                )
            }
        }

        return insights.take(5) // cap at 5 cards on home screen
    }

    fun getWeeklySummary(
        avgRecovery: Float,
        avgReadiness: Float,
        avgSleep: Float,
        deltaFromLastWeek: Float,
        topHighlights: List<String>
    ): CoachInsight {
        val direction = when {
            deltaFromLastWeek > 5f  -> "up ${deltaFromLastWeek.toInt()} points from last week 📈"
            deltaFromLastWeek < -5f -> "down ${(-deltaFromLastWeek).toInt()} points from last week 📉"
            else                    -> "stable compared to last week"
        }
        val body = buildString {
            append("This week: Recovery avg ${avgRecovery.toInt()}, Readiness avg ${avgReadiness.toInt()}, Sleep avg ${avgSleep.toInt()}. ")
            append("Overall score is $direction. ")
            topHighlights.take(2).forEach { append("$it ") }
        }
        return CoachInsight("Weekly Summary", body, InsightType.GENERAL, actionable = false)
    }

    // ── Template builders ──────────────────────────────────────────────

    private fun recoveryLow(input: CoachInput): String {
        val hrNote = if (input.restingHR != null && input.hrBaseline != null && input.restingHR > input.hrBaseline + 4)
            "Resting HR is ${input.restingHR} bpm — ${input.restingHR - input.hrBaseline} bpm above your baseline. "
        else ""
        return "${hrNote}Recovery is low today. Skip high-intensity training and focus on light movement, hydration, and quality sleep tonight."
    }

    private fun recoveryHigh(input: CoachInput): String =
        "All recovery indicators look strong. ${
            if (input.readinessScore != null && input.readinessScore > 80f) 
                "Readiness is also high at ${input.readinessScore.toInt()} — an excellent day to push hard if a workout is planned." 
            else "A good day for moderate training."
        }"

    private fun recoveryMid(input: CoachInput): String =
        "Recovery is moderate at ${input.recoveryScore?.toInt()}. A tempo run or moderate strength session is fine, " +
        "but save the high-intensity intervals for when recovery climbs above 75."

    private fun buildSleepLow(input: CoachInput): String {
        val hoursNote = if (input.todaySleepHours != null) "${input.todaySleepHours}h of sleep last night is below your need. " else ""
        return "${hoursNote}Poor sleep quality is the main drag on today's recovery. Avoid caffeine after 2pm, dim lights an hour before bed, and keep your room cool."
    }
}
