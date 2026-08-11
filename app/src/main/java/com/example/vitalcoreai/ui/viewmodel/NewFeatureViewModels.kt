package com.example.vitalcoreai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.analytics.HabitCorrelationEngine
import com.example.vitalcoreai.analytics.MuscleRecoveryEngine
import com.example.vitalcoreai.data.db.entity.CheckInEntity
import com.example.vitalcoreai.data.db.entity.JournalEntryEntity
import com.example.vitalcoreai.data.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// ─── Check-In ViewModel (Part 12) ─────────────────────────────────────────────

data class CheckInUiState(
    val energy: Int = 5,
    val stress: Int = 5,
    val soreness: Int = 3,
    val sleepQuality: Int = 5,
    val mood: Int = 5,
    val motivation: Int = 5,
    val illnessFlag: Boolean = false,
    val notes: String = "",
    val alreadyCheckedIn: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class CheckInViewModel @Inject constructor(
    private val repository: HealthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    init { loadExisting() }

    private fun loadExisting() = viewModelScope.launch {
        val today = LocalDate.now().toEpochDay()
        val existing = repository.getCheckInForDay(today)
        if (existing != null) {
            _state.value = CheckInUiState(
                energy = existing.energy,
                stress = existing.stress,
                soreness = existing.soreness,
                sleepQuality = existing.sleepQuality,
                mood = existing.mood,
                motivation = existing.motivation ?: 5,
                illnessFlag = existing.illnessFlag,
                notes = existing.notes ?: "",
                alreadyCheckedIn = true
            )
        }
    }

    fun setEnergy(v: Int)       { _state.update { it.copy(energy = v) } }
    fun setStress(v: Int)       { _state.update { it.copy(stress = v) } }
    fun setSoreness(v: Int)     { _state.update { it.copy(soreness = v) } }
    fun setSleepQuality(v: Int) { _state.update { it.copy(sleepQuality = v) } }
    fun setMood(v: Int)         { _state.update { it.copy(mood = v) } }
    fun setMotivation(v: Int)   { _state.update { it.copy(motivation = v) } }
    fun toggleIllness()         { _state.update { it.copy(illnessFlag = !it.illnessFlag) } }
    fun setNotes(v: String)     { _state.update { it.copy(notes = v) } }

    fun save() {
        val s = _state.value
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            repository.saveCheckIn(
                CheckInEntity(
                    dateEpochDay = LocalDate.now().toEpochDay(),
                    energy = s.energy,
                    stress = s.stress,
                    soreness = s.soreness,
                    sleepQuality = s.sleepQuality,
                    mood = s.mood,
                    motivation = s.motivation,
                    illnessFlag = s.illnessFlag,
                    notes = s.notes.ifBlank { null }
                )
            )
            _state.update { it.copy(isSaving = false, saveSuccess = true, alreadyCheckedIn = true) }
        }
    }
}

// ─── Journal ViewModel (Part 13) ──────────────────────────────────────────────

data class JournalUiState(
    val todayEntries: List<JournalEntryEntity> = emptyList(),
    val availableHabits: List<HabitCorrelationEngine.HabitDefinition> = HabitCorrelationEngine.BUILT_IN_HABITS,
    val correlations: List<HabitCorrelationEngine.CorrelationResult> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val selectedHabitId: String = HabitCorrelationEngine.BUILT_IN_HABITS.first().id,
    val entryValue: Float = 1f,
    val entryNotes: String = ""
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val repository: HealthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(JournalUiState())
    val state: StateFlow<JournalUiState> = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        val today = LocalDate.now().toEpochDay()
        val todayEntries = repository.getJournalForDay(today)
        _state.update { it.copy(todayEntries = todayEntries, isLoading = false) }
        computeCorrelations()
    }

    fun selectHabit(habitId: String) {
        _state.update { it.copy(selectedHabitId = habitId) }
    }
    fun setEntryValue(v: Float) { _state.update { it.copy(entryValue = v) } }
    fun setEntryNotes(v: String) { _state.update { it.copy(entryNotes = v) } }

    fun logEntry() {
        val s = _state.value
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val habit = HabitCorrelationEngine.getHabitDefinition(s.selectedHabitId)
            repository.logJournalEntry(
                JournalEntryEntity(
                    dateEpochDay = LocalDate.now().toEpochDay(),
                    habitId = s.selectedHabitId,
                    value = s.entryValue,
                    unit = habit?.unit ?: "",
                    notes = s.entryNotes.ifBlank { null }
                )
            )
            // Refresh
            val todayEntries = repository.getJournalForDay(LocalDate.now().toEpochDay())
            _state.update { it.copy(isSaving = false, todayEntries = todayEntries, entryValue = 1f, entryNotes = "") }
        }
    }

    fun deleteEntry(id: Long) = viewModelScope.launch {
        repository.deleteJournalEntry(id)
        val todayEntries = repository.getJournalForDay(LocalDate.now().toEpochDay())
        _state.update { it.copy(todayEntries = todayEntries) }
    }

    private suspend fun computeCorrelations() {
        val habitIds = repository.getAllTrackedHabitIds()
        if (habitIds.isEmpty()) return

        val startDay = LocalDate.now().minusDays(90).toEpochDay()

        // Build per-habit daily value maps
        val habitsData = mutableMapOf<String, Map<Long, Float>>()
        for (habitId in habitIds) {
            val entries = repository.getJournalByHabitFrom(habitId, startDay)
            // Average entries per day
            val dailyMap = entries.groupBy { it.dateEpochDay }
                .mapValues { (_, entries) -> entries.map { it.value }.average().toFloat() }
            habitsData[habitId] = dailyMap
        }

        // Build outcome maps from scores
        val scores = repository.getLatestScoresN(90)
        val outcomes = mapOf(
            "Sleep Score" to scores.mapNotNull { s -> s.sleepScore?.let { s.dateEpochDay to it } }.toMap(),
            "Recovery Score" to scores.mapNotNull { s -> s.recoveryScore?.let { s.dateEpochDay to it } }.toMap(),
            "Energy Bank" to scores.mapNotNull { s -> s.energyBankScore?.let { s.dateEpochDay to it } }.toMap()
        )

        val results = HabitCorrelationEngine.batchCorrelate(habitsData, outcomes)
        _state.update { it.copy(correlations = results) }
    }
}

// ─── Muscle Recovery ViewModel (Part 9) ────────────────────────────────────────

data class MuscleRecoveryUiState(
    val muscleStatuses: List<MuscleRecoveryEngine.MuscleStatus> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class MuscleRecoveryViewModel @Inject constructor(
    private val repository: HealthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MuscleRecoveryUiState())
    val state: StateFlow<MuscleRecoveryUiState> = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        val today = LocalDate.now().toEpochDay()
        val startDay = today - 14
        // Use exercise sessions from the last 14 days
        repository.exerciseFrom(startDay).collect { sessions ->
            val checkIn = repository.getCheckInForDay(today)
            val sessionInfos = sessions.map { session ->
                val groups = if (session.muscleGroups != null) {
                    session.muscleGroups.split("|").mapNotNull { g ->
                        try { MuscleRecoveryEngine.MuscleGroup.valueOf(g) } catch (_: Exception) { null }
                    }
                } else {
                    MuscleRecoveryEngine.exerciseToMuscleGroups(session.exerciseType)
                }
                MuscleRecoveryEngine.SessionInfo(
                    dateEpochDay = session.dateEpochDay,
                    exerciseType = session.exerciseType,
                    rpe = session.rpe ?: 5,
                    muscleGroups = groups
                )
            }
            val statuses = MuscleRecoveryEngine.getAllMuscleStatuses(
                recentSessions = sessionInfos,
                todayEpochDay = today,
                sorenessRating = checkIn?.soreness
            )
            _state.value = MuscleRecoveryUiState(muscleStatuses = statuses, isLoading = false)
        }
    }
}

// ─── Energy Bank ViewModel (Part 11) ──────────────────────────────────────────

data class EnergyBankUiState(
    val score: Float? = null,
    val explanation: String = "",
    val breakdown: List<com.example.vitalcoreai.analytics.ScoreFactor> = emptyList(),
    val chartValues: List<Float> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class EnergyBankViewModel @Inject constructor(
    private val repository: HealthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EnergyBankUiState())
    val state: StateFlow<EnergyBankUiState> = _state.asStateFlow()

    init { load() }

    private fun load() = viewModelScope.launch {
        repository.scoresFrom(LocalDate.now().minusDays(30).toEpochDay()).collect { scores ->
            val latest = scores.lastOrNull()
            _state.value = EnergyBankUiState(
                score = latest?.energyBankScore,
                explanation = latest?.energyBankExplanation ?: "Sync health data to compute your energy bank.",
                breakdown = com.example.vitalcoreai.analytics.decodeScoreFactors(latest?.energyBankBreakdown),
                chartValues = scores.mapNotNull { it.energyBankScore }.takeLast(14),
                isLoading = false
            )
        }
    }
}
