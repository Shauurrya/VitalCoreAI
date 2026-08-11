package com.example.vitalcoreai.data.repository

import com.example.vitalcoreai.analytics.*
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
    @ApplicationContext private val context: Context,
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
    }

    /** Result of a foreground [syncToday] call — used by [com.example.vitalcoreai.data.sync.SyncWorker] to decide which notifications to fire. */
    data class SyncResult(
        val latestScores: ComputedScoresEntity?,
        val newAchievements: List<AchievementEngine.Achievement>
    )

    // ─── Sync from Health Connect → Room ─────────────────────────────────

    suspend fun syncToday(userAge: Int = 30, userMaxHR: Int = 190): SyncResult {
        val today = LocalDate.now()

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
                runCatching { syncDay(LocalDate.ofEpochDay(day), userAge, userMaxHR, weightSeries) }
            }
        }

        // Re-sync the trailing window so late-arriving sleep and workouts land.
        for (offset in TRAILING_RESYNC_DAYS downTo 1) {
            runCatching { syncDay(today.minusDays(offset.toLong()), userAge, userMaxHR, weightSeries) }
        }

        val newAchievements = syncDay(today, userAge, userMaxHR, weightSeries)

        // Keep reports current — they were only regenerated after a backfill or by the
        // weekly worker, so the Weekly Report screen could be up to a week stale.
        runCatching { generateWeeklyReport(userAge, userMaxHR) }
        runCatching { generateMonthlyReport(userAge, userMaxHR) }
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
        val today = LocalDate.now().toEpochDay()
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
        val today = LocalDate.now()
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
            runCatching { syncDay(day, userAge, userMaxHR, weightSeries) }
        }

        // Regenerate weekly + monthly reports from backfilled data
        runCatching { generateWeeklyReport(userAge, userMaxHR) }
        runCatching { generateMonthlyReport(userAge, userMaxHR) }

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
        suspend fun <T> read(block: suspend () -> T): T? = runCatching { block() }.getOrNull()

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
        val zone = java.time.ZoneId.systemDefault()
        val hrMinutesOfDay = hrPoints.map { pt ->
            val zdt = java.time.Instant.ofEpochMilli(pt.timestampMs).atZone(zone)
            (zdt.hour * 60 + zdt.minute) to pt.bpm
        }
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
                hasData = true
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
            val hrForSession = if (session.endMs > day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) {
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

    private suspend fun computeAndStoreScores(
        day: LocalDate,
        userAge: Int,
        userMaxHR: Int,
        overnightHRGapHours: Double = 0.0,
        todayHrPoints: List<HeartRatePoint> = emptyList()
    ): List<AchievementEngine.Achievement> {
        val today = day.toEpochDay()
        val metrics = dailyMetricsDao.getForDay(today) ?: return emptyList()

        val sleepNeedMinutes = UserPrefs.sleepNeedMinutes(context)
        val stepGoal = UserPrefs.stepGoal(context)

        // ── Baselines: the window ENDING THE DAY BEFORE the day being scored ──
        //
        // Two bugs in one line previously. `getLatest(30)` is ORDER BY dateEpochDay DESC
        // LIMIT 30 — the newest rows in the table, not the 30 days preceding `day`. During
        // the backfill loop (30 downTo 1) that meant every historical day was z-scored
        // against a baseline made of its own FUTURE, so no historical score was
        // reproducible. And the window included `day` itself, because syncDay upserts
        // today's row before this runs — so each value was compared against a distribution
        // containing itself, which pulls the mean toward the anomaly and inflates the std,
        // systematically under-reporting exactly the deviations that matter most.
        //
        // getRange is ORDER BY dateEpochDay ASC, which also satisfies BaselineManager's
        // documented "oldest first" contract that takeLast() depends on.
        val past30Metrics = dailyMetricsDao.getRange(today - 30, today - 1)
        val past14Metrics = dailyMetricsDao.getRange(today - 14, today - 1)
        val past7Metrics = dailyMetricsDao.getRange(today - 7, today - 1)
        val past7Exercise = exerciseSessionDao.getRange(today - 6, today)
        val past28Exercise = exerciseSessionDao.getRange(today - 27, today)

        fun DailyMetricsEntity.toSleepData(): SleepData? = sleepDurationMinutes?.let { dur ->
            SleepData(
                dateEpochDay = dateEpochDay,
                durationMinutes = dur,
                // Genuinely nullable — no fabricated 80.0. See SleepData.efficiencyPercent.
                efficiencyPercent = sleepEfficiencyPercent,
                bedtimeMinuteOfDay = bedtimeMinuteOfDay,
                wakeTimeMinuteOfDay = wakeTimeMinuteOfDay,
                remMinutes = sleepRemMinutes ?: 0,
                deepMinutes = sleepDeepMinutes ?: 0,
                lightMinutes = sleepLightMinutes ?: 0,
                awakeMinutes = sleepAwakeMinutes ?: 0,
                stagesAvailable = sleepStagesAvailable ?: false
            )
        }

        val restingHRBaseline30 = past30Metrics.mapNotNull { m ->
            m.restingHR?.let { RestingHRData(m.dateEpochDay, it) }
        }
        val sleepBaseline14 = past14Metrics.mapNotNull { it.toSleepData() }
        val sleepPrior7 = past7Metrics.mapNotNull { it.toSleepData() }

        val todaySleep = metrics.toSleepData()
        val todayRHR = metrics.restingHR?.let { RestingHRData(today, it) }

        // ── Per-day training load ─────────────────────────────────────────────
        // Prior-day load is the sum of YESTERDAY's sessions, not "the most recent session
        // within 7 days". The old maxByOrNull{startMs} gave a rest day a workout from up to
        // six days ago — so recovery reported "Yesterday load: 78%" on a day the user did
        // nothing — and it silently discarded second sessions on multi-session days.
        val loadByDay: Map<Long, Float> = past28Exercise
            .groupBy { it.dateEpochDay }
            .mapValues { (_, sessions) ->
                sessions.sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat()
            }

        val priorDaySessions = past7Exercise.filter { it.dateEpochDay == today - 1 }
        val priorDayLoad: TrainingLoadData? = if (priorDaySessions.isEmpty()) {
            // An explicit zero, not null: a day with no sessions is genuine rest, which is
            // information, rather than missing data.
            TrainingLoadData(today - 1, 0f, 0, HRZone.BELOW_ZONE1, hasHeartRateData = false)
        } else {
            TrainingLoadData(
                dateEpochDay = today - 1,
                normalizedLoad = priorDaySessions
                    .sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat().coerceAtMost(1f),
                durationMinutes = priorDaySessions.sumOf { it.durationMinutes },
                dominantZone = priorDaySessions
                    .maxByOrNull { it.trainingLoadNormalized ?: 0f }
                    ?.dominantZone?.let { runCatching { HRZone.valueOf(it) }.getOrNull() }
                    ?: HRZone.ZONE2,
                hasHeartRateData = priorDaySessions.any { it.hasHeartRateData }
            )
        }

        val todaySessions = past7Exercise.filter { it.dateEpochDay == today }
        val todayLoadNormalized = todaySessions
            .sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat().coerceAtMost(1f)
        val todayExerciseMinutes = todaySessions.sumOf { it.durationMinutes }

        // ACWR over a zero-filled DAILY series — rest days must enter the denominator.
        val acwrResult = AcwrCalculator.calculate(
            AcwrCalculator.buildDailySeries(
                loadByDay + mapOf(today to todayLoadNormalized), today, windowDays = 28
            ).let { series ->
                // Only report as many days as we genuinely have history for.
                val earliest = dailyMetricsDao.getEarliestDayWithData() ?: today
                val availableDays = (today - earliest + 1).toInt().coerceIn(0, 28)
                series.takeLast(availableDays.coerceAtLeast(0))
            }
        )

        // A4/A3 — Real data-quality signals (wear status, sensor density, history depth)
        // instead of the always-true defaults, so a day the watch was left charging shows
        // reduced confidence rather than a misleadingly confident low score.
        val qualityInput = DataQualityEngine.QualityInput(
            hasSleepToday = todaySleep != null,
            hasHRToday = todayRHR != null,
            hasWorkoutToday = todaySessions.isNotEmpty(),
            hrPointsPerHour = metrics.hrPointsPerHour ?: 0.0,
            sleepHistoryDays = sleepBaseline14.size,
            hrHistoryDays = restingHRBaseline30.size,
            overnightHRGapHours = overnightHRGapHours,
            dataSourceType = when (metrics.dataSourceType) {
                "WATCH_SENSOR" -> DataQualityEngine.DataSourceType.WATCH_SENSOR
                "PHONE_SENSOR" -> DataQualityEngine.DataSourceType.PHONE_SENSOR
                "MANUAL"       -> DataQualityEngine.DataSourceType.MANUAL
                else           -> DataQualityEngine.DataSourceType.UNKNOWN
            },
            partialDayFraction = metrics.partialDayFraction ?: 1.0,
            hasStepsToday = (metrics.steps ?: 0) > 0,
            activityHistoryDays = past30Metrics.count { it.steps != null }
        )

        // Recovery score — capture full result to persist confidence & explanation
        val recoveryResult = if (todaySleep != null && todayRHR != null) {
            RecoveryScoreCalculator.calculate(
                todaySleep = todaySleep,
                sleepBaseline14Days = sleepBaseline14,
                todayRestingHR = todayRHR,
                restingHRBaseline30Days = restingHRBaseline30,
                priorDayTrainingLoad = priorDayLoad,
                spO2Percent = metrics.spO2Percent,
                qualityInput = qualityInput,
                personalSleepNeedMinutes = sleepNeedMinutes,
                spO2ReadingCount = metrics.spO2ReadingCount ?: 0
            )
        } else null
        val recoveryScore = recoveryResult?.score

        // Sleep score — 7 PRIOR nights (excluding today, which the calculator appends
        // itself) and the user's CONFIGURED sleep need, which was previously ignored.
        val sleepResult = todaySleep?.let {
            SleepScoreCalculator.calculate(
                todaySleep = it,
                last7Days = sleepPrior7,
                personalSleepNeedMinutes = sleepNeedMinutes,
                qualityInput = qualityInput
            )
        }
        val sleepScore = sleepResult?.score

        // ── Strain: the 0–21 logarithmic daily scale ──────────────────────────
        val restingBaselineMean = BaselineManager
            .restingHRBaseline(restingHRBaseline30.map { it.bpm.toDouble() }).mean
        val strainQuality = DataQualityEngine.evaluate(qualityInput, DataQualityEngine.MetricProfile.STRAIN)
        val strainResult = StrainCalculator.calculate(
            hrPoints = todayHrPoints,
            restingHRBaseline = restingBaselineMean,
            maxHR = userMaxHR,
            steps = metrics.steps,
            exerciseMinutes = todayExerciseMinutes,
            dataQuality = strainQuality
        )

        // Part 7 — Heart Rate Recovery from today's latest workout
        val todayExercises = exerciseSessionDao.getForDay(today)
        val latestSession = todayExercises.maxByOrNull { it.endMs }
        val hrrResult = if (latestSession != null && latestSession.maxHR != null) {
            // Read HR samples after the workout ended (up to 3 minutes post-workout)
            val postWorkoutSamples = heartRateSampleDao.getForDay(today)
                .filter { it.timestampMs > latestSession.endMs }
                .sortedBy { it.timestampMs }
                .map { ((it.timestampMs - latestSession.endMs) / 1000).toInt() to it.bpm }
                .filter { it.first in 0..180 }  // only first 3 minutes
            // Get historical HRR1 values for trend analysis
            val historicalHrr1 = computedScoresDao.getLatestN(30).mapNotNull { it.hrr1 }
            HRRecoveryCalculator.calculate(
                peakHR = latestSession.maxHR!!,
                postWorkoutSamples = postWorkoutSamples,
                historicalHrr1 = historicalHrr1
            )
        } else null

        // ── Sleep debt: 7-day rolling, EXCLUDING today (Part 10) ──────────────
        // Delegated to SleepDebtCalculator, which adds three things a plain sum of
        // deficits cannot express: sleep need estimated from the user's own longer
        // nights rather than a fixed target, older deficits decaying in importance,
        // and surplus sleep repaying debt only partially — you cannot bank sleep 1:1.
        val highLoadDays = past28Exercise
            .filter { (it.trainingLoadNormalized ?: 0f) > SleepDebtCalculator.HIGH_LOAD_THRESHOLD }
            .map { it.dateEpochDay }
            .toSet()
        val sleepDebtResult = SleepDebtCalculator.calculate(
            recentSleep = sleepPrior7,
            highLoadDays = highLoadDays,
            todayIsHighLoad = past7Exercise.any {
                it.dateEpochDay == today && (it.trainingLoadNormalized ?: 0f) > SleepDebtCalculator.HIGH_LOAD_THRESHOLD
            }
        )
        val sleepDebt = sleepDebtResult.debtMinutes
        val readinessResult = recoveryScore?.let {
            ReadinessScoreCalculator.calculate(
                recoveryScore = it,
                sleepDebtMinutes = sleepDebt,
                acwrResult = acwrResult,
                recoveryQuality = recoveryResult?.dataQuality ?: DataQualityReport.UNKNOWN,
                nightsCounted = sleepPrior7.size.coerceAtLeast(1)
            )
        }
        val readinessScore = readinessResult?.score

        // Stress score — now fed the real quality input, so it can reach HIGH confidence.
        val stressResult = todayRHR?.let {
            TrendCalculators.calculateStressScore(
                todayRestingHR = it.bpm,
                baselineHR30Days = restingHRBaseline30,
                priorDayHR = past30Metrics.lastOrNull { m -> m.dateEpochDay == today - 1 }?.restingHR,
                qualityInput = qualityInput
            )
        }
        val stressScore = stressResult?.score

        // ── Activity ──────────────────────────────────────────────────────────
        fun DailyMetricsEntity.toActivity() = steps?.let { s ->
            DailyActivityData(
                dateEpochDay = dateEpochDay,
                steps = s,
                distanceMeters = distanceMeters ?: 0f,
                caloriesBurned = caloriesBurned ?: 0,
                activeCalories = activeCalories
            )
        }
        val past30Activity = past30Metrics.mapNotNull { it.toActivity() }
        val activityResult = metrics.toActivity()?.let { todayActivity ->
            TrendCalculators.calculateActivityScore(
                today = todayActivity,
                last30 = past30Activity,
                priorDayActivity = past30Metrics.lastOrNull { it.dateEpochDay == today - 1 }?.toActivity(),
                qualityInput = qualityInput
            )
        }
        val activityScore = activityResult?.score

        // Denominator is the days ACTUALLY RECORDED, not a hardcoded 30.
        val consistencyScore = TrendCalculators.calculateConsistencyScore(past30Activity, stepGoal)

        // VO2 Max — age-referenced, correct Uth coefficient.
        val vo2Max = todayRHR?.let {
            Vo2MaxEstimator.estimateFromRestingHR(it.bpm, userMaxHR, userAge).vo2Max
        }

        // Bio age
        val bioAgeResult = vo2Max?.let {
            BiologicalAgeEstimator.estimate(
                chronologicalAge = userAge,
                vo2Max = it,
                restingHR30Days = restingHRBaseline30,
                activityData30Days = past30Activity,
                stepGoal = stepGoal
            )
        }
        val bioAge = bioAgeResult?.takeIf { !it.insufficientData }?.estimatedAge

        // ── B1 — Momentum ─────────────────────────────────────────────────────
        // ASCENDING. MomentumCalculator documents its parameters as "newest last" and
        // computes a slope over positional index; feeding it the DESC getLatestN() list
        // negated that slope, so a recovery score climbing 55→75 was stored as DECLINING
        // and the Home screen arrow pointed down. All three indicators were inverted.
        val recent5Ascending = computedScoresDao.getLatestNAscending(5)
        val last5Recovery = recent5Ascending.mapNotNull { it.recoveryScore }
        val last5Sleep    = recent5Ascending.mapNotNull { it.sleepScore }
        // Per-DAY strain, not per-session load — training momentum was previously computed
        // over a session list, so it moved when sessions were logged rather than over time.
        val last5Strain = recent5Ascending.mapNotNull { it.strain }.map { it / 21f * 100f }
        val momentum = MomentumCalculator.calculate(last5Recovery, last5Sleep, last5Strain)

        // Part 11 — Energy Bank calculation
        val checkIn = checkInDao.getForDay(today)
        val restDaysLast7 = run {
            val exerciseDays = past7Exercise.map { it.dateEpochDay }.toSet()
            (0..6).count { i -> (today - i) !in exerciseDays }
        }
        val sleepDurations7 = sleepPrior7.map { it.durationMinutes }
        val energyBankResult = EnergyBankCalculator.calculate(
            EnergyBankCalculator.EnergyBankInput(
                sleepDurationsLast7 = sleepDurations7,
                restDaysLast7 = restDaysLast7,
                acwr = acwrResult.acwr,
                stressScore = stressScore,
                sorenessRating = checkIn?.soreness,
                rhrDeviationBpm = todayRHR?.bpm?.takeIf { restingHRBaseline30.isNotEmpty() }?.let { bpm ->
                    bpm - restingHRBaseline30.map { it.bpm }.average().toInt()
                },
                recoveryScore = recoveryScore,
                trainingLoadNormalized = todayLoadNormalized
            )
        )

        computedScoresDao.upsert(
            ComputedScoresEntity(
                dateEpochDay = today,
                recoveryScore = recoveryScore,
                recoveryConfidence = recoveryResult?.confidence?.name,
                recoveryExplanation = recoveryResult?.explanation,
                recoveryBreakdown = recoveryResult?.breakdown?.encodeToString(),
                readinessScore = readinessScore,
                readinessConfidence = readinessResult?.confidence?.name,
                readinessExplanation = readinessResult?.explanation,
                readinessBreakdown = readinessResult?.breakdown?.encodeToString(),
                sleepScore = sleepScore,
                sleepConfidence = sleepResult?.confidence?.name,
                sleepExplanation = sleepResult?.explanation,
                sleepBreakdown = sleepResult?.breakdown?.encodeToString(),
                stressScore = stressScore,
                stressConfidence = stressResult?.confidence?.name,
                stressExplanation = stressResult?.explanation,
                stressBreakdown = stressResult?.breakdown?.encodeToString(),
                activityScore = activityScore,
                consistencyScore = consistencyScore,
                lifestyleScore = if (activityScore != null && sleepScore != null)
                    TrendCalculators.calculateLifestyleScore(consistencyScore, activityScore, sleepScore).score
                else null,
                // Retained but demoted: this is now TODAY's actual summed session load,
                // not a stale prior session, and it is no longer a display value anywhere.
                // `strain` below replaces it in the UI.
                trainingLoadNormalized = todayLoadNormalized,
                strain = strainResult.strain,
                dailyExertionMinutes = strainResult.exertionMinutes,
                strainConfidence = strainResult.confidence.name,
                strainExplanation = strainResult.explanation,
                strainBreakdown = strainResult.breakdown.encodeToString(),
                strainIsProxy = strainResult.isProxyEstimate,
                strainZone = strainResult.zone?.name,
                acwr = acwrResult.acwr,
                acwrZone = acwrResult.zone.name,
                acwrIsMeaningful = acwrResult.isMeaningful,
                acwrDaysOfHistory = acwrResult.daysOfHistory,
                vo2MaxEstimate = vo2Max,
                biologicalAge = bioAge,
                weeklyHealthScore = null,
                monthlyHealthScore = null,
                recoveryMomentum  = momentum.recovery.direction.name,
                sleepMomentum     = momentum.sleep.direction.name,
                trainingMomentum  = momentum.training.direction.name,
                dataQualityLevel  = recoveryResult?.dataQuality?.level?.name,
                dataQualityPercent = recoveryResult?.dataQuality?.confidencePercent,
                energyBankScore       = energyBankResult.score,
                energyBankExplanation = energyBankResult.explanation,
                energyBankBreakdown   = energyBankResult.breakdown.encodeToString(),
                hrr1 = hrrResult?.hrr1,
                hrr2 = hrrResult?.hrr2,
                hrrTrend = hrrResult?.trend?.name
            )
        )

        // ── B7 — Achievement evaluation ───────────────────────────────────────
        // Records come from queries scoped to days STRICTLY BEFORE today, so a personal
        // best is compared against the prior record rather than against a maximum that
        // already includes today's freshly-written row — which made three achievements
        // unreachable. Streaks walk the calendar via day-keyed maps, so a day the watch
        // spent charging breaks the streak instead of being skipped over.
        val achievementInput = AchievementEngine.AchievementInput(
            todayEpochDay = today,
            personalSleepNeedMinutes = sleepNeedMinutes,
            stepGoal = stepGoal,
            sleepByDay = dailyMetricsDao.getSleepDurationsInRange(today - 400, today)
                .associate { it.dateEpochDay to it.value },
            stepsByDay = dailyMetricsDao.getStepsInRange(today - 400, today)
                .associate { it.dateEpochDay to it.value },
            todayRecovery = recoveryScore,
            todayRestingHR = metrics.restingHR,
            todaySleepScore = sleepScore,
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
        val todayDate = LocalDate.now()
        val today = todayDate.toEpochDay()
        // Monday of the current week.
        val weekStartDate = todayDate.minusDays((todayDate.dayOfWeek.value - 1).toLong())
        val weekStart = weekStartDate.toEpochDay()
        val weekEnd = minOf(weekStart + 6, today)

        val scores = computedScoresDao.getRange(weekStart, weekEnd)
        if (scores.isEmpty()) return

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
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val monthStart = today.withDayOfMonth(1).toEpochDay()

        val scores = computedScoresDao.getRange(monthStart, todayEpoch)
        if (scores.isEmpty()) return

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
        val metrics = dailyMetricsDao.getRange(monthStart, todayEpoch)
        val personalRecords = buildList {
            scores.filter { it.recoveryScore != null }.maxByOrNull { it.recoveryScore!! }?.let {
                add("Best Recovery: ${it.recoveryScore!!.toInt()} on ${LocalDate.ofEpochDay(it.dateEpochDay)}")
            }
            metrics.filter { it.restingHR != null }.minByOrNull { it.restingHR!! }?.let {
                add("Lowest Resting HR: ${it.restingHR} bpm on ${LocalDate.ofEpochDay(it.dateEpochDay)}")
            }
            metrics.filter { it.sleepDurationMinutes != null }.maxByOrNull { it.sleepDurationMinutes!! }?.let {
                add("Longest Sleep: ${it.sleepDurationMinutes!! / 60}h ${it.sleepDurationMinutes!! % 60}m on ${LocalDate.ofEpochDay(it.dateEpochDay)}")
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
