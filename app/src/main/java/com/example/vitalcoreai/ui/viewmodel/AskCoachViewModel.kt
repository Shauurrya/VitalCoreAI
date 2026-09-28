package com.example.vitalcoreai.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.coach.CoachAnswerEngine
import com.example.vitalcoreai.coach.CoachContext
import com.example.vitalcoreai.coach.CoachContextFactory
import com.example.vitalcoreai.coach.CoachIntent
import com.example.vitalcoreai.core.time.perDay
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AskCoachUiState(
    val selected: CoachIntent = CoachIntent.WHY_AM_I_LOW,
    val context: CoachContext? = null,
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val answer: CoachAnswerEngine.Answer? get() = context?.let { CoachAnswerEngine.answer(selected, it) }

    val confidence: String get() {
        val c = context ?: return "LOW"
        if (answer?.hasSufficientData != true || c.evidence?.staleMetrics?.isNotEmpty() == true) return "LOW"
        return when (selected) {
            CoachIntent.WHY_AM_I_LOW -> c.readiness?.confidence
            CoachIntent.WHAT_SHOULD_I_TRAIN -> c.recommendation?.confidence
            CoachIntent.WHAT_TONIGHT -> c.forecast?.confidence ?: c.confidence.overall
            CoachIntent.WEEKLY_REVIEW -> c.trends.firstOrNull { it.window.startsWith("7") }?.confidence
            else -> c.confidence.overall
        } ?: "LOW"
    }
}

data class CoachEvidenceLink(val label: String, val route: String)

/** Every offered link is a real, registered reading destination. */
fun coachEvidenceLinks(citations: List<String>): List<CoachEvidenceLink> = citations.map { citation ->
    when (citation.substringBefore('.')) {
        "readiness" -> CoachEvidenceLink("Readiness and score factors", Routes.READINESS)
        "sleep" -> CoachEvidenceLink("Sleep readings", Routes.SLEEP)
        "rhr" -> CoachEvidenceLink("Resting heart rate", Routes.HEART)
        "training_load" -> CoachEvidenceLink("Training load", Routes.TRAINING)
        "muscle_recovery" -> CoachEvidenceLink("Muscle recovery", Routes.MUSCLE_RECOVERY)
        "recommendation" -> CoachEvidenceLink("Today's action plan", Routes.HOME)
        "forecast" -> CoachEvidenceLink("Tomorrow's outlook", Routes.FORECAST)
        "journal" -> CoachEvidenceLink("Your check-in", Routes.CHECK_IN)
        "trends" -> CoachEvidenceLink("Recorded score history", Routes.HISTORY)
        "insights", "anomalies" -> CoachEvidenceLink("Insights and patterns", Routes.INSIGHTS)
        else -> CoachEvidenceLink("Data Sources and freshness", Routes.DATA_SOURCES)
    }
}.plus(CoachEvidenceLink("Data Sources and freshness", Routes.DATA_SOURCES)).distinctBy { it.route }

@HiltViewModel
class AskCoachViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val contextFactory: CoachContextFactory,
    repository: HealthRepository
) : ViewModel() {
    private val selected = CoachIntent.entries.firstOrNull {
        it.name == savedStateHandle.get<String>("coach_question") && it != CoachIntent.FREEFORM
    } ?: CoachIntent.WHY_AM_I_LOW
    private val _state = MutableStateFlow(AskCoachUiState(selected = selected))
    val state = _state.asStateFlow()
    private val refreshRequests = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                perDay { today ->
                    combine(
                        repository.scoresFrom(today - 30),
                        repository.metricsFrom(today - 30),
                        repository.checkInsFrom(today - 30),
                        repository.journalEntriesFrom(today - 30)
                    ) { _, _, _, _ -> today }
                },
                refreshRequests
            ) { today, _ -> today }.collectLatest { today ->
                _state.update { it.copy(isLoading = true, error = null) }
                try {
                    val context = withContext(Dispatchers.Default) { contextFactory.build(today) }
                    _state.update { it.copy(context = context, isLoading = false, error = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(context = null, isLoading = false, error = "Couldn't load your saved readings. Try again.") }
                }
            }
        }
    }

    fun select(intent: CoachIntent) {
        if (intent == CoachIntent.FREEFORM) return
        savedStateHandle["coach_question"] = intent.name
        _state.update { it.copy(selected = intent) }
    }

    fun refresh() { refreshRequests.update { it + 1 } }
}
