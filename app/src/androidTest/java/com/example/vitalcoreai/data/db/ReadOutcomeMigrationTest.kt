package com.example.vitalcoreai.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise a real v7 database upgrade, including user-authored records. */
@RunWith(AndroidJUnit4::class)
class ReadOutcomeMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VitalCoreDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun upgradePreservesReadingsCheckInsJournalAndWorkoutNotes() {
        helper.createDatabase("read-outcome-migration", 7).apply {
            execSQL("INSERT INTO daily_metrics (dateEpochDay, steps, lastSyncTimestampMs, hasData) VALUES (20000, 4321, 1000, 1)")
            execSQL("INSERT INTO sync_state (recordType, lastSyncTimestampMs, lastSuccessfulSyncMs) VALUES ('StepsRecord', 1000, 900)")
            execSQL("INSERT INTO check_ins (dateEpochDay, energy, stress, soreness, sleepQuality, mood, illnessFlag, notes, createdAtMs) VALUES (20000, 7, 3, 2, 8, 8, 0, 'kept check-in', 1000)")
            execSQL("INSERT INTO journal_entries (id, dateEpochDay, habitId, value, unit, notes, createdAtMs) VALUES (1, 20000, 'CAFFEINE', 2, 'cups', 'kept journal', 1000)")
            execSQL("INSERT INTO exercise_sessions (startMs, dateEpochDay, endMs, exerciseType, exerciseTypeId, durationMinutes, hasHeartRateData, muscleGroups, rpe) VALUES (1000, 20000, 2000, 'Walking', 0, 20, 0, 'QUADS', 6)")
            execSQL("INSERT INTO workout_exercises (id, sessionStartMs, exerciseName, sets, reps, weightKg, volume, muscleGroups, notes, orderIndex) VALUES (1, 1000, 'Squat', 3, 10, 20, 600, 'QUADS', 'kept workout note', 0)")
            close()
        }

        helper.runMigrationsAndValidate("read-outcome-migration", 8, true, Migrations.MIGRATION_7_8).use { db ->
            db.query("SELECT steps, staleRecordTypes FROM daily_metrics WHERE dateEpochDay = 20000").use {
                assertTrue(it.moveToFirst())
                assertEquals(4321, it.getInt(0))
                assertTrue(it.isNull(1))
            }
            db.query("SELECT lastSuccessfulSyncMs, outcome, lastSuccessfulReadMs FROM sync_state WHERE recordType = 'StepsRecord'").use {
                assertTrue(it.moveToFirst())
                assertEquals(900L, it.getLong(0))
                // A legacy sync row is not retroactively declared a verified read.
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
            listOf("check_ins" to "kept check-in", "journal_entries" to "kept journal", "workout_exercises" to "kept workout note").forEach { (table, note) ->
                db.query("SELECT notes FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(note, it.getString(0))
                    assertFalse(it.moveToNext())
                }
            }
            db.query("SELECT rpe, muscleGroups FROM exercise_sessions WHERE startMs = 1000").use {
                assertTrue(it.moveToFirst())
                assertEquals(6, it.getInt(0))
                assertEquals("QUADS", it.getString(1))
            }
        }
    }
}
