package com.example.vitalcoreai.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.vitalcoreai.MainActivity
import com.example.vitalcoreai.R

/**
 * B3 — Notification Scheduler
 *
 * Handles creation and delivery of all VitalCore AI notifications.
 * Notifications are always opt-in; POST_NOTIFICATIONS permission is required on Android 13+.
 *
 * Channel architecture:
 * | Channel ID            | Name                    | Importance  | Use                    |
 * |-----------------------|-------------------------|-------------|------------------------|
 * | CHANNEL_DAILY_SUMMARY | Daily Summary           | DEFAULT     | Morning recovery nudge |
 * | CHANNEL_WEEKLY_REPORT | Weekly Health Report    | DEFAULT     | Sunday weekly digest   |
 * | CHANNEL_ACHIEVEMENTS  | Achievements            | HIGH        | Achievement unlocked   |
 * | CHANNEL_COACH_ALERT   | Coach Alerts            | DEFAULT     | Rule-based insights    |
 *
 * All notifications deep-link to [MainActivity] with the appropriate screen destination extra.
 *
 * Usage:
 * ```kotlin
 * NotificationScheduler.createChannels(context)   // call once from Application.onCreate()
 * NotificationScheduler.sendDailySummary(context, recoveryScore = 72f, sleepScore = 68f)
 * NotificationScheduler.sendAchievement(context, icon = "🏃", title = "Active Week", ...)
 * ```
 */
object NotificationScheduler {

    const val CHANNEL_DAILY_SUMMARY = "vitalcore_daily"
    const val CHANNEL_WEEKLY_REPORT = "vitalcore_weekly"
    const val CHANNEL_ACHIEVEMENTS  = "vitalcore_achievements"
    const val CHANNEL_COACH_ALERT   = "vitalcore_coach"

    private const val ID_DAILY_SUMMARY   = 1001
    private const val ID_WEEKLY_REPORT   = 1002
    private const val ID_COACH_ALERT     = 1003
    private const val ID_ACHIEVEMENT_BASE = 2000

    const val EXTRA_DESTINATION = "destination"
    const val DEST_DASHBOARD    = "dashboard"
    const val DEST_REPORTS      = "reports"
    const val DEST_ACHIEVEMENTS = "achievements"

    // ── Channel setup ─────────────────────────────────────────────────────────

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(CHANNEL_DAILY_SUMMARY, "Daily Summary",        NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Morning recovery and sleep summary"
            },
            NotificationChannel(CHANNEL_WEEKLY_REPORT, "Weekly Health Report", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Weekly health digest every Sunday"
            },
            NotificationChannel(CHANNEL_ACHIEVEMENTS,  "Achievements",         NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Achievement unlocked notifications"
            },
            NotificationChannel(CHANNEL_COACH_ALERT,   "Coach Alerts",         NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Rule-based health insights from VitalCore AI Coach"
            }
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    // ── Notification delivery ─────────────────────────────────────────────────

    fun sendDailySummary(
        context: Context,
        recoveryScore: Float,
        sleepScore: Float,
        coachInsight: String? = null
    ) {
        val body = buildString {
            append("Recovery ${recoveryScore.toInt()} · Sleep ${sleepScore.toInt()}")
            coachInsight?.let { append("\n$it") }
        }
        val n = NotificationCompat.Builder(context, CHANNEL_DAILY_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your morning health summary")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(makePendingIntent(context, DEST_DASHBOARD))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_DAILY_SUMMARY, n)
    }

    fun sendWeeklySummary(context: Context, weeklyScore: Float, delta: Float) {
        val deltaText = when {
            delta > 3f  -> "↑ up ${delta.toInt()} pts from last week"
            delta < -3f -> "↓ down ${(-delta).toInt()} pts from last week"
            else        -> "stable vs last week"
        }
        val body = "Weekly score: ${weeklyScore.toInt()}/100 — $deltaText."
        val n = NotificationCompat.Builder(context, CHANNEL_WEEKLY_REPORT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your weekly health report")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(makePendingIntent(context, DEST_REPORTS))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_WEEKLY_REPORT, n)
    }

    fun sendAchievement(
        context: Context,
        icon: String,
        title: String,
        description: String,
        notificationId: Int = 0
    ) {
        val body = "$icon $description"
        val n = NotificationCompat.Builder(context, CHANNEL_ACHIEVEMENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Achievement unlocked: $title")
            .setContentText(body)
            .setContentIntent(makePendingIntent(context, DEST_ACHIEVEMENTS))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(ID_ACHIEVEMENT_BASE + notificationId, n)
    }

    fun sendCoachAlert(context: Context, headline: String, body: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_COACH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(makePendingIntent(context, DEST_DASHBOARD))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_COACH_ALERT, n)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun makePendingIntent(context: Context, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_DESTINATION, destination)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            destination.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
