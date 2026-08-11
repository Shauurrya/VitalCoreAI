package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.coach.CoachEngine
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
import java.time.LocalDate
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
    val isLoading: Boolean = true,
    val syncError: String? = null
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Today's exercise sessions for the Activities feed on the Home screen. */
    val todayExercises: StateFlow<List<ExerciseSessionEntity>> =
        repository.exerciseFrom(java.time.LocalDate.now().toEpochDay())
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        observeData()
        syncOnLaunch()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                repository.latestScores(),
                repository.metricsFrom(LocalDate.now().minusDays(30).toEpochDay()),
                repository.exerciseFrom(LocalDate.now().minusDays(14).toEpochDay())
            ) { scores, metricsList, exerciseList ->
                val todayMetrics = metricsList.lastOrNull()

                // ── Real coach inputs ──────────────────────────────────────
                val rhrValues = metricsList.mapNotNull { it.restingHR?.toDouble() }
                val hrBaseline = if (rhrValues.size >= 3) rhrValues.average().toInt() else null

                // Sleep debt: sum of deficits over last 7 days vs 8h need
                val sleepNeedMin = 480
                val sleepDebtMinutes = metricsList
                    .takeLast(7)
                    .mapNotNull { it.sleepDurationMinutes }
                    .sumOf { maxOf(0, sleepNeedMin - it) }
                    .takeIf { metricsList.size >= 3 }

                // Days since last workout
                val lastWorkoutEpochDay = exerciseList.maxOfOrNull { it.dateEpochDay }
                val daysWithoutTraining = lastWorkoutEpochDay?.let {
                    (LocalDate.now().toEpochDay() - it).toInt()
                }

                // ── Coach insights (rule-based engine) ────────────────────
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

                // ── B3: Dynamic score card ordering ───────────────────────
                val orderedCards = buildDynamicCardOrder(scores, todayMetrics)

                // ── B1: Momentum strings from stored momentum columns ─────
                val recoveryMomentum  = scores?.recoveryMomentum
                val sleepMomentum     = scores?.sleepMomentum
                val trainingMomentum  = scores?.trainingMomentum

                HomeUiState(
                    latestScores = scores,
                    latestMetrics = todayMetrics,
                    insights = insights,
                    orderedScoreCards = orderedCards,
                    recoveryMomentum  = recoveryMomentum,
                    sleepMomentum     = sleepMomentum,
                    trainingMomentum  = trainingMomentum,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, syncError = e.message) }
            }.collect { state ->
                _uiState.value = state
            }
        }
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
                subtitle = metrics?.sleepDurationMinutes?.let { "${it/60}h ${it%60}m" } ?: "",
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
