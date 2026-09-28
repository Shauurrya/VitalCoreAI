package com.example.vitalcoreai.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitalcoreai.analytics.BaselineManager
import com.example.vitalcoreai.analytics.HRRecoveryCalculator
import com.example.vitalcoreai.data.db.dao.*
import com.example.vitalcoreai.data.db.entity.*
import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.core.time.perDay

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

                // Bound to a local so the smart cast holds and the !! goes away — an
                // assertion on a value the branch condition already proved non-null reads as
                // a risk where there is none.
                val peakHR = session.maxHR
                val hrrResult = if (peakHR != null && postWorkoutHR.isNotEmpty()) {
                    val historical = computedScoresDao.getLatestN(30).mapNotNull { it.hrr1 }
                    HRRecoveryCalculator.calculate(peakHR, postWorkoutHR, historical)
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
            // The `today` local that used to sit here was dead: every baseline below reads
            // getLatest(30) with no date filter, so nothing referenced it.
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

data class DataSourcesState(
    val sources: List<DataSourceItem> = emptyList(),
    val isLoading: Boolean = true,
    val isChecking: Boolean = false,
    val isSyncing: Boolean = false,
    val available: Boolean? = null,
    val connectionLabel: String = "Checking Health Connect",
    val backgroundAccess: String = "Checking availability",
    val historyAccess: String = "Checking availability",
    val message: String? = null,
    val accessError: String? = null
)

@HiltViewModel
class DataSourcesViewModel @Inject constructor(
    private val syncStateDao: SyncStateDao,
    private val healthConnectManager: HealthConnectManager,
    private val repository: HealthRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(DataSourcesState())
    val state: StateFlow<DataSourcesState> = _state.asStateFlow()
    private var savedReads: List<SyncStateEntity> = emptyList()
    private var granted: Set<String>? = null
    private var accessChecked = false
    private var accessRefreshPending = false
    private var latestMetrics: DailyMetricsEntity? = null

    private data class Metric(
        val type: kotlin.reflect.KClass<out Record>,
        val name: String,
        val expectDaily: Boolean = true
    )

    private val metrics = listOf(
        Metric(HeartRateRecord::class, "Heart rate"),
        Metric(RestingHeartRateRecord::class, "Resting heart rate"),
        Metric(SleepSessionRecord::class, "Sleep"),
        Metric(StepsRecord::class, "Steps"),
        Metric(ExerciseSessionRecord::class, "Workouts", false),
        Metric(TotalCaloriesBurnedRecord::class, "Total calories"),
        Metric(ActiveCaloriesBurnedRecord::class, "Active calories"),
        Metric(DistanceRecord::class, "Distance"),
        Metric(OxygenSaturationRecord::class, "Blood oxygen"),
        Metric(HeartRateVariabilityRmssdRecord::class, "Heart rate variability"),
        Metric(WeightRecord::class, "Weight", false),
        Metric(BodyFatRecord::class, "Body fat", false),
        Metric(FloorsClimbedRecord::class, "Floors climbed"),
        Metric(ElevationGainedRecord::class, "Elevation gained"),
        Metric(SpeedRecord::class, "Speed", false),
        Metric(Vo2MaxRecord::class, "VO₂ max", false)
    )

    init {
        viewModelScope.launch {
            syncStateDao.observeAll().collect { reads ->
                savedReads = reads
                updateSources()
            }
        }
        viewModelScope.launch {
            perDay { day -> repository.metricsFrom(day - 30) }
                .catch { emit(emptyList()) }
                .collect { metrics ->
                    latestMetrics = metrics.maxByOrNull { it.dateEpochDay }
                    updateSources()
                }
        }
        refreshAccess()
    }

    /** Called on every resume, including a return from the system permission screen. */
    fun refreshAccess() {
        if (_state.value.isChecking) {
            // A permission snapshot already in flight may predate the user's changes.
            // Coalesce additional resume/read-completion requests into one follow-up.
            accessRefreshPending = true
            return
        }
        _state.update { it.copy(isChecking = true, accessError = null) }
        viewModelScope.launch {
            try {
                do {
                    accessRefreshPending = false
                    checkAccess()
                } while (accessRefreshPending)
            } finally {
                accessChecked = true
                _state.update { it.copy(isChecking = false) }
                updateSources()
            }
        }
    }

    private suspend fun checkAccess() {
        var available: Boolean? = null
        try {
            available = healthConnectManager.isAvailable()
            _state.update { it.copy(available = available) }
            granted = if (available == true) healthConnectManager.grantedPermissions() else emptySet()
            val count = metrics.count { HealthPermission.getReadPermission(it.type) in granted.orEmpty() }
            fun capability(supported: Boolean, permission: String): String = when {
                available != true -> "Unavailable while Health Connect is unavailable"
                !supported -> "Not supported on this device"
                permission in granted.orEmpty() -> "Allowed"
                else -> "Not allowed · manage in Health Connect"
            }
            _state.update {
                it.copy(
                    available = available,
                    connectionLabel = if (available == true) "$count of ${metrics.size} metric permissions allowed" else "Health Connect unavailable",
                    accessError = null,
                    backgroundAccess = capability(available == true && healthConnectManager.supportsBackgroundRead(), HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND),
                    historyAccess = capability(available == true && healthConnectManager.supportsHistoryRead(), HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            granted = null
            _state.update {
                it.copy(
                    available = available,
                    connectionLabel = "Could not check current access",
                    backgroundAccess = "Could not check current access",
                    historyAccess = "Could not check current access",
                    accessError = "Recheck access or open Health Connect settings."
                )
            }
        }
    }

    private fun updateSources() {
        val byType = savedReads.associateBy { it.recordType }
        val rows = metrics.map { metric ->
            val recordType = metric.type.simpleName.orEmpty()
            presentDataSource(
                recordType, metric.name, _state.value.available,
                granted?.contains(HealthPermission.getReadPermission(metric.type)),
                byType[recordType], VitalTime.nowMs(), metric.expectDaily, ::sourceAppName
            ).copy(dataNotes = dataSourceNotes(recordType, latestMetrics))
        }
        _state.update { it.copy(sources = rows, isLoading = !accessChecked) }
    }

    private fun sourceAppName(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        // Android package visibility may hide a label; the recorded origin is still useful.
        packageName
    }

    fun readData() {
        if (_state.value.isSyncing || _state.value.available != true) return
        _state.update { it.copy(isSyncing = true, message = null) }
        viewModelScope.launch {
            try {
                val result = repository.syncToday(UserPrefs.age(context), UserPrefs.maxHR(context))
                _state.update { it.copy(message = dataReadMessage(result)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = "The read could not finish. Saved measurements remain available; review the outcomes below and retry.") }
            } finally {
                _state.update { it.copy(isSyncing = false) }
                refreshAccess()
            }
        }
    }

    fun openHealthConnect() {
        try {
            val intent = healthConnectManager.getInstallIntent()
                ?: Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            _state.update { it.copy(message = "Health Connect could not be opened. Check your phone's Settings for Health Connect and app permissions.") }
        }
    }
}
