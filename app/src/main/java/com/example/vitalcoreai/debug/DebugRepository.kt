package com.example.vitalcoreai.debug

import android.content.Context
import com.example.vitalcoreai.BuildConfig
import com.example.vitalcoreai.core.time.FixedVitalClock
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.db.VitalCoreDatabase
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.repository.HealthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the developer screen needs, and nothing any user-facing screen does.
 *
 * Kept out of [HealthRepository] on purpose: this class runs raw `COUNT(*)` queries, stats
 * the database file and can overwrite the whole database with fixtures. None of that should
 * be one autocomplete away from production code.
 */
@Singleton
class DebugRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: VitalCoreDatabase,
    private val repository: HealthRepository,
    private val healthConnectManager: HealthConnectManager,
    private val dailyMetricsDao: DailyMetricsDao,
    private val computedScoresDao: ComputedScoresDao,
    private val exerciseSessionDao: ExerciseSessionDao,
    private val heartRateSampleDao: HeartRateSampleDao,
    private val checkInDao: CheckInDao
) {

    /** Every table Room owns, so a new one showing zero rows is visible rather than absent. */
    private val tables = listOf(
        "daily_metrics", "computed_scores", "exercise_sessions", "heart_rate_samples",
        "weekly_reports", "monthly_reports", "achievements", "sync_state",
        "check_ins", "journal_entries", "muscle_recovery", "workout_exercises"
    )

    data class EngineTiming(val name: String, val millis: Long, val ok: Boolean, val detail: String)

    data class Snapshot(
        val versionName: String,
        val versionCode: Int,
        val isDebugBuild: Boolean,
        val schemaVersion: Int,
        val zone: String,
        val todayEpochDay: Long,
        val healthConnectAvailable: Boolean,
        val permissionSummary: String,
        val lastSyncMs: Long?,
        val daysOfHistory: Int,
        val recordsToday: Map<String, Int>,
        val rowCounts: Map<String, Int>,
        val databaseBytes: Long,
        val timings: List<EngineTiming>,
        val coachContextJson: String?,
        val errors: List<ErrorLog.Entry>
    )

    suspend fun snapshot(): Snapshot {
        val today = VitalTime.todayEpochDay()

        val rowCounts = tables.associateWith { table ->
            // Table names are the compile-time constants above, never user input — the only
            // reason this is a raw query at all is that Room cannot parameterise an
            // identifier.
            runCatching { database.countRows(table) }.getOrDefault(-1)
        }

        val available = runCatching { healthConnectManager.isAvailable() }.getOrDefault(false)
        val permissionSummary = runCatching {
            val granted = healthConnectManager.grantedPermissions().size
            "$granted of ${HealthConnectManager.ALL_PERMISSIONS.size} granted"
        }.getOrElse { "unavailable" }

        val timings = measureEngines(today)

        return Snapshot(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            isDebugBuild = BuildConfig.DEBUG,
            schemaVersion = SCHEMA_VERSION,
            zone = VitalTime.zone().id,
            todayEpochDay = today,
            healthConnectAvailable = available,
            permissionSummary = permissionSummary,
            lastSyncMs = dailyMetricsDao.getMaxSyncTimestamp(),
            daysOfHistory = dailyMetricsDao.countDaysWithData(),
            recordsToday = mapOf(
                "Sleep" to (dailyMetricsDao.getForDay(today)?.sleepDurationMinutes?.let { 1 } ?: 0),
                "Resting HR" to (dailyMetricsDao.getForDay(today)?.restingHR?.let { 1 } ?: 0),
                "HR samples" to heartRateSampleDao.countForDay(today),
                "Workouts" to exerciseSessionDao.getForDay(today).size,
                "Check-in" to (checkInDao.getForDay(today)?.let { 1 } ?: 0)
            ),
            rowCounts = rowCounts,
            databaseBytes = databaseSizeBytes(),
            timings = timings,
            coachContextJson = runCatching { buildCoachContextJson(today) }
                .onFailure { ErrorLog.record("DebugRepository.coachContext", it) }
                .getOrNull(),
            errors = ErrorLog.recent()
        )
    }

    /**
     * Wall-clock cost of the analytics for one day.
     *
     * The spec's performance bar is "< 100 ms per day", and the only honest way to report
     * that is to run the real pipeline over the real database rather than time a synthetic
     * micro-benchmark.
     */
    private suspend fun measureEngines(today: Long): List<EngineTiming> {
        val out = mutableListOf<EngineTiming>()

        val statsStart = System.nanoTime()
        val statsOk = runCatching {
            val series = (1..200).map { it * 1.37 % 17.0 }
            com.example.vitalcoreai.analytics.RobustStats.median(series) != null
        }.getOrDefault(false)
        out += EngineTiming(
            "RobustStats", (System.nanoTime() - statsStart) / 1_000_000, statsOk,
            if (statsOk) "median/MAD over 200 points" else "failed"
        )

        val pipelineStart = System.nanoTime()
        val output = runCatching { repository.runPipeline(today) }
            .onFailure { ErrorLog.record("DebugRepository.runPipeline", it) }
            .getOrNull()
        val pipelineMs = (System.nanoTime() - pipelineStart) / 1_000_000
        out += EngineTiming(
            "ScorePipeline", pipelineMs, output != null,
            when {
                output == null -> "no data for today"
                pipelineMs < 100 -> "within the 100 ms budget"
                else -> "OVER the 100 ms budget"
            }
        )
        out += EngineTiming(
            "Forecast", 0, output?.forecast?.available == true,
            output?.forecast?.let { if (it.available) it.range else "not enough data" } ?: "—"
        )
        out += EngineTiming(
            "Anomalies", 0, output != null,
            "${output?.anomalies?.size ?: 0} flagged"
        )
        out += EngineTiming(
            "Insights", 0, output != null,
            "${output?.insights?.size ?: 0} surfaced"
        )
        return out
    }

    /** The exact payload an external model would be sent. Pretty-printed for reading. */
    suspend fun buildCoachContextJson(today: Long = VitalTime.todayEpochDay()): String? =
        CoachContextFactory(repository, dailyMetricsDao, checkInDao, context)
            .build(today)?.toJson()?.let(::prettyJson)

    // ─── Synthetic data (T-14) ───────────────────────────────────────────────

    /**
     * Replace the database contents with a scenario.
     *
     * Refuses outside a debug build. A release APK that can be talked into overwriting a
     * user's real health history with fixtures is a data-loss bug wearing a developer-tool
     * costume, and the check is one line.
     */
    suspend fun loadScenario(scenarioId: String): String {
        if (!BuildConfig.DEBUG) return "Refused: synthetic data is debug-builds only."

        val scenario = SyntheticDataGenerator.byId(scenarioId, VitalTime.todayEpochDay())
            ?: return "Unknown scenario '$scenarioId'."

        // Pin the clock BEFORE generating, so a zone-sensitive scenario builds its day keys
        // in the zone it is testing rather than the device's.
        scenario.pinnedZone?.let { zone ->
            VitalTime.clock = FixedVitalClock(VitalTime.nowMs(), zone)
        }

        return runCatching {
            clearGeneratedData()
            if (scenario.days.isNotEmpty()) dailyMetricsDao.upsertAll(scenario.days)
            if (scenario.sessions.isNotEmpty()) exerciseSessionDao.upsertAll(scenario.sessions)
            if (scenario.heartRateSamples.isNotEmpty()) {
                heartRateSampleDao.insertAll(scenario.heartRateSamples)
            }
            scenario.checkIns.forEach { checkInDao.upsert(it) }

            // A memoised report from the previous scenario would otherwise survive the wipe.
            repository.invalidateReportCache()

            // Score every loaded day so the screens have something to render.
            var scored = 0
            for (day in scenario.days.map { it.dateEpochDay }.sorted()) {
                runCatching { repository.rescoreDay(day) }
                    .onSuccess { scored++ }
                    .onFailure { ErrorLog.record("loadScenario.rescore", it) }
            }
            repository.generateWeeklyReport()
            repository.generateMonthlyReport()

            "Loaded '${scenario.title}': ${scenario.days.size} days, $scored scored" +
                (scenario.pinnedZone?.let { ", zone pinned to ${it.id}" } ?: "")
        }.onFailure { ErrorLog.record("loadScenario", it) }
            .getOrElse { "Failed: ${it.message}" }
    }

    /** Drop the derived tables. User-authored tables are left alone — see [Migrations]. */
    private suspend fun clearGeneratedData() {
        database.execute("DELETE FROM daily_metrics")
        database.execute("DELETE FROM computed_scores")
        database.execute("DELETE FROM exercise_sessions")
        database.execute("DELETE FROM heart_rate_samples")
        database.execute("DELETE FROM weekly_reports")
        database.execute("DELETE FROM monthly_reports")
        database.execute("DELETE FROM muscle_recovery")
    }

    /** Undo a scenario's clock pin. */
    fun restoreRealClock() = VitalTime.reset()

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun databaseSizeBytes(): Long =
        runCatching { context.getDatabasePath(DATABASE_NAME).length() }.getOrDefault(0L)

    companion object {
        const val SCHEMA_VERSION = 7
        const val DATABASE_NAME = "vitalcore_db"
    }
}

/**
 * `COUNT(*)` for a table name that is not a query parameter.
 *
 * Room's `@Query` cannot bind an identifier, so counting twelve tables would otherwise mean
 * twelve near-identical DAO methods that a new table silently fails to join.
 */
private fun VitalCoreDatabase.countRows(table: String): Int =
    openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

private fun VitalCoreDatabase.execute(sql: String) {
    openHelper.writableDatabase.execSQL(sql)
}

/** Two-space indent. A single-line 8 KB payload is not something anyone can read on a phone. */
private fun prettyJson(json: String): String {
    val sb = StringBuilder()
    var indent = 0
    var inString = false
    var escaped = false
    for (c in json) {
        when {
            escaped -> { sb.append(c); escaped = false }
            c == '\\' && inString -> { sb.append(c); escaped = true }
            c == '"' -> { sb.append(c); inString = !inString }
            inString -> sb.append(c)
            c == '{' || c == '[' -> { indent++; sb.append(c).append('\n').append("  ".repeat(indent)) }
            c == '}' || c == ']' -> { indent--; sb.append('\n').append("  ".repeat(indent)).append(c) }
            c == ',' -> sb.append(c).append('\n').append("  ".repeat(indent))
            c == ':' -> sb.append(c).append(' ')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
