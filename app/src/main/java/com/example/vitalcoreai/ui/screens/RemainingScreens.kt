package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*

private val PremiumCardShape = RoundedCornerShape(20.dp)

@Composable
private fun PremiumCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PremiumCardShape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, PremiumCardShape)
    ) { content() }
}

// ─── Activity Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityStatCard("Steps", state.steps?.toString() ?: "—", "steps", Modifier.weight(1f))
                    ActivityStatCard("Calories", state.calories?.toString() ?: "—", "kcal", Modifier.weight(1f))
                    ActivityStatCard("Distance", state.distanceKm?.let { String.format("%.1f", it) } ?: "—", "km", Modifier.weight(1f))
                }
            }
            item {
                state.activityScore?.let {
                    ScoreCard("Activity Score", it, subtitle = "vs your 30-day avg")
                }
            }
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("14-Day Steps History", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            VicoPrimaryChart(values = state.chartValues, color = VitalGreen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityStatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(PremiumCardShape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, PremiumCardShape)
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = ActivityAccent)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Training Load", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityStatCard("Load", state.todayLoad?.let { "${it.toInt()}" } ?: "—", "/100", Modifier.weight(1f))
                    ActivityStatCard("ACWR", state.acwr?.let { String.format("%.2f", it) } ?: "—", "ratio", Modifier.weight(1f))
                }
            }
            state.acwrZone?.let { zone ->
                item {
                    val (zoneLabel, zoneColor) = when (zone) {
                        "OPTIMAL"        -> "Optimal Zone 🎯" to VitalGreen
                        "UNDER_TRAINING" -> "Under-training" to VitalAmber
                        "CAUTION"        -> "Caution ⚠️" to VitalOrange
                        "DANGER"         -> "Danger Zone 🚨" to VitalRed
                        else             -> zone to OnSurfaceDim
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("ACWR Zone", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                                Text(zoneLabel, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = zoneColor)
                            }
                        }
                    }
                }
            }
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("14-Day Training Load", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            VicoPrimaryChart(values = state.chartValues, color = VitalOrange)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
                        Text("About ACWR", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Acute:Chronic Workload Ratio = 7-day rolling avg ÷ 28-day rolling avg. " +
                            "Optimal zone: 0.8–1.3. Ratios above 1.5 are associated with elevated injury risk in research literature.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                        )
                    }
                }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biological Age", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                actions = {
                    IconButton(onClick = { showInfoSheet = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "About this score", tint = BioAgeAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ─── Permanent disclaimer ─────────────────────────────────────
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = VitalAmber.copy(alpha = 0.1f)),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, null, tint = VitalAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.disclaimer,
                            style = MaterialTheme.typography.bodySmall,
                            color = VitalAmber
                        )
                    }
                }
            }

            item {
                if (state.isLoading) {
                    CircularProgressIndicator(color = RecoveryAccent)
                } else if (state.biologicalAge != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Estimated Fitness Age", style = MaterialTheme.typography.titleMedium, color = OnSurfaceDim)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${state.biologicalAge}",
                            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                            color = if (state.ageDiff <= 0) VitalGreen else VitalAmber
                        )
                        Spacer(Modifier.height(4.dp))
                        val diffText = when {
                            state.ageDiff < 0 -> "${-state.ageDiff} years younger than calendar age"
                            state.ageDiff > 0 -> "${state.ageDiff} years older than calendar age"
                            else -> "Equal to calendar age"
                        }
                        Text(diffText, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
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
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("VO₂ Max Estimate", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(4.dp))
                            Text("${vo2.toInt()} mL/kg/min (±10%)", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = VitalBlue)
                            Text("ESTIMATE — not a clinical measurement", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Coach insights ─────────────────────────────────────────
            if (state.insights.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        "Sync health data to generate personalized insights.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted
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
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.medium
                    ) {
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
                                color = VitalGreen.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "+${suggestion.projectedDelta} pts",
                                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = VitalGreen
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
                    "ℹ️ Insights are rule-based, personalized recommendations derived from your own data trends. Not generated AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        all.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { achievement ->
                    AchievementBadge(achievement, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
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
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

// ─── History Screen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.scores.isEmpty()) {
                item { Text("No history yet. Keep syncing daily.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted) }
            } else {
                items(state.scores.reversed()) { score ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(
                                    java.time.LocalDate.ofEpochDay(score.dateEpochDay).toString(),
                                    style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    score.recoveryScore?.let { Text("R: ${it.toInt()}", style = MaterialTheme.typography.bodySmall, color = VitalBlue) }
                                    score.readinessScore?.let { Text("Rd: ${it.toInt()}", style = MaterialTheme.typography.bodySmall, color = VitalGreen) }
                                    score.sleepScore?.let { Text("S: ${it.toInt()}", style = MaterialTheme.typography.bodySmall, color = VitalPurple) }
                                }
                            }
                            score.activityScore?.let {
                                CompactScoreRing(score = it, size = 40.dp)
                            }
                        }
                    }
                }
            }
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly Report", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (report == null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📅", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Weekly report will appear after 7 days of data.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceMuted
                            )
                        }
                    }
                }
            } else {
                // ── Hero score ring + delta ────────────────────────────────
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "This Week",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = OnBackground
                            )
                            Spacer(Modifier.height(12.dp))
                            report!!.weeklyHealthScore?.let { score ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AnimatedScoreRing(score, "WEEK", size = 100.dp)
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("Health Score", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                                        report!!.deltaFromPreviousWeek?.let { delta ->
                                            val sign = if (delta > 0) "+" else ""
                                            Text(
                                                "$sign${delta.toInt()} from last week",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (delta >= 0) VitalGreen else VitalRed
                                            )
                                        }
                                    }
                                }
                            }
                            report!!.explanation?.let {
                                Spacer(Modifier.height(12.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                            }
                        }
                    }
                }

                // ── B10: Sub-score averages ────────────────────────────────
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricStatCard("Recovery",  report!!.avgRecovery?.let  { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Readiness", report!!.avgReadiness?.let { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Sleep",     report!!.avgSleep?.let     { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Activity",  report!!.avgActivity?.let  { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                    }
                }

                // ── B10: Highlights ───────────────────────────────────────
                val highlights = report!!.highlights?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
                if (highlights.isNotEmpty()) {
                    item { SectionHeader("Highlights") }
                    items(highlights) { highlight ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("•", color = VitalBlue, fontWeight = FontWeight.Bold)
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
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                                shape = MaterialTheme.shapes.medium
                            ) {
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
}

// ─── Monthly Report Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportScreen(
    viewModel: MonthlyReportViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val report by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly Report", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (report == null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📆", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Monthly report will appear after 30 days of data.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceMuted
                            )
                        }
                    }
                }
            } else {
                // ── Hero ring ─────────────────────────────────────────────
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "This Month",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = OnBackground
                            )
                            Spacer(Modifier.height(12.dp))
                            report!!.monthlyHealthScore?.let { score ->
                                AnimatedScoreRing(score, "MONTH", size = 120.dp)
                            }
                            report!!.deltaFromPreviousMonth?.let { delta ->
                                Spacer(Modifier.height(8.dp))
                                val sign = if (delta > 0) "+" else ""
                                Text(
                                    "$sign${delta.toInt()} vs last month",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (delta >= 0) VitalGreen else VitalRed
                                )
                            }
                            report!!.explanation?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                }

                // ── B10: Sub-score averages ────────────────────────────────
                item {
                    SectionHeader("Monthly Averages")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricStatCard("Recovery",  report!!.avgRecovery?.let  { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Readiness", report!!.avgReadiness?.let { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Sleep",     report!!.avgSleep?.let     { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                        MetricStatCard("Activity",  report!!.avgActivity?.let  { "${it.toInt()}" } ?: "—", "", Modifier.weight(1f))
                    }
                }

                // ── B10: Personal records ──────────────────────────────────
                report!!.personalRecords?.let { records ->
                    if (records.isNotBlank()) {
                        item { SectionHeader("Personal Records") }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                                shape = MaterialTheme.shapes.medium
                            ) {
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
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("•", color = VitalBlue, fontWeight = FontWeight.Bold)
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
}

// ─── Settings Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionHeader("Personal Settings") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

            // ── Data & Sync section ────────────────────────────────────────
            item { SectionHeader("Data & Sync") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Re-sync 30-Day History",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = OnBackground
                        )
                        Text(
                            "Forces a full 30-day backfill from Health Connect. Use if you see missing history or after granting permissions on a new device.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { viewModel.forceBackfill() },
                            enabled = !state.isBackfilling,
                            colors = ButtonDefaults.buttonColors(containerColor = RecoveryAccent)
                        ) {
                            if (state.isBackfilling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = OnBackground
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Syncing…")
                            } else {
                                Text("Force Re-Backfill")
                            }
                        }
                        state.backfillResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.startsWith("✓")) VitalGreen else VitalRed)
                        }
                    }
                }
            }

            // B9 — CSV export
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            colors = ButtonDefaults.buttonColors(containerColor = RecoveryAccent)
                        ) {
                            if (state.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = OnBackground
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Exporting…")
                            } else {
                                Text("Export as CSV")
                            }
                        }
                        state.exportResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (it.startsWith("✓")) VitalGreen else VitalRed)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
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
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
            Text("${value.toInt()} $unit",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = VitalBlue)
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
            Text(description, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RecoveryAccent,
                checkedTrackColor = RecoveryAccent.copy(alpha = 0.4f),
                uncheckedTrackColor = SurfaceL3
            )
        )
    }
}
