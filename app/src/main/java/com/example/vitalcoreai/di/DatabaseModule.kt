package com.example.vitalcoreai.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VitalCoreDatabase =
        Room.databaseBuilder(context, VitalCoreDatabase::class.java, "vitalcore_db")
            .fallbackToDestructiveMigration(true)
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
