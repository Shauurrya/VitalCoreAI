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
import com.example.vitalcoreai.ui.viewmodel.HomeViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            "VitalCore AI",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = OnBackground
                        )
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
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
                    MomentumStrip(
                        recoveryMomentum = state.recoveryMomentum,
                        sleepMomentum = state.sleepMomentum,
                        trainingMomentum = state.trainingMomentum
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
                        else -> VitalRed
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
                            AnimatedScoreRing(
                                score = eb,
                                label = "",
                                size = 48.dp,
                                strokeWidth = 5.dp
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

            // Quick Actions Row
            item {
                Spacer(Modifier.height(8.dp))
                QuickActionsRow(onNavigate = onNavigate)
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
            AnimatedScoreRing(
                score       = score ?: 0f,
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
        CoachEngine.InsightType.WARNING  -> VitalRed
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
        avg >= 70 -> VitalGreen
        avg >= 50 -> VitalAmber
        else -> VitalRed
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
        s >= 70 -> VitalRed
        s >= 45 -> VitalAmber
        else -> VitalGreen
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
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val end = java.time.Instant.ofEpochMilli(session.endMs)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    return "${start.format(fmt)} – ${end.format(fmt)}"
}

private fun exerciseIcon(type: String): ImageVector = when {
    type.contains("RUN", ignoreCase = true)   -> Icons.AutoMirrored.Filled.DirectionsRun
    type.contains("BIKE", ignoreCase = true) ||
    type.contains("CYCL", ignoreCase = true)  -> Icons.AutoMirrored.Filled.DirectionsBike
    type.contains("SWIM", ignoreCase = true)  -> Icons.Filled.Pool
    type.contains("WALK", ignoreCase = true)  -> Icons.Filled.DirectionsWalk
    type.contains("YOGA", ignoreCase = true) ||
    type.contains("MEDITAT", ignoreCase = true) -> Icons.Filled.SelfImprovement
    else -> Icons.Filled.FitnessCenter
}
