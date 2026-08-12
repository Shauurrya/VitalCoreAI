package com.example.vitalcoreai.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.notifications.NotificationScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import com.example.vitalcoreai.core.time.VitalTime

/**
 * B4/B6 — Primary Health Connect sync + score computation worker.
 *
 * Responsibilities:
 * 1. Trigger Health Connect sync (via [HealthRepository.syncToday])
 * 2. Compute daily scores (recovery, sleep, readiness, activity, stress, ACWR, VO₂, bio age)
 * 3. Evaluate and store newly-earned achievements (B7)
 * 4. Update momentum indicators (B1)
 * 5. Persist computed scores to Room
 *
 * B6 — Incremental sync: reads the last sync timestamp from [SyncStateDao]
 * and only requests Health Connect records newer than that timestamp.
 *
 * Scheduling: every 4 hours, no network required (local Health Connect only).
 * Retry policy: up to 3 attempts with exponential backoff (15min → 30min → 1h).
 *
 * Input keys:
 *   "user_age"    Int  — used for max HR estimation (220 − age)
 *   "user_max_hr" Int  — explicit override (use when known from HR test)
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: HealthRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val userAge   = inputData.getInt(KEY_USER_AGE, 30)
            val userMaxHR = inputData.getInt(KEY_USER_MAX_HR, 190)
            val syncResult = repository.syncToday(userAge, userMaxHR)
            sendNotificationsIfEnabled(syncResult, userAge)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    /**
     * B3/B4 — Fires local notifications for this sync's results, each gated on its own
     * Settings toggle (read from the same "vitalcore_settings" SharedPreferences that
     * SettingsViewModel writes to).
     */
    private suspend fun sendNotificationsIfEnabled(syncResult: HealthRepository.SyncResult, userAge: Int) {
        val prefs = applicationContext.getSharedPreferences("vitalcore_settings", Context.MODE_PRIVATE)
        val scores = syncResult.latestScores

        if (prefs.getBoolean("notify_daily_summary", true) &&
            scores?.recoveryScore != null && scores.sleepScore != null
        ) {
            NotificationScheduler.sendDailySummary(
                applicationContext,
                recoveryScore = scores.recoveryScore,
                sleepScore = scores.sleepScore
            )
        }

        if (prefs.getBoolean("notify_achievements", true)) {
            syncResult.newAchievements.forEachIndexed { index, achievement ->
                NotificationScheduler.sendAchievement(
                    applicationContext,
                    icon = achievement.icon,
                    title = achievement.title,
                    description = achievement.description,
                    notificationId = index
                )
            }
        }

        // Coach alerts — only the WARNING-severity insight, if any, to avoid over-notifying
        // on every sync (moderate/positive insights stay in-app, on the Insights screen).
        if (prefs.getBoolean("notify_coach_alerts", true) && scores != null) {
            val recentMetrics = repository.metricsFrom(VitalTime.todayEpochDay() - 14).first()
            val todayMetrics = recentMetrics.lastOrNull()
            val rhrValues = recentMetrics.mapNotNull { it.restingHR?.toDouble() }
            val hrBaseline = if (rhrValues.size >= 3) rhrValues.average().toInt() else null
            val sleepDebtMinutes = recentMetrics.takeLast(7).mapNotNull { it.sleepDurationMinutes }
                .sumOf { maxOf(0, 480 - it) }.takeIf { recentMetrics.size >= 3 }

            val insights = CoachEngine.getDailyInsights(
                CoachEngine.CoachInput(
                    recoveryScore = scores.recoveryScore,
                    readinessScore = scores.readinessScore,
                    sleepScore = scores.sleepScore,
                    stressScore = scores.stressScore,
                    activityScore = scores.activityScore,
                    acwrZone = scores.acwrZone,
                    restingHR = todayMetrics?.restingHR,
                    hrBaseline = hrBaseline,
                    sleepDebtMinutes = sleepDebtMinutes,
                    daysWithoutTraining = null,
                    todaySleepHours = todayMetrics?.sleepDurationMinutes?.let { it / 60f },
                    vo2Max = scores.vo2MaxEstimate,
                    biologicalAge = scores.biologicalAge,
                    chronologicalAge = userAge
                )
            )
            insights.firstOrNull { it.type == CoachEngine.InsightType.WARNING }?.let {
                NotificationScheduler.sendCoachAlert(applicationContext, it.title, it.body)
            }
        }
    }

    companion object {
        const val WORK_NAME    = "vitalcore_sync"
        const val KEY_USER_AGE    = "user_age"
        const val KEY_USER_MAX_HR = "user_max_hr"
        private const val MAX_RETRIES = 3

        /**
         * Schedule the periodic 4-hourly sync.
         * Uses [ExistingPeriodicWorkPolicy.UPDATE] so subsequent calls
         * update the parameters without queuing duplicate work.
         */
        fun schedulePeriodicSync(
            context: Context,
            userAge: Int = 30,
            userMaxHR: Int = 190
        ) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_USER_AGE to userAge, KEY_USER_MAX_HR to userMaxHR))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.NOT_REQUIRED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Trigger an immediate one-time sync — used after the user taps "Sync now"
         * or returns from the permissions flow.
         */
        fun syncNow(context: Context, userAge: Int = 30, userMaxHR: Int = 190) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_USER_AGE to userAge, KEY_USER_MAX_HR to userMaxHR))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
