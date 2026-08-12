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
 *   v7 — Persisted V1.1 intelligence outputs on computed_scores:
 *         forecast (low/high/confidence/drivers/risks), sleep consistency
 *         (score/label/bedtime SD/wake SD), recovery trends (7/14/30 direction +
 *         contributors), anomalies (encoded + count), recommendation
 *         (type/intensity/volume/detail), data-quality factors and positives.
 *         daily_metrics + newestRecordTimestampMs (freshness input).
 *         Index on computed_scores.dateEpochDay.
 *
 * Migration strategy: **real migrations from v6 onward** — see [Migrations].
 *
 * The previous blanket `fallbackToDestructiveMigration(dropAllTables = true)` was justified
 * on the grounds that this database is a reconstructible cache of Health Connect. That is
 * true of eight tables and false of four: `check_ins`, `journal_entries`,
 * `workout_exercises` and `muscle_recovery` are user-authored and exist nowhere else. A
 * year of journal entries is precisely the data that makes habit correlation meaningful,
 * and losing it fails silently — the correlation engine simply drops below its minimum
 * sample size and stops producing insights.
 *
 * Destructive fallback is now scoped to versions 1–5 only (see
 * [Migrations.DESTRUCTIVE_FALLBACK_FROM]).
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
    version = 7,
    // Exported so MigrationTestHelper can verify each migration against the real schema
    // rather than against the entities the same build just generated.
    exportSchema = true
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
