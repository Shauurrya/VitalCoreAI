package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*
import androidx.compose.ui.res.stringResource
import com.example.vitalcoreai.R
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Refresh
import com.example.vitalcoreai.core.time.VitalTime
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

private val PremiumCardShape = RoundedCornerShape(20.dp)

// ─── Activity Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Activity",
                subtitle = "Your daily movement",
                accent = ActivityAccent,
                onBack = onBack
            )
        }
    ) {
        if (state.isLoading) {
            item { VitalSkeleton(Modifier.fillMaxWidth().height(360.dp)) }
        } else {
            item {
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = ActivityAccent) {
                    Text("DAILY ACTIVITY", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PercentRing(
                            value = state.activityScore,
                            label = "Activity",
                            size = 200.dp,
                            accent = ActivityAccent,
                            useTierColors = false,
                            caption = if (state.activityScore != null) "DAILY SCORE" else null
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(20.dp))
                    Text("STEPS", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.steps?.let { String.format("%,d", it) } ?: "—",
                        style = VitalCoreType.metricLarge,
                        color = OnBackground
                    )
                }
            }
            item {
                DetailMetricPair(
                    first = { MetricStatCard("Calories", state.calories?.toString() ?: "—", "kcal", it) },
                    second = { MetricStatCard("Distance", state.distanceKm?.let { String.format("%.1f", it) } ?: "—", "km", it) }
                )
            }
        }
        if (state.chartValues.isNotEmpty()) {
            item {
                VitalSectionCard(
                    title = "Movement over time",
                    subtitle = "Latest ${state.chartValues.size} recorded days · steps",
                    modifier = Modifier.fillMaxWidth(),
                    accent = ActivityAccent
                ) {
                    TrendLineChart(values = state.chartValues, accent = ActivityAccent, height = 180.dp)
                }
            }
        }
    }
}

// ─── Training Load Screen ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingLoadScreen(
    viewModel: TrainingViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TrainingDetailContent(state, onBack)
}

@Composable
internal fun TrainingDetailContent(
    state: TrainingUiState,
    onBack: () -> Unit
) {

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Strain & training",
                subtitle = "Workload & balance",
                accent = StrainAccent,
                onBack = onBack
            )
        }
    ) {
        if (state.isLoading) {
            item { VitalSkeleton(Modifier.fillMaxWidth().height(300.dp)) }
        } else {
            item {
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = StrainAccent) {
                    Text("LATEST STRAIN", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StrainRing(
                            strain = state.strain,
                            size = 220.dp,
                            isProxyEstimate = state.isProxyEstimate,
                            exertionMinutes = state.exertionMinutes
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(16.dp))
                    ConfidenceBadge(state.confidence)
                    state.explanation?.takeIf { it.isNotBlank() }?.let { explanation ->
                        Spacer(Modifier.height(14.dp))
                        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                    }
                }
            }
        }
        if (!state.isLoading) {
            item {
                val hasBaseline = state.acwrIsMeaningful != false
                val zone = state.acwrZone.takeIf { hasBaseline }
                val (zoneLabel, zoneColor) = when (zone) {
                    "OPTIMAL"        -> "Optimal zone" to ActivityAccent
                    "UNDER_TRAINING" -> "Under-training" to StressAccent
                    "CAUTION"        -> "Caution" to StressAccent
                    "DANGER"         -> "Danger zone" to AlertRed
                    else             -> (zone ?: if (hasBaseline) "Awaiting data" else "Building your baseline") to OnSurfaceDim
                }
                VitalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("WORKLOAD BALANCE", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                            Spacer(Modifier.height(10.dp))
                            Text(zoneLabel, style = MaterialTheme.typography.titleMedium, color = zoneColor)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(state.acwr?.takeIf { hasBaseline }?.let { String.format("%.2f", it) } ?: "—", style = VitalCoreType.metricMedium, color = OnBackground)
                            Text("ACWR", style = VitalCoreType.monoTiny, color = OnSurfaceMuted)
                        }
                    }
                    if (!hasBaseline) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Keep syncing to build enough history for a reliable workload comparison.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
        if (state.strainHistory.isNotEmpty()) {
            item {
                VitalSectionCard(
                    title = "Strain history",
                    subtitle = "Latest ${state.strainHistory.size} recorded days · out of 21",
                    modifier = Modifier.fillMaxWidth(),
                    accent = StrainAccent
                ) {
                    TrendLineChart(values = state.strainHistory, accent = StrainAccent, yRange = 0f..21f, height = 180.dp)
                }
            }
        }
        item {
            VitalSectionCard(title = "About workload balance", modifier = Modifier.fillMaxWidth(), accent = StrainAccent) {
                    Text(
                        "Acute:Chronic Workload Ratio = 7-day rolling avg ÷ 28-day rolling avg. " +
                        "Optimal zone: 0.8–1.3. Ratios above 1.5 are associated with elevated injury risk in research literature.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                    )
            }
        }
    }
}

// ─── Biological Age Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiologicalAgeScreen(
    viewModel: BiologicalAgeViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showInfoSheet by remember { mutableStateOf(false) }

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Fitness Age (estimate)",
                subtitle = "Your long-term fitness",
                accent = BioAgeAccent,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showInfoSheet = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About this score", tint = OnSurfaceDim)
                    }
                }
            )
        }
    ) {
        // ─── Permanent disclaimer ─────────────────────────────────────
        item {
            VitalCard(
                modifier = Modifier.fillMaxWidth(),
                accent = StressAccent,
                contentPadding = PaddingValues(0.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, null, tint = StressAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.disclaimer,
                        style = MaterialTheme.typography.bodySmall,
                        color = StressAccent
                    )
                }
            }
        }

        item {
            if (state.isLoading) {
                VitalSkeleton(Modifier.fillMaxWidth().height(240.dp))
            } else if (state.biologicalAge != null) {
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = BioAgeAccent) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text("ESTIMATED FITNESS AGE", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "${state.biologicalAge}",
                            style = VitalCoreType.metricHero,
                            color = OnBackground
                        )
                        Text("YEARS", style = VitalCoreType.monoTiny, color = OnSurfaceMuted)
                        Spacer(Modifier.height(24.dp))
                        val diffText = when {
                            state.ageDiff < 0 -> "${-state.ageDiff} years younger than calendar age"
                            state.ageDiff > 0 -> "${state.ageDiff} years older than calendar age"
                            else -> "Equal to calendar age"
                        }
                        Text(
                            diffText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.ageDiff <= 0) ActivityAccent else StressAccent,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    "Not enough data yet. Sync your health data for 7+ days to compute your fitness age.",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted
                )
            }
        }

        state.vo2Max?.let { vo2 ->
            item {
                VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("VO₂ Max Estimate", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(4.dp))
                        Text("${vo2.toInt()}", style = VitalCoreType.metricMedium, color = BioAgeAccent)
                        Text("mL/kg/min (±10%)", style = VitalCoreType.metricUnit, color = OnSurfaceDim)
                        Spacer(Modifier.height(8.dp))
                        Text("Estimated from resting heart rate — not a measurement", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                    }
                }
            }
        }
    }

    if (showInfoSheet) {
        AlertDialog(
            onDismissRequest = { showInfoSheet = false },
            title = { Text("About This Score") },
            text = {
                Text(
                    "Fitness Age is estimated using a regression model derived from the HUNT Fitness Study " +
                    "(Nes BM et al. 2013, Scand J Med Sci Sports 23(6):697–704). " +
                    "Inputs: VO₂ Max estimate (Uth-Sørensen resting HR method), 30-day resting HR trend, " +
                    "and activity consistency. This is a wellness indicator, not a medical assessment.",
                    style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfoSheet = false }) { Text("Got it") }
            },
            containerColor = SurfaceL2
        )
    }
}

// ─── Insights Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Insights",
                subtitle = "Understand your patterns",
                accent = RecoveryAccent,
                onBack = onBack
            )
        }
    ) {
        // ── Coach insights ─────────────────────────────────────────
        if (state.isLoading) {
            item { VitalSkeletonList(rows = 3, rowHeight = 120.dp) }
        } else if (state.insights.isEmpty()) {
            item {
                VitalEmptyState(
                    title = "Find your patterns",
                    body = "Sync health data to discover personalized insights about your recovery and performance.",
                    icon = Icons.Outlined.Insights
                )
            }
        } else {
            items(state.insights) { insight ->
                InsightCard(title = insight.title, body = insight.body, type = insight.type)
            }
        }

        // ── B2: What-if simulator suggestions ─────────────────────
        if (state.simulatorSuggestions.isNotEmpty()) {
            item { SectionHeader("What If…") }
            items(state.simulatorSuggestions) { suggestion ->
                VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                suggestion.action,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = OnBackground
                            )
                            Text(
                                suggestion.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = ActivityAccent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "+${suggestion.projectedDelta} pts",
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = ActivityAccent
                            )
                        }
                    }
                }
            }
        }

        // ── B7: Achievements ───────────────────────────────────────
        if (state.earnedAchievements.isNotEmpty() || state.lockedAchievements.isNotEmpty()) {
            item { SectionHeader("Achievements") }
            item {
                AchievementsGrid(
                    earned = state.earnedAchievements,
                    locked = state.lockedAchievements
                )
            }
        }

        item {
            Text(
                "Insights are personalized recommendations based on patterns in your health data.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
        }
    }
}

// ─── B7: Achievements Grid ─────────────────────────────────────────────────────

@Composable
private fun AchievementsGrid(
    earned: List<com.example.vitalcoreai.analytics.AchievementEngine.Achievement>,
    locked: List<com.example.vitalcoreai.analytics.AchievementEngine.Achievement>
) {
    val all = earned + locked
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / (100.dp * fontScale)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            all.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { achievement ->
                        AchievementBadge(achievement, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AchievementBadge(
    achievement: com.example.vitalcoreai.analytics.AchievementEngine.Achievement,
    modifier: Modifier = Modifier
) {
    val earned = achievement.isEarned
    Box(
        modifier = modifier
            .clip(PremiumCardShape)
            .background(if (earned) SurfaceL1 else SurfaceL1.copy(alpha = 0.5f))
            .border(1.dp, if (earned) RecoveryAccent.copy(alpha = 0.4f) else DividerColor, PremiumCardShape)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                achievement.icon,
                style = MaterialTheme.typography.headlineSmall,
                color = if (earned) OnBackground else OnSurfaceMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                achievement.title,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (earned) OnBackground else OnSurfaceMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── History Screen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onDayClick: (Long) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.syncMessage) {
        state.syncMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncMessage()
        }
    }

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "History",
                subtitle = "Your daily record",
                accent = RecoveryAccent,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !state.isSyncing
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = RecoveryAccent
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Sync history from Health Connect",
                                tint = RecoveryAccent
                            )
                        }
                    }
                }
            )
        }
    ) {
        state.syncMessage?.let { msg ->
            item {
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = ActivityAccent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = OnBackground, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearSyncMessage() }) {
                            Icon(Icons.Filled.Info, contentDescription = "Dismiss", tint = OnSurfaceDim, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        if (state.isLoading) {
            item { VitalSkeletonList(rows = 4, rowHeight = 148.dp) }
        } else if (state.scores.isEmpty()) {
            item {
                VitalEmptyState(
                    title = "Your story starts here",
                    body = "Keep syncing daily to see your recovery, readiness, sleep, and activity in one place.",
                    icon = Icons.Outlined.History
                )
            }
        } else {
            items(state.scores.reversed(), key = { it.dateEpochDay }) { score ->
                VitalCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = if (score.recoveryScore != null) RecoveryAccent else null,
                    onClick = { onDayClick(score.dateEpochDay) }
                ) {
                    Text(
                        java.time.LocalDate.ofEpochDay(score.dateEpochDay).format(
                            java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
                        ).uppercase(),
                        style = VitalCoreType.eyebrow,
                        color = OnSurfaceDim
                    )
                    Spacer(Modifier.height(20.dp))
                    val fontScale = LocalDensity.current.fontScale
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val metrics = listOf(
                            Triple("Recovery", score.recoveryScore, RecoveryAccent),
                            Triple("Readiness", score.readinessScore, ReadinessAccent),
                            Triple("Sleep", score.sleepScore, SleepAccent),
                            Triple("Activity", score.activityScore, ActivityAccent)
                        )
                        val columns = if (maxWidth >= 300.dp * fontScale) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            metrics.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { (label, value, color) ->
                                        Column(Modifier.weight(1f)) {
                                            Text(value?.toInt()?.toString() ?: "—", style = VitalCoreType.metricSmall, color = color)
                                            Spacer(Modifier.height(6.dp))
                                            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "View details",
                            style = MaterialTheme.typography.labelSmall,
                            color = RecoveryAccent.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = RecoveryAccent.copy(alpha = 0.7f),
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }
        }
    }
}


// ─── Day Detail Screen ────────────────────────────────────────────────────────


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    viewModel: DayDetailViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val dateLabel = remember(state.dateEpochDay) {
        java.time.LocalDate.ofEpochDay(state.dateEpochDay)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
    }

    fun minutesToTime(minuteOfDay: Int?): String {
        if (minuteOfDay == null) return "\u2014"
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val amPm = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, amPm)
    }

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = dateLabel,
                subtitle = "Full day overview",
                accent = RecoveryAccent,
                onBack = onBack
            )
        }
    ) {
        if (state.isLoading) {
            item { VitalSkeletonList(rows = 5, rowHeight = 140.dp) }
            return@VitalScreenScaffold
        }

        // ── Scores ────────────────────────────────────────────────────────────
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), accent = RecoveryAccent) {
                Text("SCORES", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DayDetailScoreRing("Recovery",  state.recoveryScore,  RecoveryAccent,  Modifier.weight(1f))
                    DayDetailScoreRing("Readiness", state.readinessScore, ReadinessAccent, Modifier.weight(1f))
                    DayDetailScoreRing("Sleep",     state.sleepScore,     SleepAccent,     Modifier.weight(1f))
                    DayDetailScoreRing("Activity",  state.activityScore,  ActivityAccent,  Modifier.weight(1f))
                }
                state.recoveryExplanation?.takeIf { it.isNotBlank() }?.let { exp ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = DividerColor)
                    Spacer(Modifier.height(12.dp))
                    Text(exp, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
            }
        }

        // ── Sleep ──────────────────────────────────────────────────────────────
        if (state.sleepDurationMinutes != null) {
            item {
                VitalSectionCard(
                    title = "Sleep",
                    subtitle = com.example.vitalcoreai.core.time.VitalTime
                        .formatDurationMinutes(state.sleepDurationMinutes!!),
                    modifier = Modifier.fillMaxWidth(),
                    accent = SleepAccent
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("BEDTIME", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                            Spacer(Modifier.height(4.dp))
                            Text(minutesToTime(state.bedtimeMinuteOfDay), style = VitalCoreType.metricSmall, color = SleepAccent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("WAKE", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                            Spacer(Modifier.height(4.dp))
                            Text(minutesToTime(state.wakeTimeMinuteOfDay), style = VitalCoreType.metricSmall, color = SleepAccent)
                        }
                        state.sleepEfficiencyPercent?.let { eff ->
                            Column(Modifier.weight(1f)) {
                                Text("EFFICIENCY", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                                Spacer(Modifier.height(4.dp))
                                Text("%d%%".format(eff.toInt()), style = VitalCoreType.metricSmall, color = SleepAccent)
                            }
                        }
                    }
                    if (state.sleepStagesAvailable) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = DividerColor)
                        Spacer(Modifier.height(16.dp))
                        Text("SLEEP STAGES", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                        Spacer(Modifier.height(12.dp))
                        DayDetailSleepStageBar(
                            deep  = state.sleepDeepMinutes  ?: 0,
                            rem   = state.sleepRemMinutes   ?: 0,
                            light = state.sleepLightMinutes ?: 0,
                            awake = state.sleepAwakeMinutes ?: 0
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DayDetailStagePill("Deep",  state.sleepDeepMinutes,  SleepDeep,      Modifier.weight(1f))
                            DayDetailStagePill("REM",   state.sleepRemMinutes,   SleepRem,       Modifier.weight(1f))
                            DayDetailStagePill("Light", state.sleepLightMinutes, SleepLight,     Modifier.weight(1f))
                            DayDetailStagePill("Awake", state.sleepAwakeMinutes, SleepAwake,     Modifier.weight(1f))
                        }
                    }
                    state.sleepExplanation?.takeIf { it.isNotBlank() }?.let { exp ->
                        Spacer(Modifier.height(12.dp))
                        Text(exp, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                }
            }
        }

        // ── Activity ───────────────────────────────────────────────────────────
        if (state.steps != null || state.caloriesBurned != null || state.distanceMeters != null) {
            item {
                VitalSectionCard(
                    title = "Activity",
                    modifier = Modifier.fillMaxWidth(),
                    accent = ActivityAccent
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricStatCard(
                            "Steps", state.steps?.let { "%,d".format(it) } ?: "\u2014", "steps",
                            Modifier.weight(1f), ActivityAccent
                        )
                        MetricStatCard(
                            "Distance", state.distanceMeters?.let { "%.2f".format(it / 1000f) } ?: "\u2014", "km",
                            Modifier.weight(1f), ActivityAccent
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricStatCard(
                            "Active Cal", state.activeCalories?.toString() ?: "\u2014", "kcal",
                            Modifier.weight(1f), ActivityAccent
                        )
                        MetricStatCard(
                            "Total Cal", state.caloriesBurned?.toString() ?: "\u2014", "kcal",
                            Modifier.weight(1f), ActivityAccent
                        )
                    }
                    if (state.floorsClimbed != null) {
                        Spacer(Modifier.height(12.dp))
                        MetricStatCard(
                            "Floors climbed", state.floorsClimbed.toString(), "floors",
                            Modifier.fillMaxWidth(), ActivityAccent
                        )
                    }
                }
            }
        }

        // ── Heart & Body ───────────────────────────────────────────────────────
        if (state.restingHR != null || state.spO2Percent != null || state.hrvRmssdMs != null) {
            item {
                VitalSectionCard(
                    title = "Heart & Body",
                    modifier = Modifier.fillMaxWidth(),
                    accent = ReadinessAccent
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricStatCard(
                            if (state.restingHRDerived) "Resting HR*" else "Resting HR",
                            state.restingHR?.toString() ?: "\u2014",
                            "bpm", Modifier.weight(1f), ReadinessAccent
                        )
                        MetricStatCard(
                            "SpO\u2082",
                            state.spO2Percent?.let { "%d%%".format(it.toInt()) } ?: "\u2014",
                            state.spO2ReadingCount?.let { "$it readings" } ?: "",
                            Modifier.weight(1f), ReadinessAccent
                        )
                    }
                    state.hrvRmssdMs?.let { hrv ->
                        Spacer(Modifier.height(12.dp))
                        MetricStatCard(
                            "HRV (RMSSD)", "%d".format(hrv.toInt()), "ms",
                            Modifier.fillMaxWidth(), ReadinessAccent
                        )
                    }
                    if (state.restingHRDerived) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "* Derived from overnight HR samples \u2014 Samsung Health did not write a Resting HR record for this day.",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }
        }

        // ── Training Load ──────────────────────────────────────────────────────
        if (state.strain != null || state.acwr != null) {
            item {
                VitalSectionCard(
                    title = "Training Load",
                    modifier = Modifier.fillMaxWidth(),
                    accent = StrainAccent
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricStatCard(
                            "Strain", state.strain?.let { "%.1f".format(it) } ?: "\u2014", "/ 21",
                            Modifier.weight(1f), StrainAccent
                        )
                        MetricStatCard(
                            "ACWR", state.acwr?.let { "%.2f".format(it) } ?: "\u2014", "",
                            Modifier.weight(1f), StrainAccent
                        )
                    }
                    state.acwrZone?.let { zone ->
                        Spacer(Modifier.height(8.dp))
                        val (zoneLabel, zoneColor) = when (zone) {
                            "OPTIMAL"        -> "Optimal zone"   to ActivityAccent
                            "UNDER_TRAINING" -> "Under-training" to StressAccent
                            "CAUTION"        -> "Caution"        to StressAccent
                            "DANGER"         -> "Danger zone"    to AlertRed
                            else             -> zone             to OnSurfaceDim
                        }
                        Text(zoneLabel, style = MaterialTheme.typography.labelMedium, color = zoneColor)
                    }
                }
            }
        }

        // ── Workouts ───────────────────────────────────────────────────────────
        if (state.workouts.isNotEmpty()) {
            item {
                Text(
                    "WORKOUTS (${state.workouts.size})",
                    style = VitalCoreType.eyebrow,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            items(state.workouts) { workout ->
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = StrainAccent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                workout.exerciseType.replace('_', ' ')
                                    .lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold),
                                color = OnBackground
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    append("${workout.durationMinutes} min")
                                    workout.avgHR?.let { append(" \u00b7 avg ${it} bpm") }
                                    workout.caloriesBurned?.let { append(" \u00b7 ${it} kcal") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        workout.trainingLoadNormalized?.let { load ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "%.0f".format(load * 100f),
                                    style = VitalCoreType.metricSmall,
                                    color = StrainAccent
                                )
                                Text("load", style = VitalCoreType.monoTiny, color = OnSurfaceMuted)
                            }
                        }
                    }
                }
            }
        }

        // ── Body composition ───────────────────────────────────────────────────
        if (state.weightKg != null) {
            item {
                VitalCard(modifier = Modifier.fillMaxWidth()) {
                    Text("BODY", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                    Spacer(Modifier.height(12.dp))
                    Text("%.1f kg".format(state.weightKg), style = VitalCoreType.metricMedium, color = OnBackground)
                    Text("Weight (last recorded)", style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                }
            }
        }

        // ── No-data fallback ───────────────────────────────────────────────────
        val hasAnyData = state.recoveryScore != null || state.steps != null ||
            state.sleepDurationMinutes != null || state.restingHR != null
        if (!hasAnyData) {
            item {
                VitalEmptyState(
                    title = "No data for this day",
                    body = "Health Connect had no records for this date. Make sure Samsung Health has synced and all permissions are granted in Data Sources.",
                    icon = Icons.Outlined.History
                )
            }
        }
    }
}

// ── Day Detail helpers ────────────────────────────────────────────────────────

@Composable
private fun DayDetailScoreRing(
    label: String,
    score: Float?,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PercentRing(
            value = score,
            label = label,
            size = 72.dp,
            accent = accent,
            useTierColors = false,
            showUnit = false
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DayDetailSleepStageBar(deep: Int, rem: Int, light: Int, awake: Int) {
    val total = deep + rem + light + awake
    if (total == 0) return
    Row(
        Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
    ) {
        if (deep  > 0) Box(Modifier.weight(deep.toFloat()).fillMaxHeight().background(SleepDeep))
        if (rem   > 0) Box(Modifier.weight(rem.toFloat()).fillMaxHeight().background(SleepRem))
        if (light > 0) Box(Modifier.weight(light.toFloat()).fillMaxHeight().background(SleepLight))
        if (awake > 0) Box(Modifier.weight(awake.toFloat()).fillMaxHeight().background(SleepAwake))
    }
}

@Composable
private fun DayDetailStagePill(
    label: String,
    minutes: Int?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.height(4.dp))
        Text(minutes?.let { "${it}m" } ?: "\u2014", style = MaterialTheme.typography.labelSmall, color = OnBackground)
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
    }
}

// ─── Weekly Report Screen ─────────────────────────────────────────────────────


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReportScreen(
    viewModel: WeeklyReportViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val report by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Weekly Report",
                subtitle = "Your week in perspective",
                accent = RecoveryAccent,
                onBack = onBack
            )
        }
    ) {
        if (report == null) {
            item {
                VitalEmptyState(
                    title = "Your weekly report",
                    body = "Your report will appear after 7 days of health data. Keep syncing to build your picture.",
                    icon = Icons.Outlined.CalendarMonth
                )
            }
        } else {
            item {
                ReportScoreHero(
                    period = "Week",
                    score = report!!.weeklyHealthScore,
                    delta = report!!.deltaFromPreviousWeek,
                    explanation = report!!.explanation
                )
            }

            // ── B10: Sub-score averages ────────────────────────────────
            item {
                ReportAverages(
                    recovery = report!!.avgRecovery,
                    readiness = report!!.avgReadiness,
                    sleep = report!!.avgSleep,
                    activity = report!!.avgActivity
                )
            }

            // ── B10: Highlights ───────────────────────────────────────
            val highlights = report!!.highlights?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            if (highlights.isNotEmpty()) {
                item { SectionHeader("Highlights") }
                items(highlights) { highlight ->
                    VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("•", color = RecoveryAccent, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(highlight, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                        }
                    }
                }
            }

            // ── B10: Achievements summary ─────────────────────────────
            report!!.achievementsSummary?.let { summary ->
                if (summary.isNotBlank()) {
                    item { SectionHeader("Achievements This Week") }
                    item {
                        VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            Text(
                                summary,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnBackground
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Monthly Report Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportScreen(
    viewModel: MonthlyReportViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val report by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Monthly Report",
                subtitle = "Your month in perspective",
                accent = RecoveryAccent,
                onBack = onBack
            )
        }
    ) {
        if (report == null) {
            item {
                VitalEmptyState(
                    title = "Your monthly report",
                    body = "Your report will appear after 30 days of health data. Keep syncing to build your picture.",
                    icon = Icons.Outlined.CalendarMonth
                )
            }
        } else {
            item {
                ReportScoreHero(
                    period = "Month",
                    score = report!!.monthlyHealthScore,
                    delta = report!!.deltaFromPreviousMonth,
                    explanation = report!!.explanation
                )
            }

            // ── B10: Sub-score averages ────────────────────────────────
            item {
                ReportAverages(
                    recovery = report!!.avgRecovery,
                    readiness = report!!.avgReadiness,
                    sleep = report!!.avgSleep,
                    activity = report!!.avgActivity
                )
            }

            // ── B10: Personal records ──────────────────────────────────
            report!!.personalRecords?.let { records ->
                if (records.isNotBlank()) {
                    item { SectionHeader("Personal Records") }
                    item {
                        VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            Text(
                                records,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnBackground
                            )
                        }
                    }
                }
            }

            // ── B10: Ranked contributing factors ──────────────────────
            report!!.rankedFactors?.let { factors ->
                if (factors.isNotBlank()) {
                    item { SectionHeader("Top Contributing Factors") }
                    val items = factors.split("|").filter { it.isNotBlank() }
                    items(items) { factor ->
                        VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("•", color = RecoveryAccent, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(factor, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportScoreHero(period: String, score: Float?, delta: Float?, explanation: String?) {
    VitalCard(modifier = Modifier.fillMaxWidth(), accent = RecoveryAccent) {
        Text("THIS ${period.uppercase()}", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PercentRing(score, "Health score", size = 200.dp)
        }
        if (delta != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                "${if (delta > 0) "+" else ""}${delta.toInt()} points from last ${period.lowercase()}",
                style = MaterialTheme.typography.labelLarge,
                color = if (delta >= 0) ActivityAccent else AlertRed,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (!explanation.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = DividerColor)
            Spacer(Modifier.height(20.dp))
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
    }
}

@Composable
private fun ReportAverages(recovery: Float?, readiness: Float?, sleep: Float?, activity: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Your averages")
        DetailMetricPair(
            first = { MetricStatCard("Recovery", recovery?.toInt()?.toString() ?: "—", "out of 100", it, RecoveryAccent) },
            second = { MetricStatCard("Readiness", readiness?.toInt()?.toString() ?: "—", "out of 100", it, ReadinessAccent) }
        )
        DetailMetricPair(
            first = { MetricStatCard("Sleep", sleep?.toInt()?.toString() ?: "—", "out of 100", it, SleepAccent) },
            second = { MetricStatCard("Activity", activity?.toInt()?.toString() ?: "—", "out of 100", it, ActivityAccent) }
        )
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Settings",
                subtitle = "Make it yours",
                accent = RecoveryAccent,
                onBack = onBack
            )
        }
    ) {
        item { SectionHeader("Personal Settings") }
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsSliderRow(
                        label = "Your Age",
                        value = state.userAge.toFloat(),
                        range = 18f..80f,
                        unit = "yrs",
                        onValueChange = { viewModel.setAge(it.toInt()) }
                    )
                    HorizontalDivider(color = SurfaceL3)
                    SettingsSliderRow(
                        label = "Sleep Need",
                        value = state.personalSleepNeedHours,
                        range = 5f..10f,
                        unit = "hrs",
                        onValueChange = { viewModel.setSleepNeed(it) }
                    )
                    HorizontalDivider(color = SurfaceL3)
                    SettingsSliderRow(
                        label = "Max Heart Rate",
                        value = state.userMaxHR.toFloat(),
                        range = 150f..220f,
                        unit = "bpm",
                        onValueChange = { viewModel.setMaxHR(it.toInt()) }
                    )
                }
            }
        }

        // B4 — Notification toggles
        item { SectionHeader("Notifications") }
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NotificationToggleRow(
                        label = "Daily Summary",
                        description = "Today's recovery, readiness and sleep score",
                        checked = state.notifyDailySummary,
                        onCheckedChange = { viewModel.toggleDailySummary(it) }
                    )
                    HorizontalDivider(color = SurfaceL3)
                    NotificationToggleRow(
                        label = "Weekly Report",
                        description = "7-day health summary every Sunday",
                        checked = state.notifyWeeklyReport,
                        onCheckedChange = { viewModel.toggleWeeklyReport(it) }
                    )
                    HorizontalDivider(color = SurfaceL3)
                    NotificationToggleRow(
                        label = "Achievements",
                        description = "Personal bests and milestone unlocks",
                        checked = state.notifyAchievements,
                        onCheckedChange = { viewModel.toggleAchievements(it) }
                    )
                    HorizontalDivider(color = SurfaceL3)
                    NotificationToggleRow(
                        label = "Coach Alerts",
                        description = "Actionable insights (high training load, poor sleep)",
                        checked = state.notifyCoachAlerts,
                        onCheckedChange = { viewModel.toggleCoachAlerts(it) }
                    )
                }
            }
        }

        // ── T-17 — Security ────────────────────────────────────────────
        item { SectionHeader("Security") }
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NotificationToggleRow(
                        label = stringResource(R.string.biometric_setting_title),
                        description = if (state.biometricAvailable)
                            stringResource(R.string.biometric_setting_body)
                        else
                            stringResource(R.string.biometric_unavailable),
                        checked = state.biometricLockEnabled,
                        // Offering a switch the device cannot honour would let the user
                        // believe the app is locked when nothing is gating it.
                        enabled = state.biometricAvailable,
                        onCheckedChange = { viewModel.setBiometricLock(it) }
                    )
                }
            }
        }

        // ── Data & Sync section ────────────────────────────────────────
        item { SectionHeader("Data & Sync") }
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Re-sync 30-Day History",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = OnBackground
                    )
                    Text(
                        "Re-reads all 30 previous days, including saved days, so corrections can appear in History. Check-ins, journal entries and workout notes stay saved.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.forceBackfill() },
                        enabled = !state.isBackfilling,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OnBackground, contentColor = Background)
                    ) {
                        if (state.isBackfilling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Background
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Syncing…")
                        } else {
                            Text("Refresh 30-day history")
                        }
                    }
                    state.backfillResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (state.backfillNeedsAttention) StressAccent else ActivityAccent)
                    }
                }
            }
        }

        // B9 — CSV export
        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Export Health Data",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = OnBackground
                    )
                    Text(
                        "Export your daily metrics, scores, and workouts as CSV files to share or back up — entirely local, no cloud upload.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.exportCsv() },
                        enabled = !state.isExporting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OnBackground, contentColor = Background)
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Background
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Exporting…")
                        } else {
                            Text("Export as CSV")
                        }
                    }
                    state.exportResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("✓")) ActivityAccent else AlertRed)
                    }
                }
            }
        }

        item {
            VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("About", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                    Spacer(Modifier.height(4.dp))
                    Text("VitalCore AI \u2014 Offline health analytics. No cloud, no subscriptions.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    Text("Version 1.0", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnBackground, modifier = Modifier.padding(end = 12.dp))
            Text("${value.toInt()} $unit",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = RecoveryAccent)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = RecoveryAccent,
                activeTrackColor = RecoveryAccent,
                inactiveTrackColor = SurfaceL3
            )
        )
    }
}

// B4 — Notification toggle row
@Composable
private fun NotificationToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            // T-19: the row is the target, not just the 32dp switch thumb, and it carries
            // one merged label so a screen reader announces "<label>, <description>, on"
            // rather than reading a switch with no name.
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $description" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        }
        Switch(
            checked = checked,
            // Null: the row above owns the click, and a nested clickable would double-fire
            // and announce the control twice.
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RecoveryAccent,
                checkedTrackColor = RecoveryAccent.copy(alpha = 0.4f),
                uncheckedTrackColor = SurfaceL3
            )
        )
    }
}

