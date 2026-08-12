package com.example.vitalcoreai.coach

import com.example.vitalcoreai.analytics.AnomalyDetectionEngine
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.analytics.DataQualityReport
import com.example.vitalcoreai.analytics.InsightDiscoveryEngine
import com.example.vitalcoreai.analytics.MuscleRecoveryEngine
import com.example.vitalcoreai.analytics.PersonalBaselines
import com.example.vitalcoreai.analytics.ReadinessForecastEngine
import com.example.vitalcoreai.analytics.RecommendationEngine
import com.example.vitalcoreai.analytics.RecoveryTrendEngine
import com.example.vitalcoreai.analytics.SleepConsistencyCalculator
import kotlin.math.roundToInt

/**
 * Priority 7 — the AI Coach context pipeline.
 *
 * ## The architectural rule this file enforces
 *
 * ```
 * DATA → METRICS → SCORES → RECOMMENDATIONS → AI EXPLANATION
 * ```
 *
 * The analytics engines calculate. The coach **interprets, explains and answers**. It
 * never computes a health metric, and it is never the source of truth for one. This type
 * is the boundary: everything the coach is allowed to talk about must arrive inside a
 * [CoachContext], already computed, already validated, already carrying its own confidence.
 *
 * Concretely, that means an answer can only ever cite a number that some deterministic
 * engine produced. There is no path by which a language model could invent a readiness
 * score, because the coach layer is never given the raw inputs to invent one from.
 *
 * ## On the LLM
 *
 * This app ships **no network layer** — see the note in `app/build.gradle.kts` about the
 * downloadable-font dependency being removed to keep the app offline-only. So the answers
 * produced here are composed deterministically from the context bundle.
 *
 * The bundle is nonetheless built to be the exact payload a remote model would receive:
 * [toJson] emits the structure the product spec specifies, and [buildSystemPrompt] plus
 * [buildUserPrompt] assemble the request. Wiring an actual API call is therefore an
 * isolated change — add a client, send [toJson], render the reply — with no restructuring
 * of the data path and no change to who owns the numbers.
 */
data class CoachContext(
    val today: TodayBlock,
    val readiness: ScoreBlock?,
    val sleep: SleepBlock?,
    val restingHR: MetricBlock?,
    val trainingLoad: TrainingLoadBlock?,
    val muscleRecovery: MuscleBlock?,
    val energy: ScoreBlock?,
    val journal: JournalBlock?,
    val trends: List<TrendBlock>,
    val anomalies: List<AnomalyBlock>,
    val forecast: ForecastBlock?,
    val recommendation: RecommendationBlock?,
    val insights: List<InsightBlock>,
    val confidence: ConfidenceBlock
) {

    data class TodayBlock(
        val dateEpochDay: Long,
        val dayOfWeek: String,
        val userName: String?,
        val daysOfHistory: Int,
        val isCalibrating: Boolean
    )

    data class ScoreBlock(
        val value: Float?,
        val label: String,
        val confidence: String,
        val explanation: String?,
        val breakdown: List<FactorBlock>,
        val vsBaseline: Float?
    )

    data class FactorBlock(
        val name: String,
        val contributionPercent: Float,
        val rawValue: String,
        val subScore: Float,
        val description: String
    )

    data class MetricBlock(
        val name: String,
        val current: String,
        val baseline: String,
        val deviation: String,
        val trend: String,
        val confidence: String
    )

    data class SleepBlock(
        val durationMinutes: Int?,
        val score: Float?,
        val debtMinutes: Int,
        val consistencyScore: Float?,
        val consistencyLabel: String,
        val typicalBedtime: String?,
        val typicalWakeTime: String?,
        val stagesAvailable: Boolean
    )

    data class TrainingLoadBlock(
        val strain: Float?,
        val strainZone: String?,
        val acwr: Float?,
        val acwrZone: String?,
        val acwrIsMeaningful: Boolean,
        val consecutiveTrainingDays: Int,
        val daysSinceLastWorkout: Int?
    )

    data class MuscleBlock(
        val ready: List<String>,
        val recovering: List<String>,
        val fatigued: List<String>,
        val learnedEstimates: List<String>
    )

    data class JournalBlock(
        val trackedHabits: List<String>,
        val todayEntries: List<String>,
        val checkInEnergy: Int?,
        val checkInStress: Int?,
        val checkInSoreness: Int?,
        val checkInMood: Int?
    )

    data class TrendBlock(
        val window: String,
        val direction: String,
        val averageEarlier: Float?,
        val averageRecent: Float?,
        val contributors: List<String>,
        val confidence: String
    )

    data class AnomalyBlock(
        val metric: String,
        val severity: String,
        val title: String,
        val detail: String,
        val consecutiveDays: Int
    )

    data class ForecastBlock(
        val low: Int,
        val high: Int,
        val confidence: String,
        val positiveDrivers: List<String>,
        val negativeDrivers: List<String>,
        val risks: List<String>,
        val opportunities: List<String>
    )

    data class RecommendationBlock(
        val type: String,
        val intensity: String,
        val volumeAdjustmentPercent: Int,
        val readyRegions: List<String>,
        val avoidRegions: List<String>,
        val rationale: List<String>,
        val recoveryActions: List<String>
    )

    data class InsightBlock(
        val title: String,
        val body: String,
        val strength: String,
        val observations: Int
    )

    data class ConfidenceBlock(
        val overall: String,
        val percent: Int,
        val present: List<String>,
        val missing: List<String>,
        val factors: Map<String, Float>
    )

    // ── Serialisation ────────────────────────────────────────────────────────

    /**
     * The structured payload, in the shape the product spec specifies.
     *
     * Hand-rolled rather than reflective: this project carries no JSON serialisation
     * dependency (see the encoding note in `ScoreResult.kt`), and adding one for a single
     * outbound document would not pay for itself. It is also useful that the exact wire
     * shape is visible in one readable place, since this is the contract an external model
     * would be held to.
     */
    fun toJson(): String = buildJsonObject {
        obj("today") {
            num("date_epoch_day", today.dateEpochDay)
            str("day_of_week", today.dayOfWeek)
            str("user_name", today.userName)
            num("days_of_history", today.daysOfHistory)
            bool("is_calibrating", today.isCalibrating)
        }
        scoreObj("readiness", readiness)
        obj("sleep") {
            if (sleep == null) str("status", "no_data") else {
                num("duration_minutes", sleep.durationMinutes)
                num("score", sleep.score)
                num("debt_minutes", sleep.debtMinutes)
                num("consistency_score", sleep.consistencyScore)
                str("consistency_label", sleep.consistencyLabel)
                str("typical_bedtime", sleep.typicalBedtime)
                str("typical_wake_time", sleep.typicalWakeTime)
                bool("stages_available", sleep.stagesAvailable)
            }
        }
        obj("rhr") {
            if (restingHR == null) str("status", "no_data") else {
                str("current", restingHR.current)
                str("baseline", restingHR.baseline)
                str("deviation", restingHR.deviation)
                str("trend", restingHR.trend)
                str("confidence", restingHR.confidence)
            }
        }
        obj("training_load") {
            if (trainingLoad == null) str("status", "no_data") else {
                num("strain", trainingLoad.strain)
                str("strain_zone", trainingLoad.strainZone)
                num("acwr", trainingLoad.acwr)
                str("acwr_zone", trainingLoad.acwrZone)
                bool("acwr_is_meaningful", trainingLoad.acwrIsMeaningful)
                num("consecutive_training_days", trainingLoad.consecutiveTrainingDays)
                num("days_since_last_workout", trainingLoad.daysSinceLastWorkout)
            }
        }
        obj("muscle_recovery") {
            if (muscleRecovery == null) str("status", "no_data") else {
                arr("ready", muscleRecovery.ready)
                arr("recovering", muscleRecovery.recovering)
                arr("fatigued", muscleRecovery.fatigued)
                arr("learned_estimates", muscleRecovery.learnedEstimates)
            }
        }
        scoreObj("energy", energy)
        obj("journal") {
            if (journal == null) str("status", "no_data") else {
                arr("tracked_habits", journal.trackedHabits)
                arr("today_entries", journal.todayEntries)
                num("checkin_energy", journal.checkInEnergy)
                num("checkin_stress", journal.checkInStress)
                num("checkin_soreness", journal.checkInSoreness)
                num("checkin_mood", journal.checkInMood)
            }
        }
        arrOfObj("trends", trends) { t ->
            str("window", t.window)
            str("direction", t.direction)
            num("average_earlier", t.averageEarlier)
            num("average_recent", t.averageRecent)
            arr("contributors", t.contributors)
            str("confidence", t.confidence)
        }
        arrOfObj("anomalies", anomalies) { a ->
            str("metric", a.metric)
            str("severity", a.severity)
            str("title", a.title)
            str("detail", a.detail)
            num("consecutive_days", a.consecutiveDays)
        }
        obj("forecast") {
            if (forecast == null) str("status", "unavailable") else {
                num("low", forecast.low)
                num("high", forecast.high)
                str("confidence", forecast.confidence)
                arr("positive_drivers", forecast.positiveDrivers)
                arr("negative_drivers", forecast.negativeDrivers)
                arr("risks", forecast.risks)
                arr("opportunities", forecast.opportunities)
            }
        }
        obj("recommendation") {
            if (recommendation == null) str("status", "unavailable") else {
                str("type", recommendation.type)
                str("intensity", recommendation.intensity)
                num("volume_adjustment_percent", recommendation.volumeAdjustmentPercent)
                arr("ready_regions", recommendation.readyRegions)
                arr("avoid_regions", recommendation.avoidRegions)
                arr("rationale", recommendation.rationale)
                arr("recovery_actions", recommendation.recoveryActions)
            }
        }
        arrOfObj("insights", insights) { i ->
            str("title", i.title)
            str("body", i.body)
            str("strength", i.strength)
            num("observations", i.observations)
        }
        obj("confidence") {
            str("overall", confidence.overall)
            num("percent", confidence.percent)
            arr("present", confidence.present)
            arr("missing", confidence.missing)
            obj("factors") { confidence.factors.forEach { (k, v) -> num(k.lowercase(), v) } }
        }
    }

    // ── Prompt assembly (for a future remote model) ──────────────────────────

    companion object {
        /**
         * The system prompt an external model would receive.
         *
         * The constraints are not decoration — they are the reason it is safe to send a
         * health context to a generative model at all. The model is explicitly forbidden
         * from computing, estimating, or diagnosing; its entire job is to phrase what the
         * deterministic layer already decided.
         */
        fun buildSystemPrompt(): String = """
            You are the coaching voice of VitalCore AI, a personal fitness and recovery app.

            YOUR ROLE — you INTERPRET, EXPLAIN, SUMMARISE and ANSWER. You do not calculate.

            HARD CONSTRAINTS:
            1. Every number you state must appear verbatim in the supplied context. Never
               compute, estimate, extrapolate or round a health metric yourself.
            2. If the context does not contain something, say you do not have it. Never
               fill a gap with a plausible value.
            3. Never diagnose. Never name a disease, infection, or clinical syndrome. Never
               claim a cause for a physiological change.
            4. Never claim HRV. This device cannot measure it and the context never carries it.
            5. Respect confidence. When a value is LOW confidence, say so in the same breath
               as the value.
            6. Prefer ranges over point estimates for anything forward-looking.
            7. Permitted framing: "unusual", "above your normal range", "may reflect",
               "consider", "worth monitoring", "an association in your data".
               Forbidden framing: "you have", "this means you are", "this will".

            TONE: direct, warm, specific. Lead with the answer. No filler openings.
        """.trimIndent()

        /** Builds the user turn: the question plus the full validated context. */
        fun buildUserPrompt(intent: CoachIntent, question: String, context: CoachContext): String =
            buildString {
                appendLine("QUESTION (intent: ${intent.name}): $question")
                appendLine()
                appendLine("CONTEXT (authoritative — all numbers already computed):")
                appendLine(context.toJson())
            }
    }
}

/** The five coach questions the product spec names, plus a free-form fallback. */
enum class CoachIntent(val displayQuestion: String) {
    WHY_AM_I_LOW("Why is my readiness low today?"),
    WHAT_SHOULD_I_TRAIN("What should I train today?"),
    WHY_AM_I_TIRED("Why am I tired?"),
    WHAT_TONIGHT("What should I do tonight?"),
    WEEKLY_REVIEW("How was my week?"),
    FREEFORM("Ask anything");

    companion object {
        /** Cheap keyword routing — the deterministic answerer needs an intent, not an essay. */
        fun classify(question: String): CoachIntent {
            val q = question.lowercase()
            return when {
                q.contains("train") || q.contains("workout") || q.contains("session") ||
                        q.contains("exercise") -> WHAT_SHOULD_I_TRAIN
                q.contains("tonight") || q.contains("bed") || q.contains("this evening") -> WHAT_TONIGHT
                q.contains("tired") || q.contains("exhausted") || q.contains("fatigue") -> WHY_AM_I_TIRED
                q.contains("week") -> WEEKLY_REVIEW
                q.contains("low") || q.contains("why") -> WHY_AM_I_LOW
                else -> FREEFORM
            }
        }
    }
}

// ── Minimal JSON writer ──────────────────────────────────────────────────────
// Deliberately tiny and local. See the note on CoachContext.toJson for why this is not a
// serialisation library.

internal class JsonBuilder {
    private val sb = StringBuilder()
    private var first = true

    private fun comma() {
        if (!first) sb.append(',')
        first = false
    }

    fun str(key: String, value: String?) {
        comma(); sb.append(quote(key)).append(':')
        if (value == null) sb.append("null") else sb.append(quote(value))
    }

    fun num(key: String, value: Number?) {
        comma(); sb.append(quote(key)).append(':')
        if (value == null) sb.append("null") else sb.append(numToString(value))
    }

    fun bool(key: String, value: Boolean) {
        comma(); sb.append(quote(key)).append(':').append(value)
    }

    fun arr(key: String, values: List<String>) {
        comma(); sb.append(quote(key)).append(':')
        sb.append(values.joinToString(",", "[", "]") { quote(it) })
    }

    fun obj(key: String, block: JsonBuilder.() -> Unit) {
        comma(); sb.append(quote(key)).append(':')
        sb.append(JsonBuilder().apply(block).render())
    }

    fun <T> arrOfObj(key: String, items: List<T>, block: JsonBuilder.(T) -> Unit) {
        comma(); sb.append(quote(key)).append(':').append('[')
        items.forEachIndexed { i, item ->
            if (i > 0) sb.append(',')
            sb.append(JsonBuilder().apply { block(item) }.render())
        }
        sb.append(']')
    }

    /** A [CoachContext.ScoreBlock] renders identically wherever it appears. */
    fun scoreObj(key: String, block: CoachContext.ScoreBlock?) {
        obj(key) {
            if (block == null) str("status", "no_data") else {
                num("value", block.value)
                str("label", block.label)
                str("confidence", block.confidence)
                str("explanation", block.explanation)
                num("vs_baseline", block.vsBaseline)
                arrOfObj("breakdown", block.breakdown) { f ->
                    str("name", f.name)
                    num("contribution_percent", f.contributionPercent)
                    str("raw_value", f.rawValue)
                    num("sub_score", f.subScore)
                    str("description", f.description)
                }
            }
        }
    }

    fun render(): String = "{$sb}"

    private fun numToString(v: Number): String = when (v) {
        is Float -> if (v.isFinite()) trimTrailing(v.toDouble()) else "null"
        is Double -> if (v.isFinite()) trimTrailing(v) else "null"
        else -> v.toString()
    }

    /** 72.0 → "72", 7.25 → "7.25". Avoids emitting spurious precision into the payload. */
    private fun trimTrailing(v: Double): String {
        val rounded = (v * 100).roundToInt() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }

    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}

internal fun buildJsonObject(block: JsonBuilder.() -> Unit): String =
    JsonBuilder().apply(block).render()
