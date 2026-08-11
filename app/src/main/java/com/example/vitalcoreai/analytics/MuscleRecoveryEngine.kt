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
    data class SessionInfo(
        val dateEpochDay: Long,
        val exerciseType: String,
        val rpe: Int,
        val muscleGroups: List<MuscleGroup>
    )

    fun getAllMuscleStatuses(
        recentSessions: List<SessionInfo>,
        todayEpochDay: Long,
        sorenessRating: Int? = null
    ): List<MuscleStatus> {
        // Build per-group latest training info
        val groupLastTrained = mutableMapOf<MuscleGroup, Pair<Long, Int>>()  // group → (epochDay, rpe)

        for (session in recentSessions.sortedBy { it.dateEpochDay }) {
            for (group in session.muscleGroups) {
                groupLastTrained[group] = session.dateEpochDay to session.rpe
            }
        }

        return MuscleGroup.values().filter { it != MuscleGroup.FULL_BODY }.map { group ->
            val (lastDay, rpe) = groupLastTrained[group] ?: (todayEpochDay - 7 to 0)
            val hoursSince = ((todayEpochDay - lastDay) * 24).toInt()
            val estimatedHours = if (rpe > 0) estimateRecoveryHours(rpe) else 0
            val status = if (rpe == 0) RecoveryStatus.READY
                else calculateStatus(hoursSince, estimatedHours, sorenessRating)

            MuscleStatus(
                group = group,
                status = status,
                hoursRemaining = maxOf(0, estimatedHours - hoursSince),
                hoursSinceTrained = hoursSince,
                lastRpe = rpe,
                sorenessRating = sorenessRating
            )
        }
    }
}
