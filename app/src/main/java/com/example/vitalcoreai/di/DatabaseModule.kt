package com.example.vitalcoreai.di

import android.content.Context
import androidx.room.Room
import com.example.vitalcoreai.data.db.Migrations
import com.example.vitalcoreai.data.db.VitalCoreDatabase
import com.example.vitalcoreai.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Real migrations from v6 onward; destructive fallback only for v1–v5.
     *
     * The previous blanket `fallbackToDestructiveMigration(true)` would have silently
     * deleted `check_ins`, `journal_entries`, `workout_exercises` and `muscle_recovery` on
     * the next schema bump. Those four tables are user-authored and exist nowhere else —
     * unlike the Health Connect mirror tables, nothing can rebuild them.
     *
     * See [Migrations] for the policy and for how to add the next one.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VitalCoreDatabase =
        Room.databaseBuilder(context, VitalCoreDatabase::class.java, "vitalcore_db")
            .addMigrations(*Migrations.ALL)
            .fallbackToDestructiveMigrationFrom(
                true,
                *Migrations.DESTRUCTIVE_FALLBACK_FROM
            )
            .build()

    @Provides fun provideDailyMetricsDao(db: VitalCoreDatabase): DailyMetricsDao = db.dailyMetricsDao()
    @Provides fun provideComputedScoresDao(db: VitalCoreDatabase): ComputedScoresDao = db.computedScoresDao()
    @Provides fun provideExerciseSessionDao(db: VitalCoreDatabase): ExerciseSessionDao = db.exerciseSessionDao()
    @Provides fun provideHeartRateSampleDao(db: VitalCoreDatabase): HeartRateSampleDao = db.heartRateSampleDao()
    @Provides fun provideWeeklyReportDao(db: VitalCoreDatabase): WeeklyReportDao = db.weeklyReportDao()
    @Provides fun provideMonthlyReportDao(db: VitalCoreDatabase): MonthlyReportDao = db.monthlyReportDao()
    @Provides fun provideAchievementDao(db: VitalCoreDatabase): AchievementDao = db.achievementDao()
    @Provides fun provideSyncStateDao(db: VitalCoreDatabase): SyncStateDao = db.syncStateDao()
    @Provides fun provideCheckInDao(db: VitalCoreDatabase): CheckInDao = db.checkInDao()               // Part 12
    @Provides fun provideJournalDao(db: VitalCoreDatabase): JournalDao = db.journalDao()               // Part 13
    @Provides fun provideMuscleRecoveryDao(db: VitalCoreDatabase): MuscleRecoveryDao = db.muscleRecoveryDao() // Part 9
    @Provides fun provideWorkoutExerciseDao(db: VitalCoreDatabase): WorkoutExerciseDao = db.workoutExerciseDao() // Part 9
}
