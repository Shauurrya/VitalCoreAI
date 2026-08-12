package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
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
    val isLoading: Boolean = true
)

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

    init { load() }
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

    init { loadSleepDebt() }
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
            _state.value = HeartUiState(
                restingHR = metrics.lastOrNull()?.restingHR,
                avgHR7Day = rhrValues.takeLast(7).averageOrNull()?.toFloat(),
                avgHR30Day = rhrValues.averageOrNull()?.toFloat(),
                chartValues = rhrValues.takeLast(30),
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
    val acwr: Float? = null,
    val acwrZone: String? = null,
    val chartValues: List<Float> = emptyList(),
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
                acwr = latest?.acwr,
                acwrZone = latest?.acwrZone,
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
            val sleepNeedMin = 480
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

data class HistoryUiState(
    val scores: List<ComputedScoresEntity> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()
    init { load() }
    private fun load() = viewModelScope.launch {
        perDay { day -> repository.scoresFrom(day - 90) }.collect { scores ->
            _state.value = HistoryUiState(scores = scores, isLoading = false)
        }
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

    companion object {
        private const val PREFS_NAME = "vitalcore_settings"
        private const val KEY_AGE = "user_age"
        private const val KEY_MAX_HR = "user_max_hr"
        private const val KEY_SLEEP_NEED = "sleep_need_hours"
        private const val KEY_SYNC_PERIOD = "sync_period_hours"
        private const val KEY_NOTIFY_DAILY = "notify_daily_summary"
        private const val KEY_NOTIFY_WEEKLY = "notify_weekly_report"
        private const val KEY_NOTIFY_ACHIEVEMENTS = "notify_achievements"
        private const val KEY_NOTIFY_COACH = "notify_coach_alerts"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun loadFromPrefs() = SettingsUiState(
        userAge = prefs.getInt(KEY_AGE, 30),
        userMaxHR = prefs.getInt(KEY_MAX_HR, 190),
        personalSleepNeedHours = prefs.getFloat(KEY_SLEEP_NEED, 8f),
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

    fun setAge(age: Int) {
        _state.update { it.copy(userAge = age) }
        prefs.edit().putInt(KEY_AGE, age).apply()
    }
    fun setMaxHR(hr: Int) {
        _state.update { it.copy(userMaxHR = hr) }
        prefs.edit().putInt(KEY_MAX_HR, hr).apply()
    }
    fun setSleepNeed(hours: Float) {
        _state.update { it.copy(personalSleepNeedHours = hours) }
        prefs.edit().putFloat(KEY_SLEEP_NEED, hours).apply()
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

    /** Force a full 30-day re-backfill — wipes the backfill completion flag first. */
    fun forceBackfill() {
        if (_state.value.isBackfilling) return
        _state.update { it.copy(isBackfilling = true, backfillResult = null) }
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("vitalcore_sync", Context.MODE_PRIVATE)
                prefs.edit().remove("backfill_done_v1").apply()
                repository.backfillHistory(
                    userAge   = _state.value.userAge,
                    userMaxHR = _state.value.userMaxHR
                )
                _state.update { it.copy(isBackfilling = false, backfillResult = "✓ Backfill complete") }
            } catch (e: Exception) {
                _state.update { it.copy(isBackfilling = false, backfillResult = "✗ ${e.message}") }
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
