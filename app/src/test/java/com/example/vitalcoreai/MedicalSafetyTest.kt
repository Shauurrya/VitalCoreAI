package com.example.vitalcoreai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-20 — the forbidden-phrase check, as a test rather than a shell grep.
 *
 * ## Why not the raw grep from the handoff
 *
 * The handoff asks for `grep -r "diagnose\|illness\|…" app/src/main/java` returning zero
 * hits. Run literally over the whole tree that can never pass, and not because the copy is
 * unsafe:
 *
 *  - `CoachContext.buildSystemPrompt()` contains "Never diagnose." — the instruction that
 *    *forbids* diagnosis. A separate test asserts that sentence is present, so the two
 *    checks would contradict each other.
 *  - `CheckInEntity.illnessFlag` is a database column name. Renaming it means a migration,
 *    and an identifier is not something a user reads.
 *  - KDoc blocks state the language contract, e.g. "forbidden: any illness, infection,
 *    diagnosis…". Deleting the contract to satisfy a grep for the contract is backwards.
 *
 * So this scans **string literals only**, which is what "copy" actually means, and carries
 * an explicit, justified allowlist. Every other hit fails the build.
 *
 * The equivalent shell command, with the same exclusions, is in `docs/V1.1_PLAN.md`.
 */
class MedicalSafetyTest {

    /** The handoff's list, verbatim. Matched case-insensitively against string literals. */
    private val forbidden = listOf(
        "diagnose", "illness", "sick", "infection", "disease",
        "syndrome", "heart problem", "you have", "symptom"
    )

    /**
     * Files whose literals are exempt, each for a stated reason.
     *
     * Anything added here needs a reason in this comment. "It was failing" is not one.
     */
    private val allowlist = mapOf(
        // The system prompt names the forbidden concepts in order to forbid them. Its text
        // is sent to a model, never rendered to a user, and V11WiringTests asserts the
        // "Never diagnose" line is present.
        "coach/CoachContext.kt" to "guardrail prompt — names the concepts in order to ban them",
        // The check-in's own field name. A user sees "Feeling under the weather?", not this.
        "data/db/entity/Entities.kt" to "illnessFlag is a column name, not copy"
    )

    private val mainSource: File by lazy {
        listOf(
            "src/main/java/com/example/vitalcoreai",
            "app/src/main/java/com/example/vitalcoreai"
        ).map(::File).first { it.isDirectory }
    }

    /**
     * String literals in a Kotlin source file.
     *
     * Deliberately crude: it takes double-quoted runs, honouring backslash escapes, and
     * ignores everything else. It over-matches inside raw strings and under-matches nothing
     * that matters, which is the right bias for a safety net — a false positive costs a
     * comment, a false negative ships medical language.
     */
    private fun stringLiterals(source: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            if (source[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < source.length && source[i] != '"') {
                    if (source[i] == '\\' && i + 1 < source.length) {
                        i++
                    } else {
                        sb.append(source[i])
                    }
                    i++
                }
                out += sb.toString()
            }
            i++
        }
        return out
    }

    private fun sourceFiles(): List<File> =
        mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relativePath(file: File): String =
        file.path.replace('\\', '/').substringAfter("com/example/vitalcoreai/")

    @Test
    fun `the scan finds the source tree at all`() {
        val files = sourceFiles()
        assertTrue("expected to scan the main source tree, found ${files.size} files", files.size > 50)
    }

    @Test
    fun `no user-facing copy contains a forbidden medical phrase`() {
        val violations = mutableListOf<String>()

        for (file in sourceFiles()) {
            val relative = relativePath(file)
            if (allowlist.keys.any { relative.endsWith(it) }) continue

            for (literal in stringLiterals(file.readText())) {
                val lower = literal.lowercase()
                for (phrase in forbidden) {
                    if (lower.contains(phrase)) {
                        violations += "$relative: \"$phrase\" in \"${literal.take(120)}\""
                    }
                }
            }
        }

        assertTrue(
            "forbidden medical phrases in user-facing copy:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * The disclaimers the handoff names, present and unchanged.
     *
     * "diagnosis" is not the forbidden literal "diagnose", so the denial and the ban can
     * both hold. If a future edit reaches for the shorter word, this test is what stops it.
     */
    @Test
    fun `every engine disclaimer is present and unchanged`() {
        val expected = mapOf(
            "analytics/AnomalyDetectionEngine.kt" to
                "This is a pattern worth monitoring, not a diagnosis.",
            "analytics/ReadinessForecastEngine.kt" to
                "This is a projection from your own recent patterns, not a prediction."
        )
        for ((relative, disclaimer) in expected) {
            val file = File(mainSource, relative)
            assertTrue("missing source file $relative", file.exists())
            assertTrue(
                "disclaimer missing or altered in $relative: expected \"$disclaimer\"",
                file.readText().contains(disclaimer)
            )
        }
    }

    /**
     * The app must not be able to send health data anywhere.
     *
     * The privacy posture is a build fact, not a policy document: no INTERNET permission and
     * no HTTP client on the classpath. Both are one careless dependency away from being
     * untrue, so both are asserted.
     */
    @Test
    fun `the app declares no internet permission`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.exists() }
        val text = manifest.readText()
        assertFalse(
            "android.permission.INTERNET must not be declared — this app is offline-only",
            text.contains("android.permission.INTERNET")
        )
    }

    @Test
    fun `no http client is referenced anywhere in main source`() {
        val networkImports = listOf(
            "okhttp3", "retrofit2", "java.net.HttpURLConnection", "java.net.URL(",
            "io.ktor.client"
        )
        val hits = sourceFiles().flatMap { file ->
            val text = file.readText()
            networkImports.filter { text.contains(it) }.map { "${relativePath(file)}: $it" }
        }
        assertTrue("network client referenced in an offline-only app:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /**
     * T-11's grep, as a test: no day key may be derived outside [VitalTime].
     *
     * `VitalTime.kt` itself is the one place allowed to call the underlying APIs, and
     * `DayTicker.kt` quotes the old call in a KDoc explaining what it replaced.
     */
    @Test
    fun `no source outside VitalTime resolves the current date directly`() {
        val banned = listOf("LocalDate.now()", "ZoneId.systemDefault()", "LocalDateTime.now()")
        val exempt = listOf("core/time/VitalTime.kt", "core/time/DayTicker.kt")

        val hits = sourceFiles()
            .filterNot { file -> exempt.any { relativePath(file).endsWith(it) } }
            .flatMap { file ->
                val text = file.readText()
                banned.filter { text.contains(it) }.map { "${relativePath(file)}: $it" }
            }

        assertTrue(
            "day keys must be derived through VitalTime so travel and DST cannot corrupt them:\n" +
                hits.joinToString("\n"),
            hits.isEmpty()
        )
    }
}
