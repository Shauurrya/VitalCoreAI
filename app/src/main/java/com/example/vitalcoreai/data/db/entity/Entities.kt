package com.example.vitalcoreai.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────────────────────
// Room entities for VitalCore AI
// Schema version: 6 — see VitalCoreDatabase.kt for the full version history.
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "daily_metrics")
data class DailyMetricsEntity(
    @PrimaryKey val dateEpochDay: Long,
    val restingHR: Int?,
    val steps: Int?,
    val distanceMeters: Float?,
    val caloriesBurned: Int?,
    val weightKg: Float?,
    val bodyFatPercent: Float?,
    val spO2Percent: Float?,           // null if Fit 3 (no sensor)
    val sleepDurationMinutes: Int?,
    val sleepEfficiencyPercent: Double?,
    val sleepDeepMinutes: Int?,
    val sleepRemMinutes: Int?,
    val sleepLightMinutes: Int?,
    val sleepAwakeMinutes: Int?,
    val bedtimeMinuteOfDay: Int?,
    val wakeTimeMinuteOfDay: Int?,
    // B4/B6 — incremental sync support
    val lastSyncTimestampMs: Long = System.currentTimeMillis(),
    // A3/A4 — data quality flags
    val dataSourceType: String? = null,    // "WATCH_SENSOR" | "PHONE_SENSOR" | "MANUAL"
    val hrPointsPerHour: Double? = null,   // A4 wear detection proxy
    val partialDayFraction: Double? = null, // A4 partial day detection
    /**
     * True when restingHR was estimated from overnight HR samples rather than read
     * from a RestingHeartRateRecord. Samsung Health does not reliably write that
     * record type to Health Connect, so for a Galaxy Watch Active 2 this is usually
     * true — the UI labels it and the confidence model discounts it.
     */
    val restingHRDerived: Boolean? = null,
    // Barometer-derived, supplied by Samsung Health when available
    val floorsClimbed: Int? = null,
    val elevationGainMeters: Float? = null,
    /** Opportunistic only — always null on a Galaxy Watch Active 2. */
    val hrvRmssdMs: Double? = null,
    /**
     * Activity-only energy, separate from [caloriesBurned] (which is BMR + activity).
     * Null when Samsung Health does not write ActiveCaloriesBurnedRecord.
     */
    val activeCalories: Int? = null,
    /** How many SpO₂ readings backed [spO2Percent] — below 3 no recovery modifier applies. */
    val spO2ReadingCount: Int? = null,
    /** True when [spO2Percent] came from the overnight window rather than daytime spot checks. */
    val spO2FromSleepWindow: Boolean? = null,
    /** False when the sleep session carried no stage breakdown. */
    val sleepStagesAvailable: Boolean? = null,
    /**
     * True when Health Connect actually returned something for this day.
     *
     * A row used to be written unconditionally, with steps/distance/calories coerced to 0
     * because those reads returned non-null zeros. Those phantom rows entered the step
     * baseline as genuine sedentary days and inflated the "days of history" count that
     * drives achievement milestones. Rows are now only written when this is true, and the
     * flag is kept so an honest count survives any legacy row.
     */
    val hasData: Boolean = true,
    /**
     * Timestamp of the newest Health Connect record backing this day.
     *
     * Feeds `DataQualityEngine.QualityInput.dataAgeHours`. Completeness and freshness are
     * different failures: a day can be fully populated and still be scored from data that
     * stopped arriving 30 hours ago because the watch has not synced.
     */
    val newestRecordTimestampMs: Long? = null
)

@Entity(tableName = "computed_scores")
data class ComputedScoresEntity(
    @PrimaryKey val dateEpochDay: Long,
    val recoveryScore: Float?,
    val recoveryConfidence: String?,
    val recoveryExplanation: String?,
    val recoveryBreakdown: String? = null,   // encoded List<ScoreFactor> — see ScoreResult.kt
    val readinessScore: Float?,
    val readinessConfidence: String? = null,
    val readinessExplanation: String? = null,
    val readinessBreakdown: String? = null,
    val sleepScore: Float?,
    val sleepConfidence: String? = null,
    val sleepExplanation: String? = null,
    val sleepBreakdown: String? = null,
    val stressScore: Float?,
    val stressConfidence: String? = null,
    val stressExplanation: String? = null,
    val stressBreakdown: String? = null,
    val activityScore: Float?,
    val consistencyScore: Float?,
    val lifestyleScore: Float?,
    val trainingLoadNormalized: Float?,
    val acwr: Float?,
    val acwrZone: String?,
    val vo2MaxEstimate: Float?,
    val biologicalAge: Int?,
    val weeklyHealthScore: Float?,
    val monthlyHealthScore: Float?,
    // B1 — Momentum indicators (nullable for backward compatibility)
    val recoveryMomentum: String? = null,  // "IMPROVING" | "STABLE" | "DECLINING"
    val sleepMomentum: String? = null,
    val trainingMomentum: String? = null,
    // A3 — Data quality
    val dataQualityLevel: String? = null,  // "HIGH" | "MEDIUM" | "LOW"
    val dataQualityPercent: Int? = null,
    // Part 11 — Energy Bank
    val energyBankScore: Float? = null,
    val energyBankExplanation: String? = null,
    val energyBankBreakdown: String? = null,
    // Part 7 — Heart Rate Recovery
    val hrr1: Int? = null,         // HR drop after 1 min post-workout (bpm)
    val hrr2: Int? = null,         // HR drop after 2 min post-workout (bpm)
    val hrrTrend: String? = null,  // "IMPROVING" | "STABLE" | "DECLINING"

    // ─── Strain: the 0–21 logarithmic daily load scale ───────────────────────
    // Replaces trainingLoadNormalized as the day-level DISPLAY value. The old column is
    // retained (other code still reads it and it remains the per-session ACWR input) but
    // is no longer shown anywhere in the UI.
    /** 0–21, displayed to one decimal. Null means no data — never render it as 0.0. */
    val strain: Float? = null,
    /**
     * The raw `E_day` exertion-minute integral behind [strain].
     * Persisted so StrainCalculator's four scale constants can be re-tuned later without
     * re-reading Health Connect for every historical day.
     */
    val dailyExertionMinutes: Float? = null,
    val strainConfidence: String? = null,     // Confidence.name
    val strainExplanation: String? = null,
    val strainBreakdown: String? = null,      // encoded List<ScoreFactor> — see ScoreResult.kt
    /** True when strain came from the steps/exercise proxy rather than measured HR. */
    val strainIsProxy: Boolean? = null,
    /** StrainCalculator.StrainZone.name */
    val strainZone: String? = null,

    /** False → the UI must render "Needs 14 days", never an ACWR zone label. */
    val acwrIsMeaningful: Boolean? = null,
    val acwrDaysOfHistory: Int? = null,

    // ─── v7 — persisted outputs of the V1.1 intelligence engines ──────────────
    // Persisted rather than recomputed per screen open: the forecast and trend engines
    // each read ~30 days of history, and a stored value is one the debug screen can
    // inspect after the fact when a user reports something odd.
    //
    // Every column here is nullable because SQLite's ALTER TABLE ADD COLUMN requires it —
    // see Migrations.MIGRATION_6_7. Null means "not computed", never zero.

    /** Tomorrow's projected readiness range. Render as "low–high", never a midpoint. */
    val forecastLow: Int? = null,
    val forecastHigh: Int? = null,
    val forecastConfidence: String? = null,
    /** Pipe-delimited "name:points:description" driver list. */
    val forecastDrivers: String? = null,
    val forecastRisks: String? = null,

    /** Sleep regularity, distinct from sleep duration or quality. */
    val sleepConsistencyScore: Float? = null,
    val sleepConsistencyLabel: String? = null,
    val bedtimeSdMinutes: Int? = null,
    val wakeSdMinutes: Int? = null,

    /** RobustStats.Trend.name per window. */
    val trend7Direction: String? = null,
    val trend14Direction: String? = null,
    val trend30Direction: String? = null,
    /** Pipe-delimited contributor descriptions for the 14-day window. */
    val trendContributors: String? = null,

    /** Pipe-delimited "metric:severity:title" — full text is regenerated on demand. */
    val anomaliesEncoded: String? = null,
    val anomalyCount: Int? = null,

    val recommendationType: String? = null,
    val recommendationIntensity: String? = null,
    val recommendationVolumePct: Int? = null,
    val recommendationDetail: String? = null,

    /** Pipe-delimited "FACTOR=0.85" for the six data-quality dimensions. */
    val dataQualityFactors: String? = null,
    val dataQualityPositives: String? = null,

    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "exercise_sessions")
data class ExerciseSessionEntity(
    @PrimaryKey val startMs: Long,
    val dateEpochDay: Long,
    val endMs: Long,
    /** Readable name, e.g. "Running" — resolved from [exerciseTypeId], never "56". */
    val exerciseType: String,
    /** Raw Health Connect EXERCISE_TYPE_* constant, so icon mapping keys off a stable id. */
    val exerciseTypeId: Int = 0,
    val durationMinutes: Int,
    val caloriesBurned: Int?,
    val distanceMeters: Float?,
    val avgHR: Int?,
    val maxHR: Int?,
    val trainingLoadNormalized: Float?,
    val dominantZone: String?,
    /** False → intensity was not measured; the UI must not present a zone breakdown. */
    val hasHeartRateData: Boolean = true,
    /** Time below 50% heart-rate reserve — genuine rest, not Zone 1. */
    val belowZone1Pct: Float? = null,
    val zone1Pct: Float?,
    val zone2Pct: Float?,
    val zone3Pct: Float?,
    val zone4Pct: Float?,
    val zone5Pct: Float?,
    // Part 9 — Muscle recovery tracking
    val muscleGroups: String? = null,   // pipe-delimited: "CHEST|BACK|SHOULDERS"
    val rpe: Int? = null                // Rate of Perceived Exertion 1–10
)

/**
 * Intraday HR samples.
 *
 * The unique index on `timestampMs` is what makes `@Insert(onConflict = IGNORE)` actually
 * work. With only an autogenerated primary key nothing ever collided, so every 4-hourly
 * sync appended a full duplicate copy of the day's samples — roughly six copies per day,
 * more with manual "Sync now" — into a table nothing pruned.
 */
@Entity(
    tableName = "heart_rate_samples",
    indices = [Index("dateEpochDay"), Index(value = ["timestampMs"], unique = true)]
)
data class HeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val timestampMs: Long,
    val bpm: Int
)

@Entity(tableName = "weekly_reports")
data class WeeklyReportEntity(
    @PrimaryKey val weekStartEpochDay: Long,
    val weekEndEpochDay: Long,
    val avgRecovery: Float?,
    val avgReadiness: Float?,
    val avgSleep: Float?,
    val avgActivity: Float?,
    val weeklyHealthScore: Float?,
    val deltaFromPreviousWeek: Float?,
    val explanation: String?,
    val highlights: String?,       // JSON array stored as string
    // B10 — extended report fields
    val personalRecords: String? = null,   // JSON
    val rankedFactors: String? = null,     // JSON
    val achievementsSummary: String? = null
)

@Entity(tableName = "monthly_reports")
data class MonthlyReportEntity(
    @PrimaryKey val monthStartEpochDay: Long,
    val monthEndEpochDay: Long,
    val avgRecovery: Float?,
    val avgReadiness: Float?,
    val avgSleep: Float?,
    val avgActivity: Float?,
    val monthlyHealthScore: Float?,
    val deltaFromPreviousMonth: Float?,
    val explanation: String?,
    // B10 — extended report fields
    val personalRecords: String? = null,
    val rankedFactors: String? = null
)

// B7 — Achievements table
@Entity(tableName = "achievements", indices = [Index("achievementId", unique = true)])
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val achievementId: String,      // AchievementEngine.AchievementId.name
    val title: String,
    val description: String,
    val icon: String,
    val earnedEpochDay: Long
)

// B6 — Sync state tracking per record type
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val recordType: String,   // e.g. "SleepSessionRecord"
    val lastSyncTimestampMs: Long,
    val lastSuccessfulSyncMs: Long = lastSyncTimestampMs
)

// ─────────────────────────────────────────────────────────────────────────────
// Part 12 — Daily Check-In (Morning Check-In)
// ─────────────────────────────────────────────────────────────────────────────
@Entity(tableName = "check_ins")
data class CheckInEntity(
    @PrimaryKey val dateEpochDay: Long,
    val energy: Int,           // 1–10
    val stress: Int,           // 1–10
    val soreness: Int,         // 1–10
    val sleepQuality: Int,     // 1–10
    val mood: Int,             // 1–10
    val motivation: Int? = null,    // 1–10 optional
    val illnessFlag: Boolean = false,
    val notes: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Part 13 — Journal / Habit Tracking
// ─────────────────────────────────────────────────────────────────────────────
@Entity(
    tableName = "journal_entries",
    indices = [Index("dateEpochDay"), Index("habitId")]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val habitId: String,        // e.g. "CAFFEINE", "ALCOHOL", "MEDITATION", "CUSTOM_xyz"
    val value: Float = 1f,      // quantity (cups, drinks, minutes, etc.)
    val unit: String = "",      // "cups", "drinks", "minutes", etc.
    val timeOfDay: String? = null,  // "MORNING" | "AFTERNOON" | "EVENING" | "NIGHT"
    val notes: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────────────────────
// Part 9 — Muscle Recovery Tracking
// ─────────────────────────────────────────────────────────────────────────────
@Entity(
    tableName = "muscle_recovery",
    indices = [Index("muscleGroup")]
)
data class MuscleRecoveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val muscleGroup: String,        // "CHEST" | "BACK" | "SHOULDERS" | "QUADS" | etc.
    val lastTrainedEpochDay: Long,
    val estimatedRecoveryHours: Int, // estimated hours to full recovery
    val volumeLoad: Float = 0f,     // total volume (sets * reps * weight)
    val rpe: Int = 5,               // RPE of last session targeting this group
    val sorenessRating: Int? = null, // from check-in, 1–10
    val status: String = "READY"    // "READY" | "RECOVERING" | "FATIGUED"
)

// ─────────────────────────────────────────────────────────────────────────────
// Part 9 — Workout Exercise Tracking (per-exercise within a session)
// ─────────────────────────────────────────────────────────────────────────────
@Entity(
    tableName = "workout_exercises",
    indices = [Index("sessionStartMs")]
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionStartMs: Long,       // FK to ExerciseSessionEntity.startMs
    val exerciseName: String,       // "Bench Press", "Squat", "Deadlift", etc.
    val sets: Int = 0,
    val reps: Int = 0,
    val weightKg: Float = 0f,
    val volume: Float = 0f,         // sets * reps * weight (auto-calculated)
    val rpe: Int? = null,           // Per-exercise RPE 1–10
    val muscleGroups: String = "",  // pipe-delimited: "CHEST|TRICEPS"
    val notes: String? = null,
    val orderIndex: Int = 0         // display order within the workout
)
