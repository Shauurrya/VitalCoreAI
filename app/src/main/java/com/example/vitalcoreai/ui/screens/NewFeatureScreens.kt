package com.example.vitalcoreai.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.analytics.*
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*

private val CardShape = RoundedCornerShape(20.dp)

// ═══════════════════════════════════════════════════════════════════════════════
// Part 12 — Morning Check-In Screen
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    viewModel: CheckInViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Morning Check-In",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success state
            if (state.saveSuccess) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(ActivityAccent.copy(alpha = 0.1f))
                            .border(1.dp, ActivityAccent.copy(0.3f), CardShape)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("✓", style = MaterialTheme.typography.displayMedium, color = ActivityAccent)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Check-in saved!",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = ActivityAccent
                            )
                            Text(
                                "Your responses will be factored into today's scores.",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Header
            item {
                Text(
                    if (state.alreadyCheckedIn) "Update today's check-in" else "How are you feeling this morning?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }

            // Sliders
            item { CheckInSlider("Energy", "⚡", state.energy, RecoveryAccent, "Exhausted", "Full of energy") { viewModel.setEnergy(it) } }
            item { CheckInSlider("Stress", "😰", state.stress, AlertRed, "Very relaxed", "Extremely stressed") { viewModel.setStress(it) } }
            item { CheckInSlider("Muscle Soreness", "💪", state.soreness, StrainAccent, "None", "Very sore") { viewModel.setSoreness(it) } }
            item { CheckInSlider("Sleep Quality", "😴", state.sleepQuality, SleepAccent, "Terrible", "Amazing") { viewModel.setSleepQuality(it) } }
            item { CheckInSlider("Mood", "😊", state.mood, BioAgeAccent, "Awful", "Great") { viewModel.setMood(it) } }
            item { CheckInSlider("Motivation", "🔥", state.motivation, ActivityAccent, "None", "Very high") { viewModel.setMotivation(it) } }

            // Illness toggle
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(SurfaceL1)
                        .border(1.dp, if (state.illnessFlag) AlertRed.copy(0.5f) else DividerColor, CardShape)
                        .clickable { viewModel.toggleIllness() }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤒", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Feeling under the weather?",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = OnBackground
                            )
                            Text(
                                "This flags today for adjusted scoring",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                        Switch(
                            checked = state.illnessFlag,
                            onCheckedChange = { viewModel.toggleIllness() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AlertRed,
                                checkedTrackColor = AlertRed.copy(0.3f)
                            )
                        )
                    }
                }
            }

            // Notes
            item {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = { viewModel.setNotes(it) },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceL1,
                        unfocusedContainerColor = SurfaceL1,
                        focusedBorderColor = RecoveryAccent,
                        unfocusedBorderColor = DividerColor,
                        focusedTextColor = OnBackground,
                        unfocusedTextColor = OnBackground,
                        focusedLabelColor = RecoveryAccent,
                        unfocusedLabelColor = OnSurfaceDim
                    ),
                    shape = CardShape,
                    maxLines = 3
                )
            }

            // Save button
            item {
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !state.isSaving,
                    shape = CardShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecoveryAccent,
                        contentColor = Background
                    )
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = Background,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            if (state.alreadyCheckedIn) "Update Check-In" else "Save Check-In",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CheckInSlider(
    label: String,
    emoji: String,
    value: Int,
    accentColor: Color,
    lowLabel: String,
    highLabel: String,
    onValueChange: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, CardShape)
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = OnBackground,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "$value/10",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = SurfaceL3,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
                Text(highLabel, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Part 13 — Journal / Habit Tracking Screen
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    viewModel: JournalViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
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
            // ── Quick log section ────────────────────────────────────────
            item { SectionHeader("Log a Habit") }

            // Habit selector chips
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.availableHabits) { habit ->
                        val isSelected = state.selectedHabitId == habit.id
                        val borderColor by animateColorAsState(
                            if (isSelected) RecoveryAccent else DividerColor,
                            tween(200), label = "border"
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) RecoveryAccent.copy(0.12f) else SurfaceL1)
                                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                .clickable { viewModel.selectHabit(habit.id) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(habit.icon, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    habit.displayName,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) RecoveryAccent else OnSurfaceDim
                                )
                            }
                        }
                    }
                }
            }

            // Value + log button
            item {
                val selectedHabit = HabitCorrelationEngine.getHabitDefinition(state.selectedHabitId)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(SurfaceL1)
                        .border(1.dp, DividerColor, CardShape)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Amount",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceDim
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${state.entryValue.toInt()}",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = RecoveryAccent
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    selectedHabit?.unit ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = state.entryValue,
                                onValueChange = { viewModel.setEntryValue(it) },
                                valueRange = 1f..20f,
                                steps = 18,
                                colors = SliderDefaults.colors(
                                    thumbColor = RecoveryAccent,
                                    activeTrackColor = RecoveryAccent,
                                    inactiveTrackColor = SurfaceL3,
                                    activeTickColor = Color.Transparent,
                                    inactiveTickColor = Color.Transparent
                                )
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Button(
                            onClick = { viewModel.logEntry() },
                            enabled = !state.isSaving,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RecoveryAccent,
                                contentColor = Background
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Log", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Today's entries ──────────────────────────────────────────
            if (state.todayEntries.isNotEmpty()) {
                item { SectionHeader("Today's Log") }
                items(state.todayEntries) { entry ->
                    val habit = HabitCorrelationEngine.getHabitDefinition(entry.habitId)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(SurfaceL1)
                            .border(1.dp, DividerColor, CardShape)
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(habit?.icon ?: "📝", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    habit?.displayName ?: entry.habitId,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = OnBackground
                                )
                                Text(
                                    "${entry.value.toInt()} ${entry.unit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            IconButton(onClick = { viewModel.deleteEntry(entry.id) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Delete",
                                    tint = OnSurfaceMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Correlations ────────────────────────────────────────────
            if (state.correlations.isNotEmpty()) {
                item { SectionHeader("Habit Insights") }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(StressAccent.copy(0.08f))
                            .border(1.dp, StressAccent.copy(0.2f), CardShape)
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Info, null, tint = StressAccent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "These are observational patterns, not proven causes. Minimum 14 data points required.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StressAccent
                            )
                        }
                    }
                }
                items(state.correlations.take(6)) { correlation ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(SurfaceL1)
                            .border(1.dp, DividerColor, CardShape)
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val color = if (correlation.direction == "positive") ActivityAccent else AlertRed
                                val icon = if (correlation.direction == "positive") Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
                                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${correlation.habitName} → ${correlation.outcomeName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = OnBackground
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                correlation.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                            Spacer(Modifier.height(4.dp))
                            Row {
                                ConfidenceBadge(correlation.confidence)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "n=${correlation.sampleSize}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceMuted
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Part 9 — Muscle Recovery Screen
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuscleRecoveryScreen(
    viewModel: MuscleRecoveryViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Muscle Recovery", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Summary counts ──────────────────────────────────────────
            item {
                val ready = state.muscleStatuses.count { it.status == MuscleRecoveryEngine.RecoveryStatus.READY }
                val recovering = state.muscleStatuses.count { it.status == MuscleRecoveryEngine.RecoveryStatus.RECOVERING }
                val fatigued = state.muscleStatuses.count { it.status == MuscleRecoveryEngine.RecoveryStatus.FATIGUED }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuscleStatusCountCard("Ready", "$ready", ActivityAccent, Modifier.weight(1f))
                    MuscleStatusCountCard("Recovering", "$recovering", StressAccent, Modifier.weight(1f))
                    MuscleStatusCountCard("Fatigued", "$fatigued", AlertRed, Modifier.weight(1f))
                }
            }

            // ── Individual muscle groups ─────────────────────────────────
            item { SectionHeader("Muscle Groups") }

            items(state.muscleStatuses) { status ->
                MuscleGroupCard(status)
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(SurfaceL1)
                        .border(1.dp, DividerColor, CardShape)
                        .padding(14.dp)
                ) {
                    Text(
                        "ℹ️ Recovery estimates are based on exercise type, intensity (RPE), and time since training. " +
                        "Actual recovery depends on sleep, nutrition, hydration, and individual factors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MuscleStatusCountCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(SurfaceL1)
            .border(1.dp, color.copy(0.3f), CardShape)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = color
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
        }
    }
}

@Composable
private fun MuscleGroupCard(status: MuscleRecoveryEngine.MuscleStatus) {
    val (statusColor, statusIcon) = when (status.status) {
        MuscleRecoveryEngine.RecoveryStatus.READY      -> ActivityAccent to Icons.Filled.CheckCircle
        MuscleRecoveryEngine.RecoveryStatus.RECOVERING  -> StressAccent to Icons.Filled.Schedule
        MuscleRecoveryEngine.RecoveryStatus.FATIGUED    -> AlertRed to Icons.Filled.Warning
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(SurfaceL1)
            .border(1.dp, DividerColor, CardShape)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    status.group.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = OnBackground
                )
                Text(
                    status.status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                if (status.hoursRemaining > 0 && status.status != MuscleRecoveryEngine.RecoveryStatus.READY) {
                    Text(
                        "~${status.hoursRemaining}h remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted
                    )
                }
            }
            if (status.lastRpe > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "RPE ${status.lastRpe}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = OnSurfaceDim
                    )
                    if (status.hoursSinceTrained < 168) { // within a week
                        Text(
                            "${status.hoursSinceTrained / 24}d ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Part 11 — Energy Bank Screen
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyBankScreen(
    viewModel: EnergyBankViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Energy Bank", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RecoveryAccent) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero ring
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PercentRing(
                        value = state.score,
                        label = "ENERGY",
                        size = 180.dp,
                        strokeWidth = 14.dp,
                        accent = ActivityAccent
                    )
                }
            }

            // Explanation
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(6.dp))
                        Text(state.explanation, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                    }
                }
            }

            // Chart
            if (state.chartValues.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("14-Day Energy Bank", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            TrendLineChart(values = state.chartValues, accent = BioAgeAccent)
                        }
                    }
                }
            }

            // Breakdown
            if (state.breakdown.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Contributing Factors", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                            Spacer(Modifier.height(8.dp))
                            state.breakdown.forEach { factor ->
                                BreakdownRow(factor = factor)
                                HorizontalDivider(color = SurfaceL3, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }

            // Info
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("About Energy Bank", style = MaterialTheme.typography.titleSmall, color = OnSurfaceDim)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Energy Bank models your estimated available recovery capacity. " +
                            "Good sleep charges it, training drains it, rest days let it recover. " +
                            "Use it to decide whether to push hard or take it easy.",
                            style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
