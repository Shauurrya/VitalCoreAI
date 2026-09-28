package com.example.vitalcoreai.coach

import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity

enum class PlanStatus(val label: String) {
    SUGGESTED("Suggested"), SAVED("Saved"), COMPLETED("Completed"), DISMISSED("Dismissed")
}

data class PlanActivity(
    val title: String,
    val intensity: String,
    val detail: String,
    val reasons: List<String>,
    val confidence: String,
    val evidenceDay: Long?,
    val provisional: Boolean,
    val status: PlanStatus = PlanStatus.SUGGESTED
)

data class DailyPlan(
    val day: Long,
    val focus: String,
    val activity: PlanActivity,
    val suggestedActivity: PlanActivity,
    val alternative: PlanActivity?,
    val sleepMinutes: Int,
    val sleepStatus: PlanStatus = PlanStatus.SUGGESTED,
    val hasSavedChoice: Boolean = false,
    val guidanceChanged: Boolean = false
)

/** User choices are separate from imported health records and never count as workouts. */
data class SavedDailyPlan(
    val activity: PlanActivity? = null,
    val sleepMinutes: Int? = null,
    val sleepStatus: PlanStatus = PlanStatus.SUGGESTED,
    val guidanceAtSave: String? = null
)

object DailyPlanBuilder {
    /** Compare refreshed guidance with what was offered when the user made a choice. */
    fun guidanceKey(activity: PlanActivity): String = requireNotNull(ScorePipeline.encodeTextList(listOf(
        activity.title, activity.intensity, activity.detail, activity.confidence,
        activity.evidenceDay.toString(), activity.provisional.toString()
    ) + activity.reasons))

    fun build(day: Long, scores: ComputedScoresEntity?, stale: Boolean, sleepNeedMinutes: Int,
              saved: SavedDailyPlan = SavedDailyPlan()): DailyPlan {
        val current = scores?.dateEpochDay == day && !stale
        val confidence = if (current) scores.recommendationConfidence ?: "LOW" else "LOW"
        val supported = current && scores.recommendationType != null && scores.readinessScore != null
        val reasons = if (supported) ScorePipeline.decodeTextList(scores.recommendationRationale)
            .ifEmpty { listOf("Based on your recorded scores; detailed reasons will appear after your next sync.") }
        else listOf(if (stale) "Some readings could not be refreshed. Sync your data before relying on training guidance."
            else "Today's readings are incomplete. This is a provisional, gentle option.")
        val suggestion = PlanActivity(
            title = if (supported) scores.recommendationType else "Easy movement or rest",
            intensity = if (supported) scores.recommendationIntensity ?: "Low" else "Low",
            detail = if (supported) scores.recommendationDetail ?: "Choose a session that fits how you feel."
                else "Keep things comfortable while today's data catches up. Rest is also an option.",
            reasons = reasons, confidence = confidence, evidenceDay = scores?.dateEpochDay,
            provisional = !supported || confidence !in setOf("HIGH", "MEDIUM")
        )
        val alternative = suggestion.copy(
            title = if (supported) scores.recommendationAlternative ?: "Full Rest" else "Full Rest",
            // The alternative never increases the primary recommendation's intensity.
            intensity = "Low",
            detail = "An easier alternative for today. Adjust to how you feel.",
            reasons = reasons + "You can choose a lighter option."
        ).takeIf { it.title != suggestion.title }
        val activity = saved.activity ?: suggestion
        return DailyPlan(day = day,
            focus = when {
                !supported -> "Start gently and check in"
                scores.readinessScore < 50f -> "Make room for recovery"
                else -> "Balance movement and recovery"
            },
            activity = activity, suggestedActivity = suggestion, alternative = alternative,
            sleepMinutes = saved.sleepMinutes ?: sleepNeedMinutes,
            sleepStatus = saved.sleepStatus, hasSavedChoice = saved.activity != null,
            guidanceChanged = saved.activity != null &&
                (saved.guidanceAtSave ?: guidanceKey(activity)) != guidanceKey(suggestion)
        )
    }
}
