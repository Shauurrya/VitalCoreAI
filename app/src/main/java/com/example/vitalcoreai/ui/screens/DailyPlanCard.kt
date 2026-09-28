package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.coach.DailyPlan
import com.example.vitalcoreai.coach.PlanActivity
import com.example.vitalcoreai.coach.PlanStatus
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.VitalCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DailyPlanCard(
    plan: DailyPlan,
    checkInCompleted: Boolean,
    onStatus: (PlanStatus) -> Unit,
    onChoose: (PlanActivity) -> Unit,
    onSleep: (Int, PlanStatus) -> Unit,
    onCheckIn: () -> Unit,
    onCoach: () -> Unit,
    onSources: () -> Unit
) {
    var why by rememberSaveable(plan.day) { mutableStateOf(false) }
    var adjust by rememberSaveable(plan.day) { mutableStateOf(false) }
    var editSleep by rememberSaveable(plan.day) { mutableStateOf(false) }
    VitalCard(modifier = Modifier.fillMaxWidth(), accent = RecoveryAccent) {
        Text("YOUR DAILY PLAN", style = VitalCoreType.eyebrow, color = RecoveryAccent)
        Text(plan.focus, style = MaterialTheme.typography.headlineSmall, color = OnBackground)
        Text("For ${LocalDate.ofEpochDay(plan.day).format(DateTimeFormatter.ofPattern("EEE, MMM d"))}",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(12.dp))
        val activity = plan.activity
        Text(activity.title, style = MaterialTheme.typography.titleLarge, color = OnBackground)
        Text("${activity.status.label} · ${activity.intensity} intensity", style = MaterialTheme.typography.labelMedium, color = RecoveryAccent)
        if (activity.provisional) {
            Text("Provisional guidance · limited current evidence", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        } else {
            Text("${activity.confidence.lowercase().replaceFirstChar { it.uppercase() }} confidence",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        }
        Text(activity.detail, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        if (plan.guidanceChanged) {
            Text("Your saved choice is unchanged. Today's suggestion is ${plan.suggestedActivity.title.lowercase()}. Review it before starting.",
                style = MaterialTheme.typography.bodySmall, color = SleepAccent)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { why = !why }) { Text(if (why) "Hide reasons" else "Why this today?") }
            TextButton(onClick = { adjust = true }) { Text("Adjust activity") }
        }
        if (why) {
            activity.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim) }
            Text(activity.evidenceDay?.let { "Score evidence: ${LocalDate.ofEpochDay(it)}" } ?: "No score evidence yet",
                style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            if (plan.guidanceChanged) {
                Spacer(Modifier.height(8.dp))
                Text("Updated suggestion: ${plan.suggestedActivity.title}",
                    style = MaterialTheme.typography.titleSmall, color = OnBackground)
                Text(plan.suggestedActivity.detail, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                plan.suggestedActivity.reasons.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
                Text("Use Adjust activity to review and save a choice with the updated guidance.",
                    style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            TextButton(onClick = onSources) { Text("Review data sources") }
        }
        PlanControls(activity.status, "activity", onStatus)
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
        Text("Tonight · ${VitalTime.formatDurationMinutes(plan.sleepMinutes)} sleep target",
            style = MaterialTheme.typography.titleMedium, color = OnBackground)
        Text("${plan.sleepStatus.label} · based on your chosen sleep need",
            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        TextButton(onClick = { editSleep = true }) { Text("Adjust sleep target") }
        PlanControls(plan.sleepStatus, "sleep plan") { onSleep(plan.sleepMinutes, it) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
        Text(if (checkInCompleted) "Check-in complete" else "Add how you feel today",
            style = MaterialTheme.typography.titleMedium, color = OnBackground)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckIn) { Text(if (checkInCompleted) "View/edit check-in" else "Start check-in") }
            TextButton(onClick = onCoach) { Text("Ask Coach") }
        }
    }
    if (adjust) {
        AlertDialog(onDismissRequest = { adjust = false }, title = { Text("Choose today's activity") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose an option that fits how you feel. This saves a plan; it does not log a workout.")
                    listOfNotNull(plan.suggestedActivity, plan.alternative,
                        plan.suggestedActivity.copy(title = "Full Rest", intensity = "Low", detail = "No structured training planned today."))
                        .distinctBy { it.title }.forEach { choice ->
                            OutlinedButton(onClick = { onChoose(choice); adjust = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(choice.title)
                            }
                        }
                }
            }, confirmButton = { TextButton(onClick = { adjust = false }) { Text("Close") } })
    }
    if (editSleep) {
        var minutes by remember(plan.sleepMinutes) { mutableFloatStateOf(plan.sleepMinutes.toFloat()) }
        AlertDialog(onDismissRequest = { editSleep = false }, title = { Text("Tonight's sleep target") },
            text = {
                Column {
                    Text(VitalTime.formatDurationMinutes(minutes.roundToInt()))
                    Slider(value = minutes, onValueChange = { minutes = (it / 15).roundToInt() * 15f }, valueRange = 240f..720f, steps = 31)
                    Text("Applies to this plan only. Your usual sleep need stays in Settings.")
                }
            }, confirmButton = { TextButton(onClick = { onSleep(minutes.roundToInt(), PlanStatus.SAVED); editSleep = false }) { Text("Save target") } },
            dismissButton = { TextButton(onClick = { editSleep = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanControls(status: PlanStatus, name: String, onStatus: (PlanStatus) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (status) {
            PlanStatus.SUGGESTED -> {
                OutlinedButton(onClick = { onStatus(PlanStatus.SAVED) }) { Text("Save $name") }
                TextButton(onClick = { onStatus(PlanStatus.COMPLETED) }) { Text("Complete $name") }
                TextButton(onClick = { onStatus(PlanStatus.DISMISSED) }) { Text("Dismiss $name") }
            }
            PlanStatus.SAVED -> {
                OutlinedButton(onClick = { onStatus(PlanStatus.COMPLETED) }) { Text("Complete $name") }
                TextButton(onClick = { onStatus(PlanStatus.DISMISSED) }) { Text("Dismiss $name") }
            }
            PlanStatus.COMPLETED -> TextButton(onClick = { onStatus(PlanStatus.SAVED) }) { Text("Undo $name completion") }
            PlanStatus.DISMISSED -> TextButton(onClick = { onStatus(PlanStatus.SAVED) }) { Text("Restore $name") }
        }
    }
}
