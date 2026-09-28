package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.coach.CoachIntent
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.VitalCard
import com.example.vitalcoreai.ui.components.VitalScreenScaffold
import com.example.vitalcoreai.ui.components.VitalTopBar
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.AskCoachUiState
import com.example.vitalcoreai.ui.viewmodel.AskCoachViewModel
import com.example.vitalcoreai.ui.viewmodel.coachEvidenceLinks
import java.time.format.DateTimeFormatter

@Composable
fun AskCoachScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: AskCoachViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AskCoachContent(state, onBack, onNavigate, viewModel::select, viewModel::refresh)
}

/** Kept separate from storage so compact and large-text layouts can be exercised directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskCoachContent(
    state: AskCoachUiState,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onSelect: (CoachIntent) -> Unit,
    onRetry: () -> Unit
) {
    val answer = state.answer
    val context = state.context
    val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

    VitalScreenScaffold(topBar = {
        VitalTopBar(title = "Ask Coach", subtitle = "Your readings, explained", accent = ReadinessAccent, onBack = onBack)
    }) {
        item {
            Text(
                "Choose a question. Answers use your saved readings and work offline.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceDim
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CoachIntent.entries.filter { it != CoachIntent.FREEFORM }.forEach { intent ->
                    FilterChip(
                        selected = state.selected == intent,
                        onClick = { onSelect(intent) },
                        label = { Text(intent.displayQuestion) }
                    )
                }
            }
        }

        when {
            state.isLoading -> item {
                CircularProgressIndicator(color = ReadinessAccent)
                Text("Reading your saved history…", color = OnSurfaceDim)
            }
            state.error != null -> item {
                VitalCard(modifier = Modifier.fillMaxWidth()) {
                    Text(state.error, color = OnBackground)
                    TextButton(onClick = onRetry) { Text("Try again") }
                    TextButton(onClick = { onNavigate(Routes.DATA_SOURCES) }) { Text("Open Data Sources") }
                }
            }
            answer != null && context != null -> {
                item {
                    VitalCard(modifier = Modifier.fillMaxWidth(), accent = ReadinessAccent, topStrip = true) {
                        Text(
                            answer.headline,
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnBackground,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "${state.confidence.lowercase().replaceFirstChar { it.uppercase() }} confidence" +
                                if (!answer.hasSufficientData) " · More evidence needed" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = ReadinessAccent
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Text(answer.body, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                    }
                }
                item {
                    VitalCard(modifier = Modifier.fillMaxWidth()) {
                        Text("EVIDENCE", style = VitalCoreType.eyebrow, color = OnSurfaceDim,
                            modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(Spacing.sm))
                        val day = context.evidence?.dateEpochDay ?: context.today.dateEpochDay
                        val date = VitalTime.dateOf(day).format(dateFormat)
                        val evidenceLabel = if (state.selected == CoachIntent.WEEKLY_REVIEW) {
                            val start = VitalTime.dateOf(day - 6).format(dateFormat)
                            "$start – $date · ${context.evidence?.scoredDaysThisWeek ?: 0}/7 scored days"
                        } else "Readings for $date"
                        Text(evidenceLabel, color = OnBackground, style = MaterialTheme.typography.bodyLarge)
                        val measurement = context.evidence?.latestMeasurementMs
                        Text(
                            if (measurement == null) "No wearable measurement time available for this date."
                            else "Latest cached measurement: ${VitalTime.zonedOf(measurement).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}",
                            color = OnSurfaceDim,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (context.today.isCalibrating) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text("Still learning your baseline · ${context.today.daysOfHistory} recorded days", color = OnSurfaceDim)
                        }
                        coachEvidenceLinks(answer.citations).forEach { link ->
                            TextButton(onClick = { onNavigate(link.route) }, modifier = Modifier.fillMaxWidth()) {
                                Text(link.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (answer.followUps.isNotEmpty()) {
                    item {
                        Text("EXPLORE NEXT", style = VitalCoreType.eyebrow, color = OnSurfaceDim,
                            modifier = Modifier.semantics { heading() })
                        answer.followUps.forEach { question ->
                            TextButton(onClick = { onSelect(CoachIntent.classify(question)) }) { Text(question) }
                        }
                    }
                }
            }
        }
    }
}
