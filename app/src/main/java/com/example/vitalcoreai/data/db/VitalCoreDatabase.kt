package com.example.vitalcoreai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.db.entity.*

/**
 * Room database definition for VitalCore AI.
 *
 * Schema version history:
 *   v1 — Initial schema (daily_metrics, computed_scores, exercise_sessions,
 *         heart_rate_samples, weekly_reports, monthly_reports)
 *   v2 — Added: achievements, sync_state tables;
 *         Added columns to computed_scores (momentum, data quality);
 *         Added columns to daily_metrics (dataSourceType, hrPointsPerHour, partialDayFraction);
 *         Added columns to weekly_reports and monthly_reports (B10 extended fields);
 *         Added index on heart_rate_samples.dateEpochDay.
 *   v3 — Added confidence/explanation/breakdown columns to computed_scores for
 *         readiness/sleep/stress (recovery already had confidence+explanation) so the
 *         Score Breakdown card and confidence badges on those 4 detail screens can show
 *         real computed values instead of hardcoded placeholders.
 *   v4 — Added check_ins table (Part 12: Daily Check-In);
 *         Added journal_entries table (Part 13: Journal / Habit Tracking);
 *         Added muscle_recovery table (Part 9: Muscle Recovery Tracking);
 *         Added energyBank columns to computed_scores (Part 11);
 *         Added hrr1/hrr2/hrrTrend columns to computed_scores (Part 7);
 *         Added muscleGroups/rpe columns to exercise_sessions (Part 9).
 *   v5 — Added workout_exercises table (per-exercise tracking within sessions);
 *         added daily_metrics columns restingHRDerived (resting HR estimated from overnight
 *         samples, because Samsung Health does not reliably write RestingHeartRateRecord to
 *         Health Connect), floorsClimbed, elevationGainMeters, hrvRmssdMs.
 *   v6 — Strain and data-honesty pass:
 *         computed_scores  + strain (0–21 log scale), dailyExertionMinutes (the raw E_day
 *                            integral, so the scale constants stay re-tunable),
 *                            strainConfidence, strainExplanation, strainBreakdown,
 *                            strainIsProxy, strainZone, acwrIsMeaningful, acwrDaysOfHistory.
 *                            trainingLoadNormalized is RETAINED but demoted — it is no
 *                            longer a display value anywhere.
 *         daily_metrics    + activeCalories (distinct from total, which is BMR + activity),
 *                            spO2ReadingCount, spO2FromSleepWindow, sleepStagesAvailable,
 *                            hasData (so a day Health Connect returned nothing for cannot
 *                            inflate history depth).
 *         exercise_sessions + exerciseTypeId, hasHeartRateData, belowZone1Pct.
 *         heart_rate_samples: unique index on timestampMs so onConflict = IGNORE actually
 *                            de-duplicates instead of appending a copy every sync.
 *
 * Migration strategy: fallbackToDestructiveMigration(dropAllTables = true).
 * Correct here because the database is a local cache of Health Connect data and is fully
 * reconstructible from it — the next sync backfills 30 days. Nothing in this database is
 * user-authored except check-ins and journal entries, which are cheap to re-enter relative
 * to maintaining hand-written migrations across a schema still in flux.
 */
@Database(
    entities = [
        DailyMetricsEntity::class,
        ComputedScoresEntity::class,
        ExerciseSessionEntity::class,
        HeartRateSampleEntity::class,
        WeeklyReportEntity::class,
        MonthlyReportEntity::class,
        AchievementEntity::class,        // B7
        SyncStateEntity::class,          // B6
        CheckInEntity::class,            // Part 12
        JournalEntryEntity::class,       // Part 13
        MuscleRecoveryEntity::class,     // Part 9
        WorkoutExerciseEntity::class     // Part 9 — per-exercise tracking
    ],
    version = 6,
    exportSchema = false
)
abstract class VitalCoreDatabase : RoomDatabase() {
    abstract fun dailyMetricsDao(): DailyMetricsDao
    abstract fun computedScoresDao(): ComputedScoresDao
    abstract fun exerciseSessionDao(): ExerciseSessionDao
    abstract fun heartRateSampleDao(): HeartRateSampleDao
    abstract fun weeklyReportDao(): WeeklyReportDao
    abstract fun monthlyReportDao(): MonthlyReportDao
    abstract fun achievementDao(): AchievementDao           // B7
    abstract fun syncStateDao(): SyncStateDao               // B6
    abstract fun checkInDao(): CheckInDao                   // Part 12
    abstract fun journalDao(): JournalDao                   // Part 13
    abstract fun muscleRecoveryDao(): MuscleRecoveryDao     // Part 9
    abstract fun workoutExerciseDao(): WorkoutExerciseDao   // Part 9 — per-exercise
}
