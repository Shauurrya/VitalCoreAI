package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Priority 6 — Recovery Trend Engine.
 *
 * Today's readiness number answers "how am I right now". It cannot answer "am I getting
 * better or worse", which is the question that actually changes behaviour. This engine
 * classifies the 7-, 14- and 30-day direction and — crucially — attributes it.
 *
 * ## Why attribution matters more than the arrow
 *
 * "Recovery declining ↓" is an anxiety generator on its own. "Recovery declining, driven
 * by sleep dropping 48 minutes a night and three more high-load days than the previous
 * fortnight" is a decision. Every trend this engine reports carries its contributors or
 * it does not get reported.
 *
 * ## Method
 *
 * Direction comes from [RobustStats.trendOf] — Mann-Kendall significance plus a Theil-Sen
 * slope — not from comparing the first and last value, which on noisy daily scores is
 * close to a coin flip.
 *
 * Contributors come from a first-half / second-half comparison of candidate drivers within
 * the same window. Split-half is used rather than correlating against the score because
 * with 14 points a correlation is underpowered, while a mean shift of a full week is
 * something the user can actually recognise in their own behaviour.
 */
object RecoveryTrendEngine {

    /** One day of the inputs a trend can be attributed to. */
    data class DayInput(
        val dateEpochDay: Long,
        val recoveryScore: Float?,
        val readinessScore: Float?,
        val sleepMinutes: Int?,
        val bedtimeMinuteOfDay: Int?,
        val wakeTimeMinuteOfDay: Int?,
        val restingHR: Int?,
        val strain: Float?,
        val hadHighLoad: Boolean = false
    )

    enum class Window(val days: Int, val label: String) {
        WEEK(7, "7-day"),
        FORTNIGHT(14, "14-day"),
        MONTH(30, "30-day")
    }

    /** A named driver of the observed change, strongest first. */
    data class Contributor(
        val name: String,
        val description: String,
        val favourable: Boolean,
        /** Absolute magnitude of the shift, used only for ranking. */
        val magnitude: Double
    )

    /**
     * @param direction  classified via Mann-Kendall; INSUFFICIENT_DATA when the window is thin
     * @param averageEarlier mean score over the first half of the window
     * @param averageRecent  mean score over the second half
     * @param delta          [averageRecent] − [averageEarlier]
     * @param daysCovered    days that actually carried a score (not the window width)
     */
    data class TrendReport(
        val window: Window,
        val direction: RobustStats.Trend,
        val averageEarlier: Float?,
        val averageRecent: Float?,
        val delta: Float?,
        val slopePerDay: Float,
        val significance: Double,
        val daysCovered: Int,
        val confidence: Confidence,
        val contributors: List<Contributor>,
        val headline: String,
        val summary: String
    ) {
        val isMeaningful: Boolean get() = direction != RobustStats.Trend.INSUFFICIENT_DATA

        /** "↑" / "↓" / "→" / "↕". Always paired with [directionLabel] — never colour or glyph alone. */
        val arrow: String
            get() = when (direction) {
                RobustStats.Trend.IMPROVING -> "↑"
                RobustStats.Trend.DECLINING -> "↓"
                RobustStats.Trend.VARIABLE -> "↕"
                RobustStats.Trend.STABLE -> "→"
                RobustStats.Trend.INSUFFICIENT_DATA -> "—"
            }

        val directionLabel: String
            get() = when (direction) {
                RobustStats.Trend.IMPROVING -> "Improving"
                RobustStats.Trend.DECLINING -> "Declining"
                RobustStats.Trend.VARIABLE -> "Highly variable"
                RobustStats.Trend.STABLE -> "Stable"
                RobustStats.Trend.INSUFFICIENT_DATA -> "Not enough data"
            }
    }

    /**
     * Minimum scored days in a window before a direction is reported.
     *
     * Expressed as a fraction of the window so the 30-day view is not declared on the
     * strength of five days, with an absolute floor of 5 for the 7-day view.
     */
    private const val MIN_COVERAGE_FRACTION = 0.5
    private const val MIN_DAYS_ABSOLUTE = 5

    /** Point shift below which a change is noise, not news. */
    private const val MIN_REPORTABLE_DELTA = 3f

    // ── Public API ───────────────────────────────────────────────────────────

    /** Reports for all three windows, computed from one pass over the same history. */
    fun analyseAll(
        days: List<DayInput>,
        useReadiness: Boolean = true
    ): List<TrendReport> = Window.values().map { analyse(days, it, useReadiness) }

    /**
     * @param days ordered any way; sorted internally. Should cover at least [Window.days].
     * @param useReadiness true → trend readiness, false → trend recovery.
     */
    fun analyse(
        days: List<DayInput>,
        window: Window,
        useReadiness: Boolean = true
    ): TrendReport {
        val sorted = days.sortedBy { it.dateEpochDay }.takeLast(window.days)
        val scoreOf: (DayInput) -> Float? =
            if (useReadiness) { d -> d.readinessScore } else { d -> d.recoveryScore }

        val scored = sorted.filter { scoreOf(it) != null }
        val values = scored.mapNotNull { scoreOf(it)?.toDouble() }

        val minDays = maxOf(MIN_DAYS_ABSOLUTE, (window.days * MIN_COVERAGE_FRACTION).toInt())
        if (values.size < minDays) {
            return TrendReport(
                window = window,
                direction = RobustStats.Trend.INSUFFICIENT_DATA,
                averageEarlier = null,
                averageRecent = null,
                delta = null,
                slopePerDay = 0f,
                significance = 0.0,
                daysCovered = values.size,
                confidence = Confidence.LOW,
                contributors = emptyList(),
                headline = "${window.label} trend: not enough data",
                summary = "Needs at least $minDays scored days in the last ${window.days}. " +
                        "${values.size} recorded so far."
            )
        }

        val trend = RobustStats.trendOf(
            values = values,
            higherIsBetter = true,
            minSlopePerDay = 0.15,
            minSamples = minDays
        )

        val mid = scored.size / 2
        val earlier = scored.take(mid).mapNotNull { scoreOf(it) }
        val recent = scored.drop(mid).mapNotNull { scoreOf(it) }
        val avgEarlier = earlier.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val avgRecent = recent.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val delta = if (avgEarlier != null && avgRecent != null) avgRecent - avgEarlier else null

        val contributors = findContributors(
            earlierDays = sorted.take(sorted.size / 2),
            recentDays = sorted.drop(sorted.size / 2),
            trendIsFavourable = trend.direction == RobustStats.Trend.IMPROVING
        )

        val confidence = when {
            values.size >= (window.days * 0.85) -> Confidence.HIGH
            values.size >= (window.days * 0.6) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return TrendReport(
            window = window,
            direction = trend.direction,
            averageEarlier = avgEarlier,
            averageRecent = avgRecent,
            delta = delta,
            slopePerDay = trend.slopePerDay.toFloat(),
            significance = trend.significance,
            daysCovered = values.size,
            confidence = confidence,
            contributors = contributors,
            headline = buildHeadline(window, trend.direction),
            summary = buildSummary(window, trend.direction, avgEarlier, avgRecent, values.size)
        )
    }

    // ── Attribution ──────────────────────────────────────────────────────────

    /**
     * Compare candidate drivers between the two halves of the window.
     *
     * Each driver has a "materiality" threshold below which it is not reported at all.
     * Without them the list fills with 3-minute sleep differences that no user could act
     * on, and the genuinely large driver gets buried at position four.
     */
    private fun findContributors(
        earlierDays: List<DayInput>,
        recentDays: List<DayInput>,
        trendIsFavourable: Boolean
    ): List<Contributor> {
        if (earlierDays.isEmpty() || recentDays.isEmpty()) return emptyList()
        val out = mutableListOf<Contributor>()

        // ── Sleep duration: materiality 20 min/night ─────────────────────────
        meanOf(earlierDays) { it.sleepMinutes?.toDouble() }?.let { before ->
            meanOf(recentDays) { it.sleepMinutes?.toDouble() }?.let { after ->
                val d = after - before
                if (abs(d) >= 20) {
                    out += Contributor(
                        name = if (d > 0) "More sleep" else "Less sleep",
                        description = "Sleep averaged ${formatMinutes(after)} a night, " +
                                "${formatMinutes(abs(d))} ${if (d > 0) "more" else "less"} than the previous period.",
                        favourable = d > 0,
                        magnitude = abs(d)
                    )
                }
            }
        }

        // ── Sleep consistency: materiality 15 min of SD ──────────────────────
        val sdBefore = timingSd(earlierDays)
        val sdAfter = timingSd(recentDays)
        if (sdBefore != null && sdAfter != null) {
            val d = sdAfter - sdBefore
            if (abs(d) >= 15) {
                out += Contributor(
                    name = if (d < 0) "Better sleep consistency" else "Less consistent sleep",
                    description = "Bed and wake times varied by about ${formatMinutes(sdAfter)}, " +
                            "${formatMinutes(abs(d))} ${if (d < 0) "less" else "more"} than before.",
                    favourable = d < 0,
                    magnitude = abs(d)
                )
            }
        }

        // ── High-load days: materiality 1 day ────────────────────────────────
        val loadBefore = earlierDays.count { it.hadHighLoad }
        val loadAfter = recentDays.count { it.hadHighLoad }
        val loadDelta = loadAfter - loadBefore
        if (abs(loadDelta) >= 1) {
            out += Contributor(
                name = if (loadDelta < 0) "Fewer high-load days" else "More high-load days",
                description = "$loadAfter high-load ${dayWord(loadAfter)} in the recent period " +
                        "versus $loadBefore before.",
                favourable = loadDelta < 0,
                magnitude = abs(loadDelta).toDouble() * 10.0
            )
        }

        // ── Resting HR: materiality 2 bpm ────────────────────────────────────
        meanOf(earlierDays) { it.restingHR?.toDouble() }?.let { before ->
            meanOf(recentDays) { it.restingHR?.toDouble() }?.let { after ->
                val d = after - before
                if (abs(d) >= 2) {
                    out += Contributor(
                        name = if (d < 0) "Resting HR settling" else "Resting HR elevated",
                        description = "Resting heart rate averaged ${after.roundToInt()} bpm, " +
                                "${abs(d).roundToInt()} bpm ${if (d < 0) "lower" else "higher"} than the previous period.",
                        favourable = d < 0,
                        magnitude = abs(d) * 3.0
                    )
                }
            }
        }

        // ── Consecutive high-load streak: materiality 1 day ──────────────────
        val streakBefore = longestHighLoadStreak(earlierDays)
        val streakAfter = longestHighLoadStreak(recentDays)
        if (abs(streakAfter - streakBefore) >= 2) {
            out += Contributor(
                name = if (streakAfter < streakBefore) "Shorter hard-training blocks"
                else "Longer hard-training blocks",
                description = "Longest run of consecutive high-load days went from " +
                        "$streakBefore to $streakAfter.",
                favourable = streakAfter < streakBefore,
                magnitude = abs(streakAfter - streakBefore).toDouble() * 8.0
            )
        }

        // Rank by magnitude, but surface drivers that agree with the trend direction first —
        // a list whose top entry contradicts the headline reads as a bug to the user.
        return out
            .sortedWith(
                compareByDescending<Contributor> { it.favourable == trendIsFavourable }
                    .thenByDescending { it.magnitude }
            )
            .take(3)
    }

    private fun meanOf(days: List<DayInput>, sel: (DayInput) -> Double?): Double? {
        val v = days.mapNotNull(sel)
        return if (v.isEmpty()) null else v.average()
    }

    /**
     * Combined bed/wake timing spread for a period, using circular SD so a sleeper who
     * straddles midnight is not scored as maximally erratic.
     */
    private fun timingSd(days: List<DayInput>): Double? {
        val bed = days.mapNotNull { it.bedtimeMinuteOfDay }
        val wake = days.mapNotNull { it.wakeTimeMinuteOfDay }
        val parts = buildList {
            if (bed.size >= 3) add(BaselineUtils.circularStdDevMinutes(bed))
            if (wake.size >= 3) add(BaselineUtils.circularStdDevMinutes(wake))
        }
        return if (parts.isEmpty()) null else parts.average()
    }

    private fun longestHighLoadStreak(days: List<DayInput>): Int {
        var best = 0
        var run = 0
        for (d in days.sortedBy { it.dateEpochDay }) {
            if (d.hadHighLoad) {
                run++
                if (run > best) best = run
            } else run = 0
        }
        return best
    }

    // ── Copy ─────────────────────────────────────────────────────────────────

    private fun buildHeadline(window: Window, direction: RobustStats.Trend): String =
        when (direction) {
            RobustStats.Trend.IMPROVING -> "${window.label} recovery trend: Improving"
            RobustStats.Trend.DECLINING -> "${window.label} recovery trend: Declining"
            RobustStats.Trend.VARIABLE -> "${window.label} recovery trend: Highly variable"
            RobustStats.Trend.STABLE -> "${window.label} recovery trend: Stable"
            RobustStats.Trend.INSUFFICIENT_DATA -> "${window.label} recovery trend: Not enough data"
        }

    private fun buildSummary(
        window: Window,
        direction: RobustStats.Trend,
        avgEarlier: Float?,
        avgRecent: Float?,
        daysCovered: Int
    ): String = buildString {
        val movement = if (avgEarlier != null && avgRecent != null &&
            abs(avgRecent - avgEarlier) >= MIN_REPORTABLE_DELTA
        ) {
            "Your average moved from ${avgEarlier.roundToInt()} to ${avgRecent.roundToInt()} " +
                    "over the last ${window.days} days. "
        } else if (avgRecent != null) {
            "Your average has held near ${avgRecent.roundToInt()} over the last ${window.days} days. "
        } else ""

        when (direction) {
            RobustStats.Trend.IMPROVING -> append("Recovery is trending up. $movement")
            RobustStats.Trend.DECLINING -> append("Recovery is trending down. $movement")
            RobustStats.Trend.VARIABLE ->
                append("Recovery is swinging widely day to day rather than moving in one direction. $movement")
            RobustStats.Trend.STABLE ->
                append("Recovery is holding steady with no clear direction. $movement")
            RobustStats.Trend.INSUFFICIENT_DATA ->
                append("Not enough scored days yet to judge a direction.")
        }
        if (direction != RobustStats.Trend.INSUFFICIENT_DATA) {
            append("Based on $daysCovered scored ${dayWord(daysCovered)}.")
        }
    }

    private fun dayWord(n: Int) = if (n == 1) "day" else "days"

    private fun formatMinutes(minutes: Double): String {
        val m = minutes.roundToInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}
