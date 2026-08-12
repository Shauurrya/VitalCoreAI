package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Priority 13 — Personal Insight Discovery.
 *
 * Mines the user's own history for patterns worth telling them about, e.g.
 * *"your strongest sessions follow nights of 7h 45m or more"*.
 *
 * ## The bar for surfacing anything
 *
 * A pattern engine that reports everything it finds is a random-noise generator with good
 * copywriting. Four gates apply, and a candidate must clear **all** of them:
 *
 * 1. **Sample size** — at least [MIN_OBSERVATIONS_PER_GROUP] observations on each side of
 *    the comparison.
 * 2. **Statistical significance** — Welch's t-test at p < [ALPHA]. Welch rather than
 *    Student because the two groups are never the same size or variance.
 * 3. **Practical effect size** — the difference must also exceed a per-pattern materiality
 *    floor. A statistically detectable 4-minute sleep difference is not an insight.
 * 4. **Multiple-comparison control** — the engine tests many hypotheses, so [ALPHA] is
 *    divided by the number of tests actually run (Bonferroni). Without this, testing 10
 *    patterns at p < 0.05 yields roughly a 40% chance of at least one false positive, and
 *    the app would confidently report a coincidence every week.
 *
 * ## Language
 *
 * Every output describes an **association observed in your data**. None of it claims
 * causation, and the copy is written so it cannot be read that way.
 */
object InsightDiscoveryEngine {

    /** One day of everything a pattern can be built from. */
    data class DayRecord(
        val dateEpochDay: Long,
        val readiness: Float? = null,
        val recovery: Float? = null,
        val sleepMinutes: Int? = null,
        val sleepScore: Float? = null,
        val bedtimeMinuteOfDay: Int? = null,
        val restingHR: Int? = null,
        val strain: Float? = null,
        val hadWorkout: Boolean = false,
        val hadHighLoad: Boolean = false,
        val checkInEnergy: Int? = null,
        val checkInStress: Int? = null,
        val isRestDay: Boolean = false
    )

    /**
     * @param effectSize      difference between the two group means, in the metric's units
     * @param pValue          Welch two-sided p
     * @param observations    total days behind the finding
     * @param strength        how loudly to present it
     */
    data class Insight(
        val id: String,
        val title: String,
        val body: String,
        val effectSize: Double,
        val pValue: Double,
        val observations: Int,
        val strength: Strength,
        val category: Category
    )

    enum class Strength { SUGGESTIVE, CLEAR, STRONG }
    enum class Category { SLEEP, TRAINING, RECOVERY, CONSISTENCY, LIFESTYLE }

    // ── Gates ────────────────────────────────────────────────────────────────

    const val MIN_OBSERVATIONS_PER_GROUP = 5
    private const val ALPHA = 0.05

    /** Never surface more than this at once. */
    private const val MAX_INSIGHTS = 5

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * @param days full available history, any order. Longer is strictly better — most
     *             patterns cannot clear the gates below about three weeks of data.
     */
    fun discover(days: List<DayRecord>): List<Insight> {
        val history = days.sortedBy { it.dateEpochDay }
        if (history.size < MIN_OBSERVATIONS_PER_GROUP * 2) return emptyList()

        val candidates = mutableListOf<Insight>()
        var testsRun = 0

        fun consider(insight: Insight?) {
            if (insight != null) candidates += insight
        }

        // Each probe increments the test counter whether or not it produces a candidate —
        // the correction must account for every hypothesis examined, not just the winners.
        testsRun++; consider(sleepThresholdPattern(history))
        testsRun++; consider(sleepVsReadinessPattern(history))
        testsRun++; consider(consecutiveHighLoadPattern(history))
        testsRun++; consider(restDayPattern(history))
        testsRun++; consider(bedtimeConsistencyPattern(history))
        testsRun++; consider(restingHRSleepPattern(history))

        val corrected = ALPHA / testsRun.coerceAtLeast(1)

        return candidates
            .filter { it.pValue < corrected }
            .sortedWith(compareByDescending<Insight> { it.strength.ordinal }.thenBy { it.pValue })
            .take(MAX_INSIGHTS)
    }

    // ── Patterns ─────────────────────────────────────────────────────────────

    /**
     * "Your strongest sessions follow nights of Xh or more."
     *
     * Splits workout days by the *previous night's* sleep at the median, and compares
     * strain achieved. Uses the previous night deliberately: sleep after a hard session is
     * a consequence, not a cause, and comparing same-night sleep would invert the arrow.
     */
    private fun sleepThresholdPattern(history: List<DayRecord>): Insight? {
        val byDay = history.associateBy { it.dateEpochDay }
        val pairs = history
            .filter { it.hadWorkout && it.strain != null }
            .mapNotNull { d ->
                byDay[d.dateEpochDay - 1]?.sleepMinutes?.let { prevSleep ->
                    prevSleep.toDouble() to d.strain!!.toDouble()
                }
            }
        if (pairs.size < MIN_OBSERVATIONS_PER_GROUP * 2) return null

        val threshold = RobustStats.median(pairs.map { it.first }) ?: return null
        val wellSlept = pairs.filter { it.first >= threshold }.map { it.second }
        val short = pairs.filter { it.first < threshold }.map { it.second }
        if (wellSlept.size < MIN_OBSERVATIONS_PER_GROUP || short.size < MIN_OBSERVATIONS_PER_GROUP) return null

        val t = RobustStats.welchTTest(wellSlept, short) ?: return null
        // Materiality: at least 1.5 strain points, or it is not a session the user would notice.
        if (abs(t.difference) < 1.5) return null

        val better = t.difference > 0
        return Insight(
            id = "sleep_threshold_strain",
            title = "Your pattern: sleep and session quality",
            body = "On days following ${fmtMinutes(threshold)}+ of sleep, your sessions reach an " +
                    "average strain of ${fmt1(t.meanA)} — versus ${fmt1(t.meanB)} after shorter " +
                    "nights. That is an association observed across ${pairs.size} of your own " +
                    "sessions, not a rule.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = pairs.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 3.0),
            category = Category.SLEEP
        ).takeIf { better }
    }

    /** "Readiness after long vs short nights." */
    private fun sleepVsReadinessPattern(history: List<DayRecord>): Insight? {
        val pairs = history.mapNotNull { d ->
            val s = d.sleepMinutes ?: return@mapNotNull null
            val r = d.readiness ?: return@mapNotNull null
            s.toDouble() to r.toDouble()
        }
        if (pairs.size < MIN_OBSERVATIONS_PER_GROUP * 2) return null

        val threshold = RobustStats.median(pairs.map { it.first }) ?: return null
        val long = pairs.filter { it.first >= threshold }.map { it.second }
        val shortN = pairs.filter { it.first < threshold }.map { it.second }
        if (long.size < MIN_OBSERVATIONS_PER_GROUP || shortN.size < MIN_OBSERVATIONS_PER_GROUP) return null

        val t = RobustStats.welchTTest(long, shortN) ?: return null
        if (abs(t.difference) < 5.0) return null   // materiality: 5 readiness points

        return Insight(
            id = "sleep_readiness",
            title = "Your pattern: sleep length and readiness",
            body = "Nights of ${fmtMinutes(threshold)} or more are followed by an average readiness " +
                    "of ${t.meanA.roundToInt()}, compared with ${t.meanB.roundToInt()} after shorter " +
                    "nights — a difference of about ${abs(t.difference).roundToInt()} points across " +
                    "${pairs.size} days.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = pairs.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 10.0),
            category = Category.SLEEP
        )
    }

    /**
     * "Readiness tends to fall after N consecutive high-load days. Average drop: X points."
     */
    private fun consecutiveHighLoadPattern(history: List<DayRecord>): Insight? {
        val byDay = history.associateBy { it.dateEpochDay }
        val afterStreak = mutableListOf<Double>()
        val afterRested = mutableListOf<Double>()

        for (d in history) {
            val r = d.readiness?.toDouble() ?: continue
            val prior3 = (1..3).map { byDay[d.dateEpochDay - it] }
            if (prior3.any { it == null }) continue
            if (prior3.all { it!!.hadHighLoad }) afterStreak += r
            else if (prior3.none { it!!.hadHighLoad }) afterRested += r
        }
        if (afterStreak.size < MIN_OBSERVATIONS_PER_GROUP ||
            afterRested.size < MIN_OBSERVATIONS_PER_GROUP
        ) return null

        val t = RobustStats.welchTTest(afterStreak, afterRested) ?: return null
        if (t.difference >= -4.0) return null   // only report a genuine drop, min 4 points

        return Insight(
            id = "consecutive_high_load",
            title = "Your pattern: back-to-back hard days",
            body = "After three consecutive high-load days, your readiness averages " +
                    "${t.meanA.roundToInt()} — about ${abs(t.difference).roundToInt()} points below " +
                    "the ${t.meanB.roundToInt()} you average after three easier days. Seen across " +
                    "${afterStreak.size + afterRested.size} days of your history.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = afterStreak.size + afterRested.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 10.0),
            category = Category.TRAINING
        )
    }

    /** "Readiness the day after a rest day." */
    private fun restDayPattern(history: List<DayRecord>): Insight? {
        val byDay = history.associateBy { it.dateEpochDay }
        val afterRest = mutableListOf<Double>()
        val afterTraining = mutableListOf<Double>()

        for (d in history) {
            val r = d.readiness?.toDouble() ?: continue
            val prev = byDay[d.dateEpochDay - 1] ?: continue
            if (prev.isRestDay || !prev.hadWorkout) afterRest += r else afterTraining += r
        }
        if (afterRest.size < MIN_OBSERVATIONS_PER_GROUP ||
            afterTraining.size < MIN_OBSERVATIONS_PER_GROUP
        ) return null

        val t = RobustStats.welchTTest(afterRest, afterTraining) ?: return null
        if (t.difference < 4.0) return null

        return Insight(
            id = "rest_day_bounce",
            title = "Your pattern: what a rest day buys you",
            body = "The day after a rest day your readiness averages ${t.meanA.roundToInt()}, " +
                    "about ${abs(t.difference).roundToInt()} points above the " +
                    "${t.meanB.roundToInt()} that follows a training day. Observed over " +
                    "${afterRest.size + afterTraining.size} days.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = afterRest.size + afterTraining.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 10.0),
            category = Category.RECOVERY
        )
    }

    /** "Readiness on days following a consistent vs drifting bedtime." */
    private fun bedtimeConsistencyPattern(history: List<DayRecord>): Insight? {
        val withBedtime = history.filter { it.bedtimeMinuteOfDay != null && it.readiness != null }
        if (withBedtime.size < MIN_OBSERVATIONS_PER_GROUP * 2) return null

        val typical = BaselineUtils.circularMeanMinutes(
            withBedtime.mapNotNull { it.bedtimeMinuteOfDay }
        ) ?: return null

        // Deviation measured the short way round the clock, so a 00:20 bedtime against a
        // 23:40 norm reads as 40 minutes, not 23 hours.
        fun drift(d: DayRecord): Int {
            val b = d.bedtimeMinuteOfDay!!
            var delta = (b - typical) % 1440
            if (delta > 720) delta -= 1440
            if (delta < -720) delta += 1440
            return abs(delta)
        }

        val onSchedule = withBedtime.filter { drift(it) <= 45 }.mapNotNull { it.readiness?.toDouble() }
        val drifting = withBedtime.filter { drift(it) > 45 }.mapNotNull { it.readiness?.toDouble() }
        if (onSchedule.size < MIN_OBSERVATIONS_PER_GROUP || drifting.size < MIN_OBSERVATIONS_PER_GROUP) return null

        val t = RobustStats.welchTTest(onSchedule, drifting) ?: return null
        if (t.difference < 4.0) return null

        return Insight(
            id = "bedtime_consistency",
            title = "Your pattern: bedtime regularity",
            body = "When you go to bed within 45 minutes of your usual ${fmtClock(typical)}, " +
                    "readiness averages ${t.meanA.roundToInt()} — versus ${t.meanB.roundToInt()} " +
                    "on nights you drift further. Across ${onSchedule.size + drifting.size} nights.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = onSchedule.size + drifting.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 10.0),
            category = Category.CONSISTENCY
        )
    }

    /** "Resting HR on mornings after short vs adequate sleep." */
    private fun restingHRSleepPattern(history: List<DayRecord>): Insight? {
        val pairs = history.mapNotNull { d ->
            val s = d.sleepMinutes ?: return@mapNotNull null
            val hr = d.restingHR ?: return@mapNotNull null
            s.toDouble() to hr.toDouble()
        }
        if (pairs.size < MIN_OBSERVATIONS_PER_GROUP * 2) return null

        val threshold = RobustStats.median(pairs.map { it.first }) ?: return null
        val shortN = pairs.filter { it.first < threshold }.map { it.second }
        val longN = pairs.filter { it.first >= threshold }.map { it.second }
        if (shortN.size < MIN_OBSERVATIONS_PER_GROUP || longN.size < MIN_OBSERVATIONS_PER_GROUP) return null

        val t = RobustStats.welchTTest(shortN, longN) ?: return null
        if (t.difference < 2.0) return null   // materiality: 2 bpm

        return Insight(
            id = "rhr_after_short_sleep",
            title = "Your pattern: short nights and resting heart rate",
            body = "After nights under ${fmtMinutes(threshold)}, your resting heart rate averages " +
                    "${t.meanA.roundToInt()} bpm — about ${abs(t.difference).roundToInt()} bpm above " +
                    "the ${t.meanB.roundToInt()} bpm seen after longer nights. An association across " +
                    "${pairs.size} days, worth monitoring rather than diagnosing.",
            effectSize = t.difference,
            pValue = t.pValue,
            observations = pairs.size,
            strength = strengthOf(t.pValue, abs(t.difference) / 5.0),
            category = Category.RECOVERY
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Strength combines statistical confidence with practical size — both must be there. */
    private fun strengthOf(p: Double, normalisedEffect: Double): Strength = when {
        p < 0.005 && normalisedEffect >= 1.0 -> Strength.STRONG
        p < 0.02 && normalisedEffect >= 0.5 -> Strength.CLEAR
        else -> Strength.SUGGESTIVE
    }

    private fun fmtMinutes(minutes: Double): String {
        val m = minutes.roundToInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun fmtClock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        val h = m / 60
        val mm = m % 60
        return "${if (h < 10) "0$h" else "$h"}:${if (mm < 10) "0$mm" else "$mm"}"
    }

    private fun fmt1(v: Double): String = "%.1f".format(v)
}
