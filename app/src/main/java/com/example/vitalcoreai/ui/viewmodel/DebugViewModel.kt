package com.example.vitalcoreai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.debug.DebugRepository
import com.example.vitalcoreai.debug.ErrorLog
import com.example.vitalcoreai.debug.SyntheticDataGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val snapshot: DebugRepository.Snapshot? = null,
    val scenarios: List<SyntheticDataGenerator.Descriptor> = SyntheticDataGenerator.DESCRIPTORS,
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val lastActionResult: String? = null,
    val showCoachContext: Boolean = false
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val debugRepository: DebugRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        val snapshot = runCatching { debugRepository.snapshot() }
            .onFailure { ErrorLog.record("DebugViewModel.refresh", it) }
            .getOrNull()
        _state.update {
            it.copy(
                snapshot = snapshot,
                scenarios = SyntheticDataGenerator.DESCRIPTORS,
                isLoading = false
            )
        }
    }

    fun loadScenario(id: String) = viewModelScope.launch {
        _state.update { it.copy(isWorking = true, lastActionResult = null) }
        val result = debugRepository.loadScenario(id)
        _state.update { it.copy(isWorking = false, lastActionResult = result) }
        refresh()
    }

    fun restoreRealClock() {
        debugRepository.restoreRealClock()
        _state.update { it.copy(lastActionResult = "Clock restored to the device zone.") }
        refresh()
    }

    fun toggleCoachContext() {
        _state.update { it.copy(showCoachContext = !it.showCoachContext) }
    }

    fun clearErrors() {
        ErrorLog.clear()
        refresh()
    }
}
