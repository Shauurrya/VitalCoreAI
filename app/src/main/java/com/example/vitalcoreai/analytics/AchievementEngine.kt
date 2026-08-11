package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * B7 — Achievement Engine
 *
 * Evaluates a history snapshot and determines which achievements have been earned.
 * All achievements are motivational milestones — purely local, no backend, no sharing.
 *
 * ## Achievement list
 * | ID                    | Description                              | Threshold                |
 * |-----------------------|------------------------------------------|--------------------------|
 * | SLEEP_STREAK_7        | Consistent sleep — 7 days                | 7 nights ≥ personal need |
 * | SLEEP_STREAK_30       | Consistent sleep — 30 days               | 30 nights ≥ personal need|
 * | ACTIVE_STREAK_7       | Activity streak — 7 days                 | 7 days ≥ 7500 steps      |
 * | ACTIVE_STREAK_30      | Activity streak — 30 days                | 30 days ≥ 7500 steps     |
 * | PERSONAL_BEST_RECOVERY| Best recovery score ever                 | New all-time high        |
 * | LOWEST_RESTING_HR     | Personal best resting HR (lowest ever)   | New all-time low         |
 * | BEST_SLEEP_SCORE      | Best sleep score ever                    | New all-time high        |
 * | BEST_TRAINING_WEEK    | Highest training consistency week        | 5+ active days in a week |
 * | RECOVERY_90_PLUS      | Excellent recovery day                   | Recovery ≥ 90            |
 * | FIRST_SYNC            | First Health Connect sync complete       | Any data present         |
 * | WEEK_OF_DATA          | 7 days of data collected                 | ≥ 7 days of history      |
 * | MONTH_OF_DATA         | 30 days of data collected                | ≥ 30 days of history     |
 */
object AchievementEngine {

    enum class AchievementId {
        SLEEP_STREAK_7,
        SLEEP_STREAK_30,
        ACTIVE_STREAK_7,
        ACTIVE_STREAK_30,
        PERSONAL_BEST_RECOVERY,
        LOWEST_RESTING_HR,
        BEST_SLEEP_SCORE,
        BEST_TRAINING_WEEK,
        RECOVERY_90_PLUS,
        FIRST_SYNC,
        WEEK_OF_DATA,
        MONTH_OF_DATA
    }

    data class Achievement(
        val id: AchievementId,
        val title: String,
        val description: String,
        val icon: String,           // emoji — no Android resource dependency
        val earnedEpochDay: Long?,  // null = not yet earned
        val isEarned: Boolean = earnedEpochDay != null
    )

    /** All achievement definitions with their display metadata. */
    val ALL_ACHIEVEMENTS: List<Achievement> = listOf(
        Achievement(AchievementId.SLEEP_STREAK_7,       "Sleep Streak",         "7 nights meeting your sleep target", "🌙", null),
        Achievement(AchievementId.SLEEP_STREAK_30,      "Sleep Master",         "30 nights meeting your sleep target", "🏆", null),
        Achievement(AchievementId.ACTIVE_STREAK_7,      "Active Week",          "7 consecutive days reaching 7,500 steps", "🏃", null),
        Achievement(AchievementId.ACTIVE_STREAK_30,     "Activity Streak",      "30 consecutive days reaching 7,500 steps", "🔥", null),
        Achievement(AchievementId.PERSONAL_BEST_RECOVERY,"Recovery Record",     "Personal best recovery score", "💪", null),
        Achievement(AchievementId.LOWEST_RESTING_HR,    "Heart Efficiency",     "Personal best (lowest) resting heart rate", "❤️", null),
        Achievement(AchievementId.BEST_SLEEP_SCORE,     "Best Sleep Night",     "Personal best sleep score", "⭐", null),
        Achievement(AchievementId.BEST_TRAINING_WEEK,   "Training Consistency", "5+ active training days in one week", "🎯", null),
        Achievement(AchievementId.RECOVERY_90_PLUS,     "Peak Recovery",        "Recovery score of 90 or above", "🚀", null),
        Achievement(AchievementId.FIRST_SYNC,           "First Steps",          "First Health Connect sync completed", "✅", null),
        Achievement(AchievementId.WEEK_OF_DATA,         "One Week In",          "7 days of health data collected", "📊", null),
        Achievement(AchievementId.MONTH_OF_DATA,        "One Month In",         "30 days of health data collected", "📅", null)
    )

    /**
     * @param sleepByDay  epoch day → minutes slept. A **map**, not a compacted list: the
     *                    old `mapNotNull` dropped days with no record instead of breaking
     *                    the streak, so a day the watch spent on the charger silently
     *                    welded two separate streaks together. Streaks now walk the
     *                    calendar and a missing day is a break.
     * @param stepsByDay  epoch day → steps, same contract.
     * @param todayRecovery / todayRestingHR / todaySleepScore
     *                    today's values passed **explicitly**. They used to be inferred as
     *                    `recentRecoveryScores.lastOrNull()` from a date-DESC list, which
     *                    is the OLDEST of the window, not today.
     * @param priorBestRecovery / priorLowestRestingHR / priorBestSleepScore
     *                    the record **strictly before today**. These were previously
     *                    computed after today's row had already been written, so
     *                    `today > allTimeHigh` compared a value against a maximum that
     *                    included it — making PERSONAL_BEST_RECOVERY, LOWEST_RESTING_HR
     *                    and BEST_SLEEP_SCORE unreachable dead code.
     * @param totalDaysOfData a genuine COUNT(*) of days with real data, not a list size
     *                    capped by a query LIMIT.
     */
    data class AchievementInput(
        val todayEpochDay: Long,
        val personalSleepNeedMinutes: Int = 480,
        val stepGoal: Int = 7500,
        val sleepByDay: Map<Long, Int> = emptyMap(),
        val stepsByDay: Map<Long, Int> = emptyMap(),
        val todayRecovery: Float? = null,
        val todayRestingHR: Int? = null,
        val todaySleepScore: Float? = null,
        val priorBestRecovery: Float?,
        val priorLowestRestingHR: Int?,
        val priorBestSleepScore: Float?,
        val totalDaysOfData: Int
    )

    /**
     * Evaluate which achievements were newly earned today.
     * Returns only newly-earned achievements (not the full list).
     */
    fun evaluateNewlyEarned(input: AchievementInput): List<Achievement> {
        val earned = mutableListOf<Achievement>()
        val today  = input.todayEpochDay

        fun earn(id: AchievementId) {
            val template = ALL_ACHIEVEMENTS.first { it.id == id }
            earned += template.copy(earnedEpochDay = today, isEarned = true)
        }

        // Data milestones. Uses >= rather than == so a milestone is not missed when the
        // backfill loop jumps the count past the exact boundary; the DB's unique index on
        // achievementId makes re-earning a no-op.
        if (input.totalDaysOfData >= 1)  earn(AchievementId.FIRST_SYNC)
        if (input.totalDaysOfData >= 7)  earn(AchievementId.WEEK_OF_DATA)
        if (input.totalDaysOfData >= 30) earn(AchievementId.MONTH_OF_DATA)

        // Sleep streaks — walked over the calendar, a missing day breaks the streak.
        val sleepStreak = consecutiveDayStreak(input.sleepByDay, today, input.personalSleepNeedMinutes)
        if (sleepStreak >= 7)  earn(AchievementId.SLEEP_STREAK_7)
        if (sleepStreak >= 30) earn(AchievementId.SLEEP_STREAK_30)

        // Activity streaks
        val activityStreak = consecutiveDayStreak(input.stepsByDay, today, input.stepGoal)
        if (activityStreak >= 7)  earn(AchievementId.ACTIVE_STREAK_7)
        if (activityStreak >= 30) earn(AchievementId.ACTIVE_STREAK_30)

        // Personal bests — compared against the record strictly BEFORE today.
        val todayRecovery = input.todayRecovery
        if (todayRecovery != null && (input.priorBestRecovery == null || todayRecovery > input.priorBestRecovery)) {
            earn(AchievementId.PERSONAL_BEST_RECOVERY)
        }
        if (todayRecovery != null && todayRecovery >= 90f) {
            earn(AchievementId.RECOVERY_90_PLUS)
        }

        val todayHR = input.todayRestingHR
        if (todayHR != null && (input.priorLowestRestingHR == null || todayHR < input.priorLowestRestingHR)) {
            earn(AchievementId.LOWEST_RESTING_HR)
        }

        val todaySleepScore = input.todaySleepScore
        if (todaySleepScore != null &&
            (input.priorBestSleepScore == null || todaySleepScore > input.priorBestSleepScore)
        ) {
            earn(AchievementId.BEST_SLEEP_SCORE)
        }

        // Best training week: 5+ active days in the last 7 calendar days
        val activeDaysInWeek = (0..6).count { offset ->
            (input.stepsByDay[today - offset] ?: 0) >= input.stepGoal
        }
        if (activeDaysInWeek >= 5) earn(AchievementId.BEST_TRAINING_WEEK)

        return earned
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Length of the run of consecutive calendar days ending at [endDay] whose value meets
     * [threshold]. A day absent from [valuesByDay] breaks the streak — it is a day with no
     * data, not a day that quietly counts.
     */
    internal fun consecutiveDayStreak(
        valuesByDay: Map<Long, Int>,
        endDay: Long,
        threshold: Int,
        maxLookback: Int = 400
    ): Int {
        var streak = 0
        var day = endDay
        while (streak < maxLookback) {
            val value = valuesByDay[day] ?: break
            if (value < threshold) break
            streak++
            day--
        }
        return streak
    }
}
