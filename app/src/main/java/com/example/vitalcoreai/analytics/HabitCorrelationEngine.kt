package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY

/**
 * Part 13 — Habit Correlation Engine
 *
 * Correlates user-logged habits (caffeine, alcohol, meditation, etc.) with
 * health outcomes (sleep score, recovery score, energy level).
 *
 * IMPORTANT: Language uses "associated with" / "correlated with" — NEVER "caused."
 * The engine requires a MINIMUM of 14 observations before surfacing any insight,
 * and always reports sample size + confidence alongside the correlation.
 *
 * Correlation method: Pearson r on daily averages.
 * Not a causal model — an observational pattern detector.
 */
object HabitCorrelationEngine {

    /** Minimum observations before we surface an insight. */
    private const val MIN_OBSERVATIONS = 14

    /** Below this |r|, we consider the correlation too weak to surface. */
    private const val MIN_ABS_CORRELATION = 0.20f

    /** A habit definition with display metadata. */
    data class HabitDefinition(
        val id: String,
        val displayName: String,
        val icon: String,
        val unit: String,
        val category: String  // "SUBSTANCE" | "ACTIVITY" | "LIFESTYLE" | "CUSTOM"
    )

    /** All built-in habits. Users can also create custom ones. */
    val BUILT_IN_HABITS = listOf(
        HabitDefinition("CAFFEINE",   "Caffeine",        "☕", "cups",    "SUBSTANCE"),
        HabitDefinition("ALCOHOL",    "Alcohol",         "🍷", "drinks",  "SUBSTANCE"),
        HabitDefinition("MEDITATION", "Meditation",      "🧘", "minutes", "ACTIVITY"),
        HabitDefinition("SCREEN_TIME","Screen Time (PM)","📱", "minutes", "LIFESTYLE"),
        HabitDefinition("WATER",      "Water",           "💧", "glasses", "LIFESTYLE"),
        HabitDefinition("STRETCHING", "Stretching",      "🤸", "minutes", "ACTIVITY"),
        HabitDefinition("COLD_SHOWER","Cold Exposure",   "🧊", "minutes", "LIFESTYLE"),
        HabitDefinition("SUPPLEMENTS","Supplements",     "💊", "taken",   "SUBSTANCE"),
        HabitDefinition("JOURNALING", "Journaling",      "📝", "minutes", "ACTIVITY"),
        HabitDefinition("READING",    "Reading",         "📖", "minutes", "ACTIVITY"),
        HabitDefinition("NAPPING",    "Nap",             "💤", "minutes", "ACTIVITY")
    )

    fun getHabitDefinition(id: String): HabitDefinition? =
        BUILT_IN_HABITS.find { it.id == id }

    /** Result of a single habit-outcome correlation analysis. */
    data class CorrelationResult(
        val habitId: String,
        val habitName: String,
        val outcomeName: String,       // e.g. "Sleep Score"
        val correlation: Float,        // Pearson r, -1 to 1
        val sampleSize: Int,
        val confidence: Confidence,
        val direction: String,         // "positive" | "negative"
        val strength: String,          // "weak" | "moderate" | "strong"
        val summary: String            // Human-readable insight
    )

    /**
     * Compute correlations between a habit and a set of daily outcome values.
     *
     * @param habitValues  map of epochDay → habit quantity (e.g. cups of coffee)
     * @param outcomeValues map of epochDay → outcome score (e.g. sleep score 0-100)
     * @param habitName    display name for the habit
     * @param outcomeName  display name for the outcome (e.g. "Sleep Score")
     */
    fun correlate(
        habitValues: Map<Long, Float>,
        outcomeValues: Map<Long, Float>,
        habitName: String,
        habitId: String,
        outcomeName: String
    ): CorrelationResult? {
        // Find overlapping days
        val commonDays = habitValues.keys.intersect(outcomeValues.keys).sorted()
        if (commonDays.size < MIN_OBSERVATIONS) return null

        val x = commonDays.map { habitValues[it]!! }
        val y = commonDays.map { outcomeValues[it]!! }

        val r = pearsonR(x, y) ?: return null
        if (kotlin.math.abs(r) < MIN_ABS_CORRELATION) return null

        val direction = if (r > 0) "positive" else "negative"
        val strength = when {
            kotlin.math.abs(r) >= 0.5f -> "strong"
            kotlin.math.abs(r) >= 0.3f -> "moderate"
            else -> "weak"
        }
        val confidence = when {
            commonDays.size >= 60 && kotlin.math.abs(r) >= 0.3f -> Confidence.HIGH
            commonDays.size >= 30 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val verb = if (r > 0) "positively associated with" else "negatively associated with"
        val summary = "$habitName is $verb $outcomeName ($strength correlation, " +
                "r=${String.format("%.2f", r)}, n=${commonDays.size}). " +
                "This is an observational pattern — not a proven cause."

        return CorrelationResult(
            habitId = habitId,
            habitName = habitName,
            outcomeName = outcomeName,
            correlation = r,
            sampleSize = commonDays.size,
            confidence = confidence,
            direction = direction,
            strength = strength,
            summary = summary
        )
    }

    /**
     * Batch-correlate all habits against multiple outcomes.
     * Returns only statistically meaningful results.
     */
    fun batchCorrelate(
        habitsData: Map<String, Map<Long, Float>>,  // habitId → (epochDay → value)
        outcomes: Map<String, Map<Long, Float>>     // outcomeName → (epochDay → score)
    ): List<CorrelationResult> {
        val results = mutableListOf<CorrelationResult>()
        for ((habitId, habitDays) in habitsData) {
            val habitDef = getHabitDefinition(habitId)
            val habitName = habitDef?.displayName ?: habitId

            for ((outcomeName, outcomeDays) in outcomes) {
                correlate(habitDays, outcomeDays, habitName, habitId, outcomeName)?.let {
                    results += it
                }
            }
        }
        // Sort by absolute correlation strength (strongest first)
        return results.sortedByDescending { kotlin.math.abs(it.correlation) }
    }

    // ── Pearson correlation ─────────────────────────────────────────────────

    private fun pearsonR(x: List<Float>, y: List<Float>): Float? {
        if (x.size != y.size || x.size < 2) return null
        val n = x.size.toDouble()
        val xMean = x.average()
        val yMean = y.average()
        var numerator = 0.0
        var xVar = 0.0
        var yVar = 0.0
        for (i in x.indices) {
            val dx = x[i] - xMean
            val dy = y[i] - yMean
            numerator += dx * dy
            xVar += dx * dx
            yVar += dy * dy
        }
        val denominator = kotlin.math.sqrt(xVar * yVar)
        return if (denominator < 0.001) null else (numerator / denominator).toFloat()
    }
}
