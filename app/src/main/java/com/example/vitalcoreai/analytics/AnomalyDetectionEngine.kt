package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Priority 5 — Personal Anomaly Detection.
 *
 * Flags values that are unusual **for this user**, against their own robust baseline —
 * never against a population norm, and never as a diagnosis.
 *
 * ## Language contract
 *
 * This engine is the highest medical-safety risk surface in the app, because "your
 * resting heart rate has been elevated for three days" is one careless sentence away from
 * "you are ill". Every string produced here is drawn from a fixed vocabulary:
 *
 * - permitted: *unusual*, *above/below your normal range*, *pattern detected*,
 *   *worth monitoring*, *may reflect*, *consider*
 * - forbidden: any illness, infection, diagnosis, overtraining *syndrome*, cardiac claim,
 *   or any phrasing implying certainty about cause
 *
 * [describeCoincidence] deliberately reports *co-occurrence* only. It never asserts that
 * one signal caused another.
 *
 * ## Why two detection modes
 *
 * A single day 2.5 sigma out is usually a measurement artefact — a bad night's sensor
 * contact, a late meal, one glass of wine. A 1.5 sigma deviation sustained for three days
 * is a pattern. Reporting only the first produces false alarms; reporting only the second
 * misses genuinely large single-day events. So both are detected, and the sustained kind
 * is ranked higher because it is far more likely to be real.
 */
object AnomalyDetectionEngine {

    /** How strongly the engine is willing to speak. */
    enum class Severity(val label: String, val rank: Int) {
        NOTABLE("Worth noting", 1),
        UNUSUAL("Unusual", 2),
        STRONGLY_UNUSUAL("Well outside your normal range", 3)
    }

    enum class Kind {
        /** One day far from baseline. */
        SPIKE,

        /** A smaller deviation held for several consecutive days. */
        SUSTAINED
    }

    /**
     * @param consecutiveDays for [Kind.SUSTAINED], how many days running the deviation held
     * @param coincidingWith  other signals moving at the same time — co-occurrence only
     * @param suggestion      a behavioural suggestion, never a medical instruction
     */
    data class Anomaly(
        val metric: PersonalBaselines.TrackedMetric,
        val kind: Kind,
        val severity: Severity,
        val title: String,
        val body: String,
        val currentValue: Double,
        val baselineValue: Double,
        val deviation: Double,
        val sigma: Double,
        val consecutiveDays: Int,
        val coincidingWith: List<String>,
        val suggestion: String?,
        val confidence: Confidence
    ) {
        /** Ranking key: sustained beats spike, then severity, then magnitude. */
        val priority: Double
            get() = severity.rank * 100.0 +
                    (if (kind == Kind.SUSTAINED) 50.0 else 0.0) +
                    abs(sigma).coerceAtMost(6.0)
    }

    /** One day of every metric the engine watches. */
    data class DaySample(
        val dateEpochDay: Long,
        val restingHR: Double? = null,
        val sleepMinutes: Double? = null,
        val sleepScore: Double? = null,
        val bedtimeMinuteOfDay: Int? = null,
        val strain: Double? = null,
        val hrr1: Double? = null,
        val steps: Double? = null,
        val recovery: Double? = null,
        val readiness: Double? = null,
        val energyBank: Double? = null,
        val checkInEnergy: Double? = null,
        val checkInStress: Double? = null,
        val checkInSoreness: Double? = null
    )

    // ── Thresholds ───────────────────────────────────────────────────────────

    /** Minimum baseline observations before anything is called unusual. */
    const val MIN_HISTORY = 7

    private const val SIGMA_SPIKE = 2.5
    private const val SIGMA_SUSTAINED = 1.5
    private const val SIGMA_STRONG = 3.0
    private const val MIN_SUSTAINED_DAYS = 3

    /** Never show more than this many at once — an alert list is not an insight. */
    private const val MAX_REPORTED = 4

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * @param history oldest first, **excluding** [today]. Needs at least [MIN_HISTORY]
     *                entries per metric for that metric to be evaluated.
     * @param today   the day under test.
     */
    fun detect(history: List<DaySample>, today: DaySample): List<Anomaly> {
        val ordered = history.sortedBy { it.dateEpochDay }
        val found = mutableListOf<Anomaly>()

        for (spec in WATCHED) {
            val series = ordered.mapNotNull(spec.select)
            val current = spec.select(today) ?: continue
            if (series.size < MIN_HISTORY) continue

            // The baseline excludes outliers; the value under test never is filtered,
            // or the detector would delete exactly what it is looking for.
            val clean = RobustStats.withoutOutliers(series)
            val baseline = RobustStats.median(clean) ?: continue
            val sigma = RobustStats.robustZ(current, clean, spec.metric.minSigma) ?: continue

            // Only adverse deviations are surfaced. A resting HR well *below* baseline, or
            // sleep well *above* it, is not something to warn anyone about.
            val adverse = if (spec.metric.higherIsBetter) sigma < 0 else sigma > 0
            if (!adverse) continue

            val magnitude = abs(sigma)
            val streak = consecutiveAdverseDays(ordered, today, spec, clean, baseline)

            val anomaly = when {
                streak >= MIN_SUSTAINED_DAYS && magnitude >= SIGMA_SUSTAINED ->
                    build(spec, Kind.SUSTAINED, current, baseline, sigma, streak, ordered, today, series.size)

                magnitude >= SIGMA_SPIKE ->
                    build(spec, Kind.SPIKE, current, baseline, sigma, 1, ordered, today, series.size)

                else -> null
            }
            if (anomaly != null) found += anomaly
        }

        return found.sortedByDescending { it.priority }.take(MAX_REPORTED)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** A metric the engine watches, plus how to pull it out of a [DaySample]. */
    private data class Spec(
        val metric: PersonalBaselines.TrackedMetric,
        val select: (DaySample) -> Double?,
        val unit: String,
        val decimals: Int = 0
    )

    private val WATCHED: List<Spec> = listOf(
        Spec(PersonalBaselines.TrackedMetric.RESTING_HR, { it.restingHR }, "BPM"),
        Spec(PersonalBaselines.TrackedMetric.SLEEP_DURATION, { it.sleepMinutes }, "min"),
        Spec(PersonalBaselines.TrackedMetric.SLEEP_SCORE, { it.sleepScore }, "/100"),
        Spec(PersonalBaselines.TrackedMetric.STRAIN, { it.strain }, "", decimals = 1),
        Spec(PersonalBaselines.TrackedMetric.HRR1, { it.hrr1 }, "BPM"),
        Spec(PersonalBaselines.TrackedMetric.STEPS, { it.steps }, "steps"),
        Spec(PersonalBaselines.TrackedMetric.RECOVERY, { it.recovery }, "/100"),
        Spec(PersonalBaselines.TrackedMetric.ENERGY_BANK, { it.energyBank }, "/100"),
        Spec(PersonalBaselines.TrackedMetric.CHECKIN_ENERGY, { it.checkInEnergy }, "/10"),
        Spec(PersonalBaselines.TrackedMetric.CHECKIN_STRESS, { it.checkInStress }, "/10"),
        Spec(PersonalBaselines.TrackedMetric.CHECKIN_SORENESS, { it.checkInSoreness }, "/10")
    )

    /**
     * How many consecutive days up to and including today have been adversely deviant.
     *
     * Walks backwards day by day and stops at the first day that is either within range or
     * missing. A missing day breaks the streak rather than being skipped over — "elevated
     * for 3 days running" must mean three actual measurements, not three calendar days
     * containing one.
     */
    private fun consecutiveAdverseDays(
        ordered: List<DaySample>,
        today: DaySample,
        spec: Spec,
        cleanBaselineSeries: List<Double>,
        baseline: Double
    ): Int {
        val byDay = ordered.associateBy { it.dateEpochDay }
        var streak = 0
        var day = today.dateEpochDay
        var sample: DaySample? = today

        while (sample != null) {
            val v = spec.select(sample) ?: break
            val z = RobustStats.robustZ(v, cleanBaselineSeries, spec.metric.minSigma) ?: break
            val adverse = if (spec.metric.higherIsBetter) z < 0 else z > 0
            if (!adverse || abs(z) < SIGMA_SUSTAINED) break
            streak++
            day -= 1
            sample = byDay[day]
        }
        return streak
    }

    private fun build(
        spec: Spec,
        kind: Kind,
        current: Double,
        baseline: Double,
        sigma: Double,
        streak: Int,
        history: List<DaySample>,
        today: DaySample,
        historyDepth: Int
    ): Anomaly {
        val magnitude = abs(sigma)
        val severity = when {
            magnitude >= SIGMA_STRONG -> Severity.STRONGLY_UNUSUAL
            magnitude >= SIGMA_SPIKE -> Severity.UNUSUAL
            else -> Severity.NOTABLE
        }
        val deviation = current - baseline
        val coinciding = describeCoincidence(spec, history, today)

        val name = spec.metric.displayName
        val dirWord = if (deviation > 0) "above" else "below"

        val title = when (kind) {
            Kind.SUSTAINED -> "$name $dirWord your normal range for $streak days"
            Kind.SPIKE -> "Unusual $name today"
        }

        val body = buildString {
            append("Your ${name.lowercase()} ")
            append(if (kind == Kind.SUSTAINED) "has been " else "is ")
            append("${fmt(current, spec)} ")
            append("versus a typical ${fmt(baseline, spec)} — ")
            append("${fmtDelta(deviation, spec)} $dirWord your usual")
            if (kind == Kind.SUSTAINED) append(", for $streak consecutive days")
            append(". ")
            if (coinciding.isNotEmpty()) {
                append("This coincides with: ")
                append(coinciding.joinToString("; "))
                append(". ")
            }
            append("This is a pattern worth monitoring, not a diagnosis.")
        }

        return Anomaly(
            metric = spec.metric,
            kind = kind,
            severity = severity,
            title = title,
            body = body,
            currentValue = current,
            baselineValue = baseline,
            deviation = deviation,
            sigma = sigma,
            consecutiveDays = streak,
            coincidingWith = coinciding,
            suggestion = suggestionFor(spec.metric, kind),
            confidence = PersonalBaselines.confidenceFor(historyDepth)
        )
    }

    /**
     * Other signals that moved at the same time.
     *
     * Strictly co-occurrence. The wording is chosen so that no sentence can be read as a
     * causal claim: "this coincides with reduced sleep" is a statement about timing, while
     * "this is caused by reduced sleep" would be a claim this app has no basis to make.
     */
    private fun describeCoincidence(
        spec: Spec,
        history: List<DaySample>,
        today: DaySample
    ): List<String> {
        val recent = history.takeLast(7)
        if (recent.size < 4) return emptyList()
        val out = mutableListOf<String>()

        fun compare(
            label: String,
            sel: (DaySample) -> Double?,
            lowerIsNotable: Boolean,
            minDelta: Double,
            fmt: (Double) -> String
        ) {
            val past = recent.dropLast(2).mapNotNull(sel)
            val now = recent.takeLast(2).mapNotNull(sel) + listOfNotNull(sel(today))
            if (past.size < 3 || now.isEmpty()) return
            val base = RobustStats.median(past) ?: return
            val cur = now.average()
            val d = cur - base
            if (lowerIsNotable && d <= -minDelta) out += "$label down ${fmt(abs(d))}"
            if (!lowerIsNotable && d >= minDelta) out += "$label up ${fmt(abs(d))}"
        }

        // Never cite the metric against itself.
        if (spec.metric != PersonalBaselines.TrackedMetric.SLEEP_DURATION) {
            compare("sleep", { it.sleepMinutes }, lowerIsNotable = true, minDelta = 30.0) {
                "${it.roundToInt()} min"
            }
        }
        if (spec.metric != PersonalBaselines.TrackedMetric.STRAIN) {
            compare("training load", { it.strain }, lowerIsNotable = false, minDelta = 2.0) {
                "%.1f".format(it)
            }
        }
        if (spec.metric != PersonalBaselines.TrackedMetric.CHECKIN_STRESS) {
            compare("reported stress", { it.checkInStress }, lowerIsNotable = false, minDelta = 2.0) {
                "${it.roundToInt()} points"
            }
        }
        if (spec.metric != PersonalBaselines.TrackedMetric.CHECKIN_SORENESS) {
            compare("reported soreness", { it.checkInSoreness }, lowerIsNotable = false, minDelta = 2.0) {
                "${it.roundToInt()} points"
            }
        }
        return out.take(3)
    }

    /**
     * A behavioural suggestion. Never an instruction, never medical.
     * Null where no honest suggestion exists — silence beats filler.
     */
    private fun suggestionFor(metric: PersonalBaselines.TrackedMetric, kind: Kind): String? =
        when (metric) {
            PersonalBaselines.TrackedMetric.RESTING_HR ->
                "Consider reducing training intensity and prioritising sleep while this persists."

            PersonalBaselines.TrackedMetric.SLEEP_DURATION,
            PersonalBaselines.TrackedMetric.SLEEP_SCORE ->
                "An earlier bedtime tonight is the highest-impact change available."

            PersonalBaselines.TrackedMetric.STRAIN ->
                "Your load is well above your usual. A lighter day would let it settle."

            PersonalBaselines.TrackedMetric.HRR1 ->
                "Heart-rate recovery below your usual often follows accumulated fatigue. " +
                        "Consider an easier session."

            PersonalBaselines.TrackedMetric.CHECKIN_SORENESS ->
                "Consider training around the sore areas today rather than through them."

            PersonalBaselines.TrackedMetric.CHECKIN_STRESS ->
                "Low-intensity movement and an earlier night tend to help more than a hard session."

            PersonalBaselines.TrackedMetric.ENERGY_BANK,
            PersonalBaselines.TrackedMetric.RECOVERY ->
                "Consider a recovery day and revisit tomorrow."

            else -> null
        }

    private fun fmt(v: Double, spec: Spec): String {
        val n = if (spec.decimals == 1) "%.1f".format(v) else v.roundToInt().toString()
        return if (spec.unit.isBlank()) n else "$n ${spec.unit}"
    }

    private fun fmtDelta(v: Double, spec: Spec): String {
        val a = abs(v)
        val n = if (spec.decimals == 1) "%.1f".format(a) else a.roundToInt().toString()
        return if (spec.unit.isBlank()) n else "$n ${spec.unit}"
    }
}
