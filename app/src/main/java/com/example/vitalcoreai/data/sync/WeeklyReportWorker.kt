package com.example.vitalcoreai.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.notifications.NotificationScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * B5 — Weekly Report Worker
 *
 * Generates the weekly health report every Sunday at ~06:00.
 * Also used to generate the monthly report on the 1st of each month.
 *
 * Responsibilities:
 * 1. Average all daily computed scores over the past 7 days.
 * 2. Compute [TrendCalculators.calculateWeeklyHealthScore].
 * 3. Identify personal bests for the week.
 * 4. Store in [WeeklyReportDao] (and [MonthlyReportDao] on month boundary).
 * 5. Trigger the [NotificationScheduler] for weekly summary notification (B3).
 *
 * Scheduling: [PeriodicWorkRequestBuilder] with 7-day interval, flex window = 1 day.
 * Uses [ExistingPeriodicWorkPolicy.UPDATE] — safe to enqueue repeatedly.
 */
@HiltWorker
class WeeklyReportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: HealthRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val userAge   = inputData.getInt(SyncWorker.KEY_USER_AGE, 30)
            val userMaxHR = inputData.getInt(SyncWorker.KEY_USER_MAX_HR, 190)
            repository.generateWeeklyReport(userAge, userMaxHR)
            repository.generateMonthlyReport(userAge, userMaxHR)

            val prefs = applicationContext.getSharedPreferences("vitalcore_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("notify_weekly_report", true)) {
                val report = repository.latestWeeklyReport().first()
                report?.weeklyHealthScore?.let { score ->
                    NotificationScheduler.sendWeeklySummary(applicationContext, score, report.deltaFromPreviousWeek ?: 0f)
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "vitalcore_weekly_report"

        /**
         * Schedule the weekly report (and monthly report refresh) to run every 7 days with a
         * 1-day flex window. A flex window means WorkManager can run it anywhere in the last
         * day of the 7-day period — this avoids battery drain by clustering work with other
         * background tasks.
         */
        fun schedule(context: Context, userAge: Int = 30, userMaxHR: Int = 190) {
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
                .setInputData(workDataOf(SyncWorker.KEY_USER_AGE to userAge, SyncWorker.KEY_USER_MAX_HR to userMaxHR))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.NOT_REQUIRED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
