package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.model.SleepData
import com.example.vitalcoreai.data.model.TrainingLoadData

/**
 * B2 — Improvement Simulator ("What if?" projections)
 *
 * Produces plain-language projections of how the recovery score would change
 * if specific inputs were nudged. Uses the *actual* weighted formula from
 * [RecoveryScoreCalculator] — not new heuristics.
 *
 * Design principles:
 * - All projections are clearly labelled as estimates (not predictions).
 * - All nudges are applied to the same function that produces the real score.
 * - Only nudges that the user can actually control are offered.
 *
 * Usage:
 * ```kotlin
 * val sims = ImprovementSimulator.simulate(
 *     currentSleep = todaySleep, sleepBaseline = ...,
 *     currentHR = todayHR, hrBaseline = ...,
 *     priorLoad = null, currentScore = 62f
 * )
 * // Returns list of Suggestion("Sleep 45 min more", projectedDelta = +8)
 * ```
 */
object ImprovementSimulator {

    /** A single "what if?" projection card. */
    data class Suggestion(
        val action: String,         // e.g. "Sleep 45 minutes more"
        val projectedDelta: Int,    // estimated score change (+N or -N)
        val projectedScore: Float,  // current + delta
        val explanation: String,    // why this helps
        val category: SuggestionCategory
    )

    enum class SuggestionCategory { SLEEP, HEART_RATE, TRAINING, ACTIVITY }

    /**
     * Generate improvement suggestions based on current inputs.
     * Suggestions are only included if the projected delta is ≥ 2 points.
     *
     * ## Deltas are measured against a matched control, not the stored score
     * Every projection is `nudgedScore − controlScore`, where the control is computed
     * *inside* this function with **exactly the same call signature** as each nudge.
     *
     * Taking deltas against the stored recovery score was a category error: the stored
     * score is computed with the real SpO₂ value and the real quality input, while every
     * nudged score here passes `spO2Percent = null` and a synthesised quality input. The
     * SpO₂ modifier alone is ±5 points, so a user at ≥98% saw every projection understated
     * by 3, and a user below 90% saw a fabricated +5 "improvement" from any nudge —
     * including the no-op ones.
     *
     * @param currentScore retained for [Suggestion.projectedScore] so the card still shows
     *                     a number consistent with what the user sees elsewhere.
     */
    fun simulate(
        currentSleep: SleepData,
        sleepBaseline14: List<SleepData>,
        currentHR: RestingHRData,
        hrBaseline30: List<RestingHRData>,
        priorLoad: TrainingLoadData?,
        currentScore: Float,
        personalSleepNeedMinutes: Int = 480
    ): List<Suggestion> {
        val suggestions = mutableListOf<Suggestion>()

        // The control: same arguments as every nudge, differing only in the nudged field.
        fun scoreWith(sleep: SleepData, load: TrainingLoadData?): Float =
            RecoveryScoreCalculator.calculate(
                todaySleep = sleep,
                sleepBaseline14Days = sleepBaseline14,
                todayRestingHR = currentHR,
                restingHRBaseline30Days = hrBaseline30,
                priorDayTrainingLoad = load,
                personalSleepNeedMinutes = personalSleepNeedMinutes
            ).score

        val controlScore = scoreWith(currentSleep, priorLoad)

        fun addIfMeaningful(
            nudgedScore: Float,
            action: String,
            explanation: String,
            category: SuggestionCategory
        ) {
            val delta = (nudgedScore - controlScore).toInt()
            if (delta >= 2) {
                suggestions += Suggestion(
                    action = action,
                    projectedDelta = delta,
                    projectedScore = (currentScore + delta).coerceIn(0f, 100f),
                    explanation = explanation,
                    category = category
                )
            }
        }

        // ── Sleep duration nudges ─────────────────────────────────────────────
        // These were dead code until duration was wired into the recovery formula: the
        // recomputed score was bit-identical, so the delta was always 0 and the `>= 2`
        // gate rejected all three. Recovery now reads duration, so the most actionable
        // suggestion the app can give ("sleep 45 minutes longer") can finally fire.
        val sleepWeightPercent = (RecoveryScoreCalculator.Weights().sleepQuality * 100).toInt()
        listOf(30, 45, 60).forEach { extraMin ->
            addIfMeaningful(
                nudgedScore = scoreWith(
                    currentSleep.copy(durationMinutes = currentSleep.durationMinutes + extraMin),
                    priorLoad
                ),
                action = "Sleep ${extraMin} minutes longer",
                explanation = "Sleep carries $sleepWeightPercent% of your recovery score, and " +
                    "duration is the larger half of it. Another ${extraMin}min moves you closer " +
                    "to your ${personalSleepNeedMinutes / 60}h target.",
                category = SuggestionCategory.SLEEP
            )
        }

        // ── Sleep efficiency nudge ────────────────────────────────────────────
        // Only offered when efficiency is actually known for tonight.
        val efficiency = currentSleep.efficiencyPercent
        if (efficiency != null && efficiency < 88.0) {
            addIfMeaningful(
                nudgedScore = scoreWith(
                    currentSleep.copy(efficiencyPercent = (efficiency + 5.0).coerceAtMost(100.0)),
                    priorLoad
                ),
                action = "Improve sleep quality (less alcohol, less screen time before bed)",
                explanation = "A 5-point efficiency gain — achievable through a consistent " +
                    "wind-down routine — would lift your sleep quality score.",
                category = SuggestionCategory.SLEEP
            )
        }

        // ── Rest day (remove training load) ──────────────────────────────────
        if (priorLoad != null && priorLoad.normalizedLoad > 0.5f) {
            val trainingWeightPercent = (RecoveryScoreCalculator.Weights().trainingLoad * 100).toInt()
            addIfMeaningful(
                nudgedScore = scoreWith(currentSleep, null),
                action = "Take a full rest day after yesterday's hard session",
                explanation = "Removing prior-day training load ($trainingWeightPercent% weight) " +
                    "would allow more complete recovery.",
                category = SuggestionCategory.TRAINING
            )
        }

        // Sort by largest projected delta first
        return suggestions.sortedByDescending { it.projectedDelta }.take(4)
    }
}
