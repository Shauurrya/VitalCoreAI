package com.example.vitalcoreai.data.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.vitalcoreai.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import com.example.vitalcoreai.core.time.VitalTime

/**
 * Health Connect access layer.
 *
 * ── DEVICE REALITY (verified, see docs/CAPABILITY_MATRIX.md) ──────────────────
 * The Galaxy Watch Active 2 runs Tizen. Samsung stopped accepting new/updated
 * Tizen watch apps and ended Galaxy Store distribution for them, so there is no
 * way to ship a companion app onto the watch itself. That means NO direct access
 * to the watch's accelerometer, gyroscope, barometer or GPS.
 *
 * The only supported pipeline is:
 *     Active 2 → Samsung Health (phone) → Health Connect → this app.
 *
 * Everything below is written defensively around that: Samsung Health decides
 * which record types it actually writes, and that set varies by Samsung Health
 * version and region. Any read may legitimately come back empty, and every read
 * may throw if the specific permission was declined. Neither is an error state —
 * it lowers confidence, it does not break the app.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "HealthConnectManager"

        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        const val PLAY_STORE_URI = "market://details?id=$HEALTH_CONNECT_PACKAGE"

        /**
         * Without at least one of these the app cannot compute anything at all.
         * Onboarding gates on these — and only these.
         */
        val MINIMUM_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )

        /**
         * Strongly desired: their absence degrades specific scores but never blocks
         * the app. Each is independently optional at runtime.
         */
        val CORE_PERMISSIONS = MINIMUM_PERMISSIONS + setOf(
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
        )

        /**
         * Bonus signals. The Active 2 may or may not produce these depending on
         * Samsung Health version; SpO2 in particular is written inconsistently.
         */
        val OPTIONAL_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            // Opportunistic only — the Active 2 cannot supply HRV. Nothing depends on it.
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        )

        /**
         * Required for the background WorkManager sync to see any data at all, and
         * for reads to reach further back than 30 days before the permission grant.
         * Requested alongside the rest; declined simply means reduced capability.
         */
        val EXTENDED_PERMISSIONS = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        /** Everything the app ever asks for, in one request. */
        val ALL_PERMISSIONS = CORE_PERMISSIONS + OPTIONAL_PERMISSIONS + EXTENDED_PERMISSIONS

        @Deprecated(
            "Requesting this as an all-or-nothing set blocked onboarding whenever any " +
                "single record type was declined. Use ALL_PERMISSIONS to request and " +
                "hasMinimumPermissions() to gate.",
            ReplaceWith("ALL_PERMISSIONS")
        )
        val REQUIRED_PERMISSIONS = ALL_PERMISSIONS
    }

    private val client: HealthConnectClient? by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect client unavailable", e)
            null
        }
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /**
     * On Android 14+ Health Connect is part of the OS — nothing to install.
     * On Android 13 and below, return a Play Store intent if it is missing.
     */
    fun getInstallIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val installed = try {
            context.packageManager.getPackageInfo(HEALTH_CONNECT_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return if (!installed) Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URI)) else null
    }

    // ─── Permission state ──────────────────────────────────────────────────

    suspend fun grantedPermissions(): Set<String> {
        val c = client ?: return emptySet()
        return try {
            c.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read granted permissions", e)
            emptySet()
        }
    }

    /** The real gate: can we compute anything at all? */
    suspend fun hasMinimumPermissions(): Boolean =
        grantedPermissions().any { it in MINIMUM_PERMISSIONS }

    /** True only when every permission was granted — used for the capability report. */
    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(ALL_PERMISSIONS)

    suspend fun canReadInBackground(): Boolean =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grantedPermissions()

    suspend fun canReadHistory(): Boolean =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions()

    /**
     * Per-metric availability for the Data Sources & Permissions screen:
     * permission granted AND the record type actually has data in the last 30 days.
     */
    suspend fun capabilityReport(): List<DataSourceStatus> {
        val granted = grantedPermissions()
        val end = VitalTime.today()
        val start = end.minusDays(30)

        suspend fun probe(
            label: String,
            permission: String,
            essential: Boolean,
            count: suspend () -> Int
        ): DataSourceStatus {
            val hasPermission = permission in granted
            val records = if (hasPermission) count() else 0
            return DataSourceStatus(
                metric = label,
                source = "Samsung Health → Health Connect",
                permissionGranted = hasPermission,
                recordsLast30Days = records,
                essential = essential
            )
        }

        return listOf(
            probe("Heart rate", HealthPermission.getReadPermission(HeartRateRecord::class), true) {
                countRecords(HeartRateRecord::class, start, end)
            },
            probe("Resting heart rate", HealthPermission.getReadPermission(RestingHeartRateRecord::class), false) {
                countRecords(RestingHeartRateRecord::class, start, end)
            },
            probe("Sleep", HealthPermission.getReadPermission(SleepSessionRecord::class), true) {
                countRecords(SleepSessionRecord::class, start, end)
            },
            probe("Steps", HealthPermission.getReadPermission(StepsRecord::class), true) {
                countRecords(StepsRecord::class, start, end)
            },
            probe("Distance", HealthPermission.getReadPermission(DistanceRecord::class), false) {
                countRecords(DistanceRecord::class, start, end)
            },
            probe("Calories", HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class), false) {
                countRecords(TotalCaloriesBurnedRecord::class, start, end)
            },
            probe("Workouts", HealthPermission.getReadPermission(ExerciseSessionRecord::class), true) {
                countRecords(ExerciseSessionRecord::class, start, end)
            },
            probe("Blood oxygen (SpO₂)", HealthPermission.getReadPermission(OxygenSaturationRecord::class), false) {
                countRecords(OxygenSaturationRecord::class, start, end)
            },
            probe("Weight", HealthPermission.getReadPermission(WeightRecord::class), false) {
                countRecords(WeightRecord::class, start, end)
            },
            probe("Floors climbed", HealthPermission.getReadPermission(FloorsClimbedRecord::class), false) {
                countRecords(FloorsClimbedRecord::class, start, end)
            },
            probe("Elevation gained", HealthPermission.getReadPermission(ElevationGainedRecord::class), false) {
                countRecords(ElevationGainedRecord::class, start, end)
            },
            probe("VO₂ max", HealthPermission.getReadPermission(Vo2MaxRecord::class), false) {
                countRecords(Vo2MaxRecord::class, start, end)
            },
            probe("Heart rate variability", HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class), false) {
                countRecords(HeartRateVariabilityRmssdRecord::class, start, end)
            },
        )
    }

    private suspend fun <T : Record> countRecords(
        type: kotlin.reflect.KClass<T>,
        start: LocalDate,
        end: LocalDate
    ): Int = safeRead(0) { readAll(type, dayRange(start, end)).size }

    // ─── Read helpers ──────────────────────────────────────────────────────

    /**
     * Every Health Connect read goes through here. A declined permission throws
     * SecurityException, and the service itself can throw RemoteException or
     * IllegalStateException when Health Connect is updating. None of those should
     * take down a sync that has already gathered other data successfully.
     */
    private inline fun <T> safeRead(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Health Connect read failed (returning fallback): ${e.javaClass.simpleName}: ${e.message}")
        fallback
    }

    private fun dayRange(startDay: LocalDate, endDay: LocalDate): TimeRangeFilter {
        val zone = VitalTime.zone()
        return TimeRangeFilter.between(
            startDay.atStartOfDay(zone).toInstant(),
            endDay.plusDays(1).atStartOfDay(zone).toInstant()
        )
    }

    /**
     * Read EVERY matching record, following the page token to the end.
     *
     * `ReadRecordsRequest` defaults to `pageSize = 1000`; when more records match, Health
     * Connect returns the first page plus a non-null `pageToken` and the remainder is
     * dropped **with no error**. Every read in this file previously took
     * `.readRecords(request).records` and discarded the token.
     *
     * This bites hardest on heart rate: a Watch Active 2 day containing a workout produces
     * per-second HR, and Samsung Health frequently writes one record per sample rather
     * than batching, so a single active day can exceed 1000 records and silently lose its
     * afternoon. The truncation was invisible downstream — coverage and density were
     * computed from the truncated set, so the day was scored as "watch removed / partial
     * day" rather than "we failed to read it".
     */
    private suspend fun <T : Record> readAll(
        type: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val c = client ?: return emptyList()
        val out = mutableListOf<T>()
        var pageToken: String? = null
        var pages = 0
        do {
            val response = c.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageToken = pageToken)
            )
            out += response.records
            pageToken = response.pageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        if (pageToken != null) {
            Log.w(TAG, "Stopped paging ${type.simpleName} after $MAX_PAGES pages (${out.size} records)")
        }
        return out
    }

    /**
     * Aggregate over a time range, or null when the aggregation is unavailable.
     *
     * Aggregation is not a convenience here — it is the **only** read path that applies
     * Health Connect's cross-origin de-duplication and priority resolution. Summing raw
     * records double-counts whenever more than one app writes the same metric, which for a
     * phone carrying both Samsung Health (from the watch) and the phone's own pedometer
     * inflates step counts by 1.5–2×. That inflation then propagates into the activity
     * z-score, the step baseline, the consistency threshold and the biological-age active-day
     * count — so the fitness age was biased by how many fitness apps the user had installed.
     */
    private suspend fun aggregateOrNull(
        metrics: Set<androidx.health.connect.client.aggregate.AggregateMetric<*>>,
        range: TimeRangeFilter
    ): AggregationResult? = safeRead(null) {
        val c = client ?: return@safeRead null
        c.aggregate(AggregateRequest(metrics = metrics, timeRangeFilter = range))
    }

    // ─── Read APIs ─────────────────────────────────────────────────────────

    /**
     * Resting heart rate straight from Health Connect.
     *
     * IMPORTANT: Samsung Health is widely reported not to WRITE resting heart rate
     * to Health Connect even though it displays it in its own UI — it only reads
     * that type. So this very often returns empty for a Galaxy Watch Active 2 user.
     * [deriveRestingHR] is the fallback that keeps the whole RHR → baseline →
     * readiness chain alive; call [restingHRForDay] rather than this directly.
     */
    suspend fun readRestingHRForDays(startDay: LocalDate, endDay: LocalDate): List<RestingHRData> =
        safeRead(emptyList()) {
            readAll(RestingHeartRateRecord::class, dayRange(startDay, endDay))
                .map { record ->
                    RestingHRData(
                        dateEpochDay = VitalTime.epochDayOf(record.time.toEpochMilli()),
                        bpm = record.beatsPerMinute.toInt()
                    )
                }
        }

    /**
     * The day's resting HR when Samsung Health wrote several.
     *
     * Takes the **minimum**, not an arbitrary first record. Samsung Health can write
     * multiple resting-HR values per day and `firstOrNull()` picked whichever the query
     * happened to return first — a coin flip driving 30% of the recovery score. The
     * physiologically meaningful resting value is the lowest sustained one.
     */
    suspend fun recordedRestingHRForDay(day: LocalDate): Int? =
        readRestingHRForDays(day, day).minByOrNull { it.bpm }?.bpm

    /**
     * Resting HR for a day, with a derived fallback.
     *
     * Returns the recorded value when Samsung Health supplied one; otherwise derives
     * it from overnight heart-rate samples. Marked [RestingHRResult.derived] so the
     * UI can label it honestly and the confidence model can discount it.
     */
    suspend fun restingHRForDay(
        day: LocalDate,
        hrPoints: List<HeartRatePoint>,
        sleep: SleepData?
    ): RestingHRResult? {
        recordedRestingHRForDay(day)?.let {
            return RestingHRResult(it, derived = false, sampleCount = 1)
        }
        return deriveRestingHR(day, hrPoints, sleep)
    }

    /**
     * Estimate resting HR from overnight heart-rate samples.
     *
     * Method (labelled a heuristic, not a validated metric): take the samples inside
     * the sleep window — or 00:00–06:00 when no sleep session exists — and average the
     * lowest 20% of them. Averaging a trimmed low tail rather than taking the single
     * minimum avoids one spurious low reading defining the whole day, which matters a
     * lot given how sparsely Samsung Health syncs continuous HR to the phone.
     *
     * Requires at least [MIN_OVERNIGHT_SAMPLES] samples; below that the estimate is
     * too noisy to be worth showing and null is returned so confidence drops instead.
     */
    fun deriveRestingHR(
        day: LocalDate,
        hrPoints: List<HeartRatePoint>,
        sleep: SleepData?
    ): RestingHRResult? {
        if (hrPoints.isEmpty()) return null
        val zone = VitalTime.zone()

        val windowStart: Instant
        val windowEnd: Instant
        val bedtime = sleep?.bedtimeMinuteOfDay
        val waketime = sleep?.wakeTimeMinuteOfDay
        if (bedtime != null && waketime != null) {
            // Bedtime belongs to the previous calendar day whenever it is in the evening.
            val bedDay = if (bedtime >= 12 * 60) day.minusDays(1) else day
            windowStart = bedDay.atStartOfDay(zone).plusMinutes(bedtime.toLong()).toInstant()
            windowEnd = day.atStartOfDay(zone).plusMinutes(waketime.toLong()).toInstant()
        } else {
            windowStart = day.atStartOfDay(zone).toInstant()
            windowEnd = day.atStartOfDay(zone).plusHours(6).toInstant()
        }
        if (!windowEnd.isAfter(windowStart)) return null

        val overnight = hrPoints
            .filter { it.timestampMs >= windowStart.toEpochMilli() && it.timestampMs <= windowEnd.toEpochMilli() }
            .map { it.bpm }
            .filter { it in PLAUSIBLE_BPM }

        if (overnight.size < MIN_OVERNIGHT_SAMPLES) return null

        val sorted = overnight.sorted()
        val tailSize = (sorted.size * 0.20).toInt().coerceAtLeast(1)
        val estimate = sorted.take(tailSize).average()

        return RestingHRResult(
            bpm = estimate.toInt(),
            derived = true,
            sampleCount = overnight.size
        )
    }

    suspend fun readHeartRateForDay(day: LocalDate): List<HeartRatePoint> = safeRead(emptyList()) {
        readAll(HeartRateRecord::class, dayRange(day, day))
            .flatMap { record ->
                record.samples.map { sample ->
                    HeartRatePoint(sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
                }
            }
            .sortedBy { it.timestampMs }
    }

    /**
     * HR samples across an arbitrary instant span — used for exercise sessions, which can
     * cross midnight. Filtering a calendar-day HR list by the session's range kept only
     * the pre-midnight portion of a late-evening workout and then let the zone calculator
     * fabricate a distribution from the remainder.
     */
    suspend fun readHeartRateBetween(startMs: Long, endMs: Long): List<HeartRatePoint> =
        safeRead(emptyList()) {
            val range = TimeRangeFilter.between(
                Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)
            )
            readAll(HeartRateRecord::class, range)
                .flatMap { record ->
                    record.samples.map { HeartRatePoint(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }
                }
                .filter { it.timestampMs in startMs..endMs }
                .sortedBy { it.timestampMs }
        }

    /**
     * Heart-rate samples spanning the night that ends on [day] — needed for a
     * derived resting HR, because the pre-midnight half of the night lives on the
     * previous calendar day and a same-day read would miss it entirely.
     */
    suspend fun readOvernightHeartRate(day: LocalDate): List<HeartRatePoint> = safeRead(emptyList()) {
        readAll(HeartRateRecord::class, dayRange(day.minusDays(1), day))
            .flatMap { record ->
                record.samples.map { sample ->
                    HeartRatePoint(sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
                }
            }
            .sortedBy { it.timestampMs }
    }

    /**
     * All sleep sessions overlapping the range, each attributed to the day it **ended**.
     *
     * Times are projected through the record's own `startZoneOffset`/`endZoneOffset`, not
     * the current system zone. Health Connect carries those offsets precisely because the
     * recording zone may differ from the reading zone — without them, every historical
     * bedtime shifts after travel (or after a backfill run while abroad), which inflates
     * the bedtime standard deviation feeding two 20%-weighted consistency components and
     * retroactively tanks both scores for reasons the user cannot see.
     */
    suspend fun readSleepSessions(startDay: LocalDate, endDay: LocalDate): List<SleepData> =
        safeRead(emptyList()) {
            val zone = VitalTime.zone()
            readAll(SleepSessionRecord::class, dayRange(startDay, endDay))
                .map { record ->
                    val durationMin = ((record.endTime.epochSecond - record.startTime.epochSecond) / 60).toInt()
                    val stages = record.stages

                    fun stageMinutes(vararg types: Int): Int = stages
                        .filter { it.stage in types }
                        .sumOf { ((it.endTime.epochSecond - it.startTime.epochSecond) / 60).toInt() }

                    val deepMin = stageMinutes(SleepSessionRecord.STAGE_TYPE_DEEP)
                    val remMin = stageMinutes(SleepSessionRecord.STAGE_TYPE_REM)
                    val lightMin = stageMinutes(SleepSessionRecord.STAGE_TYPE_LIGHT)
                    val awakeMin = stageMinutes(
                        SleepSessionRecord.STAGE_TYPE_AWAKE,
                        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                    )
                    val stagedSleepMin = deepMin + remMin + lightMin
                    val hasStages = stages.isNotEmpty() && (stagedSleepMin > 0 || awakeMin > 0)

                    // Efficiency is NULL when the source gave no stages — not 0%, and not a
                    // fabricated 100%. See SleepData.efficiencyPercent.
                    val efficiencyPct: Double? = when {
                        durationMin <= 0 -> null
                        stagedSleepMin > 0 ->
                            (stagedSleepMin.toDouble() / durationMin * 100).coerceIn(0.0, 100.0)
                        awakeMin > 0 ->
                            ((durationMin - awakeMin).toDouble() / durationMin * 100).coerceIn(0.0, 100.0)
                        else -> null
                    }

                    val startOffset = record.startZoneOffset ?: zone.rules.getOffset(record.startTime)
                    val endOffset = record.endZoneOffset ?: zone.rules.getOffset(record.endTime)
                    val startLocal = record.startTime.atOffset(startOffset)
                    val endLocal = record.endTime.atOffset(endOffset)

                    SleepData(
                        // Attributed to the day the user WOKE UP. A session starting at
                        // 23:40 belongs to the next morning's record — keying off the start
                        // date files every late bedtime a day early and makes every
                        // "last night's sleep" lookup miss.
                        dateEpochDay = endLocal.toLocalDate().toEpochDay(),
                        durationMinutes = durationMin,
                        efficiencyPercent = efficiencyPct,
                        bedtimeMinuteOfDay = startLocal.hour * 60 + startLocal.minute,
                        wakeTimeMinuteOfDay = endLocal.hour * 60 + endLocal.minute,
                        remMinutes = remMin,
                        deepMinutes = deepMin,
                        lightMinutes = lightMin,
                        awakeMinutes = awakeMin,
                        stagesAvailable = hasStages
                    )
                }
        }

    /**
     * The single sleep record for [day]: the night the user woke up on that date.
     *
     * Reads a 3-day window (so a night starting two days back is visible), keeps sessions
     * whose **wake time** falls on [day], merges fragments separated by less than
     * [SLEEP_FRAGMENT_GAP_MINUTES] — Samsung Health routinely splits a night after a long
     * wake period — and returns the longest merged block. Anything shorter than
     * [MIN_NIGHT_MINUTES] that is not the only candidate is treated as a nap and excluded,
     * so an afternoon nap can no longer outrank a short night.
     *
     * The previous implementation read a 2-day window and took `maxByOrNull { duration }`,
     * which for anyone syncing in the evening could select the *following* night, and
     * silently discarded fragmented sleep instead of summing it.
     */
    suspend fun readNightForDay(day: LocalDate): SleepData? {
        val sessions = readSleepSessions(day.minusDays(2), day)
            .filter { it.dateEpochDay == day.toEpochDay() }
            .sortedBy { it.bedtimeMinuteOfDay ?: 0 }
        if (sessions.isEmpty()) return null

        val merged = mergeContiguous(sessions)
        val nights = merged.filter { it.durationMinutes >= MIN_NIGHT_MINUTES }
        return (if (nights.isNotEmpty()) nights else merged).maxByOrNull { it.durationMinutes }
    }

    /**
     * Merge sleep fragments whose wall-clock gap is below [SLEEP_FRAGMENT_GAP_MINUTES] into
     * a single night, summing stage minutes.
     */
    private fun mergeContiguous(sessions: List<SleepData>): List<SleepData> {
        if (sessions.size <= 1) return sessions
        val ordered = sessions.sortedBy { minutesFromEvening(it.bedtimeMinuteOfDay) }
        val out = mutableListOf<SleepData>()
        var current = ordered.first()

        for (next in ordered.drop(1)) {
            val currentEnd = current.wakeTimeMinuteOfDay
            val nextStart = next.bedtimeMinuteOfDay
            val gap = if (currentEnd != null && nextStart != null) {
                val raw = nextStart - currentEnd
                if (raw < 0) raw + 1440 else raw
            } else Int.MAX_VALUE

            current = if (gap in 0..SLEEP_FRAGMENT_GAP_MINUTES) {
                val deep = current.deepMinutes + next.deepMinutes
                val rem = current.remMinutes + next.remMinutes
                val light = current.lightMinutes + next.lightMinutes
                val awake = current.awakeMinutes + next.awakeMinutes + gap
                val duration = current.durationMinutes + next.durationMinutes + gap
                val staged = deep + rem + light
                current.copy(
                    durationMinutes = duration,
                    wakeTimeMinuteOfDay = next.wakeTimeMinuteOfDay,
                    deepMinutes = deep,
                    remMinutes = rem,
                    lightMinutes = light,
                    awakeMinutes = awake,
                    stagesAvailable = current.stagesAvailable || next.stagesAvailable,
                    efficiencyPercent = if (staged > 0 && duration > 0)
                        (staged.toDouble() / duration * 100).coerceIn(0.0, 100.0) else null
                )
            } else {
                out += current
                next
            }
        }
        out += current
        return out
    }

    /** Orders bedtimes so an 18:00-anchored evening sorts before a post-midnight one. */
    private fun minutesFromEvening(minuteOfDay: Int?): Int {
        val m = minuteOfDay ?: 0
        return if (m >= EVENING_ANCHOR_MINUTE) m - EVENING_ANCHOR_MINUTE else m + (1440 - EVENING_ANCHOR_MINUTE)
    }

    /**
     * SpO₂ restricted to the night's sleep window.
     *
     * All-day averaging mixed continuous overnight monitoring with ad-hoc daytime spot
     * checks taken at variable wrist placement — producing a number with no physiological
     * meaning that was nonetheless fed straight into a ±5-point recovery modifier. Returns
     * null when there are no readings, which the recovery calculator already handles by
     * applying no modifier at all (constraint 4).
     */
    suspend fun readSpO2ForDay(day: LocalDate, sleep: SleepData?): SpO2Summary? = safeRead(null) {
        val zone = VitalTime.zone()
        val records = readAll(OxygenSaturationRecord::class, dayRange(day.minusDays(1), day))
        if (records.isEmpty()) return@safeRead null

        val bedtime = sleep?.bedtimeMinuteOfDay
        val waketime = sleep?.wakeTimeMinuteOfDay
        val overnight = if (bedtime != null && waketime != null) {
            val bedDay = if (bedtime >= 12 * 60) day.minusDays(1) else day
            val windowStart = bedDay.atStartOfDay(zone).plusMinutes(bedtime.toLong()).toInstant()
            val windowEnd = day.atStartOfDay(zone).plusMinutes(waketime.toLong()).toInstant()
            records.filter { !it.time.isBefore(windowStart) && !it.time.isAfter(windowEnd) }
        } else {
            emptyList()
        }

        // Prefer overnight readings; fall back to same-day readings, flagged as such so
        // the caller can present them with lower confidence.
        val fromSleep = overnight.isNotEmpty()
        val chosen = if (fromSleep) overnight else records.filter {
            it.time.atZone(zone).toLocalDate() == day
        }
        if (chosen.isEmpty()) return@safeRead null

        SpO2Summary(
            averagePercent = chosen.map { it.percentage.value }.average().toFloat(),
            readingCount = chosen.size,
            fromSleepWindow = fromSleep
        )
    }

    /**
     * Steps for a day, or **null when no records exist**.
     *
     * Null, not 0: a day Health Connect knows nothing about is not a day the user took
     * zero steps. Returning 0 wrote phantom sedentary days into the step baseline, which
     * dragged the mean down until a mediocre real day scored as excellent.
     *
     * Uses the aggregation API for cross-origin de-duplication — see [aggregateOrNull].
     */
    suspend fun readStepsForDay(day: LocalDate): Int? {
        val aggregated = aggregateOrNull(setOf(StepsRecord.COUNT_TOTAL), dayRange(day, day))
        aggregated?.get(StepsRecord.COUNT_TOTAL)?.let { return it.toInt() }
        // Aggregation unavailable (older HC, or the metric is unsupported): fall back to
        // raw records, accepting the de-duplication risk rather than losing the day.
        return safeRead(null) {
            val records = readAll(StepsRecord::class, dayRange(day, day))
            if (records.isEmpty()) null else records.sumOf { it.count }.toInt()
        }
    }

    /** Total daily energy (BMR + activity), or null when unavailable. */
    suspend fun readCaloriesForDay(day: LocalDate): Int? {
        val aggregated = aggregateOrNull(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), dayRange(day, day))
        aggregated?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.let { return it.inKilocalories.toInt() }
        return safeRead(null) {
            val records = readAll(TotalCaloriesBurnedRecord::class, dayRange(day, day))
            if (records.isEmpty()) null else records.sumOf { it.energy.inKilocalories }.toInt()
        }
    }

    /**
     * **Active** calories — activity-only energy, excluding BMR.
     *
     * A separate read, deliberately. The activity score's calorie component was being fed
     * total daily energy against a 600 kcal target, so it clamped to 100 for every user on
     * every day while the breakdown card reported "Active Calories 100/100".
     */
    suspend fun readActiveCaloriesForDay(day: LocalDate): Int? {
        val aggregated = aggregateOrNull(
            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), dayRange(day, day)
        )
        aggregated?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            ?.let { return it.inKilocalories.toInt() }
        return safeRead(null) {
            val records = readAll(ActiveCaloriesBurnedRecord::class, dayRange(day, day))
            if (records.isEmpty()) null else records.sumOf { it.energy.inKilocalories }.toInt()
        }
    }

    /** Distance in metres, or null when unavailable. */
    suspend fun readDistanceForDay(day: LocalDate): Float? {
        val aggregated = aggregateOrNull(setOf(DistanceRecord.DISTANCE_TOTAL), dayRange(day, day))
        aggregated?.get(DistanceRecord.DISTANCE_TOTAL)?.let { return it.inMeters.toFloat() }
        return safeRead(null) {
            val records = readAll(DistanceRecord::class, dayRange(day, day))
            if (records.isEmpty()) null else records.sumOf { it.distance.inMeters }.toFloat()
        }
    }

    /** Barometer-derived, via Samsung Health. Null when the type is unavailable. */
    suspend fun readFloorsClimbedForDay(day: LocalDate): Int? = safeRead(null) {
        val records = readAll(FloorsClimbedRecord::class, dayRange(day, day))
        if (records.isEmpty()) null else records.sumOf { it.floors }.toInt()
    }

    suspend fun readElevationGainForDay(day: LocalDate): Float? = safeRead(null) {
        val records = readAll(ElevationGainedRecord::class, dayRange(day, day))
        if (records.isEmpty()) null else records.sumOf { it.elevation.inMeters }.toFloat()
    }

    /** Samsung Health computes its own VO₂ max; prefer it over our estimate when present. */
    suspend fun readLatestVo2Max(): Float? = safeRead(null) {
        readAll(Vo2MaxRecord::class, dayRange(VitalTime.today().minusDays(90), VitalTime.today()))
            .maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram?.toFloat()
    }

    /**
     * Opportunistic HRV. Always null for a Galaxy Watch Active 2 — kept so that a
     * future device that does write RMSSD is picked up with no code change.
     */
    suspend fun readHrvRmssdForDay(day: LocalDate): Double? = safeRead(null) {
        val records = readAll(HeartRateVariabilityRmssdRecord::class, dayRange(day, day))
        if (records.isEmpty()) null else records.map { it.heartRateVariabilityMillis }.average()
    }

    /**
     * Exercise sessions with real calories and distance.
     *
     * `ExerciseSessionRecord` carries neither — they live in their own record types,
     * time-aligned with the session. Each session is aggregated over its own
     * `[startTime, endTime]` span, which also picks up records that merely *overlap* the
     * session rather than being fully contained by it (the previous containment filter
     * dropped a distance record that started a second before the session did).
     *
     * `exerciseType` is resolved to a readable name and the raw constant is preserved as
     * [ExerciseSessionData.exerciseTypeId], so icon mapping keys off a stable id rather
     * than a string. Storing `exerciseType.toString()` put "56" in the database as the
     * workout's permanent name.
     */
    suspend fun readExerciseSessions(startDay: LocalDate, endDay: LocalDate): List<ExerciseSessionData> =
        safeRead(emptyList()) {
            val sessions = readAll(ExerciseSessionRecord::class, dayRange(startDay, endDay))
            if (sessions.isEmpty()) return@safeRead emptyList()

            sessions.map { record ->
                val startMs = record.startTime.toEpochMilli()
                val endMs = record.endTime.toEpochMilli()
                val sessionRange = TimeRangeFilter.between(record.startTime, record.endTime)

                val aggregated = aggregateOrNull(
                    setOf(
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL
                    ),
                    sessionRange
                )
                val sessionCalories = aggregated
                    ?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    ?.inKilocalories?.toInt()?.takeIf { it > 0 }
                val sessionDistance = aggregated
                    ?.get(DistanceRecord.DISTANCE_TOTAL)
                    ?.inMeters?.toFloat()?.takeIf { it > 0f }

                val startOffset = record.startZoneOffset ?: VitalTime.zone().rules.getOffset(record.startTime)

                ExerciseSessionData(
                    dateEpochDay = record.startTime.atOffset(startOffset).toLocalDate().toEpochDay(),
                    startMs = startMs,
                    endMs = endMs,
                    type = exerciseTypeName(record.exerciseType),
                    exerciseTypeId = record.exerciseType,
                    heartRatePoints = emptyList(), // filled by the repository over the session span
                    caloriesBurned = sessionCalories,
                    distanceMeters = sessionDistance
                )
            }
        }

    /**
     * The full weight/body-fat series over a range, newest-last.
     *
     * Read **once** per sync and attributed per day with last-observation-carried-forward
     * (see [weightForDay]). `readLatestWeight()` was called inside the per-day loop, so a
     * 30-day backfill issued 60 identical 90-day IPC round-trips for a single answer *and*
     * stamped today's weight onto all 30 historical rows — making any weight trend chart a
     * flat line and any body-composition comparison meaningless.
     */
    suspend fun readWeightSeries(startDay: LocalDate, endDay: LocalDate): List<WeightData> =
        safeRead(emptyList()) {
            val range = dayRange(startDay, endDay)
            val weights = readAll(WeightRecord::class, range)
            if (weights.isEmpty()) return@safeRead emptyList()
            val bodyFats = readAll(BodyFatRecord::class, range)
            val zone = VitalTime.zone()

            weights.sortedBy { it.time }.map { record ->
                val day = record.time.atZone(zone).toLocalDate().toEpochDay()
                // Nearest body-fat reading at or before this weight reading.
                val bf = bodyFats.filter { !it.time.isAfter(record.time) }.maxByOrNull { it.time }
                WeightData(
                    dateEpochDay = day,
                    weightKg = record.weight.inKilograms.toFloat(),
                    bodyFatPercent = bf?.percentage?.value?.toFloat()
                )
            }
        }

    /**
     * Last-observation-carried-forward: the most recent measurement dated on or before
     * [day], or null when the user had not been weighed yet.
     */
    fun weightForDay(series: List<WeightData>, day: LocalDate): WeightData? {
        val epochDay = day.toEpochDay()
        return series.filter { it.dateEpochDay <= epochDay }.maxByOrNull { it.dateEpochDay }
    }

    @Deprecated(
        "Stamps one value onto every day and re-queries per day during backfill. " +
            "Use readWeightSeries() once, then weightForDay().",
        ReplaceWith("readWeightSeries(startDay, endDay)")
    )
    suspend fun readLatestWeight(): WeightData? =
        readWeightSeries(VitalTime.today().minusDays(90), VitalTime.today()).lastOrNull()

    /**
     * Health Connect stores exercise type as an int constant. Storing the raw number
     * meant every workout in the UI read "79" instead of "Running".
     */
    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill Run"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Indoor Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open Water Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength Training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing Machine"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stair Climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stair Machine"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxing"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Martial Arts"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "Football"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Soccer"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "Table Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Volleyball"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Skiing"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "Snowboarding"
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "Skating"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "Rock Climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Stretching"
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "Gymnastics"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "Workout"
        else -> "Workout"
    }
}

/** Resting HR with provenance, so the UI can say whether it was measured or estimated. */
data class RestingHRResult(
    val bpm: Int,
    val derived: Boolean,
    val sampleCount: Int
)

/** One row of the Data Sources & Permissions screen. */
data class DataSourceStatus(
    val metric: String,
    val source: String,
    val permissionGranted: Boolean,
    val recordsLast30Days: Int,
    val essential: Boolean
) {
    val available: Boolean get() = permissionGranted && recordsLast30Days > 0
}

private const val MIN_OVERNIGHT_SAMPLES = 10
private val PLAUSIBLE_BPM = 30..120

/** Safety valve on the pagination loop — 100 × 1000 records is far beyond any real day. */
private const val MAX_PAGES = 100

/** Sleep fragments closer together than this are one night, not two. */
private const val SLEEP_FRAGMENT_GAP_MINUTES = 60

/** Shorter than this and it is a nap, not the night — unless nothing else qualifies. */
private const val MIN_NIGHT_MINUTES = 180

/** 18:00 — the anchor that makes an evening bedtime sort before a post-midnight one. */
private const val EVENING_ANCHOR_MINUTE = 18 * 60
