package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Priority 4 — Readiness Forecast.
 *
 * Estimates tomorrow's readiness as a **range**, from today's state plus the factors
 * known to move it overnight.
 *
 * ## This is a personal trend projection, not a prediction
 *
 * Nothing here is medical, and nothing here is certain. Two design decisions enforce that
 * rather than merely stating it in copy:
 *
 * 1. **The output is always an interval.** There is no code path that produces a single
 *    number. "Tomorrow: 78.4" would be a lie dressed as precision — the honest claim is
 *    "76–82, and here is what would move it".
 *
 * 2. **The interval is sized from the user's own volatility.** The half-width comes from
 *    the robust spread of this user's historical day-over-day readiness changes, widened
 *    when inputs are missing. A user whose readiness swings 20 points a day gets a wide
 *    band; a metronomic user gets a tight one. A fixed ±5 would flatter the first user and
 *    insult the second.
 *
 * ## Model
 *
 * ```
 * centre = today's readiness
 *        + sleep-opportunity effect
 *        + sleep-debt trajectory
 *        + today's training-load carryover
 *        + resting-HR reversion toward baseline
 *        + consecutive-training-day fatigue
 *        + subjective state (stress / soreness)
 *        + regression toward the personal mean
 * ```
 *
 * Each term is bounded, signed, and reported as a named driver, so the number is never a
 * black box. Coefficients are deliberately conservative: this projects a *direction and
 * rough magnitude*, and over-confident coefficients would turn ordinary noise into
 * spurious swings.
 */
object ReadinessForecastEngine {

    /**
     * @param plannedSleepMinutes  what the user intends to sleep tonight. Null → their own
     *                             recent typical sleep is assumed and the range widens.
     * @param plannedWorkoutLoad   an intended additional session today, 0–1 normalised.
     *                             Null → none planned.
     */
    data class ForecastInput(
        val todayReadiness: Float?,
        val readinessHistory: List<Float> = emptyList(),   // oldest first, excluding today
        val recentSleepMinutes: List<Int> = emptyList(),   // oldest first
        val plannedSleepMinutes: Int? = null,
        val personalSleepNeedMinutes: Int = 480,
        val sleepDebtMinutes: Int = 0,
        val todayStrain: Float? = null,                    // 0–21
        val strainHistory: List<Float> = emptyList(),
        val plannedWorkoutLoad: Float? = null,
        val restingHR: Int? = null,
        val restingHRBaseline: Double? = null,
        val restingHRHistory: List<Double> = emptyList(),
        val consecutiveTrainingDays: Int = 0,
        val checkInStress: Int? = null,                    // 1–10
        val checkInSoreness: Int? = null,                  // 1–10
        val dataQuality: DataQualityReport = DataQualityReport.UNKNOWN
    )

    /**
     * @param name      short driver label
     * @param points    signed effect on the centre, in readiness points
     * @param condition the "if" attached to it, when the driver is conditional on user action
     */
    data class Driver(
        val name: String,
        val points: Float,
        val description: String,
        val condition: String? = null
    ) {
        val isPositive: Boolean get() = points > 0
    }

    /**
     * @param low  lower bound, 0–100
     * @param high upper bound, 0–100
     */
    data class Forecast(
        val low: Int,
        val high: Int,
        val confidence: Confidence,
        val drivers: List<Driver>,
        val risks: List<String>,
        val opportunities: List<String>,
        val headline: String,
        val explanation: String,
        val available: Boolean
    ) {
        /** "76–82". The only rendering of the number the UI should ever use. */
        val range: String get() = if (available) "$low–$high" else "—"

        /** Midpoint, exposed only for charting a band. Never display this on its own. */
        val midpoint: Int get() = (low + high) / 2
    }

    // ── Bounds on each term, in readiness points ─────────────────────────────

    private const val MAX_SLEEP_EFFECT = 12f
    private const val MAX_DEBT_EFFECT = 8f
    private const val MAX_LOAD_EFFECT = 14f
    private const val MAX_RHR_EFFECT = 8f
    private const val MAX_CONSECUTIVE_EFFECT = 8f
    private const val MAX_SUBJECTIVE_EFFECT = 6f

    /** Floor and ceiling for the interval half-width, in points. */
    private const val MIN_HALF_WIDTH = 3f
    private const val MAX_HALF_WIDTH = 18f

    private const val MIN_HISTORY_FOR_VOLATILITY = 5

    // ── Public API ───────────────────────────────────────────────────────────

    fun forecast(input: ForecastInput): Forecast {
        val today = input.todayReadiness
            ?: return unavailable("Tomorrow's forecast needs today's readiness score first.")

        val drivers = mutableListOf<Driver>()
        val risks = mutableListOf<String>()
        val opportunities = mutableListOf<String>()

        // ── Sleep opportunity ────────────────────────────────────────────────
        val typicalSleep = input.recentSleepMinutes.takeIf { it.isNotEmpty() }
            ?.let { RobustStats.median(it.map(Int::toDouble)) }
        val assumedSleep = input.plannedSleepMinutes ?: typicalSleep?.roundToInt()

        if (assumedSleep != null) {
            val shortfall = input.personalSleepNeedMinutes - assumedSleep
            // ~1 readiness point per 12 minutes away from need, bounded.
            val effect = (-shortfall / 12f).coerceIn(-MAX_SLEEP_EFFECT, MAX_SLEEP_EFFECT)
            if (abs(effect) >= 1f) {
                drivers += Driver(
                    name = if (effect > 0) "Sleep opportunity" else "Short sleep",
                    points = effect,
                    description = if (effect > 0)
                        "Sleeping ${fmtMin(assumedSleep)} meets or beats your ${fmtMin(input.personalSleepNeedMinutes)} need."
                    else
                        "Sleeping ${fmtMin(assumedSleep)} leaves you ${fmtMin(shortfall)} short of your need.",
                    condition = if (input.plannedSleepMinutes != null) null
                    else "if you sleep your usual ${fmtMin(assumedSleep)}"
                )
            }
            if (shortfall > 30) {
                opportunities += "Sleeping ${fmtMin(input.personalSleepNeedMinutes)} tonight " +
                        "instead of your usual ${fmtMin(assumedSleep)} is the single biggest lever available."
            }
        }

        // ── Sleep debt trajectory ────────────────────────────────────────────
        if (input.sleepDebtMinutes > 30) {
            val effect = (-input.sleepDebtMinutes / 90f).coerceIn(-MAX_DEBT_EFFECT, 0f)
            drivers += Driver(
                name = "Accumulated sleep debt",
                points = effect,
                description = "${fmtMin(input.sleepDebtMinutes)} of accumulated debt keeps a ceiling on recovery."
            )
        } else if (input.sleepDebtMinutes <= 0) {
            drivers += Driver(
                name = "No sleep debt",
                points = 2f,
                description = "You are carrying no sleep debt into tomorrow."
            )
        }

        // ── Training-load carryover ──────────────────────────────────────────
        val typicalStrain = input.strainHistory.takeIf { it.size >= 3 }
            ?.let { RobustStats.median(it.map(Float::toDouble)) }
        val effectiveStrain = (input.todayStrain ?: 0f) +
                (input.plannedWorkoutLoad ?: 0f) * 8f   // a planned session, in strain units

        if (input.todayStrain != null || input.plannedWorkoutLoad != null) {
            val reference = (typicalStrain ?: 8.0).toFloat()
            val excess = effectiveStrain - reference
            // Above-usual load suppresses tomorrow; below-usual load frees it up, but with
            // a smaller coefficient — rest helps less than overload hurts.
            val effect = if (excess > 0) (-excess * 1.4f).coerceAtLeast(-MAX_LOAD_EFFECT)
            else (-excess * 0.7f).coerceAtMost(MAX_LOAD_EFFECT)
            if (abs(effect) >= 1f) {
                drivers += Driver(
                    name = if (effect < 0) "Training load elevated" else "Light training day",
                    points = effect,
                    description = if (effect < 0)
                        "Today's load of ${fmt1(effectiveStrain)} is above your typical ${fmt1(reference)}."
                    else
                        "Today's load of ${fmt1(effectiveStrain)} is below your typical ${fmt1(reference)}, leaving room to recover."
                )
            }
            if (input.plannedWorkoutLoad != null && input.plannedWorkoutLoad > 0.6f) {
                risks += "Another high-intensity session tonight would likely pull tomorrow's " +
                        "readiness toward the bottom of this range."
            }
        }

        // ── Resting-HR reversion ─────────────────────────────────────────────
        if (input.restingHR != null && input.restingHRBaseline != null) {
            val deviation = input.restingHR - input.restingHRBaseline
            val trend = if (input.restingHRHistory.size >= 5)
                RobustStats.trendOf(input.restingHRHistory, higherIsBetter = false) else null

            // An elevated RHR usually reverts, so an elevated reading today implies a small
            // improvement tomorrow — unless the trend says it is still climbing.
            val stillRising = trend?.direction == RobustStats.Trend.DECLINING
            val effect = when {
                deviation > 2 && !stillRising ->
                    (deviation * 0.5).toFloat().coerceAtMost(MAX_RHR_EFFECT)
                deviation > 2 && stillRising ->
                    (-deviation * 0.5).toFloat().coerceAtLeast(-MAX_RHR_EFFECT)
                else -> 0f
            }
            if (abs(effect) >= 1f) {
                drivers += Driver(
                    name = if (effect > 0) "Resting HR reverting to baseline" else "Resting HR still climbing",
                    points = effect,
                    description = if (effect > 0)
                        "Resting HR is ${deviation.roundToInt()} bpm above baseline and typically settles back."
                    else
                        "Resting HR is ${deviation.roundToInt()} bpm above baseline and still trending up."
                )
            }
            if (deviation > 5 && stillRising) {
                risks += "Resting heart rate has been climbing. If it stays elevated, tomorrow " +
                        "is more likely to land below this range."
            }
        }

        // ── Consecutive training days ────────────────────────────────────────
        if (input.consecutiveTrainingDays >= 3) {
            val effect = (-(input.consecutiveTrainingDays - 2) * 2f)
                .coerceAtLeast(-MAX_CONSECUTIVE_EFFECT)
            drivers += Driver(
                name = "Consecutive training days",
                points = effect,
                description = "${input.consecutiveTrainingDays} days in a row of training " +
                        "accumulates fatigue faster than a single session suggests."
            )
            if (input.consecutiveTrainingDays >= 4) {
                risks += "You are ${input.consecutiveTrainingDays} consecutive training days in. " +
                        "A rest day would move tomorrow toward the top of this range."
            }
        }

        // ── Subjective state ─────────────────────────────────────────────────
        val subjective = buildList {
            input.checkInStress?.let { if (it >= 7) add(-(it - 6) * 1.2f to "high reported stress") }
            input.checkInSoreness?.let { if (it >= 7) add(-(it - 6) * 1.2f to "high reported soreness") }
        }
        if (subjective.isNotEmpty()) {
            val total = subjective.sumOf { it.first.toDouble() }.toFloat()
                .coerceAtLeast(-MAX_SUBJECTIVE_EFFECT)
            drivers += Driver(
                name = "How you're feeling",
                points = total,
                description = "You reported ${subjective.joinToString(" and ") { it.second }} today."
            )
        }

        // ── Regression toward the personal mean ──────────────────────────────
        // Readiness is mean-reverting: an unusually high or low day is followed by a day
        // closer to the middle. Without this term the forecast simply echoes today, which
        // would be both less accurate and less useful.
        val personalMean = input.readinessHistory.takeIf { it.size >= MIN_HISTORY_FOR_VOLATILITY }
            ?.let { RobustStats.median(it.map(Float::toDouble)) }
        if (personalMean != null) {
            val pull = ((personalMean - today) * 0.25).toFloat().coerceIn(-8f, 8f)
            if (abs(pull) >= 1f) {
                drivers += Driver(
                    name = "Regression to your usual",
                    points = pull,
                    description = "Today (${today.roundToInt()}) is " +
                            "${if (today > personalMean) "above" else "below"} your typical " +
                            "${personalMean.roundToInt()}; days like this usually move back toward it."
                )
            }
        }

        // ── Compose ──────────────────────────────────────────────────────────
        val centre = (today + drivers.sumOf { it.points.toDouble() }.toFloat()).coerceIn(0f, 100f)
        val halfWidth = intervalHalfWidth(input)

        val low = (centre - halfWidth).roundToInt().coerceIn(0, 100)
        val high = (centre + halfWidth).roundToInt().coerceIn(0, 100)

        val confidence = confidenceFor(input, halfWidth)

        return Forecast(
            low = low,
            high = high,
            confidence = confidence,
            drivers = drivers.sortedByDescending { abs(it.points) },
            risks = risks,
            opportunities = opportunities,
            headline = "Tomorrow: $low–$high",
            explanation = buildExplanation(low, high, confidence, drivers, risks),
            available = true
        )
    }

    // ── Interval width ───────────────────────────────────────────────────────

    /**
     * Half-width of the interval, in readiness points.
     *
     * Base is the robust spread of this user's own day-over-day readiness changes — the
     * empirical answer to "how much does my readiness normally move overnight". Missing
     * inputs widen it, because a forecast built on fewer signals genuinely knows less.
     */
    internal fun intervalHalfWidth(input: ForecastInput): Float {
        val history = input.readinessHistory
        val base = if (history.size >= MIN_HISTORY_FOR_VOLATILITY) {
            val deltas = history.zipWithNext { a, b -> abs(b - a).toDouble() }
            val typical = RobustStats.median(deltas) ?: 6.0
            // Median absolute daily change ≈ 0.67 sigma for a normal; scale to roughly a
            // 1-sigma band so the interval covers about two thirds of outcomes.
            (typical * 1.5).toFloat()
        } else {
            9f  // No personal volatility yet — be visibly uncertain.
        }

        var width = base
        if (input.plannedSleepMinutes == null) width += 2f      // sleep assumed, not stated
        if (input.todayStrain == null) width += 2f              // load unknown
        if (input.restingHRBaseline == null) width += 1.5f      // no RHR baseline
        if (input.dataQuality.level == Confidence.LOW) width += 3f
        if (input.dataQuality.level == Confidence.MEDIUM) width += 1.5f
        if (history.size < MIN_HISTORY_FOR_VOLATILITY) width += 2f

        return width.coerceIn(MIN_HALF_WIDTH, MAX_HALF_WIDTH)
    }

    /**
     * Confidence is driven by how wide the honest interval had to be, plus how much
     * history backs it. A tight interval on three days of data is not high confidence.
     */
    private fun confidenceFor(input: ForecastInput, halfWidth: Float): Confidence {
        val history = input.readinessHistory.size
        return when {
            history >= 14 && halfWidth <= 6f && input.dataQuality.level == Confidence.HIGH ->
                Confidence.HIGH
            history >= 7 && halfWidth <= 10f -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    private fun unavailable(reason: String) = Forecast(
        low = 0, high = 0,
        confidence = Confidence.LOW,
        drivers = emptyList(),
        risks = emptyList(),
        opportunities = emptyList(),
        headline = "Tomorrow's forecast unavailable",
        explanation = reason,
        available = false
    )

    private fun buildExplanation(
        low: Int,
        high: Int,
        confidence: Confidence,
        drivers: List<Driver>,
        risks: List<String>
    ): String = buildString {
        append("Expected readiness tomorrow: $low–$high ")
        append("(${confidence.name.lowercase()} confidence). ")
        val positive = drivers.filter { it.isPositive }.take(2)
        val negative = drivers.filter { !it.isPositive }.take(2)
        if (positive.isNotEmpty()) {
            append("In your favour: ${positive.joinToString(", ") { it.name.lowercase() }}. ")
        }
        if (negative.isNotEmpty()) {
            append("Working against it: ${negative.joinToString(", ") { it.name.lowercase() }}. ")
        }
        if (risks.isNotEmpty()) append(risks.first())
        append(" This is a projection from your own recent patterns, not a prediction.")
    }

    private fun fmtMin(minutes: Int): String {
        val m = abs(minutes)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun fmt1(v: Float): String = "%.1f".format(v)
}
