package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*

// ─── Shared Score Detail Screen template ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreDetailScreen(
    title: String,
    state: ScoreDetailUiState,
    chartColor: androidx.compose.ui.graphics.Color = RecoveryAccent,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RecoveryAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RecoveryAccent, strokeWidth = 2.dp)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Big ring + confidence
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PercentRing(
                        value = state.score,
                        label = title.uppercase(),
                        size = 150.dp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.score?.let {
                            Text(
                                "${it.toInt()} / 100",
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = recoveryTierColor(it)
                            )
                        } ?: Text("No data", style = MaterialTheme.typography.headlineMedium, color = OnSurfaceMuted)
                        ConfidenceBadge(state.confidence)
                        TrendArrow(state.trendDirection)
                    }
                }
            }

            // Explanation
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Explanation", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(6.dp))
                        Text(state.explanation, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                    }
                }
            }

            // Chart
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("14-Day Trend", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            TrendLineChart(values = state.chartValues, accent = chartColor)
                        }
                    }
                }
            }

            // Breakdown
            if (state.breakdown.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Score Breakdown", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            state.breakdown.forEach { factor ->
                                BreakdownRow(factor = factor)
                                HorizontalDivider(color = SurfaceL3, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Individual Screen Composables ───────────────────────────────────────────

@Composable
fun RecoveryScreen(
    viewModel: RecoveryViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScoreDetailScreen("Recovery", state, RecoveryAccent, onBack)
}

@Composable
fun ReadinessScreen(
    viewModel: ReadinessViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScoreDetailScreen("Readiness", state, RecoveryAccent, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    viewModel: SleepViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sleepDebt by viewModel.sleepDebt.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Score ring
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PercentRing(
                        value = state.score,
                        label = "SLEEP",
                        size = 160.dp,
                        strokeWidth = 12.dp,
                        accent = SleepAccent
                    )
                }
            }

            // Confidence badge
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ConfidenceBadge(state.confidence)
                }
            }

            // Explanation
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Explanation", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(6.dp))
                        Text(state.explanation, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                    }
                }
            }

            // Chart
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("14-Day Sleep Score", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            TrendLineChart(values = state.chartValues, accent = SleepAccent)
                        }
                    }
                }
            }

            // Breakdown
            if (state.breakdown.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Score Breakdown", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            state.breakdown.forEach { factor ->
                                BreakdownRow(factor = factor)
                                HorizontalDivider(color = SurfaceL3, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            // ── Part 10: Sleep Debt Section ──────────────────────────────
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(listOf(SleepAccent.copy(0.3f), DividerColor))
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "SLEEP DEBT",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = SleepAccent
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SleepDebtCard(
                                label = "Recommended",
                                value = "${sleepDebt.recommendedHours}h ${sleepDebt.recommendedMinutesRemainder}m",
                                color = SleepAccent,
                                modifier = Modifier.weight(1f)
                            )
                            SleepDebtCard(
                                label = "Last Night",
                                value = if (sleepDebt.lastNightMinutes != null)
                                    "${sleepDebt.lastNightMinutes!! / 60}h ${sleepDebt.lastNightMinutes!! % 60}m" else "—",
                                color = if (sleepDebt.dailyDeficit > 0) AlertRed else ActivityAccent,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SleepDebtCard(
                                label = "Daily Deficit",
                                value = if (sleepDebt.dailyDeficit > 0)
                                    "-${sleepDebt.dailyDeficit / 60}h ${sleepDebt.dailyDeficit % 60}m"
                                else "+${(-sleepDebt.dailyDeficit) / 60}h ${(-sleepDebt.dailyDeficit) % 60}m",
                                color = if (sleepDebt.dailyDeficit > 0) AlertRed else ActivityAccent,
                                modifier = Modifier.weight(1f)
                            )
                            SleepDebtCard(
                                label = "7-Day Debt",
                                value = "${sleepDebt.rollingDebtMinutes / 60}h ${sleepDebt.rollingDebtMinutes % 60}m",
                                color = when {
                                    sleepDebt.rollingDebtMinutes > 300 -> AlertRed
                                    sleepDebt.rollingDebtMinutes > 120 -> StressAccent
                                    else -> ActivityAccent
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            sleepDebt.recommendation,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SleepDebtCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(12.dp)
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = color)
        }
    }
}

@Composable
fun StressScreen(
    viewModel: StressViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScoreDetailScreen("Stress", state, StressAccent, onBack)
}

// ─── Heart Screen (custom layout) ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartScreen(
    viewModel: HeartViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heart Rate", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricStatCard("Resting HR Today", state.restingHR?.toString() ?: "—", "bpm", Modifier.weight(1f))
                    MetricStatCard("7-Day Avg", state.avgHR7Day?.let { "${it.toInt()}" } ?: "—", "bpm", Modifier.weight(1f))
                    MetricStatCard("30-Day Avg", state.avgHR30Day?.let { "${it.toInt()}" } ?: "—", "bpm", Modifier.weight(1f))
                }
            }
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp)) {
                            Text("30-Day Resting HR", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            TrendLineChart(values = state.chartValues, accent = AlertRed)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceL1), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "ℹ️ HR zones, trends, and training load analysis are shown on the Training and Activity screens.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MetricStatCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceL1),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = AlertRed)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
        }
    }
}
