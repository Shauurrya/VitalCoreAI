package com.example.vitalcoreai.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.db.entity.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B13 — Room DAO instrumented tests.
 *
 * Run against a real in-memory Room database on a device or emulator.
 * Each test rebuilds the DB to guarantee isolation.
 *
 * Coverage:
 *  - DailyMetricsDao: insert + getRange + Flow
 *  - ComputedScoresDao: insert + getLatest + getRange
 *  - AchievementDao: insert + getAll
 *  - SyncStateDao: upsert + getLatestTimestamp
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DaoTests {

    private lateinit var db: VitalCoreDatabase
    private lateinit var dailyMetricsDao: DailyMetricsDao
    private lateinit var computedScoresDao: ComputedScoresDao
    private lateinit var achievementDao: AchievementDao
    private lateinit var syncStateDao: SyncStateDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VitalCoreDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dailyMetricsDao    = db.dailyMetricsDao()
        computedScoresDao  = db.computedScoresDao()
        achievementDao     = db.achievementDao()
        syncStateDao       = db.syncStateDao()
    }

    @After
    fun closeDb() = db.close()

    // ── DailyMetricsDao ───────────────────────────────────────────────────────

    @Test
    fun dailyMetrics_insertAndRetrieveByRange() = runTest {
        val day1 = buildMetrics(19900L, steps = 8000)
        val day2 = buildMetrics(19901L, steps = 12000)
        val day3 = buildMetrics(19902L, steps = 5000)

        dailyMetricsDao.upsert(day1)
        dailyMetricsDao.upsert(day2)
        dailyMetricsDao.upsert(day3)

        val result = dailyMetricsDao.getRange(19900L, 19901L)
        assertEquals("Should return 2 rows in range", 2, result.size)
        assertEquals(8000, result.first { it.dateEpochDay == 19900L }.steps)
        assertEquals(12000, result.first { it.dateEpochDay == 19901L }.steps)
    }

    @Test
    fun dailyMetrics_upsertOverwritesExistingRow() = runTest {
        val original = buildMetrics(19900L, steps = 5000)
        val updated  = buildMetrics(19900L, steps = 9999)

        dailyMetricsDao.upsert(original)
        dailyMetricsDao.upsert(updated)

        val result = dailyMetricsDao.getRange(19900L, 19900L)
        assertEquals(1, result.size)
        assertEquals(9999, result[0].steps)
    }

    @Test
    fun dailyMetrics_emptyRangeReturnsEmpty() = runTest {
        val result = dailyMetricsDao.getRange(99000L, 99001L)
        assertTrue("Should be empty for missing range", result.isEmpty())
    }

    // ── ComputedScoresDao ─────────────────────────────────────────────────────

    @Test
    fun computedScores_getLatestReturnsNewestRow() = runTest {
        computedScoresDao.upsert(buildScores(19900L, recovery = 60f))
        computedScoresDao.upsert(buildScores(19901L, recovery = 75f))
        computedScoresDao.upsert(buildScores(19899L, recovery = 50f))

        val latest = computedScoresDao.getLatest()
        assertNotNull(latest)
        assertEquals(75f, latest!!.recoveryScore)
        assertEquals(19901L, latest.dateEpochDay)
    }

    @Test
    fun computedScores_flowEmitsOnInsert() = runTest {
        computedScoresDao.upsert(buildScores(19900L, recovery = 82f))

        val from19900 = computedScoresDao.getLatestFlow().first()
        assertNotNull(from19900)
        assertEquals(82f, from19900!!.recoveryScore)
    }

    @Test
    fun computedScores_rangeQueryFiltersCorrectly() = runTest {
        (19895L..19905L).forEach { day ->
            computedScoresDao.upsert(buildScores(day, recovery = day.toFloat()))
        }

        val range = computedScoresDao.getRange(19898L, 19902L)
        assertEquals(5, range.size)
        assertTrue(range.all { it.dateEpochDay in 19898L..19902L })
    }

    // ── AchievementDao ────────────────────────────────────────────────────────

    @Test
    fun achievement_insertAndGetAll() = runTest {
        val a1 = AchievementEntity(id = "streak_7", title = "7-Day Streak", description = "7 days active", earnedAt = 19900L)
        val a2 = AchievementEntity(id = "best_recovery", title = "Best Recovery", description = "Personal best", earnedAt = 19901L)

        achievementDao.upsert(a1)
        achievementDao.upsert(a2)

        val all = achievementDao.getAll().first()
        assertEquals(2, all.size)
        assertTrue(all.any { it.id == "streak_7" })
        assertTrue(all.any { it.id == "best_recovery" })
    }

    @Test
    fun achievement_upsertIsIdempotent() = runTest {
        val ach = AchievementEntity(id = "streak_7", title = "7-Day Streak", description = "7 days", earnedAt = 19900L)
        achievementDao.upsert(ach)
        achievementDao.upsert(ach) // duplicate upsert

        val all = achievementDao.getAll().first()
        assertEquals("Duplicate upsert should not create duplicate rows", 1, all.size)
    }

    // ── SyncStateDao ──────────────────────────────────────────────────────────

    @Test
    fun syncState_upsertAndRetrieve() = runTest {
        val state = SyncStateEntity(recordType = "HeartRateRecord", latestSyncedTimestamp = 1700000000000L)
        syncStateDao.upsert(state)

        val ts = syncStateDao.getLatestTimestamp("HeartRateRecord")
        assertEquals(1700000000000L, ts)
    }

    @Test
    fun syncState_returnsNullForUnknownType() = runTest {
        val ts = syncStateDao.getLatestTimestamp("UnknownRecord")
        assertNull("Should return null for unknown record type", ts)
    }

    @Test
    fun syncState_upsertUpdatesTimestamp() = runTest {
        syncStateDao.upsert(SyncStateEntity("HR", 1000L))
        syncStateDao.upsert(SyncStateEntity("HR", 9999L))

        val ts = syncStateDao.getLatestTimestamp("HR")
        assertEquals(9999L, ts)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildMetrics(dateEpochDay: Long, steps: Int = 7000) = DailyMetricsEntity(
        dateEpochDay           = dateEpochDay,
        restingHR              = 60,
        steps                  = steps,
        distanceMeters         = 5000f,
        caloriesBurned         = 400,
        weightKg               = null,
        bodyFatPercent         = null,
        spO2Percent            = null,
        sleepDurationMinutes   = 450,
        sleepEfficiencyPercent = 85.0,
        sleepDeepMinutes       = 80,
        sleepRemMinutes        = 100,
        sleepLightMinutes      = 270,
        sleepAwakeMinutes      = 0,
        bedtimeMinuteOfDay     = 1380,
        wakeTimeMinuteOfDay    = 420
    )


    private fun buildScores(dateEpochDay: Long, recovery: Float = 70f) = ComputedScoresEntity(
        dateEpochDay              = dateEpochDay,
        recoveryScore             = recovery,
        recoveryConfidence        = "HIGH",
        recoveryExplanation       = "Good rest",
        readinessScore            = 68f,
        sleepScore                = 72f,
        stressScore               = 25f,
        activityScore             = 60f,
        consistencyScore          = 65f,
        lifestyleScore            = null,
        trainingLoadNormalized    = 35f,
        acwr                      = 0.9f,
        acwrZone                  = "OPTIMAL",
        vo2MaxEstimate            = 42f,
        biologicalAge             = 28,
        weeklyHealthScore         = null,
        monthlyHealthScore        = null,
        recoveryMomentum          = "STABLE",
        sleepMomentum             = "STABLE",
        trainingMomentum          = "STABLE",
        dataQualityLevel          = "HIGH",
        dataQualityPercent        = 90
    )
}
