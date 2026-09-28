package com.example.vitalcoreai.data

import android.content.Context
import android.content.SharedPreferences
import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.coach.PlanActivity
import com.example.vitalcoreai.coach.DailyPlanBuilder
import com.example.vitalcoreai.coach.PlanStatus
import com.example.vitalcoreai.coach.SavedDailyPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyPlanStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("daily_action_plans", Context.MODE_PRIVATE)

    fun observe(day: Long): Flow<SavedDailyPlan> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("$day.") == true) trySend(read(day))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(day))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun read(day: Long): SavedDailyPlan {
        val key = "$day."
        val title = prefs.getString(key + "title", null)
        fun status(field: String) = runCatching {
            PlanStatus.valueOf(prefs.getString(key + field, null) ?: "SUGGESTED")
        }.getOrDefault(PlanStatus.SUGGESTED)
        return SavedDailyPlan(
            activity = title?.let { PlanActivity(it,
                prefs.getString(key + "intensity", "Low") ?: "Low",
                prefs.getString(key + "detail", "") ?: "",
                ScorePipeline.decodeTextList(prefs.getString(key + "reasons", null)),
                prefs.getString(key + "confidence", "LOW") ?: "LOW",
                prefs.getLong(key + "evidence", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
                prefs.getBoolean(key + "provisional", true), status("activityStatus")) },
            sleepMinutes = prefs.getInt(key + "sleepMinutes", 0).takeIf { it in 240..720 },
            sleepStatus = status("sleepStatus"),
            guidanceAtSave = prefs.getString(key + "guidanceAtSave", null)
        )
    }

    fun saveActivity(day: Long, activity: PlanActivity, suggestedActivity: PlanActivity = activity) {
        val key = "$day."
        prefs.edit().putString(key + "title", activity.title)
            .putString(key + "intensity", activity.intensity).putString(key + "detail", activity.detail)
            .putString(key + "reasons", ScorePipeline.encodeTextList(activity.reasons))
            .putString(key + "confidence", activity.confidence)
            .putLong(key + "evidence", activity.evidenceDay ?: Long.MIN_VALUE)
            .putBoolean(key + "provisional", activity.provisional)
            .putString(key + "guidanceAtSave", DailyPlanBuilder.guidanceKey(suggestedActivity))
            .putString(key + "activityStatus", activity.status.name).apply()
    }

    fun saveSleep(day: Long, minutes: Int, status: PlanStatus) {
        prefs.edit().putInt("$day.sleepMinutes", minutes.coerceIn(240, 720))
            .putString("$day.sleepStatus", status.name).apply()
    }

    /** A status change does not mean the user has reviewed refreshed guidance. */
    fun setActivityStatus(day: Long, status: PlanStatus) {
        if (!prefs.contains("$day.title")) return
        prefs.edit().putString("$day.activityStatus", status.name).apply()
    }
}
