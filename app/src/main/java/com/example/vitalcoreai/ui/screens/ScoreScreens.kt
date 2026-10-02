package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*

@Composable
internal fun ScoreDetailScreen(
    title: String,
    state: ScoreDetailUiState,
    chartColor: Color = RecoveryAccent,
    onBack: () -> Unit
) {
    val isStress = title == "Stress"
    val scoreAccent = if (isStress) stressTierColor(state.score) else chartColor

    VitalScreenScaffold(
        topBar = { VitalTopBar(title = title, subtitle = "Your daily performance", accent = chartColor, onBack = onBack) }
    ) {
        if (state.isLoading) {
            item { ScoreLoading() }
        } else {
            item {
                ScoreHero(
                    title = title,
                    state = state,
                    accent = scoreAccent,
                    useTierColors = title == "Recovery",
                    caption = if (isStress) stressTierLabel(state.score) else null,
                    higherIsBetter = !isStress
                )
            }
            item { ScoreInsight(state.explanation, chartColor) }
            if (state.chartValues.isNotEmpty()) {
                item { ScoreHistory(state.chartValues, chartColor) }
            }
            if (state.breakdown.isNotEmpty()) {
                item { ScoreBreakdown(state.breakdown, chartColor, higherIsBetter = !isStress) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreHero(
    title: String,
    state: ScoreDetailUiState,
    accent: Color,
    useTierColors: Boolean = false,
    caption: String? = null,
    higherIsBetter: Boolean = true
) {
    VitalCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.07f), Color.Transparent)))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LATEST SCORE", style = VitalCoreType.eyebrow, color = OnSurfaceDim, modifier = Modifier.weight(1f))
                Text("0–100", style = VitalCoreType.monoTiny, color = OnSurfaceMuted)
            }
            Spacer(Modifier.height(24.dp))
            PercentRing(
                value = state.score,
                label = title,
                size = 220.dp,
                strokeWidth = 13.dp,
                accent = accent,
                useTierColors = useTierColors,
                caption = caption
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = HairlineColor)
            Spacer(Modifier.height(16.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConfidenceBadge(state.confidence)
                if (state.chartValues.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrendArrow(state.trendDirection, higherIsBetter = higherIsBetter)
                        Text(
                            text = when (state.trendDirection) {
                                TrendDirection.UP -> "Trending up"
                                TrendDirection.DOWN -> "Trending down"
                                TrendDirection.NEUTRAL -> "Steady"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreInsight(explanation: String, accent: Color) {
    VitalSectionCard(title = "What this means", accent = accent, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodyLarge,
            color = OnBackground
        )
    }
}

@Composable
private fun ScoreHistory(values: List<Float>, accent: Color) {
    VitalSectionCard(
        title = "Score history",
        subtitle = "Latest ${values.size} recorded scores · out of 100",
        accent = accent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(6.dp))
        TrendLineChart(values = values, accent = accent, yRange = 0f..100f, height = 172.dp)
    }
}

@Composable
private fun ScoreBreakdown(factors: List<ScoreFactor>, accent: Color, higherIsBetter: Boolean = true) {
    VitalSectionCard(
        title = "Behind your score",
        subtitle = "Your contributing factors",
        accent = accent,
        modifier = Modifier.fillMaxWidth()
    ) {
        factors.forEachIndexed { index, factor ->
            BreakdownRow(
                factor = factor,
                accent = accent,
                scoreColor = if (higherIsBetter) recoveryTierColor(factor.score) else stressTierColor(factor.score)
            )
            if (index < factors.lastIndex) {
                HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ScoreLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VitalSkeleton(Modifier.fillMaxWidth().height(360.dp))
        VitalSkeleton(Modifier.fillMaxWidth().height(120.dp))
    }
}

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
    ScoreDetailScreen("Readiness", state, ReadinessAccent, onBack)
}

@Composable
fun SleepScreen(
    viewModel: SleepViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sleepDebt by viewModel.sleepDebt.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = { VitalTopBar(title = "Sleep", subtitle = "Rest, recharge, repeat", accent = SleepAccent, onBack = onBack) }
    ) {
        if (state.isLoading) {
            item { ScoreLoading() }
        } else {
            item { ScoreHero(title = "Sleep", state = state, accent = SleepAccent) }
            item { ScoreInsight(state.explanation, SleepAccent) }
            item {
                VitalSectionCard(
                    title = "Sleep balance",
                    subtitle = "Your need, rest, and accumulated debt",
                    accent = SleepAccent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DetailMetricPair(first = { cellModifier ->
                        SleepDebtCard(
                            label = "Recommended",
                            value = "${sleepDebt.recommendedHours}h ${sleepDebt.recommendedMinutesRemainder}m",
                            color = SleepAccent,
                            modifier = cellModifier
                        )
                    }, second = { cellModifier ->
                        SleepDebtCard(
                            label = "Last night",
                            value = sleepDebt.lastNightMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "—",
                            color = if (sleepDebt.dailyDeficit > 0) StressAccent else SleepAccent,
                            modifier = cellModifier
                        )
                    })
                    Spacer(Modifier.height(10.dp))
                    DetailMetricPair(first = { cellModifier ->
                        SleepDebtCard(
                            label = "Daily deficit",
                            value = if (sleepDebt.lastNightMinutes == null) "—"
                            else if (sleepDebt.dailyDeficit > 0)
                                "−${sleepDebt.dailyDeficit / 60}h ${sleepDebt.dailyDeficit % 60}m"
                            else "+${(-sleepDebt.dailyDeficit) / 60}h ${(-sleepDebt.dailyDeficit) % 60}m",
                            color = if (sleepDebt.dailyDeficit > 0) StressAccent else ActivityAccent,
                            modifier = cellModifier
                        )
                    }, second = { cellModifier ->
                        SleepDebtCard(
                            label = "7-day debt",
                            value = "${sleepDebt.rollingDebtMinutes / 60}h ${sleepDebt.rollingDebtMinutes % 60}m",
                            color = when {
                                sleepDebt.rollingDebtMinutes > 300 -> AlertRed
                                sleepDebt.rollingDebtMinutes > 120 -> StressAccent
                                else -> ActivityAccent
                            },
                            modifier = cellModifier
                        )
                    })
                    Spacer(Modifier.height(18.dp))
                    Text(sleepDebt.recommendation, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
                }
            }
            if (state.chartValues.isNotEmpty()) {
                item { ScoreHistory(state.chartValues, SleepAccent) }
            }
            if (state.breakdown.isNotEmpty()) {
                item { ScoreBreakdown(state.breakdown, SleepAccent) }
            }
        }
    }
}

@Composable
private fun SleepDebtCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceL2.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
        Spacer(Modifier.height(10.dp))
        Text(value, style = VitalCoreType.metricSmall, color = color)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeartScreen(
    viewModel: HeartViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = { VitalTopBar(title = "Heart rate", subtitle = "Know your baseline", accent = HeartAccent, onBack = onBack) }
    ) {
        if (state.isLoading) {
            item { ScoreLoading() }
        } else {
            item {
                VitalCard(
                    modifier = Modifier.fillMaxWidth(),
                    accent = HeartAccent,
                    contentPadding = PaddingValues(24.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("RESTING HEART RATE", style = VitalCoreType.eyebrow, color = OnSurfaceDim, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = HeartAccent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(state.restingHR?.toString() ?: "—", style = VitalCoreType.metricHero, color = OnBackground, modifier = Modifier.alignByBaseline())
                        Text("bpm", style = VitalCoreType.metricUnit, color = HeartAccent, modifier = Modifier.alignByBaseline())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.restingHR != null) "Latest resting measurement" else "No measurement yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            item {
                DetailMetricPair(
                    first = { MetricStatCard("7-day average", state.avgHR7Day?.let { "${it.toInt()}" } ?: "—", "bpm", it) },
                    second = { MetricStatCard("30-day average", state.avgHR30Day?.let { "${it.toInt()}" } ?: "—", "bpm", it) }
                )
            }
            if (state.chartValues.isNotEmpty()) {
                item {
                    VitalSectionCard(
                        title = "Resting heart rate",
                        subtitle = "Latest ${state.chartValues.size} recorded values · bpm",
                        accent = HeartAccent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TrendLineChart(values = state.chartValues, accent = HeartAccent, height = 180.dp)
                    }
                }
            }
            // Improvement #6: 14-day HRV trend chart
            if (state.hrvChartValues.isNotEmpty()) {
                item {
                    VitalSectionCard(
                        title = "HRV (RMSSD) trend",
                        subtitle = "Latest ${state.hrvChartValues.size} days \u00b7 ms",
                        modifier = Modifier.fillMaxWidth(),
                        accent = HeartAccent
                    ) {
                        state.latestHrvRmssdMs?.let { hrv ->
                            Text(
                                "${hrv.toInt()} ms today",
                                style = MaterialTheme.typography.labelMedium,
                                color = HeartAccent
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        TrendLineChart(values = state.hrvChartValues, accent = HeartAccent, height = 160.dp)
                    }
                }
            }
            // Improvement #7: 30-day SpO2 trend with reference note
            if (state.spo2ChartValues.isNotEmpty()) {
                item {
                    VitalSectionCard(
                        title = "Blood oxygen (SpO\u2082) trend",
                        subtitle = "Latest ${state.spo2ChartValues.size} days \u00b7 % (normal 95\u2013100%)",
                        modifier = Modifier.fillMaxWidth(),
                        accent = ReadinessAccent
                    ) {
                        state.latestSpo2?.let { spo2 ->
                            Text(
                                "${spo2.toInt()}% today",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (spo2 >= 95f) ReadinessAccent else AlertRed
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        TrendLineChart(values = state.spo2ChartValues, accent = ReadinessAccent, yRange = 88f..100f, height = 160.dp)
                    }
                }
            }
            item {
                VitalSectionCard(title = "Your heart in context", accent = HeartAccent, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Explore heart rate zones, trends, and training load on the Training and Activity screens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailMetricPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp * fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun MetricStatCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = OnBackground
) {
    VitalCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Spacer(Modifier.height(10.dp))
            Text(value, style = VitalCoreType.metricMedium, color = accent)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(unit, style = VitalCoreType.metricUnit, color = OnSurfaceMuted)
            }
        }
    }
}
