package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.coach.DailyPlanBuilder
import com.example.vitalcoreai.coach.PlanActivity
import com.example.vitalcoreai.coach.PlanStatus
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.data.sync.SyncWorker
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.HomeUiState
import com.example.vitalcoreai.ui.viewmodel.HomeViewModel
import com.example.vitalcoreai.ui.viewmodel.Momentum
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.flow

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel(), onNavigate: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exercises by viewModel.todayExercises.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val syncFlow = remember(context) {
        flow {
            var previous = emptyList<WorkInfo>()
            var error: String? = null
            WorkManager.getInstance(context).getWorkInfosByTagFlow(SyncWorker::class.java.name).collect { work ->
                val running = work.any { it.state == WorkInfo.State.RUNNING }
                val completed = work.filter { current ->
                    previous.any { it.id == current.id && it.state == WorkInfo.State.RUNNING }
                }
                error = when {
                    running || completed.any { it.state == WorkInfo.State.SUCCEEDED } -> null
                    completed.any { it.state == WorkInfo.State.FAILED } ->
                        "Your health data couldn't be updated. Check your data connection and try again."
                    work.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } ->
                        "Your health data couldn't be updated. Another attempt is scheduled, or you can retry now."
                    else -> error
                }
                emit(DashboardSyncStatus(running, error))
                previous = work
            }
        }
    }
    val sync by syncFlow.collectAsStateWithLifecycle(initialValue = DashboardSyncStatus())
    HomeDashboard(state.copy(syncError = sync.error ?: state.syncError), exercises, viewModel::refresh, onNavigate, sync.running,
        viewModel::setActivityStatus, viewModel::chooseActivity, viewModel::setSleepPlan)
}

private data class DashboardSyncStatus(val running: Boolean = false, val error: String? = null)

/** Stateless content is previewable without Health Connect or a database. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDashboard(
    state: HomeUiState,
    exercises: List<ExerciseSessionEntity>,
    onRefresh: () -> Unit,
    onNavigate: (String) -> Unit,
    isSyncing: Boolean = false,
    onPlanStatus: (PlanStatus) -> Unit = {},
    onPlanChoice: (PlanActivity) -> Unit = {},
    onSleepPlan: (Int, PlanStatus) -> Unit = { _, _ -> }
) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    val scores = state.latestScores
    val metrics = state.latestMetrics
    val scoreDay = (scores?.dateEpochDay ?: metrics?.dateEpochDay)?.let(LocalDate::ofEpochDay) ?: VitalTime.today()
    val hasMomentum = listOf(state.recoveryMomentum, state.sleepMomentum, state.trainingMomentum)
        .any { Momentum.parse(it) != Momentum.UNKNOWN }

    if (showMore) {
        ExploreSheet(onDismiss = { showMore = false }, onNavigate = { showMore = false; onNavigate(it) })
    }
    Scaffold(
        containerColor = Background,
        topBar = { DashboardTopBar(onRefresh, onNavigate, isSyncing) },
        bottomBar = { DashboardNavigation(showMore, onNavigate, onMore = { showMore = true }) }
    ) { padding ->
        PullToRefreshBox(isRefreshing = isSyncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "heading") {
                Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (scoreDay == VitalTime.today()) "TODAY" else "LATEST OVERVIEW",
                            style = VitalCoreType.eyebrow, color = OnSurfaceDim, modifier = Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClickLabel = "View history") { onNavigate(Routes.HISTORY) }
                                .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(scoreDay.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                                style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
                            Icon(Icons.Filled.ExpandMore, null, tint = OnSurfaceDim, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(state.userName?.takeIf { it.isNotBlank() }?.let { "Your day, ${it.trim().substringBefore(' ')}." }
                        ?: "Make today count.",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold), color = OnBackground)
                    Spacer(Modifier.height(6.dp))
                    Text("Your body. Your pace. Your progress.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                }
            }
            if (state.isLoading) {
                item { VitalSkeleton(Modifier.fillMaxWidth().height(196.dp)) }
                item { VitalSkeletonList(rows = 3, rowHeight = 96.dp) }
            } else {
                item(key = "scores") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(SurfaceL1, Background)))
                        .padding(top = 18.dp, bottom = 14.dp)) {
                        HeroRingRow(
                            recovery = scores?.recoveryScore, strain = scores?.strain, sleep = scores?.sleepScore,
                            onRecoveryClick = { onNavigate(Routes.RECOVERY) },
                            onStrainClick = { onNavigate(Routes.TRAINING) },
                            onSleepClick = { onNavigate(Routes.SLEEP) },
                            recoveryConfidence = confidenceOf(scores?.recoveryConfidence),
                            strainConfidence = confidenceOf(scores?.strainConfidence),
                            sleepConfidence = confidenceOf(scores?.sleepConfidence))
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sync, null, tint = OnSurfaceDim, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSyncing) "Updating your health data…" else syncCaption(state), style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (state.syncError != null) {
                    item(key = "sync-error") {
                        VitalCard(modifier = Modifier.fillMaxWidth(), accent = StressAccent) {
                            Text("Sync needs attention", style = MaterialTheme.typography.titleMedium, color = OnBackground)
                            Text(state.syncError, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            TextButton(onClick = onRefresh) { Text("Try again") }
                        }
                    }
                }
                if (scores == null) {
                    item(key = "connect") {
                        DashboardActionCard(Icons.Filled.Watch, SleepAccent, "WELCOME TO VITALCORE",
                            "Your better days start here", "Connect your health data to bring your daily scores to life.") {
                            onNavigate(Routes.DATA_SOURCES)
                        }
                    }
                }
                item(key = "daily-plan") {
                    DailyPlanCard(
                        state.dailyPlan ?: DailyPlanBuilder.build(VitalTime.todayEpochDay(), scores, false, 480),
                        state.checkInCompleted, onPlanStatus, onPlanChoice, onSleepPlan,
                        onCheckIn = { onNavigate(Routes.CHECK_IN) },
                        onCoach = { onNavigate(Routes.ASK_COACH) },
                        onSources = { onNavigate(Routes.DATA_SOURCES) }
                    )
                }
                item(key = "dashboard-title") { DashboardSection("My dashboard", "Details", { showMore = true }) }
                item(key = "monitors") {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardMonitor("READINESS", Icons.Filled.Bolt,
                            scores?.readinessScore?.let { "${it.toInt()} / 100" } ?: "Awaiting data", "Your capacity today",
                            if (scores?.readinessScore == null) OnSurfaceMuted else ReadinessAccent,
                            Modifier.weight(1f).fillMaxHeight()) { onNavigate(Routes.READINESS) }
                        val stress = scores?.stressScore
                        DashboardMonitor("STRESS", Icons.Filled.Waves,
                            stress?.let { stressTierLabel(it) } ?: "Awaiting data",
                            "Your daily stress",
                            stressTierColor(stress),
                            Modifier.weight(1f).fillMaxHeight()) { onNavigate(Routes.STRESS) }
                    }
                }
                item(key = "fitness-age") {
                    VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), onClick = { onNavigate(Routes.BIOLOGICAL_AGE) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Timeline, null, tint = BioAgeAccent, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Fitness age", style = MaterialTheme.typography.titleMedium, color = OnBackground)
                                Text("An estimate of your fitness", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                            }
                            Text(scores?.biologicalAge?.toString() ?: "—", style = VitalCoreType.metricMedium, color = BioAgeAccent)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                item(key = "metrics") {
                    VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            DashboardMetric("Resting heart rate", metrics?.restingHR?.toString(), "bpm",
                                Icons.Filled.FavoriteBorder, HeartAccent, Modifier.weight(1f),
                                if (metrics?.restingHRDerived == true) "Estimated" else "At rest") { onNavigate(Routes.HEART) }
                            DashboardMetric("Steps", metrics?.steps?.let { NumberFormat.getIntegerInstance().format(it) }, "steps",
                                Icons.AutoMirrored.Filled.DirectionsWalk, ActivityAccent, Modifier.weight(1f), "Daily movement") { onNavigate(Routes.ACTIVITY) }
                        }
                        HorizontalDivider(color = DividerColor, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth()) {
                            DashboardMetric("Sleep duration", metrics?.sleepDurationMinutes?.let { "${it / 60}h ${it % 60}m" }, "",
                                Icons.Filled.Bedtime, SleepAccent, Modifier.weight(1f), "Time asleep") { onNavigate(Routes.SLEEP) }
                            DashboardMetric("Active energy", metrics?.activeCalories?.let { NumberFormat.getIntegerInstance().format(it) }, "kcal",
                                Icons.Filled.LocalFireDepartment, StressAccent, Modifier.weight(1f), "Activity calories") { onNavigate(Routes.ACTIVITY) }
                        }
                    }
                }
                scores?.energyBankScore?.let { energy ->
                    item(key = "energy") {
                        val accent = recoveryTierColor(energy)
                        VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                            onClick = { onNavigate(Routes.ENERGY_BANK) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bolt, null, tint = accent, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Energy bank", style = MaterialTheme.typography.titleMedium, color = OnBackground)
                                    Text("Your estimated reserves", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                                }
                                Text(energy.toInt().toString(), style = VitalCoreType.metricMedium, color = accent)
                                Text(" / 100", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                                Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceDim, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            LinearProgressIndicator(progress = { (energy / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = accent, trackColor = SurfaceL3, drawStopIndicator = {})
                        }
                    }
                }
                item(key = "activity-title") { DashboardSection("Activity & sleep", "View all", { onNavigate(Routes.WORKOUT_HISTORY) }) }
                metrics?.sleepDurationMinutes?.takeIf { it > 0 }?.let { minutes ->
                    item(key = "sleep-session") {
                        DashboardActivity(Icons.Filled.Bedtime, SleepAccent, "Sleep", "${minutes / 60}h ${minutes % 60}m",
                            sleepTimeRange(metrics.bedtimeMinuteOfDay, metrics.wakeTimeMinuteOfDay)) { onNavigate(Routes.SLEEP) }
                    }
                }
                items(exercises, key = { "workout-${it.startMs}" }) { session ->
                    DashboardActivity(exerciseIcon(session.exerciseType), StrainAccent,
                        session.exerciseType.replace("_", " ").lowercase().replaceFirstChar { it.uppercaseChar() },
                        "${session.durationMinutes} min", sessionTimeRange(session)) { onNavigate(Routes.workoutDetail(session.startMs)) }
                }
                if (exercises.isEmpty()) {
                    item(key = "no-workouts") {
                        VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FitnessCenter, null, tint = OnSurfaceDim, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text("Room to move", style = MaterialTheme.typography.titleMedium, color = OnBackground)
                                    Text("Your workouts will appear here after syncing.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                                }
                            }
                        }
                    }
                }
                item(key = "insights-title") { DashboardSection("Made for your day", "Insights", { onNavigate(Routes.INSIGHTS) }) }
                state.insights.firstOrNull()?.let { insight ->
                    item(key = "coach") { InsightCard(insight.title, insight.body, insight.type, onClick = { onNavigate(Routes.INSIGHTS) }) }
                }
                if (hasMomentum) {
                    item(key = "momentum") { MomentumRow(Momentum.parse(state.recoveryMomentum), Momentum.parse(state.sleepMomentum), Momentum.parse(state.trainingMomentum)) }
                }
                state.sleepConsistency?.let { item(key = "consistency") { SleepConsistencyCard(it) } }
                items(state.trends, key = { "trend-${it.window}" }) { TrendCard(it) }
                items(state.anomalies, key = { "anomaly-${it.metric}:${it.title}" }) { AnomalyCard(it) }
                item(key = "forecast-title") { DashboardSection("Looking ahead", "Forecast", { onNavigate(Routes.FORECAST) }) }
                item(key = "forecast") { ForecastCard(state.forecast) }
                item(key = "footer") {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = OnSurfaceMuted, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Your health. Your device. Your data.", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                    }
                }
            }
        }
        }
    }
}

private fun confidenceOf(raw: String?): Confidence = Confidence.entries.firstOrNull { it.name == raw } ?: Confidence.LOW

private fun syncCaption(state: HomeUiState): String {
    if (state.syncError != null) return "Sync needs attention"
    if (!state.latestMetrics?.staleRecordTypes.isNullOrBlank()) return "Some cached readings could not be refreshed · review Data Sources"
    val timestamp = state.latestMetrics?.lastSyncTimestampMs ?: return "Waiting for health data"
    val synced = VitalTime.zonedOf(timestamp)
    val time = synced.format(DateTimeFormatter.ofPattern("h:mm a"))
    return if (synced.toLocalDate() == VitalTime.today()) "Last read $time"
    else "Last read ${synced.format(DateTimeFormatter.ofPattern("MMM d"))} · $time"
}

@Preview(name = "Overview · empty", widthDp = 393, heightDp = 852)
@Preview(name = "Overview · small phone", widthDp = 320, heightDp = 720)
@Composable
private fun HomeDashboardPreview() {
    VitalCoreTheme { HomeDashboard(HomeUiState(isLoading = false), emptyList(), {}, {}) }
}
