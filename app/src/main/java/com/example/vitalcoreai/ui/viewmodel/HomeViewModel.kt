package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.core.time.perDay
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.db.entity.DailyMetricsEntity
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.data.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home screen UiState.
 *
 * @param orderedScoreCards  B3 dynamic ordering — score cards sorted by deviation from 7-day avg
 *                           (largest negative deviation first, i.e. problem areas at top)
 * @param recoveryMomentum   B1 momentum for recovery
 * @param sleepMomentum      B1 momentum for sleep
 * @param trainingMomentum   B1 momentum for training
 */
data class HomeUiState(
    val latestScores: ComputedScoresEntity? = null,
    val latestMetrics: DailyMetricsEntity? = null,
    val insights: List<CoachEngine.CoachInsight> = emptyList(),
    val orderedScoreCards: List<ScoreCardData> = emptyList(),  // B3
    val recoveryMomentum: String? = null,     // B1 — "IMPROVING" / "STABLE" / "DECLINING"
    val sleepMomentum: String? = null,        // B1
    val trainingMomentum: String? = null,     // B1
    // ── V1.1 (T-13) — the persisted v7 columns, decoded for rendering ─────────
    val forecast: ForecastState? = null,
    val trends: List<TrendState> = emptyList(),
    val anomalies: List<AnomalyState> = emptyList(),
    val recommendation: RecommendationState? = null,
    val sleepConsistency: SleepConsistencyState? = null,
    val dataQualityWhy: List<String> = emptyList(),
    /**
     * Days since the last logged workout.
     *
     * **Never null when history has loaded** — F-04. It used to be null whenever the
     * 14-day exercise window was empty, which is exactly the case it exists to describe:
     * a user who had not trained for a fortnight was reported as "no data" rather than
     * "14+ days", so the coach's rest-day rules could never fire for them.
     */
    val daysWithoutTraining: Int? = null,
    val userName: String? = null,
    val isLoading: Boolean = true,
    val syncError: String? = null
)

/** Tomorrow's readiness, always a range. A midpoint would imply precision that is not there. */
data class ForecastState(
    val low: Int,
    val high: Int,
    val confidence: String?,
    val drivers: List<ScorePipeline.DecodedDriver>,
    val risks: List<String>
) {
    val range: String get() = "$low–$high"
}

data class TrendState(
    val window: String,
    val direction: String,
    val contributors: List<String>
) {
    /** Words, not an arrow — a screen reader cannot read a glyph, and colour is not a label. */
    val directionLabel: String
        get() = when (direction) {
            "IMPROVING" -> "Improving"
            "DECLINING" -> "Declining"
            "VARIABLE" -> "Highly variable"
            "STABLE" -> "Stable"
            else -> "Not enough data"
        }

    val arrow: String
        get() = when (direction) {
            "IMPROVING" -> "↑"
            "DECLINING" -> "↓"
            "VARIABLE" -> "↕"
            "STABLE" -> "→"
            else -> "—"
        }

    val isMeaningful: Boolean get() = direction != "INSUFFICIENT_DATA"
}

data class AnomalyState(
    val metric: String,
    val severity: String,
    val severityLabel: String,
    val title: String
)

data class RecommendationState(
    val type: String,
    val intensity: String,
    val volumePct: Int?,
    val detail: String?
) {
    /** "Upper-body strength · Moderate intensity" — the Home screen's TODAY line. */
    val summaryLine: String get() = "$type · $intensity intensity"
}

data class SleepConsistencyState(
    val score: Float?,
    val label: String?,
    val bedtimeSdMinutes: Int?,
    val wakeSdMinutes: Int?
)

/** Data class for a dynamically-ordered score card. B3. */
data class ScoreCardData(
    val id: String,
    val title: String,
    val score: Float?,
    val subtitle: String,
    val destination: String,
    val priority: Int        // lower = higher priority (shown first). 0 = most urgent.
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HealthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Today's exercise sessions for the Activities feed on the Home screen.
     *
     * F-03: the day key used to be resolved once, in this property initialiser, so an app
     * left open past midnight kept showing yesterday's sessions as today's — and travelling
     * had the same effect without even waiting for midnight. [perDay] restarts the query
     * when the local day actually changes.
     */
    val todayExercises: StateFlow<List<ExerciseSessionEntity>> =
        perDay { day -> repository.exerciseFrom(day) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        observeData()
        syncOnLaunch()
    }

    private fun observeData() {
        viewModelScope.launch {
            perDay { today ->
                combine(
                    repository.latestScores(),
                    repository.metricsFrom(today - 30),
                    repository.exerciseFrom(today - 30)
                ) { scores, metricsList, exerciseList ->
                    buildState(today, scores, metricsList, exerciseList)
                }
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, syncError = e.message) }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun buildState(
        today: Long,
        scores: ComputedScoresEntity?,
        metricsList: List<DailyMetricsEntity>,
        exerciseList: List<ExerciseSessionEntity>
    ): HomeUiState {
        // The newest row in the window, not necessarily today's — a watch that has not
        // synced yet leaves today absent, and showing the last real day is honest where
        // showing nothing is not.
        val todayMetrics = metricsList.lastOrNull()

        val rhrValues = metricsList.mapNotNull { it.restingHR?.toDouble() }
        val hrBaseline = if (rhrValues.size >= 3) rhrValues.average().toInt() else null

        // F-05: the user's CONFIGURED sleep need, not a hardcoded 8 hours. Someone who set
        // their need to 7h was told they were 60 minutes in debt every single night.
        val sleepNeedMin = UserPrefs.sleepNeedMinutes(context)
        val sleepDebtMinutes = metricsList
            .takeLast(7)
            .mapNotNull { it.sleepDurationMinutes }
            .sumOf { maxOf(0, sleepNeedMin - it) }
            .takeIf { metricsList.size >= 3 }

        val daysWithoutTraining = calculateDaysWithoutTraining(today, exerciseList, metricsList)

        val insights = scores?.let {
            CoachEngine.getDailyInsights(
                CoachEngine.CoachInput(
                    recoveryScore = it.recoveryScore,
                    readinessScore = it.readinessScore,
                    sleepScore = it.sleepScore,
                    stressScore = it.stressScore,
                    activityScore = it.activityScore,
                    acwrZone = it.acwrZone,
                    restingHR = todayMetrics?.restingHR,
                    hrBaseline = hrBaseline,
                    sleepDebtMinutes = sleepDebtMinutes,
                    daysWithoutTraining = daysWithoutTraining,
                    todaySleepHours = todayMetrics?.sleepDurationMinutes?.let { m -> m / 60f },
                    vo2Max = it.vo2MaxEstimate,
                    biologicalAge = it.biologicalAge,
                    chronologicalAge = UserPrefs.age(context),
                    // Part 11 — Energy Bank
                    energyBankScore = it.energyBankScore
                )
            )
        } ?: emptyList()

        return HomeUiState(
            latestScores = scores,
            latestMetrics = todayMetrics,
            insights = insights,
            orderedScoreCards = buildDynamicCardOrder(scores, todayMetrics),
            recoveryMomentum = scores?.recoveryMomentum,
            sleepMomentum = scores?.sleepMomentum,
            trainingMomentum = scores?.trainingMomentum,
            forecast = scores.toForecastState(),
            trends = scores.toTrendStates(),
            anomalies = scores.toAnomalyStates(),
            recommendation = scores.toRecommendationState(),
            sleepConsistency = scores.toSleepConsistencyState(),
            dataQualityWhy = ScorePipeline.decodeTextList(scores?.dataQualityPositives),
            daysWithoutTraining = daysWithoutTraining,
            userName = UserPrefs.userName(context),
            isLoading = false
        )
    }

    // ─── v7 column decoding ──────────────────────────────────────────────────
    //
    // Encoding and decoding are the same pair of functions on both sides (ScorePipeline),
    // so a change to the delimiters cannot leave the writer and the reader disagreeing.

    private fun ComputedScoresEntity?.toForecastState(): ForecastState? {
        val low = this?.forecastLow ?: return null
        val high = forecastHigh ?: return null
        return ForecastState(
            low = low,
            high = high,
            confidence = forecastConfidence,
            drivers = ScorePipeline.decodeDrivers(forecastDrivers),
            risks = ScorePipeline.decodeTextList(forecastRisks)
        )
    }

    private fun ComputedScoresEntity?.toTrendStates(): List<TrendState> {
        if (this == null) return emptyList()
        val contributors = ScorePipeline.decodeTextList(trendContributors)
        return listOfNotNull(
            trend7Direction?.let { TrendState("7-day", it, contributors) },
            trend14Direction?.let { TrendState("14-day", it, contributors) },
            trend30Direction?.let { TrendState("30-day", it, contributors) }
        ).filter { it.isMeaningful }
    }

    private fun ComputedScoresEntity?.toAnomalyStates(): List<AnomalyState> =
        ScorePipeline.decodeAnomalies(this?.anomaliesEncoded).map {
            AnomalyState(it.metric, it.severity, it.severityLabel, it.title)
        }

    private fun ComputedScoresEntity?.toRecommendationState(): RecommendationState? {
        val type = this?.recommendationType ?: return null
        val intensity = recommendationIntensity ?: return null
        return RecommendationState(type, intensity, recommendationVolumePct, recommendationDetail)
    }

    private fun ComputedScoresEntity?.toSleepConsistencyState(): SleepConsistencyState? {
        if (this?.sleepConsistencyScore == null && this?.sleepConsistencyLabel == null) return null
        return SleepConsistencyState(
            score = sleepConsistencyScore,
            label = sleepConsistencyLabel,
            bedtimeSdMinutes = bedtimeSdMinutes,
            wakeSdMinutes = wakeSdMinutes
        )
    }

    /**
     * Days since the last logged workout — F-04.
     *
     * Returns null only when there is no history at all to reason from. Previously it
     * returned null whenever the exercise window happened to be empty, which conflated "we
     * have never seen this user" with "this user has not trained in a fortnight" — and the
     * second is the case every rest-day rule in the coach depends on.
     */
    private fun calculateDaysWithoutTraining(
        today: Long,
        exercise: List<ExerciseSessionEntity>,
        metrics: List<DailyMetricsEntity>
    ): Int? {
        exercise.maxOfOrNull { it.dateEpochDay }?.let { return (today - it).toInt().coerceAtLeast(0) }
        // No sessions in the window. Report the span of history we do have rather than null,
        // capped at the window itself so it never claims to know about days it never saw.
        val earliest = metrics.minOfOrNull { it.dateEpochDay } ?: return null
        return (today - earliest).toInt().coerceAtLeast(0)
    }

    /**
     * B3 — Dynamic card ordering.
     *
     * Strategy: rank each score card by its score value.
     * Score < 40  → priority 0 (most urgent, shown first)
     * Score 40–59 → priority 1
     * Score 60–74 → priority 2
     * Score ≥ 75  → priority 3 (shown last — already healthy)
     * null score  → priority 4 (no data — shown at end)
     */
    private fun buildDynamicCardOrder(
        scores: ComputedScoresEntity?,
        metrics: DailyMetricsEntity?
    ): List<ScoreCardData> {
        fun priority(score: Float?) = when {
            score == null  -> 4
            score < 40f    -> 0
            score < 60f    -> 1
            score < 75f    -> 2
            else           -> 3
        }

        val cards = listOf(
            ScoreCardData(
                id = "sleep",
                title = "Sleep",
                score = scores?.sleepScore,
                subtitle = metrics?.sleepDurationMinutes?.let { VitalTime.formatDurationMinutes(it) } ?: "",
                destination = "sleep",
                priority = priority(scores?.sleepScore)
            ),
            ScoreCardData(
                id = "heart",
                title = "Heart Rate",
                score = metrics?.restingHR?.toFloat(),
                subtitle = "bpm resting",
                destination = "heart",
                priority = priority(scores?.stressScore)  // HR card urgency driven by stress score
            ),
            ScoreCardData(
                id = "stress",
                title = "Stress",
                score = scores?.stressScore,
                subtitle = "",
                destination = "stress",
                priority = priority(scores?.stressScore)
            ),
            ScoreCardData(
                id = "activity",
                title = "Activity",
                score = scores?.activityScore,
                subtitle = "${metrics?.steps ?: 0} steps",
                destination = "activity",
                priority = priority(scores?.activityScore)
            ),
            ScoreCardData(
                id = "training",
                title = "Training",
                score = scores?.trainingLoadNormalized?.let { it * 100 },
                subtitle = "ACWR ${scores?.acwr?.let { String.format("%.2f", it) } ?: "—"}",
                destination = "training",
                priority = priority(scores?.trainingLoadNormalized?.let { it * 100 })
            ),
            ScoreCardData(
                id = "lifestyle",
                title = "Lifestyle",
                score = scores?.lifestyleScore,
                subtitle = "",
                destination = "insights",
                priority = priority(scores?.lifestyleScore)
            )
        )

        // Sort by priority ascending (most urgent first), then alphabetically for stability
        return cards.sortedWith(compareBy({ it.priority }, { it.title }))
    }

    private fun syncOnLaunch() {
        SyncWorker.syncNow(context, UserPrefs.age(context), UserPrefs.maxHR(context))
    }

    fun refresh() { SyncWorker.syncNow(context, UserPrefs.age(context), UserPrefs.maxHR(context)) }
}
