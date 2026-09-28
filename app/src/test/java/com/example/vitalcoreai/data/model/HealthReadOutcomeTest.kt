package com.example.vitalcoreai.data.model

import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.debug.loggingFailures
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HealthReadOutcomeTest {
    private fun emptyDay() = DailyMetricsEntity(
        dateEpochDay = 20000, restingHR = null, steps = null, distanceMeters = null,
        caloriesBurned = null, weightKg = null, bodyFatPercent = null, spO2Percent = null,
        sleepDurationMinutes = null, sleepEfficiencyPercent = null, sleepDeepMinutes = null,
        sleepRemMinutes = null, sleepLightMinutes = null, sleepAwakeMinutes = null,
        bedtimeMinuteOfDay = null, wakeTimeMinuteOfDay = null, hasData = false,
        lastSyncTimestampMs = 100
    )

    private fun result(type: String, status: ReadOutcomeStatus) = HealthReadOutcome<Nothing>(type, status)

    private fun workout() = ExerciseSessionEntity(
        startMs = 1000, dateEpochDay = 20000, endMs = 3_601_000,
        exerciseType = "Running", durationMinutes = 60, caloriesBurned = 400,
        distanceMeters = 7000f, avgHR = null, maxHR = null,
        trainingLoadNormalized = 0f, dominantZone = "ZONE1", hasHeartRateData = false,
        zone1Pct = null, zone2Pct = null, zone3Pct = null, zone4Pct = null, zone5Pct = null
    )

    @Test
    fun failedWorkoutHeartRateRetainsMeasuredLoadAndZonesAlongsideFreshSessionFields() {
        val cached = workout().copy(
            avgHR = 145, maxHR = 175, trainingLoadNormalized = 0.6f,
            dominantZone = "ZONE3", hasHeartRateData = true, belowZone1Pct = 0.1f,
            zone1Pct = 0.1f, zone2Pct = 0.2f, zone3Pct = 0.3f, zone4Pct = 0.2f, zone5Pct = 0.1f
        )
        val fresh = workout().copy(caloriesBurned = 420, rpe = 7, muscleGroups = "QUADS")
        val retained = fresh.preserveFailedHeartRate(cached, readSuccessful = false)
        assertEquals(cached.copy(caloriesBurned = 420, rpe = 7, muscleGroups = "QUADS"), retained)
    }

    @Test
    fun successfulEmptyWorkoutHeartRateClearsPreviousMeasurements() {
        val cached = workout().copy(avgHR = 145, maxHR = 175, trainingLoadNormalized = 0.6f,
            hasHeartRateData = true, zone3Pct = 1f)
        assertEquals(workout(), workout().preserveFailedHeartRate(cached, readSuccessful = true))
        val missing = workout().preserveFailedHeartRate(null, readSuccessful = false)
        assertNull(missing.trainingLoadNormalized)
        assertNull(missing.dominantZone)
        assertFalse(missing.hasHeartRateData)
    }

    @Test
    fun failedSupplementalWorkoutHeartRateInvalidatesDependentDataAndRemainsRetryable() {
        val metrics = emptyDay().copy(restingHR = 60, restingHRDerived = true, steps = 5000,
            staleRecordTypes = "SleepSessionRecord").withFailedWorkoutHeartRate()
        assertTrue(metrics.isStale("HeartRateRecord"))
        assertTrue(metrics.isStale("ExerciseSessionRecord"))
        assertTrue(metrics.isStale("SleepSessionRecord"))
        assertNull(metrics.freshForScoring().restingHR)
        assertEquals(5000, metrics.freshForScoring().steps)
        assertTrue(metrics.needsReadRetry(setOf("HeartRateRecord")))
        assertEquals(metrics, metrics.withFailedWorkoutHeartRate())
    }

    @Test
    fun failedSleepRetainsCacheButFreshStepsRemainUsable() {
        val previous = emptyDay().copy(steps = 4000, sleepDurationMinutes = 480, hasData = true)
        val merged = emptyDay().copy(steps = 5000, hasData = true).preserveFailedReads(previous, listOf(
            result("StepsRecord", ReadOutcomeStatus.SUCCESS_DATA),
            result("SleepSessionRecord", ReadOutcomeStatus.FAILED)
        ))
        assertEquals(5000, merged.steps)
        assertEquals(480, merged.sleepDurationMinutes)
        assertTrue(merged.isStale("SleepSessionRecord"))
        assertEquals(5000, merged.freshForScoring().steps)
        assertNull(merged.freshForScoring().sleepDurationMinutes)
    }

    @Test
    fun regrantReplacesStaleValueAndSuccessfulEmptyClearsIt() {
        val cached = emptyDay().copy(sleepDurationMinutes = 480, hasData = true, staleRecordTypes = "SleepSessionRecord")
        val repaired = emptyDay().copy(sleepDurationMinutes = 420, hasData = true).preserveFailedReads(cached, listOf(
            result("SleepSessionRecord", ReadOutcomeStatus.SUCCESS_DATA)
        ))
        assertEquals(420, repaired.freshForScoring().sleepDurationMinutes)
        assertFalse(repaired.isStale("SleepSessionRecord"))
        val cleared = emptyDay().preserveFailedReads(repaired, listOf(result("SleepSessionRecord", ReadOutcomeStatus.SUCCESS_EMPTY)))
        assertNull(cleared.sleepDurationMinutes)
        assertFalse(cleared.hasData)
    }

    @Test
    fun failedHistoricalReadRemainsEligibleForRepairDespiteItsCacheRow() {
        val cached = emptyDay().copy(steps = 4000, hasData = true)
        val failed = emptyDay().preserveFailedReads(cached, listOf(
            result("StepsRecord", ReadOutcomeStatus.FAILED),
            result("BodyFatRecord", ReadOutcomeStatus.PERMISSION_DENIED)
        ))
        assertTrue(failed.needsReadRetry(setOf("StepsRecord")))

        val repaired = emptyDay().copy(steps = 4500, hasData = true).preserveFailedReads(failed, listOf(
            result("StepsRecord", ReadOutcomeStatus.SUCCESS_DATA),
            result("BodyFatRecord", ReadOutcomeStatus.PERMISSION_DENIED)
        ))
        assertFalse(repaired.needsReadRetry(setOf("StepsRecord")))
        assertTrue(repaired.needsReadRetry(setOf("StepsRecord", "BodyFatRecord")))
    }

    @Test
    fun successfulEmptyDayDoesNotKeepRetryingLikeAFailedRead() {
        val completed = emptyDay().preserveFailedReads(null, listOf(
            result("StepsRecord", ReadOutcomeStatus.SUCCESS_EMPTY)
        ))
        assertFalse(completed.hasData)
        assertFalse(completed.needsReadRetry(setOf("StepsRecord")))
    }

    @Test
    fun deniedUnusedPermissionDoesNotKeepADeletedDaysCoverage() {
        val cached = emptyDay().copy(steps = 1234, hasData = true)
        val refreshed = emptyDay().preserveFailedReads(cached, listOf(
            result("StepsRecord", ReadOutcomeStatus.SUCCESS_EMPTY),
            result("BodyFatRecord", ReadOutcomeStatus.PERMISSION_DENIED)
        ))
        assertNull(refreshed.steps)
        assertFalse(refreshed.hasData)
    }

    @Test
    fun failedHeartRateInvalidatesDerivedButNotRecordedRestingRate() {
        val cached = emptyDay().copy(restingHR = 60, restingHRDerived = true, staleRecordTypes = "HeartRateRecord")
        assertNull(cached.freshForScoring().restingHR)
        assertEquals(60, cached.copy(restingHRDerived = false).freshForScoring().restingHR)
    }

    @Test
    fun failureKeepsLastSuccessAndEmptyReadDoesNotInventMeasurementArrival() {
        val original = HealthReadOutcome("StepsRecord", ReadOutcomeStatus.SUCCESS_DATA,
            data = 12, recordCount = 1, latestMeasurementMs = 50, sourcePackages = setOf("source.app"))
            .toSyncState(null, 100)
        val failed = result("StepsRecord", ReadOutcomeStatus.FAILED).toSyncState(original, 200)
        assertEquals(100L, failed.lastSuccessfulReadMs)
        assertEquals(50L, failed.latestMeasurementMs)
        assertEquals("source.app", failed.sourcePackages)
        val empty = result("StepsRecord", ReadOutcomeStatus.SUCCESS_EMPTY).toSyncState(failed, 300)
        assertEquals(300L, empty.lastSuccessfulReadMs)
        assertEquals(50L, empty.latestMeasurementMs)
    }

    @Test
    fun deniedAndUnsupportedAreDistinctFromRetryableFailures() {
        assertEquals(ReadOutcomeStatus.PERMISSION_DENIED, readFailureStatus(SecurityException()))
        assertEquals(ReadOutcomeStatus.UNSUPPORTED, readFailureStatus(UnsupportedOperationException()))
        val partial = HealthRepository.DaySyncResult(outcomes = listOf(
            result("StepsRecord", ReadOutcomeStatus.SUCCESS_EMPTY), result("SleepSessionRecord", ReadOutcomeStatus.FAILED)))
        assertTrue(partial.successful)
        assertTrue(partial.partial)
        assertTrue(partial.retryableFailure)
        assertFalse(HealthRepository.DaySyncResult(unavailable = true).successful)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotAReadFailure() { readFailureStatus(CancellationException()) }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowedByHistoryRetryLogging() {
        loggingFailures("test") { throw CancellationException() }
    }
}
