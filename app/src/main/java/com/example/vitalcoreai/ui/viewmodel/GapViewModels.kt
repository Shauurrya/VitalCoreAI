package com.example.vitalcoreai.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.analytics.BaselineManager
import com.example.vitalcoreai.analytics.HRRecoveryCalculator
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.db.entity.*
import com.example.vitalcoreai.data.model.RestingHRData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Workout History ViewModel (Part 17 — screen 6)
// ─────────────────────────────────────────────────────────────────────────────

data class WorkoutHistoryState(
    val workouts: List<ExerciseSessionEntity> = emptyList(),
    val filterType: String? = null,  // null = all
    val isLoading: Boolean = true
)

@HiltViewModel
class WorkoutHistoryViewModel @Inject constructor(
    private val exerciseSessionDao: ExerciseSessionDao
) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutHistoryState())
    val state: StateFlow<WorkoutHistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            exerciseSessionDao.getAllFlow().collect { sessions ->
                val filtered = _state.value.filterType?.let { filter ->
                    sessions.filter { it.exerciseType.contains(filter, ignoreCase = true) }
                } ?: sessions
                _state.value = _state.value.copy(workouts = filtered, isLoading = false)
            }
        }
    }

    fun setFilter(type: String?) {
        _state.update { st ->
            val all = st.workouts // will be re-collected
            st.copy(filterType = type)
        }
        // Re-trigger collection
        viewModelScope.launch {
            val sessions = exerciseSessionDao.getLatest(500)
            val filtered = type?.let { filter ->
                sessions.filter { it.exerciseType.contains(filter, ignoreCase = true) }
            } ?: sessions
            _state.value = _state.value.copy(workouts = filtered)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Workout Detail ViewModel (Part 17 — screen 7)
// ─────────────────────────────────────────────────────────────────────────────

data class WorkoutDetailState(
    val session: ExerciseSessionEntity? = null,
    val exercises: List<WorkoutExerciseEntity> = emptyList(),
    val hrSamples: List<HeartRateSampleEntity> = emptyList(),
    val hrr1: Int? = null,
    val hrr2: Int? = null,
    val hrrAssessment: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseSessionDao: ExerciseSessionDao,
    private val workoutExerciseDao: WorkoutExerciseDao,
    private val heartRateSampleDao: HeartRateSampleDao,
    private val computedScoresDao: ComputedScoresDao
) : ViewModel() {
    private val sessionStartMs: Long = savedStateHandle.get<Long>("sessionStartMs") ?: 0L

    private val _state = MutableStateFlow(WorkoutDetailState())
    val state: StateFlow<WorkoutDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val session = exerciseSessionDao.getByStartMs(sessionStartMs)
            if (session != null) {
                val exercises = workoutExerciseDao.getForSession(sessionStartMs)
                val hrSamples = heartRateSampleDao.getForDay(session.dateEpochDay)
                    .filter { it.timestampMs in session.startMs..session.endMs }

                // Compute HRR for this session
                val postWorkoutHR = heartRateSampleDao.getForDay(session.dateEpochDay)
                    .filter { it.timestampMs > session.endMs }
                    .sortedBy { it.timestampMs }
                    .map { ((it.timestampMs - session.endMs) / 1000).toInt() to it.bpm }
                    .filter { it.first in 0..180 }

                val hrrResult = if (session.maxHR != null && postWorkoutHR.isNotEmpty()) {
                    val historical = computedScoresDao.getLatestN(30).mapNotNull { it.hrr1 }
                    HRRecoveryCalculator.calculate(session.maxHR!!, postWorkoutHR, historical)
                } else null

                _state.value = WorkoutDetailState(
                    session = session,
                    exercises = exercises,
                    hrSamples = hrSamples,
                    hrr1 = hrrResult?.hrr1,
                    hrr2 = hrrResult?.hrr2,
                    hrrAssessment = hrrResult?.assessment ?: "",
                    isLoading = false
                )
            } else {
                _state.value = WorkoutDetailState(isLoading = false)
            }
        }
    }

    fun addExercise(name: String, sets: Int, reps: Int, weightKg: Float, rpe: Int?, muscleGroups: String) {
        viewModelScope.launch {
            val currentExercises = _state.value.exercises
            val entity = WorkoutExerciseEntity(
                sessionStartMs = sessionStartMs,
                exerciseName = name,
                sets = sets,
                reps = reps,
                weightKg = weightKg,
                volume = sets * reps * weightKg,
                rpe = rpe,
                muscleGroups = muscleGroups,
                orderIndex = currentExercises.size
            )
            workoutExerciseDao.insert(entity)
            val updated = workoutExerciseDao.getForSession(sessionStartMs)
            _state.update { it.copy(exercises = updated) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Heart Rate Recovery ViewModel (Part 7 — dedicated screen)
// ─────────────────────────────────────────────────────────────────────────────

data class HRRScreenState(
    val latestHrr1: Int? = null,
    val latestHrr2: Int? = null,
    val trend: String = "STABLE",
    val assessment: String = "",
    val history: List<Pair<String, Int>> = emptyList(), // (date label, hrr1)
    val personalBaseline: Int? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class HRRViewModel @Inject constructor(
    private val computedScoresDao: ComputedScoresDao
) : ViewModel() {
    private val _state = MutableStateFlow(HRRScreenState())
    val state: StateFlow<HRRScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val scores = computedScoresDao.getLatestN(30)
            val hrrHistory = scores.filter { it.hrr1 != null }.map { score ->
                val date = LocalDate.ofEpochDay(score.dateEpochDay)
                    .format(DateTimeFormatter.ofPattern("M/d"))
                date to score.hrr1!!
            }.reversed() // oldest first for chart

            val latest = scores.firstOrNull { it.hrr1 != null }
            val baselineValues = scores.mapNotNull { it.hrr1 }
            val baseline = if (baselineValues.size >= 5) baselineValues.average().toInt() else null

            val assessment = latest?.hrr1?.let { hrr1 ->
                when {
                    hrr1 >= 40 -> "Excellent"
                    hrr1 >= 25 -> "Good"
                    hrr1 >= 12 -> "Normal"
                    else -> "Below Normal"
                }
            } ?: "Insufficient data"

            _state.value = HRRScreenState(
                latestHrr1 = latest?.hrr1,
                latestHrr2 = latest?.hrr2,
                trend = latest?.hrrTrend ?: "STABLE",
                assessment = assessment,
                history = hrrHistory,
                personalBaseline = baseline,
                isLoading = false
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Personal Baselines ViewModel (Part 5 — dedicated screen)
// ─────────────────────────────────────────────────────────────────────────────

data class BaselineItem(
    val name: String,
    val currentValue: String,
    val baselineValue: String,
    val deviation: String,
    val deviationPercent: Float,
    val trend: String, // "↑" "↓" "→"
    val calibrationDays: Int,
    val confidence: String // HIGH/MEDIUM/LOW
)

data class BaselinesScreenState(
    val baselines: List<BaselineItem> = emptyList(),
    val overallCalibrationDays: Int = 0,
    val calibrationStatus: String = "Learning your baseline",
    val isLoading: Boolean = true
)

@HiltViewModel
class BaselinesViewModel @Inject constructor(
    private val dailyMetricsDao: DailyMetricsDao,
    private val exerciseSessionDao: ExerciseSessionDao,
    private val computedScoresDao: ComputedScoresDao,
    private val checkInDao: CheckInDao
) : ViewModel() {
    private val _state = MutableStateFlow(BaselinesScreenState())
    val state: StateFlow<BaselinesScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val past30 = dailyMetricsDao.getLatest(30)
            val today = LocalDate.now().toEpochDay()
            val scores = computedScoresDao.getLatestN(30)
            val items = mutableListOf<BaselineItem>()

            // RHR baseline
            val rhrValues = past30.mapNotNull { it.restingHR }
            if (rhrValues.isNotEmpty()) {
                val current = rhrValues.first()
                val avg = rhrValues.average()
                val dev = current - avg
                items += BaselineItem(
                    name = "Resting Heart Rate",
                    currentValue = "$current bpm",
                    baselineValue = "${avg.toInt()} bpm",
                    deviation = "${if (dev >= 0) "+" else ""}${dev.toInt()} bpm",
                    deviationPercent = ((dev / avg) * 100).toFloat(),
                    trend = if (dev > 2) "↑" else if (dev < -2) "↓" else "→",
                    calibrationDays = rhrValues.size,
                    confidence = if (rhrValues.size >= 14) "HIGH" else if (rhrValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            // Sleep duration baseline
            val sleepValues = past30.mapNotNull { it.sleepDurationMinutes }
            if (sleepValues.isNotEmpty()) {
                val current = sleepValues.first()
                val avg = sleepValues.average()
                val dev = current - avg
                fun minToHM(m: Int): String = "${m / 60}h ${m % 60}m"
                items += BaselineItem(
                    name = "Sleep Duration",
                    currentValue = minToHM(current),
                    baselineValue = minToHM(avg.toInt()),
                    deviation = "${if (dev >= 0) "+" else ""}${dev.toInt()} min",
                    deviationPercent = ((dev / avg) * 100).toFloat(),
                    trend = if (dev > 15) "↑" else if (dev < -15) "↓" else "→",
                    calibrationDays = sleepValues.size,
                    confidence = if (sleepValues.size >= 14) "HIGH" else if (sleepValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            // Steps baseline
            val stepValues = past30.mapNotNull { it.steps }
            if (stepValues.isNotEmpty()) {
                val current = stepValues.first()
                val avg = stepValues.average()
                val dev = current - avg
                items += BaselineItem(
                    name = "Daily Steps",
                    currentValue = "%,d".format(current),
                    baselineValue = "%,d".format(avg.toInt()),
                    deviation = "${if (dev >= 0) "+" else ""}%,d".format(dev.toInt()),
                    deviationPercent = ((dev / avg) * 100).toFloat(),
                    trend = if (dev > 500) "↑" else if (dev < -500) "↓" else "→",
                    calibrationDays = stepValues.size,
                    confidence = if (stepValues.size >= 14) "HIGH" else if (stepValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            // Training Load baseline
            val loadValues = scores.mapNotNull { it.trainingLoadNormalized }
            if (loadValues.isNotEmpty()) {
                val current = loadValues.first()
                val avg = loadValues.average().toFloat()
                val dev = current - avg
                items += BaselineItem(
                    name = "Training Load",
                    currentValue = "%.0f".format(current * 100),
                    baselineValue = "%.0f".format(avg * 100),
                    deviation = "${if (dev >= 0) "+" else ""}%.0f".format(dev * 100),
                    deviationPercent = if (avg > 0) ((dev / avg) * 100) else 0f,
                    trend = if (dev > 0.1f) "↑" else if (dev < -0.1f) "↓" else "→",
                    calibrationDays = loadValues.size,
                    confidence = if (loadValues.size >= 14) "HIGH" else if (loadValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            // Recovery Score baseline
            val recoveryValues = scores.mapNotNull { it.recoveryScore }
            if (recoveryValues.isNotEmpty()) {
                val current = recoveryValues.first()
                val avg = recoveryValues.average().toFloat()
                val dev = current - avg
                items += BaselineItem(
                    name = "Recovery Score",
                    currentValue = "%.0f".format(current),
                    baselineValue = "%.0f".format(avg),
                    deviation = "${if (dev >= 0) "+" else ""}%.0f".format(dev),
                    deviationPercent = if (avg > 0) ((dev / avg) * 100) else 0f,
                    trend = if (dev > 3) "↑" else if (dev < -3) "↓" else "→",
                    calibrationDays = recoveryValues.size,
                    confidence = if (recoveryValues.size >= 14) "HIGH" else if (recoveryValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            // HRR baseline
            val hrrValues = scores.mapNotNull { it.hrr1 }
            if (hrrValues.isNotEmpty()) {
                val current = hrrValues.first()
                val avg = hrrValues.average()
                val dev = current - avg
                items += BaselineItem(
                    name = "Heart Rate Recovery",
                    currentValue = "$current bpm/min",
                    baselineValue = "${avg.toInt()} bpm/min",
                    deviation = "${if (dev >= 0) "+" else ""}${dev.toInt()}",
                    deviationPercent = if (avg > 0) ((dev / avg) * 100).toFloat() else 0f,
                    trend = if (dev > 3) "↑" else if (dev < -3) "↓" else "→",
                    calibrationDays = hrrValues.size,
                    confidence = if (hrrValues.size >= 14) "HIGH" else if (hrrValues.size >= 7) "MEDIUM" else "LOW"
                )
            }

            val totalDays = past30.size
            val calibStatus = when {
                totalDays >= 30 -> "Fully calibrated"
                totalDays >= 14 -> "Increasing personalization"
                totalDays >= 7  -> "Preliminary baseline"
                else            -> "Learning your baseline"
            }

            _state.value = BaselinesScreenState(
                baselines = items,
                overallCalibrationDays = totalDays,
                calibrationStatus = calibStatus,
                isLoading = false
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data Sources ViewModel (Part 17 — screen 19)
// ─────────────────────────────────────────────────────────────────────────────

data class DataSourceItem(
    val name: String,
    val description: String,
    val isConnected: Boolean,
    val lastSyncTime: String?,
    val dataTypes: List<String>,
    val permissionStatus: String
)

data class DataSourcesState(
    val sources: List<DataSourceItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class DataSourcesViewModel @Inject constructor(
    private val syncStateDao: SyncStateDao
) : ViewModel() {
    private val _state = MutableStateFlow(DataSourcesState())
    val state: StateFlow<DataSourcesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val syncStates = syncStateDao.getAll()
            val syncMap = syncStates.associateBy { it.recordType }
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

            val sources = listOf(
                DataSourceItem(
                    name = "Health Connect",
                    description = "Android Health Connect API — primary data source",
                    isConnected = syncStates.isNotEmpty(),
                    lastSyncTime = syncStates.maxByOrNull { it.lastSuccessfulSyncMs }?.let {
                        Instant.ofEpochMilli(it.lastSuccessfulSyncMs)
                            .atZone(ZoneId.systemDefault())
                            .format(formatter)
                    },
                    dataTypes = listOf(
                        "Heart Rate" + if (syncMap.containsKey("HeartRateRecord")) " ✓" else " ✗",
                        "Steps" + if (syncMap.containsKey("StepsRecord")) " ✓" else " ✗",
                        "Sleep" + if (syncMap.containsKey("SleepSessionRecord")) " ✓" else " ✗",
                        "Exercise" + if (syncMap.containsKey("ExerciseSessionRecord")) " ✓" else " ✗",
                        "SpO₂" + if (syncMap.containsKey("OxygenSaturationRecord")) " ✓" else " ✗",
                        "Resting HR" + if (syncMap.containsKey("RestingHeartRateRecord")) " ✓" else " ✗"
                    ),
                    permissionStatus = if (syncStates.isNotEmpty()) "Granted" else "Not granted"
                ),
                DataSourceItem(
                    name = "Samsung Galaxy Watch Active2",
                    description = "Wearable sensor data via Samsung Health → Health Connect",
                    isConnected = syncStates.isNotEmpty(),
                    lastSyncTime = null,
                    dataTypes = listOf("PPG Heart Rate", "Accelerometer Steps", "GPS Workouts", "Sleep Detection"),
                    permissionStatus = "Via Samsung Health"
                ),
                DataSourceItem(
                    name = "Manual Input",
                    description = "Daily check-ins, journal entries, workout RPE",
                    isConnected = true,
                    lastSyncTime = null,
                    dataTypes = listOf("Morning Check-In", "Journal / Habits", "RPE / Soreness"),
                    permissionStatus = "Always available"
                )
            )

            _state.value = DataSourcesState(sources = sources, isLoading = false)
        }
    }
}
