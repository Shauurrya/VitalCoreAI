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

    const val DEFAULT_AGE = 30
    const val DEFAULT_MAX_HR = 190
    const val DEFAULT_SLEEP_NEED_HOURS = 8f
    const val DEFAULT_STEP_GOAL = 7500

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
}
