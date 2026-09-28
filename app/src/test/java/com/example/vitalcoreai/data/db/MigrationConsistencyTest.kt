package com.example.vitalcoreai.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the v6→v7 migration against Room's **exported schema**, on the JVM.
 *
 * ## Why not `MigrationTestHelper`
 *
 * The canonical migration test needs an instrumented device *and* an exported schema for
 * the **source** version. Schema export was only switched on at v7, so no `6.json` exists
 * to migrate from — a `MigrationTestHelper` test would have to be written against a
 * hand-authored v6 schema, and a hand-authored schema is exactly the artefact most likely
 * to be wrong in the same way the migration is wrong.
 *
 * This test attacks the failure mode that actually bites instead: **entity/migration
 * drift**. If a column is added to `ComputedScoresEntity` but the matching `ALTER TABLE`
 * is forgotten, Room's generated code expects a column the database does not have. A fresh
 * install works perfectly — the table is created from the entity — while every existing
 * user crashes on launch. That asymmetry is why the bug ships so often.
 *
 * Comparing the migration's statements against the exported v7 schema catches it without a
 * device. Write the full `MigrationTestHelper` test as well once a `6.json` can be
 * captured from a real v6 build.
 */
class MigrationConsistencyTest {

    private val schemaFile: File? by lazy {
        // Unit tests run with the working directory at the module root, but be forgiving
        // about it so this does not become a flaky path assertion.
        listOf(
            "schemas/com.example.vitalcoreai.data.db.VitalCoreDatabase/7.json",
            "app/schemas/com.example.vitalcoreai.data.db.VitalCoreDatabase/7.json"
        ).map(::File).firstOrNull { it.exists() }
    }

    private fun schemaJson(): String = schemaFile!!.readText()

    /** Column names declared for a table in the exported schema. */
    private fun columnsOf(json: String, table: String): Set<String> {
        // Locate the table's entity block, then harvest every "fieldPath"/"columnName".
        val marker = "\"tableName\": \"$table\""
        val start = json.indexOf(marker)
        if (start < 0) return emptySet()
        // The next tableName marker bounds this entity.
        val next = json.indexOf("\"tableName\": \"", start + marker.length)
        val block = if (next < 0) json.substring(start) else json.substring(start, next)
        return Regex("\"columnName\":\\s*\"([^\"]+)\"")
            .findAll(block)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** ("table", "column") for each ADD COLUMN statement in the migration. */
    private fun addedColumns(statements: List<String>): List<Pair<String, String>> =
        statements.mapNotNull { sql ->
            Regex("ALTER TABLE (\\w+) ADD COLUMN (\\w+)")
                .find(sql)
                ?.let { it.groupValues[1] to it.groupValues[2] }
        }

    @Test
    fun `exported schema is present and is version 7`() {
        assumeTrue("schema export not found — run an assemble first", schemaFile != null)
        val json = schemaJson()
        assertTrue(
            "exported schema should declare version 7",
            Regex("\"version\":\\s*7").containsMatchIn(json)
        )
    }

    /**
     * The core assertion: every column the migration adds must exist in the v7 schema.
     * A mismatch means the migration adds something the entity does not declare.
     */
    @Test
    fun `every column added by the migration exists in the v7 schema`() {
        assumeTrue(schemaFile != null)
        val json = schemaJson()
        val missing = addedColumns(Migrations.MIGRATION_6_7_STATEMENTS)
            .filterNot { (table, column) -> column in columnsOf(json, table) }

        assertTrue(
            "migration adds columns absent from the v7 schema: $missing",
            missing.isEmpty()
        )
    }

    /**
     * The mirror assertion, and the one that catches the dangerous direction: a field added
     * to the entity with no corresponding `ALTER TABLE`. Fresh installs would work; every
     * upgrading user would crash.
     */
    @Test
    fun `every v7-only column is created by the migration`() {
        assumeTrue(schemaFile != null)
        val json = schemaJson()

        // Columns introduced in v7. Kept explicit so adding a field without updating this
        // list fails here rather than in production.
        val expectedNewComputedScores = setOf(
            "forecastLow", "forecastHigh", "forecastConfidence", "forecastDrivers", "forecastRisks",
            "sleepConsistencyScore", "sleepConsistencyLabel", "bedtimeSdMinutes", "wakeSdMinutes",
            "trend7Direction", "trend14Direction", "trend30Direction", "trendContributors",
            "anomaliesEncoded", "anomalyCount",
            "recommendationType", "recommendationIntensity", "recommendationVolumePct",
            "recommendationDetail",
            "dataQualityFactors", "dataQualityPositives"
        )
        val expectedNewDailyMetrics = setOf("newestRecordTimestampMs")

        val added = addedColumns(Migrations.MIGRATION_6_7_STATEMENTS)
        val addedComputedScores = added.filter { it.first == "computed_scores" }.map { it.second }.toSet()
        val addedDailyMetrics = added.filter { it.first == "daily_metrics" }.map { it.second }.toSet()

        assertEquals(
            "computed_scores: migration and expected v7 columns disagree",
            expectedNewComputedScores, addedComputedScores
        )
        assertEquals(
            "daily_metrics: migration and expected v7 columns disagree",
            expectedNewDailyMetrics, addedDailyMetrics
        )

        // And they must genuinely be in the schema.
        val schemaComputed = columnsOf(json, "computed_scores")
        assertTrue(
            "v7 schema is missing: ${expectedNewComputedScores - schemaComputed}",
            schemaComputed.containsAll(expectedNewComputedScores)
        )
        assertTrue(
            columnsOf(json, "daily_metrics").containsAll(expectedNewDailyMetrics)
        )
    }

    /**
     * SQLite's `ALTER TABLE ADD COLUMN` cannot add a NOT NULL column without a default, so
     * a migration that tried would fail at runtime on a populated table.
     */
    @Test
    fun `no added column is NOT NULL without a default`() {
        val offenders = (Migrations.MIGRATION_6_7_STATEMENTS + Migrations.MIGRATION_7_8_STATEMENTS).filter { sql ->
            sql.contains("ADD COLUMN") &&
                    sql.contains("NOT NULL", ignoreCase = true) &&
                    !sql.contains("DEFAULT", ignoreCase = true)
        }
        assertTrue("NOT NULL without DEFAULT will fail on a populated table: $offenders", offenders.isEmpty())
    }

    /**
     * The policy assertion. Every version listed for destructive fallback is a version
     * whose users lose their journals and check-ins, so v6+ must never appear.
     */
    @Test
    fun `destructive fallback never covers a version with user-authored data at risk`() {
        assertTrue(
            "v6 or later must migrate, not be wiped: ${Migrations.DESTRUCTIVE_FALLBACK_FROM.toList()}",
            Migrations.DESTRUCTIVE_FALLBACK_FROM.none { it >= 6 }
        )
    }

    @Test
    fun `migration chain is contiguous and registered`() {
        assertTrue("at least one migration must be registered", Migrations.ALL.isNotEmpty())
        val sorted = Migrations.ALL.sortedBy { it.startVersion }
        for (i in 1 until sorted.size) {
            assertEquals(
                "gap in the migration chain",
                sorted[i - 1].endVersion, sorted[i].startVersion
            )
        }
        assertEquals("the chain must end at the current schema version", 8, sorted.last().endVersion)
    }

    @Test
    fun `v8 migration exactly covers the exported schema changes`() {
        val previousFile = requireNotNull(schemaFile) { "The checked-in v7 schema is required" }
        val currentFile = File(previousFile.parentFile, "8.json")
        assertTrue("The checked-in v8 schema is required", currentFile.exists())
        val previous = previousFile.readText()
        val current = currentFile.readText()
        val tables = Regex("\\\"tableName\\\":\\s*\\\"([^\\\"]+)\\\"")
            .findAll(current).map { it.groupValues[1] }.toSet()
        val addedInSchema = tables.flatMap { table ->
            (columnsOf(current, table) - columnsOf(previous, table)).map { table to it }
        }.toSet()
        assertEquals(addedInSchema, addedColumns(Migrations.MIGRATION_7_8_STATEMENTS).toSet())
        tables.forEach { table ->
            assertTrue("v8 must preserve every existing $table column", columnsOf(current, table).containsAll(columnsOf(previous, table)))
        }
    }
}
