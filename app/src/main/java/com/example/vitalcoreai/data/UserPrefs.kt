package com.example.vitalcoreai.data

import android.content.Context
import kotlin.math.roundToInt

/**
 * Shared reader for the user profile settings that
 * [com.example.vitalcoreai.ui.viewmodel.SettingsViewModel] writes to SharedPreferences.
 *
 * Anything that needs the user's real age, max HR, sleep need or step goal (sync workers,
 * the scoring engine, coach insights, biological age) reads through here rather than
 * hardcoding a default, so a value the user configured in Settings is never silently
 * ignored.
 *
 * ## Types must match what SettingsViewModel writes
 * `sleep_need_hours` is written with **putFloat** as a value in HOURS. Reading it with
 * `getInt` throws ClassCastException at runtime on any device where the user has touched
 * the setting — so [sleepNeedMinutes] uses `getFloat` and converts. Do not "simplify" it.
 */
object UserPrefs {
    const val PREFS_NAME = "vitalcore_settings"

    private const val KEY_AGE = "user_age"
    private const val KEY_MAX_HR = "user_max_hr"
    private const val KEY_SLEEP_NEED_HOURS = "sleep_need_hours"
    private const val KEY_STEP_GOAL = "step_goal"
    private const val KEY_MAX_HR_AUTO = "max_hr_auto_derived"
    private const val KEY_TRAINING_GOAL = "training_goal"
    private const val KEY_ONBOARDING_DONE = "onboarding_complete_v2"
    private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
    private const val KEY_COACH_REMOTE_OPT_IN = "coach_remote_opt_in"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_CHECK_IN_REMINDER = "check_in_reminder_enabled"
    private const val KEY_USER_NAME = "user_name"

    const val DEFAULT_AGE = 30
    const val DEFAULT_MAX_HR = 190
    const val DEFAULT_SLEEP_NEED_HOURS = 8f
    const val DEFAULT_STEP_GOAL = 7500

    /** Matches `RecommendationEngine.Goal`. Stored by name so the enum can be reordered safely. */
    const val DEFAULT_TRAINING_GOAL = "GENERAL_FITNESS"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun age(context: Context): Int = prefs(context).getInt(KEY_AGE, DEFAULT_AGE)

    /**
     * The user's max HR.
     *
     * When [KEY_MAX_HR_AUTO] is set the value is derived from age via Tanaka rather than
     * read, so changing age keeps max HR consistent without the user re-entering it.
     */
    fun maxHR(context: Context): Int {
        val p = prefs(context)
        return if (p.getBoolean(KEY_MAX_HR_AUTO, false)) {
            tanakaMaxHR(p.getInt(KEY_AGE, DEFAULT_AGE))
        } else {
            p.getInt(KEY_MAX_HR, DEFAULT_MAX_HR)
        }
    }

    fun maxHRIsAutoDerived(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAX_HR_AUTO, false)

    /**
     * Max HR from age — Tanaka H et al. (2001) J Am Coll Cardiol 37(1):153-156:
     * `208 − 0.7 × age`. Materially more accurate above 40 than the folk `220 − age`.
     */
    fun tanakaMaxHR(age: Int): Int = (208.0 - 0.7 * age).roundToInt().coerceIn(120, 220)

    /** Configured sleep need in hours, as written by the Settings slider. */
    fun sleepNeedHours(context: Context): Float =
        prefs(context).getFloat(KEY_SLEEP_NEED_HOURS, DEFAULT_SLEEP_NEED_HOURS)

    /**
     * Configured sleep need in minutes — the form every calculator wants.
     *
     * Replaces the hardcoded 480 that appeared in the sleep score, the sleep-debt sum,
     * the readiness debt component and the achievement streak thresholds, none of which
     * previously honoured the user's setting.
     */
    fun sleepNeedMinutes(context: Context): Int =
        (sleepNeedHours(context) * 60f).roundToInt().coerceIn(240, 720)

    fun stepGoal(context: Context): Int =
        prefs(context).getInt(KEY_STEP_GOAL, DEFAULT_STEP_GOAL)

    // ── Onboarding-set preferences (T-18) ────────────────────────────────────

    /**
     * The user's stated training emphasis, as a `RecommendationEngine.Goal` name.
     *
     * Returned as a String rather than the enum so this file stays free of an analytics
     * import; the caller resolves it with `enumValueOf` behind a `runCatching`, which also
     * makes a value written by an older build harmless.
     */
    fun trainingGoal(context: Context): String =
        prefs(context).getString(KEY_TRAINING_GOAL, DEFAULT_TRAINING_GOAL) ?: DEFAULT_TRAINING_GOAL

    fun setTrainingGoal(context: Context, goal: String) {
        prefs(context).edit().putString(KEY_TRAINING_GOAL, goal).apply()
    }

    fun userName(context: Context): String? =
        prefs(context).getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() }

    fun setUserName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun onboardingComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, complete).apply()
    }

    fun biometricLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC_LOCK, false)

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
    }

    /**
     * Whether the user has opted in to sending context to a remote coach model.
     *
     * Defaults to **false** and is currently read-only in effect: no network layer ships in
     * v1.1, so the deterministic coach answers every question. The flag exists so the consent
     * is recorded before any egress path is ever added, never inferred afterwards.
     */
    fun remoteCoachOptIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COACH_REMOTE_OPT_IN, false)

    fun setRemoteCoachOptIn(context: Context, optIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_COACH_REMOTE_OPT_IN, optIn).apply()
    }

    fun notificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun checkInReminderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_IN_REMINDER, true)

    fun setCheckInReminderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_IN_REMINDER, enabled).apply()
    }
}
