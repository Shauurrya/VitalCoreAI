package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.roundToInt

/**
 * Priority 8 — Smart Recommendations 2.0.
 *
 * The previous recommendation surface answered one question: train or rest. That is not
 * actionable enough to change a session. This engine answers four:
 *
 * - **What kind** of session ([WorkoutType])
 * - **How hard** ([Intensity])
 * - **How much** ([volumeAdjustmentPercent])
 * - **Which muscles** ([readyGroups] / [avoidGroups])
 *
 * plus concrete recovery actions when the answer is "not much".
 *
 * ## Deterministic and transparent
 *
 * Every recommendation carries [rationale] — the specific numbers that produced it. This
 * is a rules engine, not a model: given the same inputs it always returns the same output,
 * which is what makes it safe to hand to an LLM as ground truth rather than asking the LLM
 * to invent training advice.
 *
 * ## Safety
 *
 * Nothing here is a medical instruction. Recommendations are framed as suggestions, and a
 * hard cap applies whenever the data says the user is under-recovered: the engine will
 * never recommend high intensity on a low-readiness day regardless of what other signals
 * say.
 */
object RecommendationEngine {

    enum class WorkoutType(val displayName: String, val description: String) {
        REST("Full Rest", "No structured training today"),
        MOBILITY("Mobility & Stretching", "Light range-of-motion work, 15–30 min"),
        WALK("Easy Walk", "Low-intensity movement, 20–45 min"),
        ZONE2("Zone 2 Cardio", "Conversational-pace aerobic work"),
        RUN("Run", "Steady-state running"),
        STRENGTH("Strength", "Heavy, low-rep compound work"),
        HYPERTROPHY("Hypertrophy", "Moderate load, higher volume"),
        INTERVALS("Intervals", "High-intensity intervals with recovery"),
        ACTIVE_RECOVERY("Active Recovery", "Very light movement to aid circulation")
    }

    enum class Intensity(val displayName: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High") }

    enum class BodyRegion(val displayName: String) {
        UPPER("Upper Body"), LOWER("Lower Body"), CORE("Core"), FULL("Full Body")
    }

    /**
     * @param volumeAdjustmentPercent signed change vs the user's normal volume for this
     *                                session type. −20 means "about 20% less than usual".
     * @param rationale               the numbers behind the call, one clause per driver
     * @param recoveryActions         concrete non-training actions
     * @param confidence              inherited from the data the decision rests on
     */
    data class Recommendation(
        val primaryType: WorkoutType,
        val alternativeType: WorkoutType?,
        val intensity: Intensity,
        val volumeAdjustmentPercent: Int,
        val readyGroups: List<MuscleRecoveryEngine.MuscleGroup>,
        val avoidGroups: List<MuscleRecoveryEngine.MuscleGroup>,
        val readyRegions: List<BodyRegion>,
        val avoidRegions: List<BodyRegion>,
        val headline: String,
        val detail: String,
        val rationale: List<String>,
        val recoveryActions: List<String>,
        val confidence: Confidence
    ) {
        /** "Upper-body strength · Moderate intensity" — the Home screen's TODAY line. */
        val summaryLine: String
            get() {
                val region = readyRegions.firstOrNull()?.displayName
                val base = if (region != null && primaryType !in RESTFUL_TYPES)
                    "$region ${primaryType.displayName.lowercase()}"
                else primaryType.displayName
                return if (primaryType in RESTFUL_TYPES) base
                else "$base · ${intensity.displayName} intensity"
            }
    }

    private val RESTFUL_TYPES = setOf(
        WorkoutType.REST, WorkoutType.MOBILITY, WorkoutType.ACTIVE_RECOVERY, WorkoutType.WALK
    )

    /**
     * @param goal user's stated training emphasis; nudges type selection but never overrides
     *             a recovery-driven cap.
     */
    enum class Goal { GENERAL_FITNESS, STRENGTH, ENDURANCE, WEIGHT_LOSS }

    data class RecommendationInput(
        val readiness: Float?,
        val recovery: Float?,
        val energyBank: Float?,
        val acwrZone: String?,
        val acwrIsMeaningful: Boolean = false,
        val muscleStatuses: List<MuscleRecoveryEngine.MuscleStatus> = emptyList(),
        val consecutiveTrainingDays: Int = 0,
        val daysSinceLastWorkout: Int? = null,
        val sleepDebtMinutes: Int = 0,
        val lastNightSleepMinutes: Int? = null,
        val checkInSoreness: Int? = null,
        val checkInEnergy: Int? = null,
        val restingHRDeviationBpm: Int? = null,
        val goal: Goal = Goal.GENERAL_FITNESS,
        val dataQuality: DataQualityReport = DataQualityReport.UNKNOWN
    )

    // ── Thresholds ───────────────────────────────────────────────────────────

    private const val READINESS_REST = 35f
    private const val READINESS_LIGHT = 50f
    private const val READINESS_MODERATE = 68f
    private const val READINESS_HIGH = 80f

    // ── Public API ───────────────────────────────────────────────────────────

    fun recommend(input: RecommendationInput): Recommendation {
        val rationale = mutableListOf<String>()
        val recoveryActions = mutableListOf<String>()

        // ── Muscle availability ──────────────────────────────────────────────
        val ready = input.muscleStatuses
            .filter { it.status == MuscleRecoveryEngine.RecoveryStatus.READY }
            .map { it.group }
        val avoid = input.muscleStatuses
            .filter { it.status == MuscleRecoveryEngine.RecoveryStatus.FATIGUED }
            .map { it.group }

        val readyRegions = regionsOf(ready)
        val avoidRegions = regionsOf(avoid)

        // ── Base tier from readiness ─────────────────────────────────────────
        // Readiness is the primary gate. When it is absent the engine deliberately drops to
        // a conservative default rather than assuming the user is fine — an unmeasured day
        // is not a good day.
        val readiness = input.readiness
        var tier = when {
            readiness == null -> Tier.LIGHT
            readiness < READINESS_REST -> Tier.REST
            readiness < READINESS_LIGHT -> Tier.LIGHT
            readiness < READINESS_MODERATE -> Tier.MODERATE
            readiness < READINESS_HIGH -> Tier.SOLID
            else -> Tier.HARD
        }
        rationale += if (readiness != null)
            "Readiness ${readiness.roundToInt()}/100"
        else
            "No readiness score today — defaulting to a lighter session"

        // ── Downgrades. Each can only ever reduce the tier, never raise it. ──
        if (input.acwrIsMeaningful && input.acwrZone == "DANGER") {
            tier = tier.atMost(Tier.LIGHT)
            rationale += "Training load has spiked well above your 28-day baseline"
        } else if (input.acwrIsMeaningful && input.acwrZone == "CAUTION") {
            tier = tier.atMost(Tier.MODERATE)
            rationale += "Training load is climbing above your usual range"
        }

        if (input.consecutiveTrainingDays >= 5) {
            tier = tier.atMost(Tier.LIGHT)
            rationale += "${input.consecutiveTrainingDays} consecutive training days"
        } else if (input.consecutiveTrainingDays >= 3) {
            tier = tier.atMost(Tier.MODERATE)
            rationale += "${input.consecutiveTrainingDays} consecutive training days"
        }

        input.checkInSoreness?.let {
            if (it >= 8) {
                tier = tier.atMost(Tier.RECOVERY)
                rationale += "You reported soreness $it/10"
            } else if (it >= 6) {
                tier = tier.atMost(Tier.MODERATE)
                rationale += "You reported soreness $it/10"
            }
        }

        input.restingHRDeviationBpm?.let {
            if (it >= 7) {
                tier = tier.atMost(Tier.LIGHT)
                rationale += "Resting HR is $it bpm above your baseline"
            } else if (it >= 4) {
                tier = tier.atMost(Tier.MODERATE)
                rationale += "Resting HR is $it bpm above your baseline"
            }
        }

        if (input.sleepDebtMinutes >= 240) {
            tier = tier.atMost(Tier.MODERATE)
            rationale += "${input.sleepDebtMinutes / 60}h of accumulated sleep debt"
        }

        input.energyBank?.let {
            if (it < 30f) {
                tier = tier.atMost(Tier.RECOVERY)
                rationale += "Energy Bank at ${it.roundToInt()}/100"
            } else if (it < 50f) {
                tier = tier.atMost(Tier.MODERATE)
                rationale += "Energy Bank at ${it.roundToInt()}/100"
            }
        }

        // ── One upgrade path: genuine under-training ─────────────────────────
        // Applied last and only from a already-permissive tier, so it can never override a
        // recovery signal.
        if (input.acwrIsMeaningful && input.acwrZone == "UNDER_TRAINING" &&
            (input.daysSinceLastWorkout ?: 0) >= 3 && tier >= Tier.MODERATE
        ) {
            rationale += "${input.daysSinceLastWorkout} days since your last session, " +
                    "and recovery supports training"
        }

        // ── Type, intensity, volume ──────────────────────────────────────────
        val (type, alt) = chooseType(tier, input, readyRegions, avoidRegions)
        val intensity = when (tier) {
            Tier.REST, Tier.RECOVERY, Tier.LIGHT -> Intensity.LOW
            Tier.MODERATE, Tier.SOLID -> Intensity.MODERATE
            Tier.HARD -> Intensity.HIGH
        }
        val volumeAdjust = volumeAdjustment(tier, input)

        // ── Recovery actions ─────────────────────────────────────────────────
        if (input.sleepDebtMinutes >= 60) {
            recoveryActions += "Aim for an extra ${(input.sleepDebtMinutes / 2).coerceAtMost(90)} " +
                    "minutes of sleep tonight to start clearing your debt."
        }
        if (tier <= Tier.LIGHT) {
            recoveryActions += "A 20–30 minute easy walk supports recovery better than complete inactivity."
        }
        if ((input.checkInSoreness ?: 0) >= 6) {
            recoveryActions += "Gentle mobility work on the sore areas, not through them."
        }
        if ((input.restingHRDeviationBpm ?: 0) >= 5) {
            recoveryActions += "Keep fluids up and favour an earlier night while resting HR is elevated."
        }
        if (recoveryActions.isEmpty() && tier >= Tier.SOLID) {
            recoveryActions += "Keep your usual post-session routine — protein and a consistent bedtime."
        }

        return Recommendation(
            primaryType = type,
            alternativeType = alt,
            intensity = intensity,
            volumeAdjustmentPercent = volumeAdjust,
            readyGroups = ready,
            avoidGroups = avoid,
            readyRegions = readyRegions,
            avoidRegions = avoidRegions,
            headline = type.displayName,
            detail = buildDetail(type, intensity, volumeAdjust, readyRegions, avoidRegions),
            rationale = rationale,
            recoveryActions = recoveryActions.take(3),
            confidence = input.dataQuality.level
        )
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Ordered so `atMost` and `>=` express "no harder than" and "at least as hard as". */
    private enum class Tier { REST, RECOVERY, LIGHT, MODERATE, SOLID, HARD;

        fun atMost(cap: Tier): Tier = if (this.ordinal > cap.ordinal) cap else this
    }

    private fun chooseType(
        tier: Tier,
        input: RecommendationInput,
        readyRegions: List<BodyRegion>,
        avoidRegions: List<BodyRegion>
    ): Pair<WorkoutType, WorkoutType?> = when (tier) {
        Tier.REST -> WorkoutType.REST to WorkoutType.MOBILITY
        Tier.RECOVERY -> WorkoutType.ACTIVE_RECOVERY to WorkoutType.MOBILITY
        Tier.LIGHT -> WorkoutType.WALK to WorkoutType.MOBILITY
        Tier.MODERATE -> when (input.goal) {
            Goal.STRENGTH -> WorkoutType.HYPERTROPHY to WorkoutType.ZONE2
            Goal.ENDURANCE -> WorkoutType.ZONE2 to WorkoutType.WALK
            Goal.WEIGHT_LOSS -> WorkoutType.ZONE2 to WorkoutType.HYPERTROPHY
            Goal.GENERAL_FITNESS -> WorkoutType.ZONE2 to WorkoutType.HYPERTROPHY
        }
        Tier.SOLID -> when (input.goal) {
            Goal.STRENGTH -> WorkoutType.STRENGTH to WorkoutType.HYPERTROPHY
            Goal.ENDURANCE -> WorkoutType.RUN to WorkoutType.ZONE2
            Goal.WEIGHT_LOSS -> WorkoutType.HYPERTROPHY to WorkoutType.ZONE2
            Goal.GENERAL_FITNESS -> WorkoutType.HYPERTROPHY to WorkoutType.RUN
        }
        Tier.HARD -> when (input.goal) {
            Goal.STRENGTH -> WorkoutType.STRENGTH to WorkoutType.INTERVALS
            Goal.ENDURANCE -> WorkoutType.INTERVALS to WorkoutType.RUN
            Goal.WEIGHT_LOSS -> WorkoutType.INTERVALS to WorkoutType.HYPERTROPHY
            Goal.GENERAL_FITNESS -> WorkoutType.INTERVALS to WorkoutType.STRENGTH
        }
    }

    /**
     * Volume advice as a signed percentage against the user's own normal.
     *
     * Deliberately coarse — 10% steps. A recommendation of "reduce volume by 17%" would
     * imply a precision the inputs cannot support.
     */
    private fun volumeAdjustment(tier: Tier, input: RecommendationInput): Int {
        var pct = when (tier) {
            Tier.REST -> -100
            Tier.RECOVERY -> -70
            Tier.LIGHT -> -40
            Tier.MODERATE -> -20
            Tier.SOLID -> 0
            Tier.HARD -> +10
        }
        if (input.consecutiveTrainingDays >= 4 && pct > -60) pct -= 10
        if ((input.checkInEnergy ?: 5) >= 8 && pct < 0) pct += 10
        return pct.coerceIn(-100, 20)
    }

    private fun regionsOf(groups: List<MuscleRecoveryEngine.MuscleGroup>): List<BodyRegion> {
        val g = groups.toSet()
        val upper = setOf(
            MuscleRecoveryEngine.MuscleGroup.CHEST, MuscleRecoveryEngine.MuscleGroup.BACK,
            MuscleRecoveryEngine.MuscleGroup.SHOULDERS, MuscleRecoveryEngine.MuscleGroup.BICEPS,
            MuscleRecoveryEngine.MuscleGroup.TRICEPS
        )
        val lower = setOf(
            MuscleRecoveryEngine.MuscleGroup.QUADS, MuscleRecoveryEngine.MuscleGroup.HAMSTRINGS,
            MuscleRecoveryEngine.MuscleGroup.GLUTES, MuscleRecoveryEngine.MuscleGroup.CALVES
        )
        return buildList {
            // A region counts as available only when most of it is, not when one muscle is.
            if (g.intersect(upper).size >= 3) add(BodyRegion.UPPER)
            if (g.intersect(lower).size >= 3) add(BodyRegion.LOWER)
            if (MuscleRecoveryEngine.MuscleGroup.CORE in g) add(BodyRegion.CORE)
            if (size >= 2) add(BodyRegion.FULL)
        }
    }

    private fun buildDetail(
        type: WorkoutType,
        intensity: Intensity,
        volumePct: Int,
        readyRegions: List<BodyRegion>,
        avoidRegions: List<BodyRegion>
    ): String = buildString {
        append(type.description)
        append(". ")
        if (type !in RESTFUL_TYPES) {
            append("${intensity.displayName} intensity. ")
            when {
                volumePct <= -30 -> append("Cut volume to roughly ${100 + volumePct}% of your usual. ")
                volumePct < 0 -> append("Reduce volume by about ${-volumePct}%. ")
                volumePct > 0 -> append("There is room for about ${volumePct}% more volume than usual. ")
                else -> append("Normal volume. ")
            }
        }
        if (readyRegions.isNotEmpty()) {
            append("Ready: ${readyRegions.joinToString(", ") { it.displayName }}. ")
        }
        if (avoidRegions.isNotEmpty()) {
            append("Still recovering: ${avoidRegions.joinToString(", ") { it.displayName }}.")
        }
    }
}
