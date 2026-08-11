package com.example.vitalcoreai.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.vitalcoreai.data.db.dao.ComputedScoresDao
import com.example.vitalcoreai.data.db.dao.DailyMetricsDao
import com.example.vitalcoreai.data.db.dao.ExerciseSessionDao
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B9 — CSV Export Engine
 *
 * Exports health data to comma-separated value files and returns a shareable [Uri]
 * via [FileProvider] so the user can send to Google Drive, email, etc.
 *
 * All export operations run on [Dispatchers.IO].
 * Output files are written to `getExternalFilesDir("exports")` which is private
 * to the app — no storage permission required on Android 10+.
 *
 * Available exports:
 * - [exportDailyMetrics]  — raw daily metrics (steps, HR, sleep, weight, SpO₂)
 * - [exportComputedScores] — all analytics scores per day
 * - [exportExerciseSessions] — exercise log with HR zones
 * - [exportAll] — all three files zipped (delegates to [exportDailyMetrics] + [exportComputedScores] + [exportExerciseSessions])
 *
 * Date range: defaults to last 90 days. All epoch days are converted to ISO-8601 dates.
 */
@Singleton
class HealthDataExporter @Inject constructor(
    private val dailyMetricsDao: DailyMetricsDao,
    private val computedScoresDao: ComputedScoresDao,
    private val exerciseSessionDao: ExerciseSessionDao
) {

    companion object {
        const val FILE_AUTHORITY = "com.example.vitalcoreai.fileprovider"
        private const val EXPORT_DIR = "exports"

        // CSV column separator — comma, RFC 4180 compliant
        private const val SEP = ","
    }

    // ── Daily Metrics Export ───────────────────────────────────────────────────

    /**
     * Export daily metrics to a CSV file and return a shareable URI.
     *
     * Columns: date, steps, distanceMeters, caloriesBurned, restingHR,
     *          sleepDurationMinutes, sleepEfficiencyPercent, sleepDeepMinutes,
     *          sleepRemMinutes, sleepLightMinutes, sleepAwakeMinutes,
     *          bedtimeMinuteOfDay, wakeTimeMinuteOfDay, weightKg, bodyFatPercent, spO2Percent
     */
    suspend fun exportDailyMetrics(
        context: Context,
        since: LocalDate = LocalDate.now().minusDays(90),
        until: LocalDate = LocalDate.now()
    ): Uri = withContext(Dispatchers.IO) {
        val rows = dailyMetricsDao.getRange(since.toEpochDay(), until.toEpochDay())
        val file = writeFile(context, "vitalcore_daily_metrics_${dateSuffix()}.csv") { pw ->
            pw.println(
                listOf(
                    "date", "steps", "distance_m", "calories", "resting_hr_bpm",
                    "sleep_duration_min", "sleep_efficiency_pct", "sleep_deep_min",
                    "sleep_rem_min", "sleep_light_min", "sleep_awake_min",
                    "bedtime_min_of_day", "wake_time_min_of_day",
                    "weight_kg", "body_fat_pct", "spo2_pct"
                ).joinToString(SEP)
            )
            rows.forEach { m -> pw.println(m.toCsvRow()) }
        }
        fileUri(context, file)
    }

    // ── Computed Scores Export ─────────────────────────────────────────────────

    /**
     * Export computed analytics scores to CSV.
     *
     * Columns: date, recovery_score, recovery_confidence, readiness_score,
     *          sleep_score, stress_score, activity_score, lifestyle_score,
     *          training_load_normalized, acwr, acwr_zone, vo2_max_estimate,
     *          biological_age, recovery_momentum, sleep_momentum, training_momentum,
     *          data_quality_level, data_quality_pct
     */
    suspend fun exportComputedScores(
        context: Context,
        since: LocalDate = LocalDate.now().minusDays(90),
        until: LocalDate = LocalDate.now()
    ): Uri = withContext(Dispatchers.IO) {
        val rows = computedScoresDao.getRange(since.toEpochDay(), until.toEpochDay())
        val file = writeFile(context, "vitalcore_scores_${dateSuffix()}.csv") { pw ->
            pw.println(
                listOf(
                    "date", "recovery_score", "recovery_confidence", "readiness_score",
                    "sleep_score", "stress_score", "activity_score", "lifestyle_score",
                    "training_load_normalized", "acwr", "acwr_zone",
                    "vo2_max_estimate", "biological_age",
                    "recovery_momentum", "sleep_momentum", "training_momentum",
                    "data_quality_level", "data_quality_pct"
                ).joinToString(SEP)
            )
            rows.forEach { s -> pw.println(s.toCsvRow()) }
        }
        fileUri(context, file)
    }

    // ── Exercise Sessions Export ───────────────────────────────────────────────

    suspend fun exportExerciseSessions(
        context: Context,
        since: LocalDate = LocalDate.now().minusDays(90),
        until: LocalDate = LocalDate.now()
    ): Uri = withContext(Dispatchers.IO) {
        val rows = exerciseSessionDao.getRange(since.toEpochDay(), until.toEpochDay())
        val file = writeFile(context, "vitalcore_exercise_${dateSuffix()}.csv") { pw ->
            pw.println(
                listOf(
                    "date", "exercise_type", "duration_min", "calories", "distance_m",
                    "avg_hr_bpm", "max_hr_bpm", "training_load_normalized", "dominant_zone",
                    "zone1_pct", "zone2_pct", "zone3_pct", "zone4_pct", "zone5_pct"
                ).joinToString(SEP)
            )
            rows.forEach { e -> pw.println(e.toCsvRow()) }
        }
        fileUri(context, file)
    }

    /**
     * Export all three CSV files (daily metrics, computed scores, exercise sessions)
     * and return their shareable URIs together.
     */
    suspend fun exportAll(
        context: Context,
        since: LocalDate = LocalDate.now().minusDays(90),
        until: LocalDate = LocalDate.now()
    ): List<Uri> = listOf(
        exportDailyMetrics(context, since, until),
        exportComputedScores(context, since, until),
        exportExerciseSessions(context, since, until)
    )

    // ── Share intent helper ────────────────────────────────────────────────────

    /**
     * Create a share [Intent] for the given URI. Pass to [Context.startActivity].
     */
    fun createShareIntent(uri: Uri, mimeType: String = "text/csv"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Create a share [Intent] for multiple URIs at once (used by [exportAll]). */
    fun createShareIntentMultiple(uris: List<Uri>, mimeType: String = "text/csv"): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun writeFile(context: Context, name: String, block: (PrintWriter) -> Unit): File {
        val dir = File(context.getExternalFilesDir(null), EXPORT_DIR).also { it.mkdirs() }
        val file = File(dir, name)
        PrintWriter(FileWriter(file)).use { block(it) }
        return file
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, FILE_AUTHORITY, file)

    private fun dateSuffix(): String = LocalDate.now().toString().replace("-", "")

    private fun Long.toIsoDate(): String = LocalDate.ofEpochDay(this).toString()

    private fun DailyMetricsEntity.toCsvRow(): String = listOf(
        dateEpochDay.toIsoDate(), steps, distanceMeters, caloriesBurned, restingHR,
        sleepDurationMinutes, sleepEfficiencyPercent, sleepDeepMinutes,
        sleepRemMinutes, sleepLightMinutes, sleepAwakeMinutes,
        bedtimeMinuteOfDay, wakeTimeMinuteOfDay, weightKg, bodyFatPercent, spO2Percent
    ).joinToString(SEP) { it?.toString() ?: "" }

    private fun ComputedScoresEntity.toCsvRow(): String = listOf(
        dateEpochDay.toIsoDate(), recoveryScore, recoveryConfidence, readinessScore,
        sleepScore, stressScore, activityScore, lifestyleScore,
        trainingLoadNormalized, acwr, acwrZone, vo2MaxEstimate, biologicalAge,
        recoveryMomentum, sleepMomentum, trainingMomentum,
        dataQualityLevel, dataQualityPercent
    ).joinToString(SEP) { it?.toString() ?: "" }

    private fun ExerciseSessionEntity.toCsvRow(): String = listOf(
        dateEpochDay.toIsoDate(), exerciseType, durationMinutes, caloriesBurned, distanceMeters,
        avgHR, maxHR, trainingLoadNormalized, dominantZone,
        zone1Pct, zone2Pct, zone3Pct, zone4Pct, zone5Pct
    ).joinToString(SEP) { it?.toString() ?: "" }
}
