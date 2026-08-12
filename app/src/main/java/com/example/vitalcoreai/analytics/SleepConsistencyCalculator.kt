package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Priority 10 — Sleep Consistency.
 *
 * Scores how *regular* sleep is, independently of how *much* of it there is. These are
 * genuinely different things: a shift worker averaging 8 hours can be circadian-disrupted,
 * and a consistent 6.5-hour sleeper is short but stable. [SleepScoreCalculator] already
 * covers duration and quality; this covers regularity, and nothing else did.
 *
 * ## What is measured
 *
 * | Component | Weight | Why |
 * |---|---|---|
 * | Wake-time variance | 40% | The strongest zeitgeber. Wake time anchors the circadian phase far more tightly than bedtime, and it is the more controllable of the two. |
 * | Bedtime variance | 35% | Drives sleep-onset timing and total opportunity. |
 * | Duration variance | 25% | Catches the "5h weeknights, 10h weekends" pattern that leaves both timings looking acceptable on average. |
 *
 * ## Circular statistics
 *
 * Clock times are angles, not numbers. 23:50 and 00:10 are 20 minutes apart, but plain
 * arithmetic makes them 1420 minutes apart — which would hand the most consistent
 * sleepers in the app the worst possible score, purely for sleeping near midnight. All
 * timing statistics here go through [BaselineUtils.circularStdDevMinutes] and
 * [BaselineUtils.circularMeanMinutes].
 *
 * ## Honesty
 *
 * This is a behavioural regularity measure derived from timestamps. It is not a circadian
 * phase measurement and must never be described as one.
 *
 * References:
 * - Phillips AJK et al. (2017) Sci Rep 7:3216 (sleep regularity and outcomes)
 * - Mardia KV & Jupp PE (2000) Directional Statistics. Wiley.
 */
object SleepConsistencyCalculator {

    /** One night's timing. Nulls are tolerated and simply reduce the sample. */
    data class NightTiming(
        val dateEpochDay: Long,
        val bedtimeMinuteOfDay: Int?,
        val wakeTimeMinuteOfDay: Int?,
        val durationMinutes: Int?
    )

    /**
     * @param score            0–100; null when there is not enough history to mean anything
     * @param bedtimeSdMinutes circular SD of bedtime
     * @param wakeSdMinutes    circular SD of wake time
     * @param durationSdMinutes plain SD of duration
     * @param typicalBedtime   circular mean bedtime, minutes past midnight
     * @param typicalWakeTime  circular mean wake time, minutes past midnight
     * @param nightsCounted    nights that contributed at least one component
     */
    data class ConsistencyResult(
        val score: Float?,
        val confidence: Confidence,
        val bedtimeSdMinutes: Int?,
        val wakeSdMinutes: Int?,
        val durationSdMinutes: Int?,
        val typicalBedtime: Int?,
        val typicalWakeTime: Int?,
        val nightsCounted: Int,
        val explanation: String,
        val breakdown: List<ScoreFactor>,
        val insufficientData: Boolean
    ) {
        /** "Highly consistent" … "Very irregular". Paired with the number, never colour alone. */
        val label: String
            get() = when {
                score == null -> "Not enough data"
                score >= 85f -> "Highly consistent"
                score >= 70f -> "Consistent"
                score >= 50f -> "Somewhat irregular"
                score >= 30f -> "Irregular"
                else -> "Very irregular"
            }
    }

    // ── Tuning ───────────────────────────────────────────────────────────────

    /**
     * Minimum nights before a consistency score is produced.
     *
     * Five is the floor at which a standard deviation over clock times carries any signal.
     * Below it the result would be dominated by whichever two nights happened to record.
     */
    const val MIN_NIGHTS = 5
    private const val NIGHTS_FOR_MEDIUM = 7
    private const val NIGHTS_FOR_HIGH = 14

    /**
     * SD at which a component scores 50/100.
     *
     * An hour of night-to-night drift is the point at which the pattern stops being a
     * schedule; the exponential below is anchored there and decays smoothly either side,
     * so there are no cliff edges where one minute changes the label.
     */
    private const val HALF_SCORE_SD_MINUTES = 60.0

    private const val W_WAKE = 0.40f
    private const val W_BEDTIME = 0.35f
    private const val W_DURATION = 0.25f

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * @param nights recent nights, any order; only the most recent [windowNights] are used.
     */
    fun calculate(nights: List<NightTiming>, windowNights: Int = 14): ConsistencyResult {
        val window = nights.sortedBy { it.dateEpochDay }.takeLast(windowNights)

        val bedtimes = window.mapNotNull { it.bedtimeMinuteOfDay }
        val wakeTimes = window.mapNotNull { it.wakeTimeMinuteOfDay }
        val durations = window.mapNotNull { it.durationMinutes?.toDouble() }

        val counted = window.count {
            it.bedtimeMinuteOfDay != null || it.wakeTimeMinuteOfDay != null || it.durationMinutes != null
        }

        if (counted < MIN_NIGHTS) {
            return ConsistencyResult(
                score = null,
                confidence = Confidence.LOW,
                bedtimeSdMinutes = null,
                wakeSdMinutes = null,
                durationSdMinutes = null,
                typicalBedtime = null,
                typicalWakeTime = null,
                nightsCounted = counted,
                explanation = "Sleep consistency needs at least $MIN_NIGHTS nights of " +
                        "bedtime and wake-time data. $counted recorded so far.",
                breakdown = emptyList(),
                insufficientData = true
            )
        }

        // Each component is scored only if it has enough nights of its own; the weights of
        // whichever components survive are renormalised, so a user whose device records
        // duration but not bedtime still gets an honest score rather than a depressed one.
        val bedtimeSd = if (bedtimes.size >= MIN_NIGHTS)
            BaselineUtils.circularStdDevMinutes(bedtimes) else null
        val wakeSd = if (wakeTimes.size >= MIN_NIGHTS)
            BaselineUtils.circularStdDevMinutes(wakeTimes) else null
        val durationSd = if (durations.size >= MIN_NIGHTS)
            BaselineUtils.stdDev(durations) else null

        val components = buildList {
            wakeSd?.let { add(Triple("Wake Time", W_WAKE, it)) }
            bedtimeSd?.let { add(Triple("Bedtime", W_BEDTIME, it)) }
            durationSd?.let { add(Triple("Duration", W_DURATION, it)) }
        }

        if (components.isEmpty()) {
            return ConsistencyResult(
                score = null,
                confidence = Confidence.LOW,
                bedtimeSdMinutes = null,
                wakeSdMinutes = null,
                durationSdMinutes = null,
                typicalBedtime = null,
                typicalWakeTime = null,
                nightsCounted = counted,
                explanation = "No bedtime, wake-time or duration data recorded in the last " +
                        "$windowNights nights.",
                breakdown = emptyList(),
                insufficientData = true
            )
        }

        val totalWeight = components.sumOf { it.second.toDouble() }.toFloat()
        val score = components.sumOf { (_, w, sd) ->
            (sdToScore(sd) * w).toDouble()
        }.toFloat() / totalWeight

        val typicalBedtime = if (bedtimes.size >= MIN_NIGHTS)
            BaselineUtils.circularMeanMinutes(bedtimes) else null
        val typicalWake = if (wakeTimes.size >= MIN_NIGHTS)
            BaselineUtils.circularMeanMinutes(wakeTimes) else null

        val breakdown = components
            .map { (name, w, sd) ->
                ScoreFactor(
                    name = name,
                    contribution = (w / totalWeight) * 100f,
                    rawValue = "±${formatSpread(sd)}",
                    score = sdToScore(sd),
                    description = describeComponent(name, sd)
                )
            }
            .sortedByDescending { abs(it.score - 50f) }

        val confidence = when {
            counted >= NIGHTS_FOR_HIGH -> Confidence.HIGH
            counted >= NIGHTS_FOR_MEDIUM -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return ConsistencyResult(
            score = score.coerceIn(0f, 100f),
            confidence = confidence,
            bedtimeSdMinutes = bedtimeSd?.roundToInt(),
            wakeSdMinutes = wakeSd?.roundToInt(),
            durationSdMinutes = durationSd?.roundToInt(),
            typicalBedtime = typicalBedtime,
            typicalWakeTime = typicalWake,
            nightsCounted = counted,
            explanation = buildExplanation(score, wakeSd, bedtimeSd, durationSd, counted),
            breakdown = breakdown,
            insufficientData = false
        )
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * SD in minutes → 0–100 sub-score, `100 · 2^(−sd / 60)`.
     *
     * Exponential rather than linear because the *ratio* of drift is what matters: going
     * from 15 to 30 minutes of drift is a far bigger behavioural change than going from
     * 120 to 135, and a linear map would score those identically.
     */
    fun sdToScore(sdMinutes: Double): Float {
        if (sdMinutes <= 0.0) return 100f
        val decay = exp(-kotlin.math.ln(2.0) * sdMinutes / HALF_SCORE_SD_MINUTES)
        return (100.0 * decay).toFloat().coerceIn(0f, 100f)
    }

    private fun formatSpread(sdMinutes: Double): String {
        val m = sdMinutes.roundToInt()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun describeComponent(name: String, sd: Double): String {
        val spread = formatSpread(sd)
        val quality = when {
            sd <= 20 -> "very consistent"
            sd <= 45 -> "consistent"
            sd <= 90 -> "moderately variable"
            else -> "highly variable"
        }
        return when (name) {
            "Wake Time" -> "Your wake time is $quality, varying by about $spread night to night."
            "Bedtime" -> "Your bedtime is $quality, varying by about $spread night to night."
            else -> "Your sleep duration is $quality, varying by about $spread night to night."
        }
    }

    private fun buildExplanation(
        score: Float,
        wakeSd: Double?,
        bedtimeSd: Double?,
        durationSd: Double?,
        nights: Int
    ): String = buildString {
        append("Sleep Consistency: ${score.roundToInt()}/100 over $nights nights. ")

        // Name the best and worst component so the number is actionable rather than a verdict.
        val scored = buildList {
            wakeSd?.let { add("wake time" to it) }
            bedtimeSd?.let { add("bedtime" to it) }
            durationSd?.let { add("sleep duration" to it) }
        }.sortedBy { it.second }

        scored.firstOrNull()?.let { (name, sd) ->
            append("Your $name is the most stable, varying by about ${formatSpread(sd)}. ")
        }
        if (scored.size > 1) {
            val (name, sd) = scored.last()
            if (sd > 45) {
                append("Your $name varies by about ${formatSpread(sd)} — the biggest source of irregularity. ")
            }
        }
        if (score < 60f) {
            append("Anchoring your wake time, even at weekends, is the single highest-impact change.")
        }
    }
}
