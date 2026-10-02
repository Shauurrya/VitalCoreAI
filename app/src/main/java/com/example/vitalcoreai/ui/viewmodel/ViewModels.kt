package com.example.vitalcoreai.ui.viewmodel

import android.content.Context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.db.entity.ComputedScoresEntity
import com.example.vitalcoreai.data.model.*
import com.example.vitalcoreai.data.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.core.time.perDay

// ─── Recovery ────────────────────────────────────────────────────────────────

data class ScoreDetailUiState(
    val score: Float? = null,
    val confidence: Confidence = Confidence.LOW,
    val explanation: String = "",
    val breakdown: List<ScoreFactor> = emptyList(),
    val trendDirection: TrendDirection = TrendDirection.NEUTRAL,
    val chartValues: List<Float> = emptyList(),
    // HRV trend (14-day RMSSD) - improvement #6
    val hrvChartValues: List<Float> = emptyList(),
    val latestHrvRmssdMs: Double? = null,
    // SpO2 trend (30-day) - improvement #7
    val spo2ChartValues: List<Float> = emptyList(),
    val latestSpo2: Float? = null,
    val isLoading: Boolean = true
)

// Improvement #1: Abstract base class eliminates boilerplate in Recovery, Readiness, Stress.
abstract class ScoreDetailViewModelBase(
    protected val repository: HealthRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDetailUiState())
    val state: StateFlow<ScoreDetailUiState> = _state.asStateFlow()

    init { viewModelScope.launch { observe().collect { _state.value = it } } }

    protected abstract fun observe(): Flow<ScoreDetailUiState>
}

@HiltViewModel
class RecoveryViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDetailUiState())
    val state: StateFlow<ScoreDetailUiState> = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 30) }
            .collect { scores ->
                val latest = scores.lastOrNull()
                _state.value = ScoreDetailUiState(
                    score = latest?.recoveryScore,
                    confidence = latest?.recoveryConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW,
                    explanation = latest?.recoveryExplanation ?: "Sync health data to compute your recovery score.",
                    breakdown = decodeScoreFactors(latest?.recoveryBreakdown),
                    chartValues = scores.mapNotNull { it.recoveryScore }.takeLast(14),
                    trendDirection = trendOf(scores.mapNotNull { it.recoveryScore }),
                    isLoading = false
                )
            }
    }
}

// ─── Readiness ───────────────────────────────────────────────────────────────

@HiltViewModel
class ReadinessViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDetailUiState())
    val state: StateFlow<ScoreDetailUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 30) }.collect { scores ->
            val latest = scores.lastOrNull()
            _state.value = ScoreDetailUiState(
                score = latest?.readinessScore,
                confidence = latest?.readinessConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW,
                explanation = latest?.readinessExplanation
                    ?: (if (latest?.readinessScore != null) "Readiness score of ${latest.readinessScore.toInt()}/100 based on recovery, sleep debt, and training load trend." else "No data yet."),
                breakdown = decodeScoreFactors(latest?.readinessBreakdown),
                chartValues = scores.mapNotNull { it.readinessScore }.takeLast(14),
                trendDirection = trendOf(scores.mapNotNull { it.readinessScore }),
                isLoading = false
            )
        }
    }
}

// ─── Sleep ───────────────────────────────────────────────────────────────────

data class SleepDebtState(
    val recommendedHours: Int = 8,
    val recommendedMinutesRemainder: Int = 0,
    val lastNightMinutes: Int? = null,
    val dailyDeficit: Int = 0,         // positive = deficit, negative = surplus
    val rollingDebtMinutes: Int = 0,   // accumulated over 7 days
    val recommendation: String = "Sync data to see your sleep debt."
)

@HiltViewModel
class SleepViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDetailUiState())
    val state: StateFlow<ScoreDetailUiState> = _state.asStateFlow()

    private val _sleepDebt = MutableStateFlow(SleepDebtState())
    val sleepDebt: StateFlow<SleepDebtState> = _sleepDebt.asStateFlow()

    // Improvement #3: merged two separate init blocks into one.
    init {
        load()
        loadSleepDebt()
    }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 30) }.collect { scores ->
            val latest = scores.lastOrNull()
            _state.value = ScoreDetailUiState(
                score = latest?.sleepScore,
                confidence = latest?.sleepConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW,
                explanation = latest?.sleepExplanation
                    ?: "Sleep score reflects duration, stage quality (deep + REM), consistency, and accumulated debt.",
                breakdown = decodeScoreFactors(latest?.sleepBreakdown),
                chartValues = scores.mapNotNull { it.sleepScore }.takeLast(14),
                trendDirection = trendOf(scores.mapNotNull { it.sleepScore }),
                isLoading = false
            )
        }
    }
    private fun loadSleepDebt() = viewModelScope.launch {
        perDay { day -> repository.metricsFrom(day - 14) }.collect { metrics ->
            val last14 = metrics.sortedByDescending { it.dateEpochDay }.take(14)
            val last7 = last14.take(7)

            // Personalized sleep need: average of top 3 best nights over 14 days
            // (clamped to 7-9.5h range to avoid nonsensical values)
            val sleepDurations = last14.mapNotNull { it.sleepDurationMinutes }
            val personalSleepNeedMinutes = if (sleepDurations.size >= 3) {
                sleepDurations.sortedDescending().take(3).average().toInt()
                    .coerceIn(420, 570) // 7h - 9.5h
            } else 480 // default 8h

            val lastNight = last7.firstOrNull()?.sleepDurationMinutes
            val dailyDeficit = if (lastNight != null) (personalSleepNeedMinutes - lastNight) else 0

            val rollingDebt = last7.mapNotNull { it.sleepDurationMinutes }
                .sumOf { maxOf(0, personalSleepNeedMinutes - it) }

            val recommendation = when {
                rollingDebt > 300 -> "Sleep debt is significant. Adding 30-60 min to the next few nights would close it. Aim for bed by 9:30 PM."
                rollingDebt > 120 -> "Moderate sleep debt. An extra 20 min tonight will help. Target lights-out by 10:00 PM."
                rollingDebt > 0 -> "Slight sleep debt — nothing concerning. Maintain your current routine."
                else -> "You're sleep-positive this week! Your recovery capacity is maximized."
            }

            _sleepDebt.value = SleepDebtState(
                recommendedHours = personalSleepNeedMinutes / 60,
                recommendedMinutesRemainder = personalSleepNeedMinutes % 60,
                lastNightMinutes = lastNight,
                dailyDeficit = dailyDeficit,
                rollingDebtMinutes = rollingDebt,
                recommendation = recommendation
            )
        }
    }
}

// ─── Heart ───────────────────────────────────────────────────────────────────

data class HeartUiState(
    val restingHR: Int? = null,
    val avgHR7Day: Float? = null,
    val avgHR30Day: Float? = null,
    val chartValues: List<Float> = emptyList(),
    // HRV trend (14-day RMSSD) - improvement #6
    val hrvChartValues: List<Float> = emptyList(),
    val latestHrvRmssdMs: Double? = null,
    // SpO2 trend (30-day) - improvement #7
    val spo2ChartValues: List<Float> = emptyList(),
    val latestSpo2: Float? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class HeartViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(HeartUiState())
    val state: StateFlow<HeartUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.metricsFrom(day - 30) }.collect { metrics ->
            val rhrValues = metrics.mapNotNull { it.restingHR?.toFloat() }
            val hrvValues = metrics.mapNotNull { it.hrvRmssdMs?.toFloat() }
            val spo2Values = metrics.mapNotNull { it.spO2Percent }
            _state.value = HeartUiState(
                restingHR = metrics.lastOrNull()?.restingHR,
                avgHR7Day = rhrValues.takeLast(7).averageOrNull()?.toFloat(),
                avgHR30Day = rhrValues.averageOrNull()?.toFloat(),
                chartValues = rhrValues.takeLast(30),
                hrvChartValues = hrvValues.takeLast(14),
                latestHrvRmssdMs = metrics.lastOrNull()?.hrvRmssdMs,
                spo2ChartValues = spo2Values.takeLast(30),
                latestSpo2 = metrics.lastOrNull()?.spO2Percent,
                isLoading = false
            )
        }
    }
}

// ─── Stress ──────────────────────────────────────────────────────────────────

@HiltViewModel
class StressViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScoreDetailUiState())
    val state: StateFlow<ScoreDetailUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 30) }.collect { scores ->
            val latest = scores.lastOrNull()
            _state.value = ScoreDetailUiState(
                score = latest?.stressScore,
                confidence = latest?.stressConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW,
                explanation = latest?.stressExplanation
                    ?: "Physiological stress is estimated from resting HR deviation vs your 30-day personal baseline.",
                breakdown = decodeScoreFactors(latest?.stressBreakdown),
                chartValues = scores.mapNotNull { it.stressScore }.takeLast(14),
                trendDirection = trendOf(scores.mapNotNull { it.stressScore }),
                isLoading = false
            )
        }
    }
}

// ─── Activity ────────────────────────────────────────────────────────────────

data class ActivityUiState(
    val steps: Int? = null,
    val calories: Int? = null,
    val distanceKm: Float? = null,
    val activityScore: Float? = null,
    val chartValues: List<Float> = emptyList(),
    // HRV trend (14-day RMSSD) - improvement #6
    val hrvChartValues: List<Float> = emptyList(),
    val latestHrvRmssdMs: Double? = null,
    // SpO2 trend (30-day) - improvement #7
    val spo2ChartValues: List<Float> = emptyList(),
    val latestSpo2: Float? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class ActivityViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        combine(
            perDay { day -> repository.metricsFrom(day - 30) },
            perDay { day -> repository.scoresFrom(day - 30) }
        ) { metrics, scores ->
            val today = metrics.lastOrNull()
            ActivityUiState(
                steps = today?.steps,
                calories = today?.caloriesBurned,
                distanceKm = today?.distanceMeters?.let { it / 1000f },
                activityScore = scores.lastOrNull()?.activityScore,
                chartValues = metrics.mapNotNull { it.steps?.toFloat() }.takeLast(14),
                isLoading = false
            )
        }.collect { _state.value = it }
    }
}

// ─── Training Load ────────────────────────────────────────────────────────────

data class TrainingUiState(
    val todayLoad: Float? = null,
    val strain: Float? = null,
    val confidence: Confidence = Confidence.LOW,
    val isProxyEstimate: Boolean = false,
    val exertionMinutes: Float? = null,
    val explanation: String? = null,
    val strainHistory: List<Float> = emptyList(),
    val acwr: Float? = null,
    val acwrZone: String? = null,
    val acwrIsMeaningful: Boolean? = null,
    val acwrDaysOfHistory: Int? = null,
    val chartValues: List<Float> = emptyList(),
    // HRV trend (14-day RMSSD) - improvement #6
    val hrvChartValues: List<Float> = emptyList(),
    val latestHrvRmssdMs: Double? = null,
    // SpO2 trend (30-day) - improvement #7
    val spo2ChartValues: List<Float> = emptyList(),
    val latestSpo2: Float? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class TrainingViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(TrainingUiState())
    val state: StateFlow<TrainingUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 30) }.collect { scores ->
            val latest = scores.lastOrNull()
            _state.value = TrainingUiState(
                todayLoad = latest?.trainingLoadNormalized?.let { it * 100 },
                // Daily strain has its own persisted 0–21 scale; normalized session
                // load remains separate and must never stand in for a missing strain.
                strain = latest?.strain,
                confidence = latest?.strainConfidence?.let { runCatching { Confidence.valueOf(it) }.getOrNull() } ?: Confidence.LOW,
                isProxyEstimate = latest?.strainIsProxy == true,
                exertionMinutes = latest?.dailyExertionMinutes,
                explanation = latest?.strainExplanation,
                strainHistory = scores.mapNotNull { it.strain }.takeLast(14),
                acwr = latest?.acwr,
                acwrZone = latest?.acwrZone,
                acwrIsMeaningful = latest?.acwrIsMeaningful,
                acwrDaysOfHistory = latest?.acwrDaysOfHistory,
                chartValues = scores.mapNotNull { it.trainingLoadNormalized?.let { l -> l * 100f } }.takeLast(14),
                isLoading = false
            )
        }
    }
}

// ─── Biological Age ───────────────────────────────────────────────────────────

data class BioAgeUiState(
    val biologicalAge: Int? = null,
    val chronologicalAge: Int = 30,
    val ageDiff: Int = 0,
    val vo2Max: Float? = null,
    val disclaimer: String = "A wellness estimate based on published fitness research — not a medical or clinical measurement.",
    val source: String = "Reference: Nes BM et al. (2013), HUNT Fitness Study. Scand J Med Sci Sports 23(6):697–704.",
    val isLoading: Boolean = true
)

@HiltViewModel
class BiologicalAgeViewModel @Inject constructor(
    private val repository: HealthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(BioAgeUiState())
    val state: StateFlow<BioAgeUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        val chronologicalAge = UserPrefs.age(context)
        repository.latestScores().collect { scores ->
            val bioAge = scores?.biologicalAge
            _state.value = BioAgeUiState(
                biologicalAge = bioAge,
                chronologicalAge = chronologicalAge,
                ageDiff = if (bioAge != null) bioAge - chronologicalAge else 0,
                vo2Max = scores?.vo2MaxEstimate,
                isLoading = false
            )
        }
    }
}


// ─── Insights (B2 simulator wired) ────────────────────────────────────────────────────────────

data class InsightsUiState(
    val insights: List<CoachEngine.CoachInsight> = emptyList(),
    val simulatorSuggestions: List<ImprovementSimulator.Suggestion> = emptyList(),  // B2
    val earnedAchievements: List<AchievementEngine.Achievement> = emptyList(),      // B7
    val lockedAchievements: List<AchievementEngine.Achievement> = emptyList(),      // B7
    val isLoading: Boolean = true
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val repository: HealthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        combine(
            repository.latestScores(),
            perDay { day -> repository.metricsFrom(day - 14) },
            repository.earnedAchievements()
        ) { scores, metricsList, earned ->
            val todayMetrics = metricsList.lastOrNull()
            val rhrValues = metricsList.mapNotNull { it.restingHR?.toDouble() }
            val hrBaseline = if (rhrValues.size >= 3) rhrValues.average().toInt() else null
            // Improvement #5: use user's configured sleep need instead of hardcoded 8h.
            val sleepNeedMin = UserPrefs.sleepNeedMinutes(context)
            val sleepDebtMinutes = metricsList.takeLast(7).mapNotNull { it.sleepDurationMinutes }
                .sumOf { maxOf(0, sleepNeedMin - it) }.takeIf { metricsList.size >= 3 }

            // ── Coach insights ─────────────────────────────────────────
            val insights = scores?.let {
                CoachEngine.getDailyInsights(
                    CoachEngine.CoachInput(
                        recoveryScore     = it.recoveryScore,
                        readinessScore    = it.readinessScore,
                        sleepScore        = it.sleepScore,
                        stressScore       = it.stressScore,
                        activityScore     = it.activityScore,
                        acwrZone          = it.acwrZone,
                        restingHR         = todayMetrics?.restingHR,
                        hrBaseline        = hrBaseline,
                        sleepDebtMinutes  = sleepDebtMinutes,
                        daysWithoutTraining = null,
                        todaySleepHours   = todayMetrics?.sleepDurationMinutes?.let { m -> m / 60f },
                        vo2Max            = it.vo2MaxEstimate,
                        biologicalAge     = it.biologicalAge,
                        chronologicalAge  = UserPrefs.age(context)
                    )
                )
            } ?: emptyList()

            // ── B2: Simulator suggestions ─────────────────────────────
            val simulatorSuggestions: List<ImprovementSimulator.Suggestion> =
                if (scores != null && todayMetrics != null) {
                    val sleepHistory = metricsList.mapNotNull { m ->
                        m.sleepDurationMinutes?.let { dur ->
                            SleepData(
                                dateEpochDay        = m.dateEpochDay,
                                durationMinutes     = dur,
                                // Passed through as null when the source had no stage detail.
                                // Substituting a plausible-looking 83.0 made a night with no
                                // stages score as an average one — see SleepData.efficiencyPercent,
                                // which is nullable precisely so this cannot be fabricated.
                                efficiencyPercent   = m.sleepEfficiencyPercent,
                                bedtimeMinuteOfDay  = m.bedtimeMinuteOfDay,
                                wakeTimeMinuteOfDay = m.wakeTimeMinuteOfDay,
                                remMinutes          = m.sleepRemMinutes   ?: 0,
                                deepMinutes         = m.sleepDeepMinutes  ?: 0,
                                lightMinutes        = m.sleepLightMinutes ?: 0,
                                awakeMinutes        = m.sleepAwakeMinutes ?: 0
                            )
                        }
                    }
                    val hrHistory = metricsList.mapNotNull { m ->
                        m.restingHR?.let { hr -> RestingHRData(m.dateEpochDay, hr) }
                    }
                    val todaySleep = sleepHistory.lastOrNull()
                    val todayHR    = todayMetrics.restingHR?.let { RestingHRData(todayMetrics.dateEpochDay, it) }

                    if (todaySleep != null && todayHR != null &&
                        sleepHistory.size >= 2 && hrHistory.size >= 2) {
                        try {
                            ImprovementSimulator.simulate(
                                currentSleep    = todaySleep,
                                sleepBaseline14 = sleepHistory.takeLast(14),
                                currentHR       = todayHR,
                                hrBaseline30    = hrHistory.takeLast(30),
                                priorLoad       = null,
                                currentScore    = scores.recoveryScore ?: 50f
                            )
                        } catch (_: Exception) { emptyList() }
                    } else emptyList()
                } else emptyList()


            // ── B7: Achievements — earned (with real earned date) + locked ────
            val earnedIds = earned.map { it.achievementId }.toSet()
            val earnedAchievements = earned.map { e ->
                AchievementEngine.Achievement(
                    id = AchievementEngine.AchievementId.valueOf(e.achievementId),
                    title = e.title,
                    description = e.description,
                    icon = e.icon,
                    earnedEpochDay = e.earnedEpochDay
                )
            }
            val lockedAchievements = AchievementEngine.ALL_ACHIEVEMENTS
                .filterNot { it.id.name in earnedIds }

            InsightsUiState(
                insights             = insights,
                simulatorSuggestions = simulatorSuggestions,
                earnedAchievements   = earnedAchievements,
                lockedAchievements   = lockedAchievements,
                isLoading            = false
            )
        }.catch {
            _state.update { it.copy(isLoading = false) }
        }.collect { newState ->
            _state.value = newState
        }
    }
}

// ─── History ──────────────────────────────────────────────────────────────────

// Improvement #13: Filter enum for chip-based history filtering.
enum class HistoryFilter { THIS_WEEK, THIS_MONTH, ALL }

data class HistoryUiState(
    val scores: List<ComputedScoresEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val filter: HistoryFilter = HistoryFilter.ALL
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HealthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        load()
        syncMissingDays()
    }

    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 90) }.collect { scores ->
            _state.update { it.copy(scores = scores, isLoading = false) }
        }
    }

    /** User-triggered full 30-day backfill re-sync. */
    fun syncNow() {
        if (_state.value.isSyncing) return
        _state.update { it.copy(isSyncing = true, syncMessage = null) }
        viewModelScope.launch {
            try {
                val result = repository.backfillHistory(
                    userAge   = com.example.vitalcoreai.data.UserPrefs.age(context),
                    userMaxHR = com.example.vitalcoreai.data.UserPrefs.maxHR(context),
                    force     = true
                )
                val msg = when {
                    result.unavailable  -> "Health Connect is unavailable. Grant permissions in the Data Sources screen, then try again."
                    result.failedDays > 0 -> "${result.refreshedDays} days synced \u00b7 ${result.failedDays} could not be read. See Data Sources."
                    else -> "${result.refreshedDays} days refreshed \u00b7 history is up to date."
                }
                _state.update { it.copy(syncMessage = msg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncMessage = "Sync did not complete: ${e.message}") }
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    /** Quiet background sync on screen open so missing rows get filled automatically.
     *
     * If Health Connect is available and we detect 2 or more days in the last 30 that
     * have raw metrics but no computed scores (exactly what happened Sep 24–26), we
     * silently kick off a force backfill so the user sees data without having to tap
     * the refresh button manually.
     */
    private fun syncMissingDays() = viewModelScope.launch {
        try {
            val userAge   = com.example.vitalcoreai.data.UserPrefs.age(context)
            val userMaxHR = com.example.vitalcoreai.data.UserPrefs.maxHR(context)

            // First pass: normal syncToday now includes the score-gap scan internally.
            repository.syncToday(userAge = userAge, userMaxHR = userMaxHR)

            // Second pass: if many days are still scoreless, force a full backfill.
            // This covers the case where Samsung Health didn’t push sleep/HR for an
            // extended stretch and the score pipeline had no HR data to work with.
            val today    = com.example.vitalcoreai.core.time.VitalTime.todayEpochDay()
            val gapStart = today - HealthRepository.GAP_SCAN_DAYS
            val missingCount = repository.countDaysWithMissingScores(gapStart, today)
            if (missingCount >= 2) {
                _state.update { it.copy(isSyncing = true) }
                repository.backfillHistory(userAge = userAge, userMaxHR = userMaxHR, force = true)
            }
        } catch (_: Exception) { /* silent */ } finally {
            _state.update { it.copy(isSyncing = false) }
        }
    }

    fun clearSyncMessage() = _state.update { it.copy(syncMessage = null) }

    /** Improvement #13: filter the displayed history days by time window. */
    fun setFilter(filter: HistoryFilter) {
        _state.update { it.copy(filter = filter) }
    }
}

// ─── Weekly Report ────────────────────────────────────────────────────────────

@HiltViewModel
class WeeklyReportViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    val state = repository.latestWeeklyReport()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

// ─── Monthly Report ───────────────────────────────────────────────────────────

@HiltViewModel
class MonthlyReportViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    val state = repository.latestMonthlyReport()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/**
 * B4 — Notification preferences stored in SharedPreferences.
 * Each channel can be toggled on/off independently per B4 spec.
 */
data class SettingsUiState(
    val userAge: Int = 30,
    val userMaxHR: Int = 190,
    val personalSleepNeedHours: Float = 8f,
    val syncPeriodHours: Int = 4,
    // B4 notification toggles (default all ON)
    val notifyDailySummary: Boolean = true,
    val notifyWeeklyReport: Boolean = true,
    val notifyAchievements: Boolean = true,
    val notifyCoachAlerts: Boolean = true,
    // Backfill state
    val isBackfilling: Boolean = false,
    val backfillResult: String? = null,
    val backfillNeedsAttention: Boolean = false,
    // B9 — CSV export state
    val isExporting: Boolean = false,
    val exportResult: String? = null,
    // T-17 — biometric lock
    val biometricLockEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val trainingGoal: String = UserPrefs.DEFAULT_TRAINING_GOAL
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: HealthRepository,
    private val exporter: com.example.vitalcoreai.data.export.HealthDataExporter
) : ViewModel() {

    // Improvement #2: User-profile keys (age, maxHR, sleepNeed) are now owned exclusively
    // by UserPrefs. Only non-profile keys that UserPrefs doesn't cover remain here, all
    // sharing the same UserPrefs.PREFS_NAME file to avoid split-brain reads.
    companion object {
        private const val KEY_SYNC_PERIOD   = "sync_period_hours"
        private const val KEY_NOTIFY_DAILY  = "notify_daily_summary"
        private const val KEY_NOTIFY_WEEKLY = "notify_weekly_report"
        private const val KEY_NOTIFY_ACHIEVEMENTS = "notify_achievements"
        private const val KEY_NOTIFY_COACH  = "notify_coach_alerts"
    }

    // All reads/writes use UserPrefs.PREFS_NAME — single source of truth (#2).
    private val prefs = context.getSharedPreferences(UserPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun loadFromPrefs() = SettingsUiState(
        userAge                = UserPrefs.age(context),
        userMaxHR              = UserPrefs.maxHR(context),
        personalSleepNeedHours = UserPrefs.sleepNeedHours(context),
        syncPeriodHours = prefs.getInt(KEY_SYNC_PERIOD, 4),
        notifyDailySummary = prefs.getBoolean(KEY_NOTIFY_DAILY, true),
        notifyWeeklyReport = prefs.getBoolean(KEY_NOTIFY_WEEKLY, true),
        notifyAchievements = prefs.getBoolean(KEY_NOTIFY_ACHIEVEMENTS, true),
        notifyCoachAlerts = prefs.getBoolean(KEY_NOTIFY_COACH, true),
        biometricLockEnabled = UserPrefs.biometricLockEnabled(context),
        biometricAvailable = com.example.vitalcoreai.security.BiometricLock
            .availability(context) == com.example.vitalcoreai.security.BiometricLock.Availability.AVAILABLE,
        trainingGoal = UserPrefs.trainingGoal(context)
    )

    /**
     * T-17. Written through [UserPrefs] rather than this class private prefs handle so the
     * key has exactly one definition — SettingsViewModel duplicating UserPrefs key strings
     * is how sleep_need_hours ended up written as a Float and read as an Int.
     */
    fun setBiometricLock(enabled: Boolean) {
        UserPrefs.setBiometricLockEnabled(context, enabled)
        _state.update { it.copy(biometricLockEnabled = enabled) }
    }

    fun setTrainingGoal(goal: String) {
        UserPrefs.setTrainingGoal(context, goal)
        _state.update { it.copy(trainingGoal = goal) }
    }

    // Write through the exact same key names UserPrefs uses, via the shared prefs file.
    fun setAge(age: Int) {
        _state.update { it.copy(userAge = age) }
        prefs.edit().putInt("user_age", age).apply()
    }
    fun setMaxHR(hr: Int) {
        _state.update { it.copy(userMaxHR = hr) }
        prefs.edit().putInt("user_max_hr", hr).apply()
    }
    fun setSleepNeed(hours: Float) {
        _state.update { it.copy(personalSleepNeedHours = hours) }
        // Written as Float with the same key UserPrefs reads — no more type mismatch (#2).
        prefs.edit().putFloat("sleep_need_hours", hours).apply()
    }
    fun toggleDailySummary(on: Boolean) {
        _state.update { it.copy(notifyDailySummary = on) }
        prefs.edit().putBoolean(KEY_NOTIFY_DAILY, on).apply()
    }
    fun toggleWeeklyReport(on: Boolean) {
        _state.update { it.copy(notifyWeeklyReport = on) }
        prefs.edit().putBoolean(KEY_NOTIFY_WEEKLY, on).apply()
    }
    fun toggleAchievements(on: Boolean) {
        _state.update { it.copy(notifyAchievements = on) }
        prefs.edit().putBoolean(KEY_NOTIFY_ACHIEVEMENTS, on).apply()
    }
    fun toggleCoachAlerts(on: Boolean) {
        _state.update { it.copy(notifyCoachAlerts = on) }
        prefs.edit().putBoolean(KEY_NOTIFY_COACH, on).apply()
    }

    /** Re-read existing days as well as gaps; retain the completion flag on cancellation. */
    fun forceBackfill() {
        if (_state.value.isBackfilling) return
        _state.update { it.copy(isBackfilling = true, backfillResult = null, backfillNeedsAttention = false) }
        viewModelScope.launch {
            try {
                val result = repository.backfillHistory(
                    userAge   = _state.value.userAge,
                    userMaxHR = _state.value.userMaxHR,
                    force = true
                )
                _state.update { it.copy(
                    backfillResult = historyRefreshMessage(result),
                    backfillNeedsAttention = result.unavailable || result.failedDays > 0 || result.partialDays > 0
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(
                    backfillResult = "History refresh did not finish. Review Data Sources and retry; saved entries remain available.",
                    backfillNeedsAttention = true
                ) }
            } finally {
                _state.update { it.copy(isBackfilling = false) }
            }
        }
    }

    /** B9 — Export daily metrics, scores, and exercise sessions to CSV and open the share sheet. */
    fun exportCsv() {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true, exportResult = null) }
        viewModelScope.launch {
            try {
                val uris = exporter.exportAll(context)
                val shareIntent = exporter.createShareIntentMultiple(uris).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Export health data").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                _state.update { it.copy(isExporting = false, exportResult = "✓ Export ready — choose where to save") }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, exportResult = "✗ ${e.message}") }
            }
        }
    }
}

// ─── Day Detail ───────────────────────────────────────────────────────────────

data class DayDetailUiState(
    val dateEpochDay: Long = 0L,
    val recoveryScore: Float? = null,
    val recoveryExplanation: String? = null,
    val readinessScore: Float? = null,
    val sleepScore: Float? = null,
    val sleepExplanation: String? = null,
    val activityScore: Float? = null,
    val stressScore: Float? = null,
    val restingHR: Int? = null,
    val restingHRDerived: Boolean = false,
    val steps: Int? = null,
    val distanceMeters: Float? = null,
    val caloriesBurned: Int? = null,
    val activeCalories: Int? = null,
    val sleepDurationMinutes: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val sleepEfficiencyPercent: Double? = null,
    val sleepStagesAvailable: Boolean = false,
    val bedtimeMinuteOfDay: Int? = null,
    val wakeTimeMinuteOfDay: Int? = null,
    val spO2Percent: Float? = null,
    val spO2ReadingCount: Int? = null,
    val floorsClimbed: Int? = null,
    val weightKg: Float? = null,
    val hrvRmssdMs: Double? = null,
    val strain: Float? = null,
    val acwr: Float? = null,
    val acwrZone: String? = null,
    val workouts: List<com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class DayDetailViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val dailyMetricsDao: com.example.vitalcoreai.data.db.dao.DailyMetricsDao,
    private val computedScoresDao: com.example.vitalcoreai.data.db.dao.ComputedScoresDao,
    private val exerciseSessionDao: com.example.vitalcoreai.data.db.dao.ExerciseSessionDao
) : ViewModel() {

    private val epochDay: Long = savedStateHandle.get<Long>("epochDay") ?: 0L

    private val _state = MutableStateFlow(DayDetailUiState(dateEpochDay = epochDay))
    val state: StateFlow<DayDetailUiState> = _state.asStateFlow()

    init { load() }

    // Improvement #4: Flow-based queries so the UI re-renders if the DB is updated in background.
    private fun load() = viewModelScope.launch {
        combine(
            dailyMetricsDao.getFrom(epochDay).map { it.firstOrNull { m -> m.dateEpochDay == epochDay } },
            computedScoresDao.getFrom(epochDay).map { it.firstOrNull { s -> s.dateEpochDay == epochDay } },
            exerciseSessionDao.getFrom(epochDay).map { it.filter { e -> e.dateEpochDay == epochDay } }
        ) { metrics, scores, workouts ->
            DayDetailUiState(
                dateEpochDay          = epochDay,
                recoveryScore         = scores?.recoveryScore,
                recoveryExplanation   = scores?.recoveryExplanation,
                readinessScore        = scores?.readinessScore,
                sleepScore            = scores?.sleepScore,
                sleepExplanation      = scores?.sleepExplanation,
                activityScore         = scores?.activityScore,
                stressScore           = scores?.stressScore,
                restingHR             = metrics?.restingHR,
                restingHRDerived      = metrics?.restingHRDerived ?: false,
                steps                 = metrics?.steps,
                distanceMeters        = metrics?.distanceMeters,
                caloriesBurned        = metrics?.caloriesBurned,
                activeCalories        = metrics?.activeCalories,
                sleepDurationMinutes  = metrics?.sleepDurationMinutes,
                sleepDeepMinutes      = metrics?.sleepDeepMinutes,
                sleepRemMinutes       = metrics?.sleepRemMinutes,
                sleepLightMinutes     = metrics?.sleepLightMinutes,
                sleepAwakeMinutes     = metrics?.sleepAwakeMinutes,
                sleepEfficiencyPercent= metrics?.sleepEfficiencyPercent,
                sleepStagesAvailable  = metrics?.sleepStagesAvailable ?: false,
                bedtimeMinuteOfDay    = metrics?.bedtimeMinuteOfDay,
                wakeTimeMinuteOfDay   = metrics?.wakeTimeMinuteOfDay,
                spO2Percent           = metrics?.spO2Percent,
                spO2ReadingCount      = metrics?.spO2ReadingCount,
                floorsClimbed         = metrics?.floorsClimbed,
                weightKg              = metrics?.weightKg,
                hrvRmssdMs            = metrics?.hrvRmssdMs,
                strain                = scores?.strain,
                acwr                  = scores?.acwr,
                acwrZone              = scores?.acwrZone,
                workouts              = workouts,
                isLoading             = false
            )
        }.collect { _state.value = it }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun trendOf(values: List<Float>): TrendDirection {
    if (values.size < 3) return TrendDirection.NEUTRAL
    val recent = values.takeLast(3).average()
    val prior = values.dropLast(3).takeLast(3).average()
    return when {
        recent > prior + 3 -> TrendDirection.UP
        recent < prior - 3 -> TrendDirection.DOWN
        else -> TrendDirection.NEUTRAL
    }
}

private fun List<Float>.averageOrNull(): Double? =
    if (isEmpty()) null else average()
