package com.example.vitalcoreai.data.healthconnect

import com.example.vitalcoreai.data.model.*
import java.time.LocalDate

/**
 * B12 — Extensible Health Data Importer Interface
 *
 * Abstracts the data ingestion layer so the app can eventually support
 * multiple data sources (Health Connect, Garmin, Fitbit, Polar, CSV, etc.)
 * without changing the repository or analytics code.
 *
 * Current implementation: [HealthConnectManager] (sole concrete class).
 * Future implementations: GarminConnectImporter, FitbitApiImporter,
 *   PolarFlowImporter, CsvImporter, etc.
 *
 * Each importer provides the same structured output types, so the
 * repository/scoring pipeline is source-agnostic.
 */
interface HealthDataImporter {

    /** Source identifier (for data provenance tracking). */
    val sourceId: String

    /** Human-readable name for UI display. */
    val displayName: String

    /** Whether this importer is available and ready to use. */
    suspend fun isAvailable(): Boolean

    // ── Daily Metrics ──────────────────────────────────────────────────

    suspend fun readActivityForDay(day: LocalDate): DailyActivityData?

    // ── Heart Rate ─────────────────────────────────────────────────────

    suspend fun readHeartRateForDay(day: LocalDate): List<HeartRatePoint>
    suspend fun readRestingHRForDays(start: LocalDate, end: LocalDate): List<RestingHRData>

    // ── Sleep ──────────────────────────────────────────────────────────

    suspend fun readSleepSessions(start: LocalDate, end: LocalDate): List<SleepData>

    // ── Exercise ───────────────────────────────────────────────────────

    suspend fun readExerciseSessions(start: LocalDate, end: LocalDate): List<ExerciseSessionData>

    // ── Body ───────────────────────────────────────────────────────────

    suspend fun readLatestWeight(): WeightData?
    suspend fun readSpO2ForDay(day: LocalDate): SpO2Reading?
}
