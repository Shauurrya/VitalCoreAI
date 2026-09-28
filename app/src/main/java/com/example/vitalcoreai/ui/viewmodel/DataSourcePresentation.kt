package com.example.vitalcoreai.ui.viewmodel

import com.example.vitalcoreai.data.db.entity.SyncStateEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.model.isStale
import com.example.vitalcoreai.data.repository.HealthRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Permission is a live fact; persisted read history must never grant it. */
enum class DataReadStatus {
    READ, EMPTY, DELAYED, PERMISSION_DENIED, UNSUPPORTED, FAILED, NOT_READ, UNAVAILABLE, UNKNOWN
}

data class DataSourceItem(
    val recordType: String,
    val name: String,
    val status: DataReadStatus,
    val statusLabel: String,
    val explanation: String,
    val lastAttemptMs: Long?,
    val lastSuccessfulReadMs: Long?,
    val latestMeasurementMs: Long?,
    val sourceApps: List<String>,
    val nextAction: String,
    val dataNotes: List<String> = emptyList()
)

internal fun presentDataSource(
    recordType: String,
    name: String,
    available: Boolean?,
    permissionGranted: Boolean?,
    read: SyncStateEntity?,
    nowMs: Long,
    expectDaily: Boolean = true,
    sourceName: (String) -> String = { it }
): DataSourceItem {
    val status = when {
        available == null -> DataReadStatus.UNKNOWN
        !available -> DataReadStatus.UNAVAILABLE
        permissionGranted == null -> DataReadStatus.UNKNOWN
        !permissionGranted -> DataReadStatus.PERMISSION_DENIED
        read?.outcome == "UNSUPPORTED" -> DataReadStatus.UNSUPPORTED
        read?.outcome == "FAILED" -> DataReadStatus.FAILED
        // Regranting access is not evidence that a new read has succeeded.
        read?.outcome == "PERMISSION_DENIED" -> DataReadStatus.NOT_READ
        read?.outcome == "SUCCESS_EMPTY" -> DataReadStatus.EMPTY
        read?.outcome == "SUCCESS_DATA" && expectDaily && read.latestMeasurementMs != null &&
            nowMs - read.latestMeasurementMs > 48L * 60 * 60 * 1000 -> DataReadStatus.DELAYED
        read?.outcome == "SUCCESS_DATA" -> DataReadStatus.READ
        else -> DataReadStatus.NOT_READ
    }
    val label = when (status) {
        DataReadStatus.READ -> "Read successfully"
        DataReadStatus.EMPTY -> "Read successfully · no records"
        DataReadStatus.DELAYED -> "Older measurements"
        DataReadStatus.PERMISSION_DENIED -> "Permission not granted"
        DataReadStatus.UNSUPPORTED -> "Not supported"
        DataReadStatus.FAILED -> "Read failed"
        DataReadStatus.NOT_READ -> "Ready to read"
        DataReadStatus.UNAVAILABLE -> "Health Connect unavailable"
        DataReadStatus.UNKNOWN -> "Access status unknown"
    }
    val explanation = when (status) {
        DataReadStatus.READ -> "The latest read succeeded. A successful read does not mean new watch data arrived."
        DataReadStatus.EMPTY -> "The latest requested time range contained no records. Earlier measurements may still be listed below."
        DataReadStatus.DELAYED -> "The read succeeded, but the latest known measurement is over 48 hours old. Your source app may not have shared newer data."
        DataReadStatus.PERMISSION_DENIED -> "VitalCore cannot read this metric. Previously saved values may be stale."
        DataReadStatus.UNSUPPORTED -> "This record type could not be read on this device or Health Connect version."
        DataReadStatus.FAILED -> "The last read did not finish. Any saved values are retained and marked stale."
        DataReadStatus.NOT_READ -> "Access is granted, but a successful read has not been recorded since access was restored or first enabled."
        DataReadStatus.UNAVAILABLE -> "Health Connect is not currently available. Saved measurements are not proof of a live connection."
        DataReadStatus.UNKNOWN -> "VitalCore could not check current availability or permissions. Saved read history is shown for reference."
    }
    val nextAction = when (status) {
        DataReadStatus.PERMISSION_DENIED -> "Open Health Connect and allow this metric for VitalCore."
        DataReadStatus.UNSUPPORTED -> "Check for Health Connect updates; other available metrics can still be used."
        DataReadStatus.EMPTY, DataReadStatus.DELAYED -> "Open your source app, sync your wearable and check that it shares this metric with Health Connect. Then read data again."
        DataReadStatus.FAILED, DataReadStatus.NOT_READ -> "Read data again. If this persists, check Health Connect permissions."
        DataReadStatus.UNAVAILABLE -> "Install or update Health Connect, then return here."
        DataReadStatus.UNKNOWN -> "Recheck access or open Health Connect settings."
        DataReadStatus.READ -> "Use the measurement time below to judge freshness."
    }
    return DataSourceItem(
        recordType = recordType,
        name = name,
        status = status,
        statusLabel = label,
        explanation = explanation,
        lastAttemptMs = read?.lastSyncTimestampMs,
        // Legacy sync_state rows alone do not establish a successful structured read.
        lastSuccessfulReadMs = read?.lastSuccessfulReadMs,
        latestMeasurementMs = read?.latestMeasurementMs,
        sourceApps = read?.sourcePackages?.split('|')?.filter { it.isNotBlank() }
            ?.distinct()?.map(sourceName).orEmpty(),
        nextAction = nextAction
    )
}

/** These notes describe a dated cached record, not the latest read request's payload. */
internal fun dataSourceNotes(recordType: String, metrics: DailyMetricsEntity?): List<String> {
    if (metrics == null) return emptyList()
    val date = LocalDate.ofEpochDay(metrics.dateEpochDay).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    if (recordType == "ExerciseSessionRecord" && metrics.isStale(recordType) && metrics.isStale("HeartRateRecord")) {
        return listOf("Workout heart rate could not be refreshed on $date. Saved workout values are excluded from fresh scores until a successful read.")
    }
    if (metrics.isStale(recordType)) return emptyList()
    return when (recordType) {
        "SleepSessionRecord" -> if (metrics.sleepDurationMinutes != null &&
            (metrics.sleepStagesAvailable == false || listOf(metrics.sleepDeepMinutes, metrics.sleepRemMinutes,
                metrics.sleepLightMinutes).all { it == null })) {
            listOf("Missing field on $date: sleep stage breakdown. Sleep duration can still be used.")
        } else emptyList()
        "RestingHeartRateRecord" -> if (metrics.restingHRDerived == true && metrics.restingHR != null &&
            !metrics.isStale("HeartRateRecord")) {
            listOf("No direct resting heart rate reading on $date. VitalCore estimated it from overnight heart rate.")
        } else emptyList()
        else -> emptyList()
    }
}

internal fun dataReadMessage(result: HealthRepository.SyncResult): String = when {
    result.unavailable -> "Health Connect is unavailable. No new read completed; check access and retry."
    !result.successful -> "No metrics could be read. Saved measurements remain available; review the outcomes below and retry."
    result.partial -> "Read partly completed. Some metrics could not be refreshed; review their outcomes below."
    else -> "Read completed. Check each metric's measurement time to see whether newer data arrived."
}

internal fun historyRefreshMessage(result: HealthRepository.BackfillResult): String {
    if (result.unavailable) return "Health Connect is unavailable. History was not refreshed. Open Data Sources to check access."
    val counts = "${result.refreshedDays} refreshed (${result.partialDays} partial) · " +
        "${result.skippedDays} skipped · ${result.failedDays} failed days."
    return if (result.failedDays > 0 || result.partialDays > 0) {
        "History refresh needs attention. $counts Open Data Sources to review missing access or failed reads, then retry."
    } else {
        "History refresh complete. $counts Your check-ins, journal and workout notes were preserved."
    }
}
