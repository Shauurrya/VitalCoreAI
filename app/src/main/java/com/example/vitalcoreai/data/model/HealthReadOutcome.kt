package com.example.vitalcoreai.data.model

import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.data.db.entity.SyncStateEntity
import kotlinx.coroutines.CancellationException

enum class ReadOutcomeStatus {
    SUCCESS_DATA, SUCCESS_EMPTY, PERMISSION_DENIED, UNSUPPORTED, FAILED;

    val successful: Boolean get() = this == SUCCESS_DATA || this == SUCCESS_EMPTY
}

/** An authoritative empty result is different from a read we could not complete. */
data class HealthReadOutcome<out T>(
    val recordType: String,
    val status: ReadOutcomeStatus,
    val data: T? = null,
    val recordCount: Int? = null,
    val latestMeasurementMs: Long? = null,
    val sourcePackages: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
    val successful: Boolean get() = status.successful

    fun toSyncState(previous: SyncStateEntity?, attemptedAtMs: Long): SyncStateEntity = SyncStateEntity(
        recordType = recordType,
        lastSyncTimestampMs = attemptedAtMs,
        lastSuccessfulSyncMs = if (successful) attemptedAtMs else previous?.lastSuccessfulSyncMs ?: 0L,
        outcome = status.name,
        lastSuccessfulReadMs = if (successful) attemptedAtMs else previous?.lastSuccessfulReadMs,
        // A successful empty query is not a new measurement. Keep the last known arrival.
        latestMeasurementMs = listOfNotNull(latestMeasurementMs, previous?.latestMeasurementMs).maxOrNull(),
        sourcePackages = sourcePackages.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")
            ?: previous?.sourcePackages,
        recordCount = recordCount,
        errorMessage = errorMessage
    )
}

/** Shared classification, also used by adapters so cancellation never becomes stale data. */
fun readFailureStatus(error: Exception): ReadOutcomeStatus = when (error) {
    is CancellationException -> throw error
    is SecurityException -> ReadOutcomeStatus.PERMISSION_DENIED
    is UnsupportedOperationException -> ReadOutcomeStatus.UNSUPPORTED
    else -> ReadOutcomeStatus.FAILED
}

fun DailyMetricsEntity.isStale(recordType: String): Boolean =
    staleRecordTypes?.split('|')?.contains(recordType) == true

/** A cached day is not complete while a currently readable field still needs repair. */
fun DailyMetricsEntity.needsReadRetry(readableRecordTypes: Set<String>): Boolean =
    staleRecordTypes?.split('|')?.any { it in readableRecordTypes } == true

/** A workout can be read successfully while its separate heart-rate read fails. */
fun ExerciseSessionEntity.preserveFailedHeartRate(
    previous: ExerciseSessionEntity?,
    readSuccessful: Boolean
): ExerciseSessionEntity {
    if (readSuccessful) return this
    return copy(
        avgHR = previous?.avgHR,
        maxHR = previous?.maxHR,
        trainingLoadNormalized = previous?.trainingLoadNormalized,
        dominantZone = previous?.dominantZone,
        hasHeartRateData = previous?.hasHeartRateData ?: false,
        belowZone1Pct = previous?.belowZone1Pct,
        zone1Pct = previous?.zone1Pct,
        zone2Pct = previous?.zone2Pct,
        zone3Pct = previous?.zone3Pct,
        zone4Pct = previous?.zone4Pct,
        zone5Pct = previous?.zone5Pct
    )
}

/** Existing per-day staleness also protects cached workout load from fresh scoring. */
fun DailyMetricsEntity.withFailedWorkoutHeartRate(): DailyMetricsEntity = copy(
    staleRecordTypes = (staleRecordTypes?.split('|').orEmpty() +
        listOf("HeartRateRecord", "ExerciseSessionRecord")).distinct().joinToString("|")
)

/** Retained cache remains available to explain gaps, but never enters a fresh score. */
fun DailyMetricsEntity.freshForScoring(): DailyMetricsEntity {
    val sleep = isStale("SleepSessionRecord")
    val hr = isStale("HeartRateRecord")
    val rhr = isStale("RestingHeartRateRecord") || (restingHRDerived == true && hr)
    val oxygen = isStale("OxygenSaturationRecord")
    return copy(
        restingHR = restingHR.takeUnless { rhr },
        restingHRDerived = restingHRDerived.takeUnless { rhr },
        steps = steps.takeUnless { isStale("StepsRecord") },
        distanceMeters = distanceMeters.takeUnless { isStale("DistanceRecord") },
        caloriesBurned = caloriesBurned.takeUnless { isStale("TotalCaloriesBurnedRecord") },
        activeCalories = activeCalories.takeUnless { isStale("ActiveCaloriesBurnedRecord") },
        weightKg = weightKg.takeUnless { isStale("WeightRecord") },
        bodyFatPercent = bodyFatPercent.takeUnless { isStale("BodyFatRecord") },
        spO2Percent = spO2Percent.takeUnless { oxygen },
        spO2ReadingCount = spO2ReadingCount.takeUnless { oxygen },
        spO2FromSleepWindow = spO2FromSleepWindow.takeUnless { oxygen },
        sleepDurationMinutes = sleepDurationMinutes.takeUnless { sleep },
        sleepEfficiencyPercent = sleepEfficiencyPercent.takeUnless { sleep },
        sleepStagesAvailable = sleepStagesAvailable.takeUnless { sleep },
        sleepDeepMinutes = sleepDeepMinutes.takeUnless { sleep },
        sleepRemMinutes = sleepRemMinutes.takeUnless { sleep },
        sleepLightMinutes = sleepLightMinutes.takeUnless { sleep },
        sleepAwakeMinutes = sleepAwakeMinutes.takeUnless { sleep },
        bedtimeMinuteOfDay = bedtimeMinuteOfDay.takeUnless { sleep },
        wakeTimeMinuteOfDay = wakeTimeMinuteOfDay.takeUnless { sleep },
        hrPointsPerHour = hrPointsPerHour.takeUnless { hr },
        partialDayFraction = partialDayFraction.takeUnless { hr },
        floorsClimbed = floorsClimbed.takeUnless { isStale("FloorsClimbedRecord") },
        elevationGainMeters = elevationGainMeters.takeUnless { isStale("ElevationGainedRecord") },
        hrvRmssdMs = hrvRmssdMs.takeUnless { isStale("HeartRateVariabilityRmssdRecord") }
    )
}

/** Merge only failed types from the previous cache; successful empties deliberately clear. */
fun DailyMetricsEntity.preserveFailedReads(
    previous: DailyMetricsEntity?,
    outcomes: Collection<HealthReadOutcome<*>>,
    retainedHeartRateData: Boolean = false,
    retainedExerciseData: Boolean = false
): DailyMetricsEntity {
    val failed = outcomes.filterNot { it.successful }.map { it.recordType }.toSet()
    val stale = failed.joinToString("|").ifEmpty { null }
    if (previous == null) return copy(staleRecordTypes = stale)
    fun <T> retain(type: String, fresh: T, cached: T): T = if (type in failed) cached else fresh
    return copy(
        staleRecordTypes = stale,
        restingHR = retain("RestingHeartRateRecord", restingHR, previous.restingHR),
        restingHRDerived = retain("RestingHeartRateRecord", restingHRDerived, previous.restingHRDerived),
        steps = retain("StepsRecord", steps, previous.steps),
        distanceMeters = retain("DistanceRecord", distanceMeters, previous.distanceMeters),
        caloriesBurned = retain("TotalCaloriesBurnedRecord", caloriesBurned, previous.caloriesBurned),
        activeCalories = retain("ActiveCaloriesBurnedRecord", activeCalories, previous.activeCalories),
        weightKg = retain("WeightRecord", weightKg, previous.weightKg),
        bodyFatPercent = retain("BodyFatRecord", bodyFatPercent, previous.bodyFatPercent),
        spO2Percent = retain("OxygenSaturationRecord", spO2Percent, previous.spO2Percent),
        spO2ReadingCount = retain("OxygenSaturationRecord", spO2ReadingCount, previous.spO2ReadingCount),
        spO2FromSleepWindow = retain("OxygenSaturationRecord", spO2FromSleepWindow, previous.spO2FromSleepWindow),
        sleepDurationMinutes = retain("SleepSessionRecord", sleepDurationMinutes, previous.sleepDurationMinutes),
        sleepEfficiencyPercent = retain("SleepSessionRecord", sleepEfficiencyPercent, previous.sleepEfficiencyPercent),
        sleepStagesAvailable = retain("SleepSessionRecord", sleepStagesAvailable, previous.sleepStagesAvailable),
        sleepDeepMinutes = retain("SleepSessionRecord", sleepDeepMinutes, previous.sleepDeepMinutes),
        sleepRemMinutes = retain("SleepSessionRecord", sleepRemMinutes, previous.sleepRemMinutes),
        sleepLightMinutes = retain("SleepSessionRecord", sleepLightMinutes, previous.sleepLightMinutes),
        sleepAwakeMinutes = retain("SleepSessionRecord", sleepAwakeMinutes, previous.sleepAwakeMinutes),
        bedtimeMinuteOfDay = retain("SleepSessionRecord", bedtimeMinuteOfDay, previous.bedtimeMinuteOfDay),
        wakeTimeMinuteOfDay = retain("SleepSessionRecord", wakeTimeMinuteOfDay, previous.wakeTimeMinuteOfDay),
        hrPointsPerHour = retain("HeartRateRecord", hrPointsPerHour, previous.hrPointsPerHour),
        partialDayFraction = retain("HeartRateRecord", partialDayFraction, previous.partialDayFraction),
        floorsClimbed = retain("FloorsClimbedRecord", floorsClimbed, previous.floorsClimbed),
        elevationGainMeters = retain("ElevationGainedRecord", elevationGainMeters, previous.elevationGainMeters),
        hrvRmssdMs = retain("HeartRateVariabilityRmssdRecord", hrvRmssdMs, previous.hrvRmssdMs),
        // This timestamp describes newly verified evidence. Per-type last-good
        // measurement times remain in sync_state even when a refresh fails.
        hasData = hasData || retainedHeartRateData || retainedExerciseData || failed.any { type ->
            when (type) {
                "RestingHeartRateRecord" -> previous.restingHR != null
                "StepsRecord" -> previous.steps != null
                "DistanceRecord" -> previous.distanceMeters != null
                "TotalCaloriesBurnedRecord" -> previous.caloriesBurned != null
                "ActiveCaloriesBurnedRecord" -> previous.activeCalories != null
                "OxygenSaturationRecord" -> previous.spO2Percent != null
                "SleepSessionRecord" -> previous.sleepDurationMinutes != null
                "FloorsClimbedRecord" -> previous.floorsClimbed != null
                "ElevationGainedRecord" -> previous.elevationGainMeters != null
                "HeartRateVariabilityRmssdRecord" -> previous.hrvRmssdMs != null
                // Carried-forward weight/body fat alone do not establish daily coverage.
                else -> false
            }
        }
    )
}
