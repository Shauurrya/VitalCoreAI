package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY

/**
 * Part 9 — Muscle Recovery Engine
 *
 * Tracks per-muscle-group recovery status based on:
 * - Time since last trained
 * - Training volume and intensity (RPE)
 * - User-reported soreness (from check-in)
 * - Exercise type → muscle group mapping
 *
 * Recovery time estimates use evidence-based ranges:
 * - Light session (RPE 1-4): 24-36h
 * - Moderate session (RPE 5-7): 48-72h
 * - Heavy session (RPE 8-10): 72-96h
 *
 * These are baseline estimates — actual recovery depends on sleep, nutrition,
 * age, training experience, etc.
 */
object MuscleRecoveryEngine {

    /** Supported muscle groups. */
    enum class MuscleGroup(val displayName: String) {
        CHEST("Chest"),
        BACK("Back"),
        SHOULDERS("Shoulders"),
        BICEPS("Biceps"),
        TRICEPS("Triceps"),
        QUADS("Quadriceps"),
        HAMSTRINGS("Hamstrings"),
        GLUTES("Glutes"),
        CALVES("Calves"),
        CORE("Core"),
        FULL_BODY("Full Body")
    }

    /** Recovery status for a single muscle group. */
    data class MuscleStatus(
        val group: MuscleGroup,
        val status: RecoveryStatus,
        val hoursRemaining: Int,       // estimated hours until fully recovered
        val hoursSinceTrained: Int,
        val lastRpe: Int,
        val sorenessRating: Int?       // from check-in, null if not reported
    )

    enum class RecoveryStatus(val label: String) {
        READY("Ready to Train"),
        RECOVERING("Recovering"),
        FATIGUED("Fatigued — Rest")
    }

    /** Map exercise types (from Health Connect) to primary muscle groups. */
    fun exerciseToMuscleGroups(exerciseType: String): List<MuscleGroup> {
        val type = exerciseType.uppercase()
        return when {
            type.contains("BENCH") || type.contains("PUSH_UP") || type.contains("CHEST") ->
                listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
            type.contains("DEADLIFT") || type.contains("ROW") || type.contains("PULL") ->
                listOf(MuscleGroup.BACK, MuscleGroup.BICEPS)
            type.contains("SQUAT") || type.contains("LEG_PRESS") ->
                listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)
            type.contains("LUNGE") ->
                listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS)
            type.contains("SHOULDER") || type.contains("OVERHEAD") ->
                listOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS)
            type.contains("CURL") ->
                listOf(MuscleGroup.BICEPS)
            type.contains("TRICEP") || type.contains("DIP") ->
                listOf(MuscleGroup.TRICEPS)
            type.contains("CALF") ->
                listOf(MuscleGroup.CALVES)
            type.contains("PLANK") || type.contains("CRUNCH") || type.contains("SIT_UP") || type.contains("AB") ->
                listOf(MuscleGroup.CORE)
            type.contains("RUN") || type.contains("JOG") ->
                listOf(MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES, MuscleGroup.GLUTES)
            type.contains("BIKE") || type.contains("CYCL") ->
                listOf(MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES)
            type.contains("SWIM") ->
                listOf(MuscleGroup.BACK, MuscleGroup.SHOULDERS, MuscleGroup.CORE)
            type.contains("YOGA") || type.contains("PILATES") ->
                listOf(MuscleGroup.FULL_BODY, MuscleGroup.CORE)
            type.contains("STRENGTH") || type.contains("WEIGHT") || type.contains("GYM") ->
                listOf(MuscleGroup.FULL_BODY)
            type.contains("WALK") || type.contains("HIKE") ->
                listOf(MuscleGroup.QUADS, MuscleGroup.CALVES, MuscleGroup.GLUTES)
            else -> listOf(MuscleGroup.FULL_BODY)
        }
    }

    /**
     * Estimate recovery hours based on RPE.
     */
    fun estimateRecoveryHours(rpe: Int): Int = when {
        rpe <= 4 -> 30    // Light: ~30h
        rpe <= 7 -> 60    // Moderate: ~60h (2.5 days)
        rpe <= 9 -> 84    // Heavy: ~84h (3.5 days)
        else     -> 96    // Max effort: ~96h (4 days)
    }

    /**
     * Calculate current status for a muscle group.
     *
     * @param hoursSinceTrained  hours since last trained
     * @param estimatedRecoveryHours  total estimated recovery time
     * @param sorenessRating  1-10, from user check-in (null = not reported)
     */
    fun calculateStatus(
        hoursSinceTrained: Int,
        estimatedRecoveryHours: Int,
        sorenessRating: Int? = null
    ): RecoveryStatus {
        // Soreness override: if user reports 7+ soreness, they're fatigued regardless
        if (sorenessRating != null && sorenessRating >= 7) return RecoveryStatus.FATIGUED

        val recoveryPercent = (hoursSinceTrained.toFloat() / estimatedRecoveryHours.toFloat())
            .coerceIn(0f, 2f)

        return when {
            recoveryPercent >= 1.0f -> RecoveryStatus.READY
            recoveryPercent >= 0.5f -> RecoveryStatus.RECOVERING
            else -> RecoveryStatus.FATIGUED
        }
    }

    /**
     * Get current status for all muscle groups.
     *
     * @param recentSessions  list of (epochDay, exerciseType, rpe, muscleGroups)
     * @param todayEpochDay   current day
     * @param sorenessRating  from today's check-in
     */
    /**
     * @param endMinuteOfDay when the session finished, minutes past local midnight.
     *                       Defaults to noon so existing callers keep working; supplying it
     *                       makes "hours since trained" accurate to the hour instead of
     *                       rounding every session to a whole day.
     * @param volumeLoad     sets × reps × weight where known. Reserved for future
     *                       volume-sensitive estimates; not yet weighted into the model,
     *                       because there is no honest coefficient for it until enough
     *                       users log volume consistently.
     */
    data class SessionInfo(
        val dateEpochDay: Long,
        val exerciseType: String,
        val rpe: Int,
        val muscleGroups: List<MuscleGroup>,
        val endMinuteOfDay: Int = 12 * 60,
        val volumeLoad: Float = 0f
    )

    /**
     * Every concrete group a [MuscleGroup.FULL_BODY] session actually loads.
     *
     * ## The bug this fixes
     *
     * [exerciseToMuscleGroups] maps "STRENGTH", "WEIGHT", "GYM" — and, via its `else`
     * branch, *every unrecognised exercise type* — to [MuscleGroup.FULL_BODY]. Health
     * Connect's generic `EXERCISE_TYPE_STRENGTH_TRAINING` is what Samsung Health writes
     * for most gym sessions, so FULL_BODY was the common case, not the rare one.
     *
     * [getAllMuscleStatuses] then filtered FULL_BODY out of its output. The result: a user
     * could complete a punishing full-body session and every muscle group would still
     * report READY, because the only group the session trained was the one group excluded
     * from the report. Expanding it here means a full-body session loads every group, which
     * is what a full-body session does.
     */
    private val FULL_BODY_EXPANSION: List<MuscleGroup> =
        MuscleGroup.values().filter { it != MuscleGroup.FULL_BODY }

    /**
     * Learned recovery time for one muscle group, derived from the user's own history.
     *
     * @param hoursP50   median observed gap before this group was trained again *and* the
     *                   user did not report elevated soreness
     * @param observations how many completed recovery cycles this was learned from
     */
    data class LearnedRecovery(
        val group: MuscleGroup,
        val hoursP50: Int,
        val observations: Int
    ) {
        /** Below this the estimate is not trusted and the RPE default is used instead. */
        val isReliable: Boolean get() = observations >= MIN_OBSERVATIONS_TO_LEARN
    }

    /** Personal recovery timing needs this many observed cycles before it overrides RPE defaults. */
    const val MIN_OBSERVATIONS_TO_LEARN = 4

    /**
     * Learn per-group recovery times from training history.
     *
     * Method: for each group, collect the gaps between consecutive sessions that the user
     * *chose* to take, keeping only gaps where the follow-up session was not preceded by a
     * high soreness report. Those are the intervals after which this user was, in practice,
     * ready to train again. The median of those gaps is the personal estimate.
     *
     * This is a behavioural estimate learned from what the user actually did, not a
     * physiological measurement, and the UI must say so.
     */
    fun learnRecoveryTimes(
        sessions: List<SessionInfo>,
        sorenessByDay: Map<Long, Int> = emptyMap()
    ): Map<MuscleGroup, LearnedRecovery> {
        val byGroup = mutableMapOf<MuscleGroup, MutableList<Double>>()

        val expanded = sessions.sortedBy { it.dateEpochDay }.map { s ->
            s to s.muscleGroups.flatMap { g ->
                if (g == MuscleGroup.FULL_BODY) FULL_BODY_EXPANSION else listOf(g)
            }.distinct()
        }

        for (group in FULL_BODY_EXPANSION) {
            val days = expanded.filter { group in it.second }.map { it.first.dateEpochDay }.distinct().sorted()
            for (i in 1 until days.size) {
                val gapDays = days[i] - days[i - 1]
                // Ignore same-day repeats and implausibly long gaps (those are breaks from
                // training, not recovery windows).
                if (gapDays in 1..10) {
                    val soreOnReturn = (sorenessByDay[days[i]] ?: 0) >= 7
                    if (!soreOnReturn) {
                        byGroup.getOrPut(group) { mutableListOf() }.add(gapDays * 24.0)
                    }
                }
            }
        }

        return byGroup.mapValues { (group, gaps) ->
            LearnedRecovery(
                group = group,
                hoursP50 = (RobustStats.median(gaps) ?: 48.0).toInt(),
                observations = gaps.size
            )
        }
    }

    /**
     * Current status for every muscle group.
     *
     * @param sorenessRating today's overall soreness from the check-in. Applied only to
     *                       groups trained recently enough to plausibly be the cause — a
     *                       single global figure was previously forcing *every* group to
     *                       FATIGUED, including ones untrained for a week.
     * @param learned        personal recovery times from [learnRecoveryTimes]; groups
     *                       without a reliable estimate fall back to the RPE default.
     * @param nowMinuteOfDay minutes past midnight, so "trained this morning" and "trained
     *                       last night" are not both reported as zero hours ago.
     */
    fun getAllMuscleStatuses(
        recentSessions: List<SessionInfo>,
        todayEpochDay: Long,
        sorenessRating: Int? = null,
        learned: Map<MuscleGroup, LearnedRecovery> = emptyMap(),
        nowMinuteOfDay: Int = 12 * 60
    ): List<MuscleStatus> {
        // group → (epochDay, rpe, minuteOfDay)
        val groupLastTrained = mutableMapOf<MuscleGroup, Triple<Long, Int, Int>>()

        for (session in recentSessions.sortedBy { it.dateEpochDay }) {
            val groups = session.muscleGroups.flatMap { g ->
                if (g == MuscleGroup.FULL_BODY) FULL_BODY_EXPANSION else listOf(g)
            }.distinct()
            for (group in groups) {
                groupLastTrained[group] = Triple(
                    session.dateEpochDay, session.rpe, session.endMinuteOfDay
                )
            }
        }

        return FULL_BODY_EXPANSION.map { group ->
            val entry = groupLastTrained[group]

            if (entry == null) {
                // Never trained in the window: genuinely ready, and honest about why.
                return@map MuscleStatus(
                    group = group,
                    status = RecoveryStatus.READY,
                    hoursRemaining = 0,
                    hoursSinceTrained = Int.MAX_VALUE,
                    lastRpe = 0,
                    sorenessRating = null
                )
            }

            val (lastDay, rpe, endMinute) = entry
            // Real elapsed hours, not whole days. A session that finished at 20:00 last
            // night is 14 hours ago at 10:00 today, not 24.
            val hoursSince = (((todayEpochDay - lastDay) * 24 * 60) + (nowMinuteOfDay - endMinute))
                .toInt().coerceAtLeast(0) / 60

            val learnedForGroup = learned[group]?.takeIf { it.isReliable }
            val estimatedHours = learnedForGroup?.hoursP50
                ?: if (rpe > 0) estimateRecoveryHours(rpe) else estimateRecoveryHours(5)

            // Soreness is attributed only to groups trained inside their own recovery
            // window. Applying today's overall soreness to a group last trained nine days
            // ago blamed the wrong muscle and hid the real one.
            val applicableSoreness = sorenessRating?.takeIf { hoursSince <= estimatedHours }

            MuscleStatus(
                group = group,
                status = calculateStatus(hoursSince, estimatedHours, applicableSoreness),
                hoursRemaining = maxOf(0, estimatedHours - hoursSince),
                hoursSinceTrained = hoursSince,
                lastRpe = rpe,
                sorenessRating = applicableSoreness
            )
        }
    }

    /**
     * Plain-language line for a learned estimate.
     * Deliberately hedged: this is a pattern from the user's own logs, not physiology.
     */
    fun describeLearned(learned: LearnedRecovery): String {
        val lo = (learned.hoursP50 * 0.85).toInt()
        val hi = (learned.hoursP50 * 1.15).toInt()
        return "Your ${learned.group.displayName.lowercase()} typically needs around " +
                "$lo–$hi hours after a hard session, based on ${learned.observations} of " +
                "your own training cycles."
    }
}
