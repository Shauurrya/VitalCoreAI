package com.example.vitalcoreai.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written Room migrations.
 *
 * ## Why these exist
 *
 * The database previously ran on `fallbackToDestructiveMigration(dropAllTables = true)` with
 * no migrations at all. The justification recorded in [VitalCoreDatabase] was that the
 * database "is a local cache of Health Connect data and is fully reconstructible from it".
 *
 * That reasoning is sound for six of the twelve tables and **wrong for four**:
 *
 * | Table | Reconstructible? |
 * |---|---|
 * | `daily_metrics`, `computed_scores`, `exercise_sessions`, `heart_rate_samples`, `weekly_reports`, `monthly_reports` | Yes — re-derived from Health Connect on the next sync |
 * | `achievements`, `sync_state` | Yes — recomputed |
 * | **`check_ins`** | **No.** User-authored, exists nowhere else. |
 * | **`journal_entries`** | **No.** User-authored. |
 * | **`workout_exercises`** | **No.** User-authored (sets, reps, weight, RPE). |
 * | **`muscle_recovery`** | **No.** |
 *
 * A year of journal entries is exactly the data that makes habit correlation meaningful,
 * and it is precisely what a destructive migration silently deletes. Worse, the failure is
 * invisible: `HabitCorrelationEngine` simply drops below its minimum-observation threshold
 * and stops producing insights, with nothing to tell the user why.
 *
 * ## Policy
 *
 * - Versions **6 and later** migrate properly. Nothing user-authored is ever dropped.
 * - Versions **1–5** retain the destructive fallback (see `DatabaseModule`). Those schemas
 *   predate or barely postdate the user-authored tables, and hand-writing four historical
 *   migration paths for a pre-release schema would cost more than it protects.
 *
 * ## Adding the next one
 *
 * `ALTER TABLE ... ADD COLUMN` is the only safe operation SQLite offers without a table
 * rebuild. Every added column must therefore be **nullable or carry a default**, which is
 * why every new field on `ComputedScoresEntity` is declared `T? = null`. Keep that rule.
 */
object Migrations {

    /**
     * v6 → v7 — persisted outputs of the V1.1 intelligence engines.
     *
     * These are stored rather than recomputed on every screen open for two reasons: the
     * forecast and trend engines read 30 days of history each, and a persisted value is a
     * value the debug screen can inspect after the fact when a user reports something odd.
     */
    /**
     * The statements v6→v7 executes, exposed so a JVM test can cross-check every added
     * column against the exported schema without needing a device.
     *
     * The defect this guards against is the commonest migration bug there is: a field is
     * added to the entity, Room's generated code expects the column, and the `ALTER TABLE`
     * is forgotten — which crashes on first launch for every existing user while looking
     * perfectly fine on a fresh install.
     */
    val MIGRATION_6_7_STATEMENTS: List<String> = listOf(
        // ── Readiness forecast (Priority 4) ──────────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN forecastLow INTEGER",
        "ALTER TABLE computed_scores ADD COLUMN forecastHigh INTEGER",
        "ALTER TABLE computed_scores ADD COLUMN forecastConfidence TEXT",
        "ALTER TABLE computed_scores ADD COLUMN forecastDrivers TEXT",
        "ALTER TABLE computed_scores ADD COLUMN forecastRisks TEXT",

        // ── Sleep consistency (Priority 10) ──────────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN sleepConsistencyScore REAL",
        "ALTER TABLE computed_scores ADD COLUMN sleepConsistencyLabel TEXT",
        "ALTER TABLE computed_scores ADD COLUMN bedtimeSdMinutes INTEGER",
        "ALTER TABLE computed_scores ADD COLUMN wakeSdMinutes INTEGER",

        // ── Recovery trends (Priority 6) ─────────────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN trend7Direction TEXT",
        "ALTER TABLE computed_scores ADD COLUMN trend14Direction TEXT",
        "ALTER TABLE computed_scores ADD COLUMN trend30Direction TEXT",
        "ALTER TABLE computed_scores ADD COLUMN trendContributors TEXT",

        // ── Anomalies (Priority 5) ───────────────────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN anomaliesEncoded TEXT",
        "ALTER TABLE computed_scores ADD COLUMN anomalyCount INTEGER",

        // ── Recommendation (Priority 8) ──────────────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN recommendationType TEXT",
        "ALTER TABLE computed_scores ADD COLUMN recommendationIntensity TEXT",
        "ALTER TABLE computed_scores ADD COLUMN recommendationVolumePct INTEGER",
        "ALTER TABLE computed_scores ADD COLUMN recommendationDetail TEXT",

        // ── Data quality dimensions (Priority 2) ─────────────────────────────
        "ALTER TABLE computed_scores ADD COLUMN dataQualityFactors TEXT",
        "ALTER TABLE computed_scores ADD COLUMN dataQualityPositives TEXT",

        // ── Freshness input (Priority 2) ─────────────────────────────────────
        "ALTER TABLE daily_metrics ADD COLUMN newestRecordTimestampMs INTEGER",

        // Index supporting the trend and forecast reads, which scan a day range and are
        // the heaviest queries the analytics layer issues.
        "CREATE INDEX IF NOT EXISTS index_computed_scores_dateEpochDay " +
                "ON computed_scores (dateEpochDay)"
    )

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_6_7_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Every migration, in order. Registered by `DatabaseModule`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_6_7)

    /**
     * Schema versions that may still be wiped rather than migrated.
     *
     * Deliberately does **not** include 6 or later. If you find yourself wanting to add a
     * version to this list to avoid writing a migration, write the migration instead —
     * every entry here is a version whose users lose their journals and check-ins.
     */
    val DESTRUCTIVE_FALLBACK_FROM: IntArray = intArrayOf(1, 2, 3, 4, 5)
}
