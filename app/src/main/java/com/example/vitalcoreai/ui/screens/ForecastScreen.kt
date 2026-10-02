package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.VitalCard
import com.example.vitalcoreai.ui.components.VitalScreenScaffold
import com.example.vitalcoreai.ui.components.VitalTopBar
import com.example.vitalcoreai.ui.viewmodel.HomeViewModel

/**
 * The forecast in full.
 *
 * The Home card shows the range and the top few drivers; this screen shows all of them, plus
 * the risks. It reads the same persisted columns through [HomeViewModel] rather than
 * recomputing, so the number here and the number on Home cannot disagree.
 */
@Composable
fun ForecastScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    VitalScreenScaffold(
        topBar = {
            VitalTopBar(
                title = "Tomorrow",
                subtitle = "A projection, not a prediction",
                accent = ReadinessAccent,
                onBack = onBack
            )
        }
    ) {
        item { ForecastCard(forecast = state.forecast) }

        // Improvement #11: Share forecast as plain text
        item {
            val forecast = state.forecast
            if (forecast != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val text = buildString {
                            appendLine("VitalCoreAI tomorrow forecast")
                            appendLine("Range: ${forecast.low}–${forecast.high} / 100")
                            forecast.drivers.take(3).forEach { d -> appendLine("  \u2022 ${d.name}: ${if (d.points >= 0f) "+" else ""}${d.points.toInt()} pts") }
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share forecast"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Share forecast") }
            }
        }

        item {
            VitalCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "HOW THIS IS BUILT",
                    style = VitalCoreType.eyebrow,
                    color = OnSurfaceDim,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "The centre of the range is today's readiness adjusted by seven named " +
                        "drivers — sleep opportunity, sleep debt, training load, resting-heart-rate " +
                        "deviation, consecutive training days, how you rated today, and how far " +
                        "today sits from your own recent average.\n\n" +
                        "The width comes from your own day-to-day variability. A steady few weeks " +
                        "narrows it; an erratic few weeks widens it. That width is the honest part: " +
                        "a single number would imply a precision the data does not support.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
            }
        }

        if (state.trends.isNotEmpty()) {
            item {
                Text(
                    "RECENT DIRECTION",
                    style = VitalCoreType.eyebrow,
                    color = OnSurfaceDim,
                    modifier = Modifier.semantics { heading() }
                )
            }
            items(state.trends.size) { index -> TrendCard(trend = state.trends[index]) }
        }
    }
}
