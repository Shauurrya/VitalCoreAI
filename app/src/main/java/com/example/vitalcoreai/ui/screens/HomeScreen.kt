package com.example.vitalcoreai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.HomeUiState
import com.example.vitalcoreai.ui.viewmodel.Momentum
import com.example.vitalcoreai.ui.viewmodel.HomeViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.example.vitalcoreai.core.time.VitalTime
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.vitalcoreai.BuildConfig

// ─── 3-Tab Bottom Nav (Whoop-style: Overview, Strain, Recovery) ──────────────

sealed class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val accentColor: androidx.compose.ui.graphics.Color
) {
    data object Overview : MainTab(Routes.HOME, "Overview", Icons.Filled.Home, RecoveryAccent)
    data object Strain   : MainTab(Routes.TRAINING, "Strain", Icons.Filled.FitnessCenter, StrainAccent)
    data object Recovery : MainTab(Routes.RECOVERY, "Recovery", Icons.Filled.Favorite, SleepAccent)
}

private val mainTabs = listOf(MainTab.Overview, MainTab.Strain, MainTab.Recovery)

// ─── HomeScreen (Whoop-style: 3 large rings as hero, 3-tab bottom nav) ─────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val todayExercises by viewModel.todayExercises.collectAsStateWithLifecycle()
    var selectedTab: MainTab by remember { mutableStateOf(MainTab.Overview) }

    val recoveryScore = state.latestScores?.recoveryScore
    val sleepScore = state.latestScores?.sleepScore
    val strainScore = state.latestScores?.trainingLoadNormalized?.let { it * 100 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // T-14 — hidden developer gesture. Three taps inside 300 ms on the
                    // brand mark, and only in a debug build: a release APK has no way in
                    // at all, rather than a way in that happens to be obscure.
                    var debugTaps by remember { mutableIntStateOf(0) }
                    var lastTapMs by remember { mutableLongStateOf(0L) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    if (!BuildConfig.DEBUG) return@detectTapGestures
                                    val now = VitalTime.nowMs()
                                    debugTaps = if (now - lastTapMs < 300) debugTaps + 1 else 1
                                    lastTapMs = now
                                    if (debugTaps >= 3) {
                                        debugTaps = 0
                                        onNavigate(Routes.DEBUG)
                                    }
                                })
                            }
                    ) {
                        Text(
                            "VitalCore AI",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = OnBackground
                        )
                        Text(
                            VitalTime.today().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .semantics { contentDescription = "Sync health data now" }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = RecoveryAccent, modifier = Modifier.size(24.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceL1,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                mainTabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            selectedTab = tab
                            if (tab.route != Routes.HOME) onNavigate(tab.route)
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(24.dp),
                                tint = if (isSelected) tab.accentColor else OnSurfaceDim
                            )
                        },
                        label = { 
                            Text(
                                tab.label, 
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isSelected) tab.accentColor else OnSurfaceDim
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = tab.accentColor,
                            selectedTextColor   = tab.accentColor,
                            indicatorColor      = tab.accentColor.copy(alpha = 0.12f),
                            unselectedIconColor = OnSurfaceDim,
                            unselectedTextColor = OnSurfaceDim
                        ),
                        alwaysShowLabel = true
                    )
                }
            }
        },
        containerColor = Background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color       = RecoveryAccent,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(32.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier        = Modifier.fillMaxSize().padding(padding),
            contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── HERO: Three large score rings (Whoop-style) ───────────────────
            item {
                HeroRingRow(
                    recovery = recoveryScore,
                    strain = strainScore,
                    sleep = sleepScore,
                    onRecoveryClick = { onNavigate(Routes.RECOVERY) },
                    onStrainClick = { onNavigate(Routes.TRAINING) },
                    onSleepClick = { onNavigate(Routes.SLEEP) }
                )
            }

            // ── Momentum Indicators (B1) ──────────────────────────────────────
            if (state.recoveryMomentum != null || state.sleepMomentum != null || state.trainingMomentum != null) {
                item {
                    // MomentumRow takes the parsed enum rather than raw column strings, so a
                    // value the database never wrote lands on UNKNOWN instead of rendering
                    // an arrow for a direction nothing computed.
                    MomentumRow(
                        recovery = Momentum.parse(state.recoveryMomentum),
                        sleep = Momentum.parse(state.sleepMomentum),
                        strain = Momentum.parse(state.trainingMomentum)
                    )
                }
            }

            // ── Morning Check-In Card (Part 12) ─────────────────────────────────
            item {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(RecoveryAccent.copy(0.10f), SleepAccent.copy(0.08f))
                            )
                        )
                        .border(1.dp, RecoveryAccent.copy(0.25f), shape)
                        .clickable { onNavigate(Routes.CHECK_IN) }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("☀️", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Morning Check-In",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = OnBackground
                            )
                            Text(
                                "10 seconds • Improves score accuracy",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = RecoveryAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ── Health Monitor + Stress Monitor (side-by-side compact cards) ───
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonitorCard(
                        title = "HEALTH MONITOR",
                        headline = healthStatusHeadline(state),
                        subline = healthStatusSubline(state),
                        statusColor = healthStatusColor(state),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigate(Routes.RECOVERY) }
                    )
                    MonitorCard(
                        title = "STRESS MONITOR",
                        headline = stressHeadline(state),
                        subline = "RHR: ${state.latestMetrics?.restingHR ?: "—"} bpm",
                        statusColor = stressStatusColor(state),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigate(Routes.STRESS) }
                    )
                }
            }

            // ── Energy Bank Card (Part 11) ──────────────────────────────────────
            state.latestScores?.energyBankScore?.let { eb ->
                item {
                    val shape = RoundedCornerShape(16.dp)
                    val ebColor = when {
                        eb >= 70 -> ActivityAccent
                        eb >= 40 -> StressAccent
                        else -> AlertRed
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(SurfaceL1)
                            .border(1.dp, ebColor.copy(0.3f), shape)
                            .clickable { onNavigate(Routes.ENERGY_BANK) }
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactRing(
                                value = eb,
                                size = 48.dp,
                                strokeWidth = 5.dp,
                                color = ebColor
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Energy Bank",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = OnBackground
                                )
                                Text(
                                    "${eb.toInt()}/100 • " + when {
                                        eb >= 80 -> "High capacity"
                                        eb >= 60 -> "Moderate reserves"
                                        eb >= 40 -> "Running low"
                                        else -> "Depleted"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ebColor
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── V1.1 (T-13) — the persisted engine output ─────────────────────
            //
            // Ordered as the product spec asks: what to do today, then how tonight's
            // sleep has been going, then where the trend is heading, then anything
            // unusual, and the forecast last because it is the least actionable.
            //
            // Every one of these degrades on its own: a card with nothing to say either
            // states why (forecast, recommendation) or does not render at all
            // (anomalies, trends). None of them renders a zero in place of a null.

            item {
                RecommendationCard(recommendation = state.recommendation)
            }

            state.sleepConsistency?.let {
                item { SleepConsistencyCard(state = it) }
            }

            // Rendered only when a window has enough coverage to have a direction at
            // all; TrendState.isMeaningful has already filtered INSUFFICIENT_DATA out.
            items(state.trends, key = { it.window }) { trend ->
                TrendCard(trend = trend)
            }

            // Deliberately absent rather than "No anomalies" — a reassurance the engine
            // is not entitled to give is worse than saying nothing.
            items(state.anomalies, key = { "${it.metric}:${it.title}" }) { anomaly ->
                AnomalyCard(anomaly = anomaly)
            }

            item {
                ForecastCard(forecast = state.forecast)
            }

            // ── Key Insight Card ──────────────────────────────────────────────
            if (state.insights.isNotEmpty()) {
                item {
                    CoachInsightRow(
                        insight = state.insights.first(),
                        onClick = { onNavigate(Routes.INSIGHTS) }
                    )
                }
            }

            // ── Today's Activities ────────────────────────────────────────────
            item {
                SectionHeader("Today's Activities")
                Spacer(Modifier.height(8.dp))
            }

            // Sleep row
            state.latestMetrics?.let { metrics ->
                if (metrics.sleepDurationMinutes != null && metrics.sleepDurationMinutes > 0) {
                    item {
                        ActivityRow(
                            icon     = Icons.Filled.Bedtime,
                            iconTint = SleepAccent,
                            name     = "Sleep",
                            stat     = "${metrics.sleepDurationMinutes / 60}h ${metrics.sleepDurationMinutes % 60}m",
                            timeRange = buildSleepTimeRange(
                                metrics.bedtimeMinuteOfDay,
                                metrics.wakeTimeMinuteOfDay
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // Workout rows
            if (todayExercises.isNotEmpty()) {
                todayExercises.forEachIndexed { index, session ->
                    item(key = index) {
                        ActivityRow(
                            icon     = exerciseIcon(session.exerciseType),
                            iconTint = StrainAccent,
                            name     = session.exerciseType.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercaseChar() },
                            stat     = "${session.durationMinutes}m",
                            timeRange = buildSessionTimeRange(session)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            } else {
                val m = state.latestMetrics
                if (m == null || m.sleepDurationMinutes == null || m.sleepDurationMinutes == 0) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceL1)
                                .border(1.dp, DividerColor, RoundedCornerShape(16.dp))
                                .padding(24.dp)
                        ) {
                            Text(
                                "No activities logged today yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─── Medium Ring Card (compact 3-ring layout) ──────────────────────────────────

@Composable
private fun MediumRingCard(
    title: String,
    score: Float?,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceL1)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(accentColor.copy(0.30f), DividerColor)
                ),
                shape = shape
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = title,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = OnSurfaceDim
            )
            Spacer(Modifier.height(8.dp))
            PercentRing(
                value       = score,
                label       = label,
                size        = 76.dp,
                strokeWidth = 7.dp
            )
        }
    }
}

// ─── Monitor Card ─────────────────────────────────────────────────────────────

@Composable
private fun MonitorCard(
    title: String,
    headline: String,
    subline: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, shape)
            .padding(12.dp)
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = OnSurfaceDim
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = OnBackground,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = OnSurfaceMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ─── Coach Insight Row (My Day) ───────────────────────────────────────────────

@Composable
private fun CoachInsightRow(
    insight: CoachEngine.CoachInsight,
    onClick: () -> Unit
) {
    val accentColor = when (insight.type) {
        CoachEngine.InsightType.WARNING  -> AlertRed
        CoachEngine.InsightType.SLEEP    -> SleepAccent
        CoachEngine.InsightType.TRAINING -> StrainAccent
        CoachEngine.InsightType.ACTIVITY -> ActivityAccent
        else -> RecoveryAccent
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, shape)
            .clickable(onClick = onClick)
    ) {
        // Accent left strip
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                .background(Brush.verticalGradient(listOf(accentColor, accentColor.copy(0.3f))))
        )
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "TODAY'S INSIGHT",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = accentColor
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    insight.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = OnBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    insight.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 2
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "View all insights",
                tint = OnSurfaceMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─── Activity Row (Today's Activities feed) ───────────────────────────────────

@Composable
private fun ActivityRow(
    icon: ImageVector,
    iconTint: Color,
    name: String,
    stat: String,
    timeRange: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = OnBackground
            )
            Text(
                timeRange,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
        Text(
            stat,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = iconTint
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun healthStatusHeadline(state: HomeUiState): String {
    val scores = state.latestScores ?: return "No data"
    val total = listOfNotNull(
        scores.recoveryScore, scores.sleepScore, scores.activityScore,
        scores.stressScore, scores.readinessScore
    ).size
    val normal = listOfNotNull(
        scores.recoveryScore, scores.sleepScore, scores.activityScore,
        scores.readinessScore
    ).count { it >= 60f }
    return when {
        total == 0 -> "No data yet"
        normal >= total - 1 -> "Within range"
        normal >= total / 2 -> "Partially normal"
        else -> "Needs attention"
    }
}

private fun healthStatusSubline(state: HomeUiState): String {
    val scores = state.latestScores ?: return "—"
    val total = listOfNotNull(
        scores.recoveryScore, scores.sleepScore, scores.activityScore, scores.readinessScore
    ).size
    val normal = listOfNotNull(
        scores.recoveryScore, scores.sleepScore, scores.activityScore, scores.readinessScore
    ).count { it >= 60f }
    return if (total > 0) "$normal/$total metrics normal" else "Sync to see status"
}

private fun healthStatusColor(state: HomeUiState): Color {
    val scores = state.latestScores ?: return OnSurfaceMuted
    val avg = listOfNotNull(
        scores.recoveryScore, scores.sleepScore, scores.activityScore
    ).average().takeIf { !it.isNaN() } ?: return OnSurfaceMuted
    return when {
        avg >= 70 -> ActivityAccent
        avg >= 50 -> StressAccent
        else -> AlertRed
    }
}

private fun stressHeadline(state: HomeUiState): String {
    val s = state.latestScores?.stressScore ?: return "—"
    return when {
        s >= 70 -> "High stress"
        s >= 45 -> "Moderate"
        else -> "Low stress"
    }
}

private fun stressStatusColor(state: HomeUiState): Color {
    val s = state.latestScores?.stressScore ?: return OnSurfaceMuted
    return when {
        s >= 70 -> AlertRed
        s >= 45 -> StressAccent
        else -> ActivityAccent
    }
}

private fun buildSleepTimeRange(bedMinute: Int?, wakeMinute: Int?): String {
    if (bedMinute == null || wakeMinute == null) return "Sleep session"
    val bed  = LocalTime.ofSecondOfDay((bedMinute  * 60L).coerceIn(0, 86399))
    val wake = LocalTime.ofSecondOfDay((wakeMinute * 60L).coerceIn(0, 86399))
    val fmt  = DateTimeFormatter.ofPattern("h:mm a")
    return "${bed.format(fmt)} – ${wake.format(fmt)}"
}

private fun buildSessionTimeRange(session: ExerciseSessionEntity): String {
    val start = java.time.Instant.ofEpochMilli(session.startMs)
        .let { VitalTime.zonedOf(it.toEpochMilli()) }.toLocalTime()
    val end = java.time.Instant.ofEpochMilli(session.endMs)
        .let { VitalTime.zonedOf(it.toEpochMilli()) }.toLocalTime()
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    return "${start.format(fmt)} – ${end.format(fmt)}"
}

private fun exerciseIcon(type: String): ImageVector = when {
    type.contains("RUN", ignoreCase = true)   -> Icons.AutoMirrored.Filled.DirectionsRun
    type.contains("BIKE", ignoreCase = true) ||
    type.contains("CYCL", ignoreCase = true)  -> Icons.AutoMirrored.Filled.DirectionsBike
    type.contains("SWIM", ignoreCase = true)  -> Icons.Filled.Pool
    type.contains("WALK", ignoreCase = true)  -> Icons.AutoMirrored.Filled.DirectionsWalk
    type.contains("YOGA", ignoreCase = true) ||
    type.contains("MEDITAT", ignoreCase = true) -> Icons.Filled.SelfImprovement
    else -> Icons.Filled.FitnessCenter
}
