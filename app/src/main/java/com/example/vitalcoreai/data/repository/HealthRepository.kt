package com.example.vitalcoreai.data.repository

import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.debug.ErrorLog
import com.example.vitalcoreai.debug.loggingFailures
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.db.entity.*
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.model.*
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class HealthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val healthConnectManager: HealthConnectManager,
    private val dailyMetricsDao: DailyMetricsDao,
    private val computedScoresDao: ComputedScoresDao,
    private val exerciseSessionDao: ExerciseSessionDao,
    private val heartRateSampleDao: HeartRateSampleDao,
    private val weeklyReportDao: WeeklyReportDao,
    private val monthlyReportDao: MonthlyReportDao,
    private val achievementDao: AchievementDao,           // B7
    private val syncStateDao: SyncStateDao,               // B6
    private val checkInDao: CheckInDao,                   // Part 12
    private val journalDao: JournalDao,                   // Part 13
    private val muscleRecoveryDao: MuscleRecoveryDao      // Part 9
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("vitalcore_sync", Context.MODE_PRIVATE)
    }
    private val PREF_BACKFILL_DONE = "backfill_done_v1"

    companion object {
        /** How far back the one-time backfill reaches. */
        const val BACKFILL_DAYS = 30

        /**
         * Every sync re-runs this many trailing days, not just today.
         *
         * Without it a missed day was permanent — the backfill flag was already set, so
         * nothing ever revisited it — and late-arriving data was never picked up. Samsung
         * Health routinely pushes last night's sleep to Health Connect hours after the
         * fact, well after the run that already wrote today's row, leaving
         * sleepDurationMinutes and recoveryScore null for that day forever.
         *
         * Re-running is safe: every score is keyed per-day and idempotent.
         */
        const val TRAILING_RESYNC_DAYS = 3

        /** How far back to hunt for missing days on each sync. */
        const val GAP_SCAN_DAYS = 30

        /** Intraday HR older than this is dropped — the table is otherwise unbounded. */
        const val HR_SAMPLE_RETENTION_DAYS = 35

        /**
         * How much prior history the score pipeline is handed.
         *
         * The widest consumer is [InsightDiscoveryEngine], which needs a long series before
         * Bonferroni-corrected tests can clear their threshold at all.
         */
        const val PIPELINE_HISTORY_DAYS = 90
    }

    /**
     * T-16 — memo for a generated report.
     *
     * Reports used to be regenerated on every sync. `syncToday` runs on a 4-hour periodic
     * worker plus every manual refresh and every app launch, so the weekly and monthly
     * reports were rebuilt tens of times a day from data that had not moved. The hash is
     * over the rows the window actually reads, so a genuinely changed day still invalidates.
     */
    private data class ReportCacheEntry(val dataHash: Int, val generatedAtMs: Long)

    private val reportCache = mutableMapOf<String, ReportCacheEntry>()

    /** Test/debug hook — a synthetic-data load must not be masked by a stale memo. */
    fun invalidateReportCache() = reportCache.clear()

    /**
     * True when [key]'s report was last generated from exactly [sources], so regenerating it
     * would rewrite an identical row.
     *
     * Recording the hash on a miss is what makes the *next* call a hit; a caller that returns
     * early on true must therefore not also need to write anything else.
     */
    private fun isReportUpToDate(key: String, vararg sources: List<Any>): Boolean {
        val hash = sources.toList().hashCode()
        val cached = reportCache[key]
        if (cached != null && cached.dataHash == hash) return true
        reportCache[key] = ReportCacheEntry(hash, VitalTime.nowMs())
        return false
    }

    /** Result of a foreground [syncToday] call — used by [com.example.vitalcoreai.data.sync.SyncWorker] to decide which notifications to fire. */
    data class SyncResult(
        val latestScores: ComputedScoresEntity?,
        val newAchievements: List<AchievementEngine.Achievement>
    )

    // ─── Sync from Health Connect → Room ─────────────────────────────────

    suspend fun syncToday(userAge: Int = 30, userMaxHR: Int = 190): SyncResult {
        val today = VitalTime.today()

        if (!prefs.getBoolean(PREF_BACKFILL_DONE, false)) {
            backfillHistory(userAge, userMaxHR)
        }

        val weightSeries = healthConnectManager.readWeightSeries(
            today.minusDays(GAP_SCAN_DAYS.toLong()), today
        )

        // Fill any day missing from the recent window. A phone that was off for three days
        // used to lose those days permanently.
        val gapStart = today.minusDays(GAP_SCAN_DAYS.toLong()).toEpochDay()
        val present = dailyMetricsDao.getPresentDays(gapStart, today.toEpochDay()).toSet()
        for (day in gapStart until today.toEpochDay()) {
            if (day !in present) {
                loggingFailures("syncDay(gap)") { syncDay(LocalDate.ofEpochDay(day), userAge, userMaxHR, weightSeries) }
            }
        }

        // Re-sync the trailing window so late-arriving sleep and workouts land.
        for (offset in TRAILING_RESYNC_DAYS downTo 1) {
            loggingFailures("syncDay(trailing)") { syncDay(today.minusDays(offset.toLong()), userAge, userMaxHR, weightSeries) }
        }

        val newAchievements = syncDay(today, userAge, userMaxHR, weightSeries)

        // Keep reports current — they were only regenerated after a backfill or by the
        // weekly worker, so the Weekly Report screen could be up to a week stale.
        loggingFailures("generateWeeklyReport") { generateWeeklyReport(userAge, userMaxHR) }
        loggingFailures("generateMonthlyReport") { generateMonthlyReport(userAge, userMaxHR) }
        runCatching {
            heartRateSampleDao.deleteBefore(today.minusDays(HR_SAMPLE_RETENTION_DAYS.toLong()).toEpochDay())
        }

        val latestScores = computedScoresDao.getForDay(today.toEpochDay())
        return SyncResult(latestScores, newAchievements)
    }

    /** Days of genuine Health Connect data — what the UI must show as "X days of history". */
    suspend fun daysOfHistory(): Int = dailyMetricsDao.countDaysWithData()

    /** Coverage inside a window: e.g. "46 of 90 days recorded". */
    suspend fun coverageInLastDays(days: Int): Int {
        val today = VitalTime.todayEpochDay()
        return dailyMetricsDao.countDaysWithDataInRange(today - days + 1, today)
    }

    /**
     * Part 2 — Historical Backfill.
     *
     * Queries Health Connect for the last 30 days of every supported record type,
     * persists each day's raw metrics to Room, then recomputes all scores
     * (Recovery, Readiness, Sleep, Training Load, ACWR, VO2, Bio Age, Baselines)
     * for each day so History / Weekly / Monthly screens have real data
     * immediately after first launch — not just data from since app install.
     *
     * Skips days already present in Room to avoid redundant work.
     * Uses SharedPreferences to mark completion so it runs only once,
     * unless fewer than 7 days of data are present (gap detection).
     */
    /**
     * @param force re-read and recompute every day in the window even when a row already
     *              exists. Settings offers "Re-sync 30-day history" as the remedy for
     *              missing or corrupted history, but the skip-if-present check made it a
     *              no-op for exactly the days the user was complaining about.
     */
    suspend fun backfillHistory(userAge: Int = 30, userMaxHR: Int = 190, force: Boolean = false) {
        if (!healthConnectManager.isAvailable()) return
        val today = VitalTime.today()
        val windowStart = today.minusDays(BACKFILL_DAYS.toLong())

        // Day keys inside the backfill window specifically. getLatest(30) returned the
        // newest 30 rows regardless of whether they fell in the window at all, so after a
        // long gap the skip set could be entirely outside it and thus useless.
        val existingDays = if (force) emptySet() else
            dailyMetricsDao.getPresentDays(windowStart.toEpochDay(), today.toEpochDay()).toSet()

        // One weight read for the whole window, attributed per day by LOCF.
        val weightSeries = healthConnectManager.readWeightSeries(windowStart, today)

        for (daysBack in BACKFILL_DAYS downTo 1) {
            val day = today.minusDays(daysBack.toLong())
            if (day.toEpochDay() in existingDays) continue
            // One failed day must not abort the month. Health Connect grants permissions
            // individually, so a single declined record type used to throw out of syncDay,
            // out of the loop, and be swallowed by SyncWorker's catch — leaving a partial
            // month with no indication why, which then retried identically forever because
            // PREF_BACKFILL_DONE is only set after the loop completes.
            loggingFailures("syncDay(backfill)") { syncDay(day, userAge, userMaxHR, weightSeries) }
        }

        // Regenerate weekly + monthly reports from backfilled data
        loggingFailures("generateWeeklyReport") { generateWeeklyReport(userAge, userMaxHR) }
        loggingFailures("generateMonthlyReport") { generateMonthlyReport(userAge, userMaxHR) }

        prefs.edit().putBoolean(PREF_BACKFILL_DONE, true).apply()
    }

    suspend fun syncDay(
        day: LocalDate,
        userAge: Int,
        userMaxHR: Int,
        weightSeries: List<WeightData>? = null
    ): List<AchievementEngine.Achievement> {
        if (!healthConnectManager.isAvailable()) return emptyList()

        // Each read is isolated: Health Connect grants permissions individually, so a
        // single declined or unsupported type must degrade to null rather than unwind the
        // whole day. BodyFatRecord in particular is one Samsung Health often has no data
        // for, and a user can plausibly grant nine of ten.
        suspend fun <T> read(block: suspend () -> T): T? =
            runCatching { block() }.onFailure { ErrorLog.record("HealthConnect read", it) }.getOrNull()

        val hrPoints = read { healthConnectManager.readHeartRateForDay(day) } ?: emptyList()
        val sleep = read { healthConnectManager.readNightForDay(day) }
        val steps = read { healthConnectManager.readStepsForDay(day) }
        val calories = read { healthConnectManager.readCaloriesForDay(day) }
        val activeCalories = read { healthConnectManager.readActiveCaloriesForDay(day) }
        val distance = read { healthConnectManager.readDistanceForDay(day) }
        val spO2 = read { healthConnectManager.readSpO2ForDay(day, sleep) }
        val exerciseSessions = read { healthConnectManager.readExerciseSessions(day, day) } ?: emptyList()

        // Resting HR: recorded when Samsung Health wrote it (minimum of the day, not an
        // arbitrary first record), otherwise derived from overnight samples. Whether
        // Samsung Health writes RestingHeartRateRecord at all is version- and
        // device-dependent for the Active 2, and without the fallback a null here nulls
        // recovery, stress, VO2, biological age AND readiness together.
        val overnightHR = read { healthConnectManager.readOvernightHeartRate(day) } ?: emptyList()
        val restingHRResult = read {
            healthConnectManager.restingHRForDay(day, overnightHR.ifEmpty { hrPoints }, sleep)
        }

        val series = weightSeries ?: read {
            healthConnectManager.readWeightSeries(day.minusDays(90), day)
        } ?: emptyList()
        val weight = healthConnectManager.weightForDay(series, day)

        // A day Health Connect returned nothing for gets NO ROW. Writing one with steps,
        // distance and calories coerced to 0 created phantom sedentary days that entered
        // the step baseline and inflated the history depth that drives milestones.
        val hasAnyData = hrPoints.isNotEmpty() || sleep != null || steps != null ||
            calories != null || distance != null || exerciseSessions.isNotEmpty() ||
            restingHRResult != null
        if (!hasAnyData) return emptyList()

        // A4 — Wear detection: did the watch actually appear to be worn (vs. charging /
        // phone-only / partial day), so a sensor gap is never scored as poor health.
        val hrMinutesOfDay = hrPoints.map { pt -> VitalTime.minuteOfDay(pt.timestampMs) to pt.bpm }
        val wearStatus = WearDetector.detect(
            hrPointsWithMinuteOfDay = hrMinutesOfDay,
            hasSteps = (steps ?: 0) > 0,
            hasCalories = (calories ?: 0) > 0,
            firstDataMinuteOfDay = hrMinutesOfDay.minByOrNull { it.first }?.first,
            lastDataMinuteOfDay = hrMinutesOfDay.maxByOrNull { it.first }?.first,
            hasSleep = sleep != null
        )
        // Density per ELAPSED hour, matching WearDetector — dividing by hours-that-contain-
        // a-sample made a 3-hour wear look continuous and defeated the quality threshold.
        val elapsedMinutes = if (hrMinutesOfDay.size < 2) 0
            else (hrMinutesOfDay.maxOf { it.first } - hrMinutesOfDay.minOf { it.first })
        val hrPointsPerHour = if (elapsedMinutes > 0)
            hrMinutesOfDay.size.toDouble() / (elapsedMinutes / 60.0) else 0.0
        // Coverage as distinct hours actually containing data, so a 12-hour hole in the
        // middle is no longer counted as full coverage by a first-to-last span.
        val hoursCovered = hrMinutesOfDay.map { it.first / 60 }.distinct().size
        val partialDayFraction = (hoursCovered / 24.0).coerceIn(0.0, 1.0)
        val dataSourceType = when {
            wearStatus.phoneOnlyTracking -> "PHONE_SENSOR"
            hrMinutesOfDay.isNotEmpty()  -> "WATCH_SENSOR"
            else                         -> null
        }

        // Priority 2 — freshness. The v7 column existed but nothing ever wrote it, so
        // DataQualityEngine's dataAgeHours was permanently null and every score was judged
        // on completeness alone. A day can be complete and still be built from readings that
        // stopped arriving thirty hours ago because the watch has not synced.
        val newestRecordMs = listOfNotNull(
            hrPoints.maxOfOrNull { it.timestampMs },
            exerciseSessions.maxOfOrNull { it.endMs }
        ).maxOrNull()

        dailyMetricsDao.upsert(
            DailyMetricsEntity(
                dateEpochDay = day.toEpochDay(),
                restingHR = restingHRResult?.bpm,
                restingHRDerived = restingHRResult?.derived,
                steps = steps,
                distanceMeters = distance,
                caloriesBurned = calories,
                activeCalories = activeCalories,
                weightKg = weight?.weightKg,
                bodyFatPercent = weight?.bodyFatPercent,
                spO2Percent = spO2?.averagePercent,
                spO2ReadingCount = spO2?.readingCount,
                spO2FromSleepWindow = spO2?.fromSleepWindow,
                sleepDurationMinutes = sleep?.durationMinutes,
                sleepEfficiencyPercent = sleep?.efficiencyPercent,
                sleepStagesAvailable = sleep?.stagesAvailable,
                sleepDeepMinutes = sleep?.deepMinutes,
                sleepRemMinutes = sleep?.remMinutes,
                sleepLightMinutes = sleep?.lightMinutes,
                sleepAwakeMinutes = sleep?.awakeMinutes,
                bedtimeMinuteOfDay = sleep?.bedtimeMinuteOfDay,
                wakeTimeMinuteOfDay = sleep?.wakeTimeMinuteOfDay,
                dataSourceType = dataSourceType,
                hrPointsPerHour = hrPointsPerHour,
                partialDayFraction = partialDayFraction,
                hasData = true,
                newestRecordTimestampMs = newestRecordMs
            )
        )

        // Replace the day's samples rather than appending. The unique index on timestampMs
        // now makes IGNORE meaningful, but deleting first also drops samples Health Connect
        // has since removed.
        if (hrPoints.isNotEmpty()) {
            heartRateSampleDao.deleteForDay(day.toEpochDay())
            heartRateSampleDao.insertAll(hrPoints.map { pt ->
                HeartRateSampleEntity(
                    dateEpochDay = day.toEpochDay(),
                    timestampMs = pt.timestampMs,
                    bpm = pt.bpm
                )
            })
        }

        val restingForZones = restingHRResult?.bpm ?: 60

        for (session in exerciseSessions) {
            // Read HR over the session's ACTUAL span. Filtering the calendar-day list kept
            // only the pre-midnight portion of a late-evening workout, and the zone
            // calculator then fabricated a distribution from the remainder.
            val hrForSession = if (session.endMs > VitalTime.endOfDayExclusiveMs(day.toEpochDay())) {
                read { healthConnectManager.readHeartRateBetween(session.startMs, session.endMs) } ?: emptyList()
            } else {
                hrPoints.filter { it.timestampMs in session.startMs..session.endMs }
            }

            val load = TrainingLoadCalculator.calculateForSession(
                session.copy(heartRatePoints = hrForSession), userMaxHR, restingForZones
            )
            val zones = TrainingLoadCalculator.calculateZoneDistribution(
                hrForSession, userMaxHR, restingForZones
            )
            val avgHR = if (hrForSession.isEmpty()) null else hrForSession.map { it.bpm }.average().toInt()
            val maxHRSession = hrForSession.maxOfOrNull { it.bpm }

            exerciseSessionDao.upsert(
                ExerciseSessionEntity(
                    startMs = session.startMs,
                    dateEpochDay = session.dateEpochDay,
                    endMs = session.endMs,
                    exerciseType = session.type,
                    exerciseTypeId = session.exerciseTypeId,
                    durationMinutes = load.durationMinutes,
                    caloriesBurned = session.caloriesBurned,
                    distanceMeters = session.distanceMeters,
                    avgHR = avgHR,
                    maxHR = maxHRSession,
                    trainingLoadNormalized = load.normalizedLoad,
                    dominantZone = load.dominantZone.name,
                    hasHeartRateData = hrForSession.isNotEmpty(),
                    belowZone1Pct = zones[HRZone.BELOW_ZONE1],
                    zone1Pct = zones[HRZone.ZONE1],
                    zone2Pct = zones[HRZone.ZONE2],
                    zone3Pct = zones[HRZone.ZONE3],
                    zone4Pct = zones[HRZone.ZONE4],
                    zone5Pct = zones[HRZone.ZONE5]
                )
            )
        }

        val overnightGapHours = wearStatus.gaps
            .filter { it.startMinuteOfDay < 7 * 60 }
            .maxOfOrNull { it.durationMinutes / 60.0 } ?: 0.0
        return computeAndStoreScores(day, userAge, userMaxHR, overnightGapHours, hrPoints)
    }

    /**
     * Gather this day's inputs and run [ScorePipeline] WITHOUT persisting anything.
     *
     * Split out because two callers want it: [computeAndStoreScores], which writes the
     * result, and the coach-context builder, which needs the typed engine results that the
     * flattened columns cannot carry (a Forecast's full driver list, a TrendReport's
     * contributors, the muscle statuses). Recomputing is cheap and pure; caching a second
     * copy of the same numbers somewhere else is how the two would drift apart.
     */
    suspend fun runPipeline(
        today: Long,
        userAge: Int = UserPrefs.age(context),
        userMaxHR: Int = UserPrefs.maxHR(context),
        overnightHRGapHours: Double = 0.0,
        todayHrPoints: List<HeartRatePoint> = emptyList()
    ): ScorePipeline.Output? {
        val metrics = dailyMetricsDao.getForDay(today) ?: return null
        val history = dailyMetricsDao.getRange(today - PIPELINE_HISTORY_DAYS, today - 1)
        val priorScores = computedScoresDao.getBeforeAscending(today, PIPELINE_HISTORY_DAYS)
        val sessions = exerciseSessionDao.getRange(today - PIPELINE_HISTORY_DAYS, today)
        val checkIns = checkInDao.getRange(today - PIPELINE_HISTORY_DAYS, today)

        // Part 7 — HR recovery needs the samples in the three minutes after the day's last
        // session ended.
        val latestSession = sessions.filter { it.dateEpochDay == today }.maxByOrNull { it.endMs }
        val postWorkoutSamples = if (latestSession?.maxHR != null) {
            heartRateSampleDao.getForDay(today)
                .filter { it.timestampMs > latestSession.endMs }
                .sortedBy { it.timestampMs }
                .map { ((it.timestampMs - latestSession.endMs) / 1000).toInt() to it.bpm }
                .filter { it.first in 0..180 }
        } else emptyList()

        // Only claim as many days of training history as genuinely exist, so ACWR reports
        // "needs more history" rather than a ratio built out of zero-filled absence.
        val earliestDay = dailyMetricsDao.getEarliestDayWithData() ?: today
        val daysAvailable = (today - earliestDay + 1).toInt().coerceAtLeast(0)

        val output = ScorePipeline.compute(
            ScorePipeline.Input(
                todayEpochDay = today,
                // A day still in progress is scored against the current clock; a day being
                // re-scored after the fact is scored as at its own end, so "hours since
                // trained" does not quietly come to mean "hours until now, several days on".
                nowMinuteOfDay = if (today == VitalTime.todayEpochDay())
                    VitalTime.nowMinuteOfDay() else VitalTime.MINUTES_PER_DAY - 1,
                userAge = userAge,
                userMaxHR = userMaxHR,
                sleepNeedMinutes = UserPrefs.sleepNeedMinutes(context),
                stepGoal = UserPrefs.stepGoal(context),
                goal = runCatching {
                    enumValueOf<RecommendationEngine.Goal>(UserPrefs.trainingGoal(context))
                }.getOrDefault(RecommendationEngine.Goal.GENERAL_FITNESS),
                today = metrics.toPipelineMetrics(),
                history = history.map { it.toPipelineMetrics() },
                priorScores = priorScores.map { it.toPipelineScores() },
                sessions = sessions.map { it.toPipelineSession() },
                todayHrPoints = todayHrPoints,
                postWorkoutSamples = postWorkoutSamples,
                checkIns = checkIns.map { it.toPipelineCheckIn() },
                overnightHRGapHours = overnightHRGapHours,
                daysOfHistoryAvailable = daysAvailable
            )
        )

        return output
    }

    /**
     * Score one day and persist the result.
     *
     * All the arithmetic lives in [ScorePipeline]; this function's entire job is to gather
     * the inputs, hand them over, and write what comes back. That split is what makes the
     * computation testable on the JVM — see `ScorePipelineTest`.
     *
     * Every history window read here is **exclusive of the day being scored**. `syncToday`
     * re-runs the trailing three days on each sync, so a day is written many times; a
     * history query that included today's own row would feed a previous run's output back
     * into the current run's input, and the second sync would disagree with the first.
     */
    private suspend fun computeAndStoreScores(
        day: LocalDate,
        userAge: Int,
        userMaxHR: Int,
        overnightHRGapHours: Double = 0.0,
        todayHrPoints: List<HeartRatePoint> = emptyList()
    ): List<AchievementEngine.Achievement> {
        val today = day.toEpochDay()
        val metrics = dailyMetricsDao.getForDay(today) ?: return emptyList()
        val output = runPipeline(today, userAge, userMaxHR, overnightHRGapHours, todayHrPoints)
            ?: return emptyList()

        computedScoresDao.upsert(output.toEntity(today))

        // Part 9 — muscle_recovery is a snapshot of the CURRENT state, so it is rewritten
        // rather than appended to. Nothing wrote this table at all before now, which is why
        // the muscle recovery card had no data behind it.
        if (today == VitalTime.todayEpochDay()) {
            runCatching {
                muscleRecoveryDao.replaceAll(
                    output.muscleStatuses.map { st ->
                        MuscleRecoveryEntity(
                            muscleGroup = st.group.name,
                            lastTrainedEpochDay = today - (st.hoursSinceTrained / 24).toLong(),
                            estimatedRecoveryHours = st.hoursSinceTrained + st.hoursRemaining,
                            rpe = st.lastRpe,
                            sorenessRating = st.sorenessRating,
                            status = st.status.name
                        )
                    }
                )
            }
        }

        // ── B7 — Achievement evaluation ───────────────────────────────────────
        // Records come from queries scoped to days STRICTLY BEFORE today, so a personal
        // best is compared against the prior record rather than against a maximum that
        // already includes today's freshly-written row — which made three achievements
        // unreachable. Streaks walk the calendar via day-keyed maps, so a day the watch
        // spent charging breaks the streak instead of being skipped over.
        val achievementInput = AchievementEngine.AchievementInput(
            todayEpochDay = today,
            personalSleepNeedMinutes = UserPrefs.sleepNeedMinutes(context),
            stepGoal = UserPrefs.stepGoal(context),
            sleepByDay = dailyMetricsDao.getSleepDurationsInRange(today - 400, today)
                .associate { it.dateEpochDay to it.value },
            stepsByDay = dailyMetricsDao.getStepsInRange(today - 400, today)
                .associate { it.dateEpochDay to it.value },
            todayRecovery = output.recoveryScore,
            todayRestingHR = metrics.restingHR,
            todaySleepScore = output.sleepScore,
            priorBestRecovery = computedScoresDao.getBestRecoveryBefore(today),
            priorLowestRestingHR = dailyMetricsDao.getLowestRestingHRBefore(today),
            priorBestSleepScore = computedScoresDao.getBestSleepScoreBefore(today),
            // A genuine COUNT(*), not a list size capped by a query LIMIT — which made
            // MONTH_OF_DATA fire by construction.
            totalDaysOfData = dailyMetricsDao.countDaysWithData()
        )
        val newAchievements = AchievementEngine.evaluateNewlyEarned(achievementInput)
        if (newAchievements.isNotEmpty()) {
            achievementDao.insertAll(newAchievements.map { a ->
                AchievementEntity(
                    achievementId = a.id.name,
                    title = a.title,
                    description = a.description,
                    icon = a.icon,
                    earnedEpochDay = today
                )
            })
        }
        return newAchievements
    }

    // ─── Room ⇄ ScorePipeline mapping ─────────────────────────────────────────
    //
    // Straight field copies, deliberately kept next to the database rather than inside the
    // analytics package — see the module-boundary note at the top of ScorePipeline.kt.

    private fun DailyMetricsEntity.toPipelineMetrics() = ScorePipeline.DayMetrics(
        dateEpochDay = dateEpochDay,
        restingHR = restingHR,
        steps = steps,
        distanceMeters = distanceMeters,
        caloriesBurned = caloriesBurned,
        activeCalories = activeCalories,
        spO2Percent = spO2Percent,
        spO2ReadingCount = spO2ReadingCount,
        sleepDurationMinutes = sleepDurationMinutes,
        sleepEfficiencyPercent = sleepEfficiencyPercent,
        sleepDeepMinutes = sleepDeepMinutes,
        sleepRemMinutes = sleepRemMinutes,
        sleepLightMinutes = sleepLightMinutes,
        sleepAwakeMinutes = sleepAwakeMinutes,
        sleepStagesAvailable = sleepStagesAvailable,
        bedtimeMinuteOfDay = bedtimeMinuteOfDay,
        wakeTimeMinuteOfDay = wakeTimeMinuteOfDay,
        dataSourceType = dataSourceType,
        hrPointsPerHour = hrPointsPerHour,
        partialDayFraction = partialDayFraction,
        dataAgeHours = newestRecordTimestampMs?.let { newest ->
            (VitalTime.nowMs() - newest).coerceAtLeast(0L) / 3_600_000.0
        }
    )

    private fun ComputedScoresEntity.toPipelineScores() = ScorePipeline.DayScores(
        dateEpochDay = dateEpochDay,
        recoveryScore = recoveryScore,
        readinessScore = readinessScore,
        sleepScore = sleepScore,
        strain = strain,
        energyBankScore = energyBankScore,
        hrr1 = hrr1
    )

    private fun ExerciseSessionEntity.toPipelineSession() = ScorePipeline.Session(
        dateEpochDay = dateEpochDay,
        startMs = startMs,
        endMs = endMs,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        trainingLoadNormalized = trainingLoadNormalized,
        dominantZone = dominantZone,
        hasHeartRateData = hasHeartRateData,
        maxHR = maxHR,
        rpe = rpe,
        endMinuteOfDay = VitalTime.minuteOfDay(endMs)
    )

    private fun CheckInEntity.toPipelineCheckIn() = ScorePipeline.CheckIn(
        dateEpochDay = dateEpochDay,
        energy = energy,
        stress = stress,
        soreness = soreness,
        sleepQuality = sleepQuality,
        mood = mood
    )

    private fun ScorePipeline.Output.toEntity(dateEpochDay: Long) = ComputedScoresEntity(
        dateEpochDay = dateEpochDay,
        recoveryScore = recoveryScore,
        recoveryConfidence = recoveryConfidence,
        recoveryExplanation = recoveryExplanation,
        recoveryBreakdown = recoveryBreakdown,
        readinessScore = readinessScore,
        readinessConfidence = readinessConfidence,
        readinessExplanation = readinessExplanation,
        readinessBreakdown = readinessBreakdown,
        sleepScore = sleepScore,
        sleepConfidence = sleepConfidence,
        sleepExplanation = sleepExplanation,
        sleepBreakdown = sleepBreakdown,
        stressScore = stressScore,
        stressConfidence = stressConfidence,
        stressExplanation = stressExplanation,
        stressBreakdown = stressBreakdown,
        activityScore = activityScore,
        consistencyScore = consistencyScore,
        lifestyleScore = lifestyleScore,
        trainingLoadNormalized = trainingLoadNormalized,
        acwr = acwr,
        acwrZone = acwrZone,
        acwrIsMeaningful = acwrIsMeaningful,
        acwrDaysOfHistory = acwrDaysOfHistory,
        vo2MaxEstimate = vo2MaxEstimate,
        biologicalAge = biologicalAge,
        weeklyHealthScore = null,
        monthlyHealthScore = null,
        recoveryMomentum = recoveryMomentum,
        sleepMomentum = sleepMomentum,
        trainingMomentum = trainingMomentum,
        dataQualityLevel = dataQualityLevel,
        dataQualityPercent = dataQualityPercent,
        energyBankScore = energyBankScore,
        energyBankExplanation = energyBankExplanation,
        energyBankBreakdown = energyBankBreakdown,
        hrr1 = hrr1,
        hrr2 = hrr2,
        hrrTrend = hrrTrend,
        strain = strain,
        dailyExertionMinutes = dailyExertionMinutes,
        strainConfidence = strainConfidence,
        strainExplanation = strainExplanation,
        strainBreakdown = strainBreakdown,
        strainIsProxy = strainIsProxy,
        strainZone = strainZone,
        // ── v7 ───────────────────────────────────────────────────────────────
        forecastLow = forecastLow,
        forecastHigh = forecastHigh,
        forecastConfidence = forecastConfidence,
        forecastDrivers = forecastDrivers,
        forecastRisks = forecastRisks,
        sleepConsistencyScore = sleepConsistencyScore,
        sleepConsistencyLabel = sleepConsistencyLabel,
        bedtimeSdMinutes = bedtimeSdMinutes,
        wakeSdMinutes = wakeSdMinutes,
        trend7Direction = trend7Direction,
        trend14Direction = trend14Direction,
        trend30Direction = trend30Direction,
        trendContributors = trendContributors,
        anomaliesEncoded = anomaliesEncoded,
        anomalyCount = anomalyCount,
        recommendationType = recommendationType,
        recommendationIntensity = recommendationIntensity,
        recommendationVolumePct = recommendationVolumePct,
        recommendationDetail = recommendationDetail,
        dataQualityFactors = dataQualityFactors,
        dataQualityPositives = dataQualityPositives,
        // Derived from the day rather than System.currentTimeMillis(), so re-scoring the
        // same day from the same data produces an identical row and the idempotency test
        // is testing the pipeline rather than the clock.
        createdAtMs = VitalTime.startOfDayMs(dateEpochDay)
    )

    /**
     * Recompute and persist one day's scores from data already in Room.
     *
     * No Health Connect read — the day's raw metrics must already exist. Used by the debug
     * screen after loading a synthetic scenario, where there is nothing to fetch and the
     * whole point is to exercise the engines over fixture data.
     */
    suspend fun rescoreDay(epochDay: Long) {
        computeAndStoreScores(
            day = LocalDate.ofEpochDay(epochDay),
            userAge = UserPrefs.age(context),
            userMaxHR = UserPrefs.maxHR(context),
            todayHrPoints = heartRateSampleDao.getForDay(epochDay)
                .map { HeartRatePoint(it.timestampMs, it.bpm) }
        )
    }

    fun latestScores(): Flow<ComputedScoresEntity?> = computedScoresDao.getLatest()

    fun scoresFrom(epochDay: Long): Flow<List<ComputedScoresEntity>> = computedScoresDao.getFrom(epochDay)

    fun metricsFrom(epochDay: Long): Flow<List<DailyMetricsEntity>> = dailyMetricsDao.getFrom(epochDay)

    fun exerciseFrom(epochDay: Long): Flow<List<ExerciseSessionEntity>> = exerciseSessionDao.getFrom(epochDay)

    fun latestWeeklyReport(): Flow<WeeklyReportEntity?> = weeklyReportDao.getLatest()

    fun latestMonthlyReport(): Flow<MonthlyReportEntity?> = monthlyReportDao.getLatest()

    fun earnedAchievements(): Flow<List<AchievementEntity>> = achievementDao.getAllEarned()  // B7

    suspend fun getLatestScoresN(n: Int) = computedScoresDao.getLatestN(n)

    suspend fun getMetricsRange(start: Long, end: Long) = dailyMetricsDao.getRange(start, end)

    // Part 12 — Check-In access
    suspend fun saveCheckIn(entity: CheckInEntity) = checkInDao.upsert(entity)
    suspend fun getCheckInForDay(day: Long) = checkInDao.getForDay(day)
    fun checkInsFrom(epochDay: Long) = checkInDao.getFrom(epochDay)

    // Part 13 — Journal access
    suspend fun logJournalEntry(entity: JournalEntryEntity) = journalDao.insert(entity)
    suspend fun getJournalForDay(day: Long) = journalDao.getForDay(day)
    fun journalEntriesFrom(epochDay: Long) = journalDao.getFrom(epochDay)
    suspend fun getJournalByHabit(habitId: String) = journalDao.getByHabit(habitId)
    suspend fun getJournalByHabitFrom(habitId: String, startDay: Long) = journalDao.getByHabitFrom(habitId, startDay)
    suspend fun getAllTrackedHabitIds() = journalDao.getAllHabitIds()
    suspend fun deleteJournalEntry(id: Long) = journalDao.deleteById(id)

    // Part 9 — Muscle Recovery access
    fun allMuscleRecovery() = muscleRecoveryDao.getAll()
    suspend fun upsertMuscleRecovery(entities: List<MuscleRecoveryEntity>) = muscleRecoveryDao.upsertAll(entities)

    /**
     * B5 — Weekly report, anchored to a real calendar week (Monday–Sunday).
     *
     * The window used to be a rolling `today − 6`, keyed by `weekStartEpochDay`, so every
     * invocation on a different day created a new row overlapping the previous one by six
     * days. `getLatestN(2).drop(1)` then read that overlapping window as "last week", so
     * the week-over-week delta compared two 7-day means sharing 6 of 7 days and was
     * structurally pinned near zero.
     */
    suspend fun generateWeeklyReport(userAge: Int = 30, userMaxHR: Int = 190) {
        val todayDate = VitalTime.today()
        val today = todayDate.toEpochDay()
        // Monday of the current week.
        val weekStartDate = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong())
        val weekStart = weekStartDate.toEpochDay()
        val weekEnd = minOf(weekStart + 6, today)

        val scores = computedScoresDao.getRange(weekStart, weekEnd)
        if (scores.isEmpty()) return

        // T-16 — skip the rebuild when nothing inside the window has changed. The hash is
        // taken over the rows this report actually reads, so a late-arriving night still
        // invalidates it; only a genuinely identical window is short-circuited.
        if (isReportUpToDate("week:$weekStart", scores)) return

        fun avg(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.average().toFloat()

        val avgRecovery  = avg(scores.mapNotNull { it.recoveryScore  })
        val avgReadiness = avg(scores.mapNotNull { it.readinessScore })
        val avgSleep     = avg(scores.mapNotNull { it.sleepScore     })
        val avgActivity  = avg(scores.mapNotNull { it.activityScore  })

        // The immediately preceding calendar week — disjoint by construction.
        val previousWeek = weeklyReportDao.getForWeek(weekStart - 7)

        val weeklyHealthResult = TrendCalculators.calculateWeeklyHealthScore(
            weekRecovery  = avgRecovery,
            weekReadiness = avgReadiness,
            weekSleep     = avgSleep,
            weekActivity  = avgActivity,
            previousWeekScore = previousWeek?.weeklyHealthScore
        )

        // B10 — Achievements earned within this week's date range
        val weekAchievements = achievementDao.getEarnedBetween(weekStart, weekEnd)
        val achievementsSummary = weekAchievements.joinToString("|") { "${it.icon} ${it.title} — ${it.description}" }

        weeklyReportDao.upsert(
            WeeklyReportEntity(
                weekStartEpochDay   = weekStart,
                weekEndEpochDay     = weekEnd,
                avgRecovery         = avgRecovery,
                avgReadiness        = avgReadiness,
                avgSleep            = avgSleep,
                avgActivity         = avgActivity,
                weeklyHealthScore   = weeklyHealthResult.score,
                deltaFromPreviousWeek = weeklyHealthResult.deltaFromPreviousPeriod,
                explanation         = weeklyHealthResult.explanation,
                highlights          = weeklyHealthResult.highlights.joinToString("|"),
                achievementsSummary = achievementsSummary.ifBlank { null }
            )
        )
    }

    /**
     * B10 — Monthly report generation, mirroring [generateWeeklyReport].
     * Averages this calendar month's computed scores, feeds the month's already-generated
     * weekly health scores into [TrendCalculators.calculateMonthlyHealthScore], and derives
     * personal records + ranked contributing factors from the raw daily data.
     */
    suspend fun generateMonthlyReport(userAge: Int = 30, userMaxHR: Int = 190) {
        val today = VitalTime.today()
        val todayEpoch = today.toEpochDay()
        val monthStart = today.withDayOfMonth(1).toEpochDay()

        val scores = computedScoresDao.getRange(monthStart, todayEpoch)
        if (scores.isEmpty()) return

        val monthMetrics = dailyMetricsDao.getRange(monthStart, todayEpoch)
        // T-16 — see the note in generateWeeklyReport. The monthly report also reads the raw
        // metrics for its personal records, so both lists enter the hash.
        if (isReportUpToDate("month:$monthStart", scores, monthMetrics)) return

        fun avg(values: List<Float>): Float? =
            if (values.isEmpty()) null else values.average().toFloat()

        val avgRecovery  = avg(scores.mapNotNull { it.recoveryScore  })
        val avgReadiness = avg(scores.mapNotNull { it.readinessScore })
        val avgSleep     = avg(scores.mapNotNull { it.sleepScore     })
        val avgActivity  = avg(scores.mapNotNull { it.activityScore  })

        // Weeks OVERLAPPING this month, not weeks starting inside it. Filtering by
        // weekStartEpochDay dropped any week straddling the month boundary, so early in a
        // month the list was reliably empty — which is how the Monthly Report screen ended
        // up showing a fabricated 50/100 that looked like a real measurement.
        val weekScores = weeklyReportDao.getLatestN(8)
            .filter { it.weekEndEpochDay >= monthStart && it.weekStartEpochDay <= todayEpoch }
            .mapNotNull { it.weeklyHealthScore }

        val previousMonth = monthlyReportDao.getLatestN(2).drop(1).firstOrNull()

        val monthlyResult = TrendCalculators.calculateMonthlyHealthScore(
            weekScores = weekScores,
            previousMonthScore = previousMonth?.monthlyHealthScore
        )

        // Personal records for the month
        val metrics = monthMetrics
        val personalRecords = buildList {
            // Pair the row with its non-null value up front rather than filtering and then
            // re-asserting: `!!` on a value the filter already guaranteed reads as a risk
            // where there is none, and hides the one place a genuine null could appear.
            scores.mapNotNull { row -> row.recoveryScore?.let { row to it } }
                .maxByOrNull { it.second }?.let { (row, score) ->
                    add("Best Recovery: ${score.toInt()} on ${LocalDate.ofEpochDay(row.dateEpochDay)}")
                }
            metrics.mapNotNull { row -> row.restingHR?.let { row to it } }
                .minByOrNull { it.second }?.let { (row, bpm) ->
                    add("Lowest Resting HR: $bpm bpm on ${LocalDate.ofEpochDay(row.dateEpochDay)}")
                }
            metrics.mapNotNull { row -> row.sleepDurationMinutes?.let { row to it } }
                .maxByOrNull { it.second }?.let { (row, minutes) ->
                    add(
                        "Longest Sleep: ${VitalTime.formatDurationMinutes(minutes)} on " +
                            LocalDate.ofEpochDay(row.dateEpochDay)
                    )
                }
        }

        // Ranked contributing factors — vs previous month if available, otherwise plain averages
        val rankedFactors = if (previousMonth != null) {
            listOfNotNull(
                avgRecovery?.let  { "Recovery"  to (it - (previousMonth.avgRecovery  ?: it)) },
                avgSleep?.let     { "Sleep"     to (it - (previousMonth.avgSleep     ?: it)) },
                avgActivity?.let  { "Activity"  to (it - (previousMonth.avgActivity  ?: it)) },
                avgReadiness?.let { "Readiness" to (it - (previousMonth.avgReadiness ?: it)) }
            ).sortedByDescending { kotlin.math.abs(it.second) }
                .map { (name, delta) ->
                    val dir = if (delta >= 0) "improved" else "declined"
                    "$name $dir ${"%.0f".format(kotlin.math.abs(delta))} pts vs last month"
                }
        } else {
            listOfNotNull(
                avgRecovery?.let  { "Recovery averaged ${it.toInt()} this month" },
                avgSleep?.let     { "Sleep averaged ${it.toInt()} this month" },
                avgActivity?.let  { "Activity averaged ${it.toInt()} this month" }
            )
        }

        monthlyReportDao.upsert(
            MonthlyReportEntity(
                monthStartEpochDay = monthStart,
                monthEndEpochDay = todayEpoch,
                avgRecovery = avgRecovery,
                avgReadiness = avgReadiness,
                avgSleep = avgSleep,
                avgActivity = avgActivity,
                monthlyHealthScore = monthlyResult.score,
                deltaFromPreviousMonth = monthlyResult.deltaFromPreviousPeriod,
                explanation = monthlyResult.explanation,
                personalRecords = personalRecords.joinToString("|").ifBlank { null },
                rankedFactors = rankedFactors.joinToString("|").ifBlank { null }
            )
        )
    }
}
