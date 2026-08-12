package com.example.vitalcoreai.data.db.dao

import androidx.room.*
import com.example.vitalcoreai.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyMetricsDao {
    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay = :day")
    suspend fun getForDay(day: Long): DailyMetricsEntity?

    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay >= :startDay ORDER BY dateEpochDay ASC")
    fun getFrom(startDay: Long): Flow<List<DailyMetricsEntity>>

    @Query("SELECT * FROM daily_metrics ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<DailyMetricsEntity>

    @Query("SELECT * FROM daily_metrics WHERE dateEpochDay BETWEEN :start AND :end ORDER BY dateEpochDay ASC")
    suspend fun getRange(start: Long, end: Long): List<DailyMetricsEntity>

    @Upsert
    suspend fun upsert(entity: DailyMetricsEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DailyMetricsEntity>)

    // B8 — Historical search queries
    @Query("SELECT * FROM daily_metrics WHERE steps >= :minSteps ORDER BY steps DESC")
    suspend fun searchByMinSteps(minSteps: Int): List<DailyMetricsEntity>

    @Query("SELECT * FROM daily_metrics WHERE sleepDurationMinutes >= :minMinutes ORDER BY sleepDurationMinutes DESC")
    suspend fun searchByMinSleep(minMinutes: Int): List<DailyMetricsEntity>

    @Query("SELECT * FROM daily_metrics WHERE restingHR IS NOT NULL ORDER BY restingHR DESC LIMIT :limit")
    suspend fun getHighestHRDays(limit: Int): List<DailyMetricsEntity>

    @Query("SELECT * FROM daily_metrics WHERE restingHR IS NOT NULL ORDER BY restingHR ASC LIMIT :limit")
    suspend fun getLowestHRDays(limit: Int): List<DailyMetricsEntity>

    // B6 — Incremental sync: max existing timestamp for a given day range
    @Query("SELECT MAX(lastSyncTimestampMs) FROM daily_metrics")
    suspend fun getMaxSyncTimestamp(): Long?

    // ── Honest history depth ─────────────────────────────────────────────────
    // A COUNT over rows that actually hold data, rather than a list size capped by a
    // query LIMIT. `totalDaysOfData = getLatest(30).size` was capped at 30, so the
    // "one month of data" milestone fired by construction and the "one week" milestone
    // depended on the == 7 equality landing exactly during the backfill loop.

    /** Days with genuine Health Connect data. This is the number the UI must show. */
    @Query("SELECT COUNT(*) FROM daily_metrics WHERE hasData = 1")
    suspend fun countDaysWithData(): Int

    @Query("SELECT COUNT(*) FROM daily_metrics WHERE hasData = 1 AND dateEpochDay BETWEEN :start AND :end")
    suspend fun countDaysWithDataInRange(start: Long, end: Long): Int

    /** Every day key present in a range — used to find gaps the sync must fill. */
    @Query("SELECT dateEpochDay FROM daily_metrics WHERE dateEpochDay BETWEEN :start AND :end")
    suspend fun getPresentDays(start: Long, end: Long): List<Long>

    @Query("SELECT MIN(dateEpochDay) FROM daily_metrics WHERE hasData = 1")
    suspend fun getEarliestDayWithData(): Long?

    /** All-time lowest resting HR strictly BEFORE :day — the true prior record. */
    @Query("SELECT MIN(restingHR) FROM daily_metrics WHERE restingHR IS NOT NULL AND dateEpochDay < :day")
    suspend fun getLowestRestingHRBefore(day: Long): Int?

    /** epoch day → minutes slept, for calendar-walking streaks. */
    @Query("SELECT dateEpochDay, sleepDurationMinutes AS value FROM daily_metrics WHERE sleepDurationMinutes IS NOT NULL AND dateEpochDay BETWEEN :start AND :end")
    suspend fun getSleepDurationsInRange(start: Long, end: Long): List<DayIntValue>

    @Query("SELECT dateEpochDay, steps AS value FROM daily_metrics WHERE steps IS NOT NULL AND dateEpochDay BETWEEN :start AND :end")
    suspend fun getStepsInRange(start: Long, end: Long): List<DayIntValue>
}

/** Projection for day-keyed integer series (streaks, gap detection). */
data class DayIntValue(val dateEpochDay: Long, val value: Int)

@Dao
interface ComputedScoresDao {
    @Query("SELECT * FROM computed_scores WHERE dateEpochDay = :day")
    suspend fun getForDay(day: Long): ComputedScoresEntity?

    @Query("SELECT * FROM computed_scores ORDER BY dateEpochDay DESC LIMIT 1")
    fun getLatest(): Flow<ComputedScoresEntity?>

    @Query("SELECT * FROM computed_scores WHERE dateEpochDay >= :startDay ORDER BY dateEpochDay ASC")
    fun getFrom(startDay: Long): Flow<List<ComputedScoresEntity>>

    @Query("SELECT * FROM computed_scores WHERE dateEpochDay BETWEEN :start AND :end ORDER BY dateEpochDay ASC")
    suspend fun getRange(start: Long, end: Long): List<ComputedScoresEntity>

    @Query("SELECT * FROM computed_scores ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getLatestN(limit: Int): List<ComputedScoresEntity>

    @Upsert
    suspend fun upsert(entity: ComputedScoresEntity)

    // B8 — Historical search queries
    @Query("SELECT * FROM computed_scores WHERE recoveryScore < :maxScore ORDER BY recoveryScore ASC LIMIT :limit")
    suspend fun searchPoorRecoveryDays(maxScore: Float, limit: Int = 30): List<ComputedScoresEntity>

    @Query("SELECT * FROM computed_scores WHERE sleepScore >= :minScore ORDER BY sleepScore DESC LIMIT :limit")
    suspend fun searchBestSleepNights(minScore: Float, limit: Int = 30): List<ComputedScoresEntity>

    @Query("SELECT * FROM computed_scores ORDER BY recoveryScore DESC LIMIT :limit")
    suspend fun getTopRecoveryDays(limit: Int = 10): List<ComputedScoresEntity>

    // B3 — Dynamic home ordering: fetch last N days
    @Query("SELECT * FROM computed_scores ORDER BY dateEpochDay DESC LIMIT 5")
    suspend fun getLatest5(): List<ComputedScoresEntity>

    // ── Chronological reads ──────────────────────────────────────────────────
    // Every consumer documented as "newest last" must be fed from one of these, never
    // from getLatestN(), which is ORDER BY dateEpochDay DESC. Feeding a DESC list to
    // MomentumCalculator negated its slope, so a recovery score climbing 55→75 was
    // reported as DECLINING and the momentum arrow pointed down.

    /** The N most recent rows, returned OLDEST FIRST. */
    @Query("SELECT * FROM (SELECT * FROM computed_scores ORDER BY dateEpochDay DESC LIMIT :limit) ORDER BY dateEpochDay ASC")
    suspend fun getLatestNAscending(limit: Int): List<ComputedScoresEntity>

    /**
     * The N most recent rows STRICTLY BEFORE :day, oldest first.
     *
     * Every history series the score pipeline consumes reads through this rather than
     * [getLatestNAscending]. `syncToday` re-scores the trailing three days on every run, so
     * by the second pass over a day its own row is already in the table — and a series that
     * includes it feeds the previous run's output back into the current run's input. Momentum
     * and the HRR trend both did exactly that, which meant the second sync of a day could
     * disagree with the first. The pipeline appends today's freshly computed value instead.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM computed_scores WHERE dateEpochDay < :day " +
            "ORDER BY dateEpochDay DESC LIMIT :limit) ORDER BY dateEpochDay ASC"
    )
    suspend fun getBeforeAscending(day: Long, limit: Int): List<ComputedScoresEntity>

    // ── True prior records (strictly before :day) ────────────────────────────
    // These were previously derived from a window that already contained today's freshly
    // upserted row, so `today > allTimeHigh` compared a value against a maximum including
    // itself — making three achievements unreachable. They are also unbounded, so
    // "all-time" is genuinely all-time rather than "this month".

    @Query("SELECT MAX(recoveryScore) FROM computed_scores WHERE recoveryScore IS NOT NULL AND dateEpochDay < :day")
    suspend fun getBestRecoveryBefore(day: Long): Float?

    @Query("SELECT MAX(sleepScore) FROM computed_scores WHERE sleepScore IS NOT NULL AND dateEpochDay < :day")
    suspend fun getBestSleepScoreBefore(day: Long): Float?

    @Query("SELECT COUNT(*) FROM computed_scores")
    suspend fun countAll(): Int
}

@Dao
interface ExerciseSessionDao {
    @Query("SELECT * FROM exercise_sessions WHERE dateEpochDay = :day ORDER BY startMs DESC")
    suspend fun getForDay(day: Long): List<ExerciseSessionEntity>

    @Query("SELECT * FROM exercise_sessions WHERE dateEpochDay >= :startDay ORDER BY startMs DESC")
    fun getFrom(startDay: Long): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE dateEpochDay BETWEEN :start AND :end ORDER BY startMs ASC")
    suspend fun getRange(start: Long, end: Long): List<ExerciseSessionEntity>

    @Upsert
    suspend fun upsert(entity: ExerciseSessionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ExerciseSessionEntity>)

    // B8 — Historical search
    @Query("SELECT * FROM exercise_sessions WHERE caloriesBurned >= :minKcal ORDER BY caloriesBurned DESC")
    suspend fun searchByMinCalories(minKcal: Int): List<ExerciseSessionEntity>

    @Query("SELECT * FROM exercise_sessions ORDER BY startMs DESC")
    fun getAllFlow(): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE startMs = :startMs")
    suspend fun getByStartMs(startMs: Long): ExerciseSessionEntity?

    @Query("SELECT * FROM exercise_sessions ORDER BY startMs DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<ExerciseSessionEntity>
}

@Dao
interface HeartRateSampleDao {
    @Query("SELECT * FROM heart_rate_samples WHERE dateEpochDay = :day ORDER BY timestampMs ASC")
    suspend fun getForDay(day: Long): List<HeartRateSampleEntity>

    @Query("SELECT * FROM heart_rate_samples WHERE dateEpochDay BETWEEN :start AND :end ORDER BY timestampMs ASC")
    suspend fun getRange(start: Long, end: Long): List<HeartRateSampleEntity>

    @Query("SELECT COUNT(*) FROM heart_rate_samples WHERE dateEpochDay = :day")
    suspend fun countForDay(day: Long): Int

    /** Works as intended now that timestampMs carries a unique index. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<HeartRateSampleEntity>)

    /** Called before re-inserting a day, so a re-sync replaces rather than duplicates. */
    @Query("DELETE FROM heart_rate_samples WHERE dateEpochDay = :day")
    suspend fun deleteForDay(day: Long)

    /** Retention cutoff — this table is unbounded otherwise. */
    @Query("DELETE FROM heart_rate_samples WHERE dateEpochDay < :cutoffDay")
    suspend fun deleteBefore(cutoffDay: Long)
}

@Dao
interface WeeklyReportDao {
    @Query("SELECT * FROM weekly_reports ORDER BY weekStartEpochDay DESC LIMIT 1")
    fun getLatest(): Flow<WeeklyReportEntity?>

    @Query("SELECT * FROM weekly_reports ORDER BY weekStartEpochDay DESC LIMIT :limit")
    suspend fun getLatestN(limit: Int): List<WeeklyReportEntity>

    @Query("SELECT * FROM weekly_reports WHERE weekStartEpochDay = :start")
    suspend fun getForWeek(start: Long): WeeklyReportEntity?

    @Upsert
    suspend fun upsert(entity: WeeklyReportEntity)
}

@Dao
interface MonthlyReportDao {
    @Query("SELECT * FROM monthly_reports ORDER BY monthStartEpochDay DESC LIMIT 1")
    fun getLatest(): Flow<MonthlyReportEntity?>

    @Query("SELECT * FROM monthly_reports ORDER BY monthStartEpochDay DESC LIMIT :limit")
    suspend fun getLatestN(limit: Int): List<MonthlyReportEntity>

    @Upsert
    suspend fun upsert(entity: MonthlyReportEntity)
}

// B7 — Achievements DAO
@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY earnedEpochDay DESC")
    fun getAllEarned(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements WHERE achievementId = :id LIMIT 1")
    suspend fun getById(id: String): AchievementEntity?

    @Query("SELECT * FROM achievements WHERE earnedEpochDay BETWEEN :start AND :end ORDER BY earnedEpochDay DESC")
    suspend fun getEarnedBetween(start: Long, end: Long): List<AchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AchievementEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<AchievementEntity>)

    @Query("SELECT COUNT(*) FROM achievements")
    suspend fun count(): Int
}

// B6 — Sync state DAO (incremental sync tracking)
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE recordType = :type")
    suspend fun getForType(type: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(entity: SyncStateEntity)

    @Query("SELECT MIN(lastSyncTimestampMs) FROM sync_state")
    suspend fun getEarliestSyncTimestamp(): Long?

    @Query("SELECT * FROM sync_state ORDER BY recordType ASC")
    suspend fun getAll(): List<SyncStateEntity>
}

// Part 12 — Check-In DAO
@Dao
interface CheckInDao {
    @Query("SELECT * FROM check_ins WHERE dateEpochDay = :day")
    suspend fun getForDay(day: Long): CheckInEntity?

    @Query("SELECT * FROM check_ins ORDER BY dateEpochDay DESC LIMIT 1")
    suspend fun getLatest(): CheckInEntity?

    @Query("SELECT * FROM check_ins ORDER BY dateEpochDay DESC LIMIT :limit")
    suspend fun getLatestN(limit: Int): List<CheckInEntity>

    @Query("SELECT * FROM check_ins WHERE dateEpochDay >= :startDay ORDER BY dateEpochDay ASC")
    fun getFrom(startDay: Long): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE dateEpochDay BETWEEN :start AND :end ORDER BY dateEpochDay ASC")
    suspend fun getRange(start: Long, end: Long): List<CheckInEntity>

    @Upsert
    suspend fun upsert(entity: CheckInEntity)
}

// Part 13 — Journal DAO
@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entries WHERE dateEpochDay = :day ORDER BY createdAtMs DESC")
    suspend fun getForDay(day: Long): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE dateEpochDay >= :startDay ORDER BY dateEpochDay DESC")
    fun getFrom(startDay: Long): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries WHERE habitId = :habitId ORDER BY dateEpochDay DESC")
    suspend fun getByHabit(habitId: String): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE habitId = :habitId AND dateEpochDay >= :startDay ORDER BY dateEpochDay ASC")
    suspend fun getByHabitFrom(habitId: String, startDay: Long): List<JournalEntryEntity>

    @Query("SELECT DISTINCT habitId FROM journal_entries ORDER BY habitId ASC")
    suspend fun getAllHabitIds(): List<String>

    @Insert
    suspend fun insert(entity: JournalEntryEntity): Long

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

// Part 9 — Muscle Recovery DAO
@Dao
interface MuscleRecoveryDao {
    @Query("SELECT * FROM muscle_recovery ORDER BY lastTrainedEpochDay DESC")
    fun getAll(): Flow<List<MuscleRecoveryEntity>>

    @Query("SELECT * FROM muscle_recovery WHERE muscleGroup = :group ORDER BY lastTrainedEpochDay DESC LIMIT 1")
    suspend fun getForGroup(group: String): MuscleRecoveryEntity?

    @Upsert
    suspend fun upsert(entity: MuscleRecoveryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MuscleRecoveryEntity>)

    /**
     * The primary key here is autogenerated and nothing in the table is unique per group, so
     * `@Upsert` appends rather than replaces — a sync every four hours would grow the table
     * by fourteen rows a time and the Home screen would render each group repeatedly. The
     * table is a snapshot of *current* status, so it is rewritten wholesale.
     */
    @Query("DELETE FROM muscle_recovery")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(entities: List<MuscleRecoveryEntity>) {
        deleteAll()
        upsertAll(entities)
    }
}

// Part 9 — Workout Exercise DAO (per-exercise within a session)
@Dao
interface WorkoutExerciseDao {
    @Query("SELECT * FROM workout_exercises WHERE sessionStartMs = :sessionMs ORDER BY orderIndex ASC")
    suspend fun getForSession(sessionMs: Long): List<WorkoutExerciseEntity>

    @Query("SELECT * FROM workout_exercises WHERE sessionStartMs = :sessionMs ORDER BY orderIndex ASC")
    fun getForSessionFlow(sessionMs: Long): Flow<List<WorkoutExerciseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WorkoutExerciseEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<WorkoutExerciseEntity>)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM workout_exercises WHERE sessionStartMs = :sessionMs")
    suspend fun deleteAllForSession(sessionMs: Long)
}
