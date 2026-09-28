package com.example.vitalcoreai.coach

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Answers the coach's five questions from a validated [CoachContext].
 *
 * ## Why this is deterministic
 *
 * Every sentence produced here is assembled from values the analytics engines already
 * computed. Nothing is estimated at this layer. That is the point of the architecture
 * rule in [CoachContext]: the coach's job is phrasing, and phrasing is something a rules
 * engine can do correctly and verifiably, whereas arithmetic performed by a language
 * model cannot be verified at all.
 *
 * When a remote model is wired in later, it replaces the *prose assembly* in this file
 * while consuming the identical [CoachContext]. The answers would get warmer; the numbers
 * would not change, because the model is forbidden from producing them.
 *
 * ## Honesty rules encoded here
 *
 * - An answer that has no data says so, and says which data is missing.
 * - Low-confidence values are labelled at the point of use, not in a footnote.
 * - Nothing forward-looking is stated as a single number.
 */
object CoachAnswerEngine {

    /**
     * @param citations the specific context fields this answer drew on, so the UI can show
     *                  "based on: readiness, sleep debt, resting HR" and the debug screen
     *                  can verify nothing was invented.
     */
    data class Answer(
        val intent: CoachIntent,
        val headline: String,
        val body: String,
        val citations: List<String>,
        val followUps: List<String>,
        val hasSufficientData: Boolean
    )

    fun answer(intent: CoachIntent, context: CoachContext): Answer {
        val answer = when (intent) {
            CoachIntent.WHY_AM_I_LOW -> whyAmILow(context)
            CoachIntent.WHAT_SHOULD_I_TRAIN -> whatShouldITrain(context)
            CoachIntent.WHY_AM_I_TIRED -> whyAmITired(context)
            CoachIntent.WHAT_TONIGHT -> whatTonight(context)
            CoachIntent.WEEKLY_REVIEW -> weeklyReview(context)
            CoachIntent.FREEFORM -> overview(context)
        }
        val stale = context.evidence?.staleMetrics.orEmpty()
        return answer.copy(
            body = if (stale.isEmpty()) answer.body else
                "Some readings could not be refreshed: ${stale.joinToString(", ")}. " +
                    "Treat this answer as provisional until Data Sources confirms a successful read. " + answer.body,
            citations = answer.citations.filter { citationAvailable(it, context) }
        )
    }

    private fun citationAvailable(citation: String, c: CoachContext): Boolean = when (citation.substringBefore('.')) {
        "readiness" -> c.readiness?.value != null
        "sleep" -> c.sleep != null
        "rhr" -> c.restingHR != null
        "training_load" -> c.trainingLoad != null
        "muscle_recovery" -> c.muscleRecovery != null
        "recommendation" -> c.recommendation != null
        "forecast" -> c.forecast != null
        "anomalies" -> c.anomalies.isNotEmpty()
        "trends" -> c.trends.isNotEmpty()
        "insights" -> c.insights.isNotEmpty()
        "journal" -> c.journal != null
        else -> true
    }

    // ── 1. Why am I low? ─────────────────────────────────────────────────────

    private fun whyAmILow(c: CoachContext): Answer {
        val readiness = c.readiness
        if (readiness?.value == null) {
            return noData(
                CoachIntent.WHY_AM_I_LOW,
                "I don't have a readiness score for today yet.",
                c
            )
        }

        val cited = mutableListOf("readiness")
        val v = readiness.value.roundToInt()

        // The breakdown is already ranked by impact; the drags are the sub-scores that
        // came in below neutral. This is why the answer can name a cause without guessing.
        val drags = readiness.breakdown.filter { it.subScore < 50f }.sortedBy { it.subScore }
        val supports = readiness.breakdown.filter { it.subScore >= 65f }

        val body = buildString {
            append("Your readiness is $v/100")
            if (readiness.confidence != "HIGH") {
                append(" (${readiness.confidence.lowercase()} confidence — ")
                append(c.confidence.missing.firstOrNull() ?: "some inputs are missing")
                append(")")
            }
            append(". ")

            if (readiness.breakdown.isEmpty()) {
                append(readiness.explanation ?: "The saved score has no factor breakdown yet.")
                append(" ")
            } else if (drags.isEmpty()) {
                append(if (v >= 65) "Your recorded readiness is not low. " else
                    "No recorded component is below 50/100; the score reflects their combination. ")
            } else {
                append("The biggest drag is ${drags.first().name.lowercase()} at ")
                append("${drags.first().subScore.roundToInt()}/100 — ${drags.first().description} ")
                cited += "readiness.breakdown"
                if (drags.size > 1) {
                    append("${drags[1].name} is also below par (${drags[1].subScore.roundToInt()}/100). ")
                }
            }

            c.restingHR?.let {
                if (it.isAboveBaseline()) {
                    append("Resting heart rate is ${it.deviation} against your ${it.baseline} baseline. ")
                    cited += "rhr"
                }
            }

            c.sleep?.let { s ->
                if (s.debtMinutes > 60) {
                    append("You are carrying ${fmtMin(s.debtMinutes)} of sleep debt. ")
                    cited += "sleep.debt"
                }
            }

            c.anomalies.firstOrNull()?.let {
                append("${it.title}. ")
                cited += "anomalies"
            }

            if (supports.isNotEmpty()) {
                append("Holding it up: ${supports.joinToString(", ") { f -> f.name.lowercase() }}.")
                }
        }

        return Answer(
            intent = CoachIntent.WHY_AM_I_LOW,
            headline = "Readiness $v/100",
            body = body.trim(),
            citations = cited.distinct(),
            followUps = listOf("What should I train today?", "What should I do tonight?"),
            hasSufficientData = true
        )
    }

    // ── 2. What should I train? ──────────────────────────────────────────────

    private fun whatShouldITrain(c: CoachContext): Answer {
        if (c.evidence?.staleMetrics?.isNotEmpty() == true) {
            return noData(
                CoachIntent.WHAT_SHOULD_I_TRAIN,
                "Choose rest or gentle movement while today's readings are incomplete. " +
                    "Refresh Data Sources before using a saved training recommendation.",
                c
            )
        }
        val rec = c.recommendation
            ?: return noData(
                CoachIntent.WHAT_SHOULD_I_TRAIN,
                "I need today's recovery data before I can suggest a session.",
                c
            )

        val body = buildString {
            if (rec.confidence != "HIGH") {
                append("Provisional suggestion (${rec.confidence.lowercase()} confidence). ")
            }
            append("${rec.type}, ${rec.intensity.lowercase()} intensity. ")
            when {
                rec.volumeAdjustmentPercent <= -30 ->
                    append("Keep volume to roughly ${100 + rec.volumeAdjustmentPercent}% of a normal session. ")
                rec.volumeAdjustmentPercent < 0 ->
                    append("Trim volume by about ${-rec.volumeAdjustmentPercent}%. ")
                rec.volumeAdjustmentPercent > 0 ->
                    append("There is room for about ${rec.volumeAdjustmentPercent}% more than usual. ")
                else -> append("Normal volume. ")
            }
            if (rec.readyRegions.isNotEmpty()) {
                append("${rec.readyRegions.joinToString(" and ")} ${
                    if (rec.readyRegions.size > 1) "are" else "is"
                } ready. ")
            }
            if (rec.avoidRegions.isNotEmpty()) {
                append("${rec.avoidRegions.joinToString(" and ")} still recovering — train around ")
                append(if (rec.avoidRegions.size > 1) "those" else "that")
                append(" today. ")
            }
            if (rec.rationale.isNotEmpty()) {
                append("Why: ${rec.rationale.joinToString("; ").lowercase()}.")
            }
        }

        return Answer(
            intent = CoachIntent.WHAT_SHOULD_I_TRAIN,
            headline = rec.type,
            body = body.trim(),
            citations = listOf("recommendation", "muscle_recovery", "readiness", "training_load"),
            followUps = listOf("Why is my readiness low today?", "What should I do tonight?"),
            hasSufficientData = true
        )
    }

    // ── 3. Why am I tired? ───────────────────────────────────────────────────

    private fun whyAmITired(c: CoachContext): Answer {
        val cited = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        c.sleep?.let { s ->
            s.durationMinutes?.let { d ->
                if (d < 400) {
                    reasons += "you slept ${fmtMin(d)} last night"
                    cited += "sleep.duration"
                }
            }
            if (s.debtMinutes > 90) {
                reasons += "you're carrying ${fmtMin(s.debtMinutes)} of accumulated sleep debt"
                cited += "sleep.debt"
            }
            s.consistencyScore?.let {
                if (it < 55f) {
                    reasons += "your sleep timing has been irregular (consistency ${it.roundToInt()}/100)"
                    cited += "sleep.consistency"
                }
            }
        }

        c.trainingLoad?.let { t ->
            if (t.consecutiveTrainingDays >= 3) {
                reasons += "you've trained ${t.consecutiveTrainingDays} days in a row"
                cited += "training_load"
            }
            if (t.acwrIsMeaningful && (t.acwrZone == "DANGER" || t.acwrZone == "CAUTION")) {
                reasons += "your recent load is running above your 28-day baseline"
                cited += "training_load.acwr"
            }
        }

        c.restingHR?.let {
            if (it.isAboveBaseline()) {
                reasons += "your resting heart rate is ${it.deviation} above baseline"
                cited += "rhr"
            }
        }

        c.journal?.let { j ->
            j.checkInStress?.let { if (it >= 7) { reasons += "you reported stress at $it/10"; cited += "journal" } }
            j.checkInSoreness?.let { if (it >= 7) { reasons += "you reported soreness at $it/10"; cited += "journal" } }
        }

        if (reasons.isEmpty()) {
            if (c.sleep?.durationMinutes == null || c.restingHR?.baselineDeviation() == null ||
                c.trainingLoad?.strain == null) {
                return noData(
                    CoachIntent.WHY_AM_I_TIRED,
                    "I don't have enough current sleep, resting heart rate and training readings, " +
                        "including a resting heart rate baseline, to explain tiredness. " +
                        "Missing evidence does not mean those signals are normal.",
                    c
                )
            }
            return Answer(
                intent = CoachIntent.WHY_AM_I_TIRED,
                headline = "Nothing obvious in your data",
                body = "The available sleep, training load and resting heart rate readings don't show " +
                        "one of the patterns this coach checks, so I can't point to a cause in what I can measure. " +
                        "Tiredness has plenty of drivers this app doesn't see — nutrition, hydration, " +
                        "life stress, and plenty besides. Worth monitoring rather than reading anything into.",
                citations = listOf("sleep", "training_load", "rhr"),
                followUps = listOf("What should I do tonight?"),
                hasSufficientData = true
            )
        }

        return Answer(
            intent = CoachIntent.WHY_AM_I_TIRED,
            headline = "${reasons.size} contributing ${if (reasons.size == 1) "factor" else "factors"}",
            body = "Based on what I can measure: ${joinNaturally(reasons)}. " +
                    "These are associations in your own data, not a diagnosis — tiredness has " +
                    "causes this app can't see.",
            citations = cited.distinct(),
            followUps = listOf("What should I train today?", "What should I do tonight?"),
            hasSufficientData = true
        )
    }

    // ── 4. What should I do tonight? ─────────────────────────────────────────

    private fun whatTonight(c: CoachContext): Answer {
        val cited = mutableListOf<String>()
        val actions = mutableListOf<String>()

        val bedtime = c.sleep?.typicalBedtime
        val debt = c.sleep?.debtMinutes ?: 0

        // The highest-impact single action is whichever forecast opportunity is largest;
        // the forecast engine already ranked them.
        c.forecast?.opportunities?.firstOrNull()?.let {
            actions += it
            cited += "forecast.opportunities"
        }

        if (debt > 60 && bedtime != null) {
            actions += "Consider making room for sleep before your usual $bedtime; " +
                    "your recorded sleep debt is ${fmtMin(debt)}."
            cited += "sleep"
        } else if (bedtime != null) {
            actions += "Consider keeping your usual $bedtime to support consistent sleep timing."
            cited += "sleep.consistency"
        }

        c.recommendation?.recoveryActions?.forEach { actions += it }
        if (c.recommendation != null) cited += "recommendation"

        c.forecast?.risks?.firstOrNull()?.let {
            actions += it
            cited += "forecast.risks"
        }

        if (actions.isEmpty()) {
            return noData(
                CoachIntent.WHAT_TONIGHT,
                "I need a bit more sleep history before I can suggest anything specific for tonight.",
                c
            )
        }

        val head = c.forecast?.let {
            cited += "forecast"
            "Tomorrow: ${it.low}–${it.high}"
        } ?: "Tonight"

        return Answer(
            intent = CoachIntent.WHAT_TONIGHT,
            headline = head,
            body = actions.distinct().take(3).joinToString(" "),
            citations = cited.distinct(),
            followUps = listOf("Why am I tired?", "What should I train today?"),
            hasSufficientData = true
        )
    }

    // ── 5. Weekly review ─────────────────────────────────────────────────────

    private fun weeklyReview(c: CoachContext): Answer {
        val week = c.trends.firstOrNull { it.window.startsWith("7") }
        val fortnight = c.trends.firstOrNull { it.window.startsWith("14") }

        if (week == null || week.direction == "INSUFFICIENT_DATA" ||
            (c.evidence != null && c.evidence.scoredDaysThisWeek < 5)) {
            return noData(
                CoachIntent.WEEKLY_REVIEW,
                "I need at least five scored days in the past week before I can review it.",
                c
            )
        }

        val cited = mutableListOf("trends", "sleep", "training_load", "insights")
        val body = buildString {
            appendLine("RECOVERY AND READINESS")
            append("  ${directionWord(week.direction)}")
            if (week.averageEarlier != null && week.averageRecent != null) {
                append(" — averaging ${week.averageRecent.roundToInt()}")
                val d = week.averageRecent - week.averageEarlier
                if (abs(d) >= 3) {
                    append(", ${if (d > 0) "up" else "down"} ${abs(d).roundToInt()} points")
                }
            }
            appendLine(".")

            c.sleep?.let { s ->
                appendLine()
                appendLine("SLEEP")
                s.consistencyScore?.let {
                    appendLine("  Consistency ${it.roundToInt()}/100 (${s.consistencyLabel}).")
                }
                if (s.debtMinutes > 0) appendLine("  Carrying ${fmtMin(s.debtMinutes)} of debt.")
            }

            c.trainingLoad?.let { t ->
                appendLine()
                appendLine("TRAINING")
                t.strain?.let { appendLine("  Latest day strain ${fmt1(it)}${t.strainZone?.let { z -> " ($z)" } ?: ""}.") }
                if (t.acwrIsMeaningful) appendLine("  Load ratio ${fmt2(t.acwr)} — ${t.acwrZone?.lowercase()}.")
                else appendLine("  Load ratio needs more history to be meaningful.")
            }

            if (week.contributors.isNotEmpty()) {
                appendLine()
                appendLine("WHAT MOVED IT")
                week.contributors.forEach { appendLine("  • $it") }
            }

            c.insights.firstOrNull()?.let {
                appendLine()
                appendLine("WHAT YOU'VE LEARNED")
                appendLine("  ${it.body}")
            }

            appendLine()
            appendLine("NEXT WEEK")
            val nextStep = when {
                c.recommendation?.recoveryActions?.isNotEmpty() == true -> {
                    cited += "recommendation"
                    c.recommendation.recoveryActions.first()
                }
                week.direction == "DECLINING" ->
                    "Consider keeping sleep timing consistent and making room for a rest day."
                else ->
                    "Review which routines felt manageable and aim to keep them consistent."
            }
            append("  $nextStep")
            if (fortnight != null && fortnight.direction != "INSUFFICIENT_DATA" &&
                fortnight.direction != week.direction
            ) {
                append(" Note the 14-day view reads ${directionWord(fortnight.direction).lowercase()}, ")
                append("so treat one week as a data point rather than a turning point.")
            }
        }

        return Answer(
            intent = CoachIntent.WEEKLY_REVIEW,
            headline = "Your week: ${directionWord(week.direction)}",
            body = body.trim(),
            citations = cited.distinct(),
            followUps = listOf("What should I train today?"),
            hasSufficientData = true
        )
    }

    // ── Fallback ─────────────────────────────────────────────────────────────

    private fun overview(c: CoachContext): Answer {
        val r = c.readiness?.value
        val cited = mutableListOf<String>()
        val body = buildString {
            if (r != null) {
                append("Readiness ${r.roundToInt()}/100")
                c.readiness.explanation?.let { append(". $it") }
                append(" ")
                cited += "readiness"
            }
            if (c.evidence?.staleMetrics?.isNotEmpty() == true) {
                append("Choose rest or gentle movement until today's readings can be refreshed. ")
                cited += "confidence"
            } else {
                c.recommendation?.let {
                    append("Suggested today: ${it.type}, ${it.intensity.lowercase()} intensity. ")
                    cited += "recommendation"
                }
            }
            c.forecast?.let {
                append("Tomorrow projects to ${it.low}–${it.high}. ")
                cited += "forecast"
            }
            if (r == null) {
                append("I don't have enough of today's data to say much yet. ")
                append(c.confidence.missing.firstOrNull()?.let { "Missing: $it." } ?: "")
                cited += "confidence"
            }
        }
        return Answer(
            intent = CoachIntent.FREEFORM,
            headline = "Today at a glance",
            body = body.trim(),
            citations = cited.distinct(),
            followUps = CoachIntent.values()
                .filter { it != CoachIntent.FREEFORM }
                .map { it.displayQuestion },
            hasSufficientData = r != null
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun noData(intent: CoachIntent, reason: String, c: CoachContext) = Answer(
        intent = intent,
        headline = "Not enough data yet",
        body = buildString {
            append(reason)
            if (c.confidence.missing.isNotEmpty()) {
                append(" Missing: ${c.confidence.missing.joinToString(", ")}.")
            }
            if (c.today.isCalibrating) {
                append(" VitalCore is still learning your baseline — ")
                append("${c.today.daysOfHistory} days recorded so far.")
            }
        },
        citations = listOf("confidence"),
        followUps = emptyList(),
        hasSufficientData = false
    )

    private fun directionWord(d: String): String = when (d) {
        "IMPROVING" -> "Improving"
        "DECLINING" -> "Declining"
        "VARIABLE" -> "Highly variable"
        "STABLE" -> "Stable"
        else -> "Not enough data"
    }

    private fun CoachContext.MetricBlock.baselineDeviation(): Float? =
        deviation.substringBefore(' ').toFloatOrNull()?.takeIf { it.isFinite() }

    private fun CoachContext.MetricBlock.isAboveBaseline(): Boolean =
        (baselineDeviation() ?: 0f) > 0f

    private fun joinNaturally(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    private fun fmtMin(minutes: Int): String {
        val m = abs(minutes)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun fmt1(v: Float): String = "%.1f".format(v)
    private fun fmt2(v: Float?): String = if (v == null) "—" else "%.2f".format(v)
}
