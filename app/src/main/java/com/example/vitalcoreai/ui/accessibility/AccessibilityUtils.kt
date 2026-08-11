package com.example.vitalcoreai.ui.accessibility

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * B9 — Accessibility Utilities for VitalCore AI
 *
 * Provides semantic content description helpers and accessible score announcement
 * strings for all score cards and charts.
 *
 * All public functions follow the Android Accessibility guidelines:
 * - Scores are announced as "[name] score [value] out of 100, [confidence] confidence"
 * - Trend directions include directional language: "improving", "stable", "declining"
 * - Charts include axis labels and summary descriptions
 * - "Not enough data" states are explicitly described
 */
object AccessibilityUtils {

    // ── Score announcement strings ─────────────────────────────────────────────

    /**
     * Generates a screen-reader-friendly description for any score card.
     *
     * Example: "Recovery score: 74 out of 100. High confidence. Resting HR 2 bpm
     * below your 30-day baseline. Trend: stable."
     */
    fun scoreCardDescription(
        scoreName: String,
        score: Float,
        confidence: String,
        explanation: String,
        trend: String? = null
    ): String = buildString {
        append("$scoreName score: ${score.toInt()} out of 100. ")
        append("$confidence confidence. ")
        append(explanation.trimEnd('.'))
        append(".")
        trend?.let { append(" Trend: $it.") }
    }

    /**
     * Short content description for the score circle widget used in TalkBack traversal.
     * Example: "Recovery score 74, High confidence"
     */
    fun scoreCircleDescription(scoreName: String, score: Float, confidence: String): String =
        "$scoreName score ${score.toInt()}, $confidence confidence"

    /**
     * Trend description using explicit directional language.
     * TrendDirection.UP → "improving", DOWN → "declining", NEUTRAL → "stable"
     */
    fun trendDescription(trend: String): String = when (trend.uppercase()) {
        "UP"   -> "improving"
        "DOWN" -> "declining"
        else   -> "stable"
    }

    /**
     * Description when data is insufficient.
     * Example: "Recovery score not yet available. Missing sleep data, no HR history.
     * Sync your watch to see your score."
     */
    fun insufficientDataDescription(scoreName: String, reasons: List<String>): String {
        val reasonsText = reasons.take(2).joinToString("; ")
        return "$scoreName score not yet available. $reasonsText. Sync your watch to see your score."
    }

    // ── Chart accessibility ────────────────────────────────────────────────────

    /**
     * Content description for a 7-day score sparkline chart.
     * Example: "7-day recovery trend chart. Scores ranged from 55 to 78.
     * Current score 72, trending up."
     */
    fun sparklineChartDescription(
        scoreName: String,
        values: List<Float>,
        trend: String
    ): String {
        if (values.isEmpty()) return "$scoreName chart: no data available."
        val min = values.min().toInt()
        val max = values.max().toInt()
        val current = values.last().toInt()
        val trendText = trendDescription(trend)
        return "${values.size}-day $scoreName trend chart. " +
                "Scores ranged from $min to $max. " +
                "Current score $current, $trendText."
    }

    /**
     * Content description for an HR zone distribution bar chart.
     * Example: "Heart rate zone distribution. Zone 1: 20%. Zone 2: 45%. Zone 3: 25%.
     * Zone 4: 10%. Zone 5: 0%. Dominant zone: Zone 2 Aerobic Base."
     */
    fun hrZoneChartDescription(
        zone1Pct: Float, zone2Pct: Float, zone3Pct: Float,
        zone4Pct: Float, zone5Pct: Float, dominantZone: String
    ): String = "Heart rate zone distribution. " +
            "Zone 1 Recovery: ${zone1Pct.toInt()}%. " +
            "Zone 2 Aerobic Base: ${zone2Pct.toInt()}%. " +
            "Zone 3 Aerobic: ${zone3Pct.toInt()}%. " +
            "Zone 4 Threshold: ${zone4Pct.toInt()}%. " +
            "Zone 5 VO2 Max: ${zone5Pct.toInt()}%. " +
            "Dominant zone: $dominantZone."

    /**
     * Content description for the breakdown factor row in a score detail screen.
     * Example: "Sleep Quality: 35% weight, scored 72 out of 100. Sleep efficiency
     * above your 14-day average."
     */
    fun factorRowDescription(
        factorName: String,
        contribution: Float,
        score: Float,
        description: String,
        delta: String? = null
    ): String = buildString {
        append("$factorName: ${contribution.toInt()}% weight, scored ${score.toInt()} out of 100. ")
        append(description.trimEnd('.'))
        append(".")
        delta?.let { append(" Change: $it.") }
    }
}

// ── Compose accessibility modifier extension ──────────────────────────────────

/**
 * Wrapper composable that applies a TalkBack content description to its content
 * without affecting layout. Use on any container that needs a custom semantic.
 */
@Composable
fun AccessibleContainer(
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        content()
    }
}
