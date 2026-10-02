package com.example.vitalcoreai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.health.connect.client.PermissionController
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.viewmodel.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.vitalcoreai.core.time.VitalTime

// ═════════════════════════════════════════════════════════════════════════════
// SCREEN 1 — WORKOUT HISTORY (Part 17, screen 6)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryScreen(
    onBack: () -> Unit,
    onWorkoutClick: (Long) -> Unit,
    viewModel: WorkoutHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val filterOptions = listOf("All", "RUN", "BIKE", "SWIM", "WALK", "STRENGTH", "YOGA")
    var selectedFilter by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background,
                    titleContentColor = OnBackground
                )
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                filterOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = selectedFilter == index,
                        onClick = {
                            selectedFilter = index
                            viewModel.setFilter(if (index == 0) null else label)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, filterOptions.size)
                    ) {
                        Text(label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RecoveryAccent)
                }
            } else if (state.workouts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FitnessCenter, contentDescription = null,
                            tint = OnSurfaceMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No workouts found", color = OnSurfaceDim, fontSize = 16.sp)
                        Text("Workouts will appear here after syncing", color = OnSurfaceMuted, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.workouts, key = { it.startMs }) { session ->
                        WorkoutHistoryCard(session = session, onClick = { onWorkoutClick(session.startMs) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutHistoryCard(
    session: com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity,
    onClick: () -> Unit
) {
    val icon = exerciseIconForType(session.exerciseType)
    val accentColor = exerciseColor(session.exerciseType)
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    val startTime = VitalTime.zonedOf(session.startMs)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.exerciseType.replace("_", " ").lowercase()
                        .replaceFirstChar { it.uppercase() },
                    color = OnBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                )
                Text(
                    "${startTime.format(dateFmt)} · ${startTime.format(timeFmt)}",
                    color = OnSurfaceDim, fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${session.durationMinutes} min", color = OnSurfaceDim, fontSize = 12.sp)
                    session.caloriesBurned?.let {
                        Text("$it kcal", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                    session.avgHR?.let {
                        Text("$it bpm avg", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                }
            }

            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = OnSurfaceMuted, modifier = Modifier.size(20.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SCREEN 2 — WORKOUT DETAIL (Part 17, screen 7)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    onBack: () -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout Detail", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background, titleContentColor = OnBackground
                )
            )
        },
        containerColor = Background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RecoveryAccent)
            }
        } else if (state.session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Workout not found", color = OnSurfaceDim)
            }
        } else {
            val session = state.session!!
            val accentColor = exerciseColor(session.exerciseType)
            val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
            val startTime = VitalTime.zonedOf(session.startMs)
            val endTime = VitalTime.zonedOf(session.endMs)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero header
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(exerciseIconForType(session.exerciseType), null,
                                    tint = accentColor, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    session.exerciseType.replace("_", " ").lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    color = OnBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp
                                )
                                Text(startTime.format(dateFmt), color = OnSurfaceDim, fontSize = 13.sp)
                                Text(
                                    "${startTime.format(timeFmt)} – ${endTime.format(timeFmt)}",
                                    color = OnSurfaceDim, fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // Metrics grid
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Performance", color = OnSurfaceDim, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MetricBox("Duration", "${session.durationMinutes} min", accentColor)
                            session.caloriesBurned?.let {
                                MetricBox("Calories", "$it kcal", StrainAccent)
                            }
                            session.distanceMeters?.let { d ->
                                MetricBox("Distance", "%.1f km".format(d / 1000f), ActivityAccent)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            session.avgHR?.let {
                                MetricBox("Avg HR", "$it bpm", RecoveryAccent)
                            }
                            session.maxHR?.let {
                                MetricBox("Max HR", "$it bpm", AlertRed)
                            }
                            session.trainingLoadNormalized?.let { load ->
                                MetricBox("Load", "%.0f".format(load * 100), StressAccent)
                            }
                        }
                    }
                }

                // HR Zones
                if (session.zone1Pct != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Heart Rate Zones", color = OnSurfaceDim, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))

                            // The `if (session.zone1Pct != null)` guard above already smart-casts
                            // this one, so its elvis was dead code. The other four are still
                            // genuinely nullable here.
                            val zones = listOf(
                                "Zone 1 (Recovery)" to session.zone1Pct,
                                "Zone 2 (Endurance)" to (session.zone2Pct ?: 0f),
                                "Zone 3 (Tempo)" to (session.zone3Pct ?: 0f),
                                "Zone 4 (Threshold)" to (session.zone4Pct ?: 0f),
                                "Zone 5 (Max)" to (session.zone5Pct ?: 0f)
                            )
                            val zoneColors = listOf(
                                Color(0xFF4FC3F7), Color(0xFF66BB6A),
                                Color(0xFFFFCA28), Color(0xFFFF7043), Color(0xFFEF5350)
                            )

                            zones.forEachIndexed { idx, (name, pct) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, color = OnSurfaceDim, fontSize = 12.sp,
                                        modifier = Modifier.width(120.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(12.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SurfaceL2)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction = (pct / 100f).coerceIn(0f, 1f))
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(zoneColors[idx])
                                        )
                                    }
                                    Text("%.0f%%".format(pct), color = OnBackground, fontSize = 12.sp,
                                        modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }

                // Heart Rate Recovery
                if (state.hrr1 != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Heart Rate Recovery", color = OnSurfaceDim, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                MetricBox("HRR₁", "${state.hrr1} bpm", RecoveryAccent)
                                state.hrr2?.let {
                                    MetricBox("HRR₂", "$it bpm", RecoveryAccent)
                                }
                                MetricBox("Assessment", state.hrrAssessment,
                                    if (state.hrrAssessment == "Excellent" || state.hrrAssessment == "Good")
                                        ActivityAccent else StressAccent)
                            }
                        }
                    }
                }

                // Per-exercise tracking
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Exercises", color = OnSurfaceDim, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            // Add exercise button
                            var showAddDialog by remember { mutableStateOf(false) }
                            TextButton(onClick = { showAddDialog = true }) {
                                Icon(Icons.Filled.Add, null, tint = RecoveryAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add Exercise", color = RecoveryAccent, fontSize = 12.sp)
                            }

                            if (showAddDialog) {
                                AddExerciseDialog(
                                    onDismiss = { showAddDialog = false },
                                    onAdd = { name, sets, reps, weight, rpe, muscles ->
                                        viewModel.addExercise(name, sets, reps, weight, rpe, muscles)
                                        showAddDialog = false
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        if (state.exercises.isEmpty()) {
                            Text("No exercises logged for this workout.\nTap \"Add Exercise\" to track sets, reps & weight.",
                                color = OnSurfaceMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                        } else {
                            state.exercises.forEach { ex ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ex.exerciseName, color = OnBackground, fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium)
                                        Text(
                                            "${ex.sets} × ${ex.reps}" +
                                                    (if (ex.weightKg > 0) " @ %.1f kg".format(ex.weightKg) else "") +
                                                    (if (ex.rpe != null) " · RPE ${ex.rpe}" else ""),
                                            color = OnSurfaceDim, fontSize = 12.sp
                                        )
                                    }
                                    if (ex.volume > 0) {
                                        Text("Vol: %.0f".format(ex.volume), color = StressAccent, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(color = DividerColor)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun AddExerciseDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, Int, Float, Int?, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("3") }
    var reps by remember { mutableStateOf("10") }
    var weight by remember { mutableStateOf("0") }
    var rpe by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceL2,
        title = { Text("Add Exercise", color = OnBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Exercise name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sets, onValueChange = { sets = it },
                        label = { Text("Sets") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = reps, onValueChange = { reps = it },
                        label = { Text("Reps") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = weight, onValueChange = { weight = it },
                        label = { Text("Weight (kg)") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = rpe, onValueChange = { rpe = it },
                        label = { Text("RPE (1-10)") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = sets.toIntOrNull() ?: 0
                val r = reps.toIntOrNull() ?: 0
                val w = weight.toFloatOrNull() ?: 0f
                val rpeVal = rpe.toIntOrNull()
                onAdd(name.ifBlank { "Unknown" }, s, r, w, rpeVal, "")
            }) { Text("Add", color = RecoveryAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurfaceDim) }
        }
    )
}

@Composable
private fun MetricBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = OnSurfaceDim, fontSize = 11.sp)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SCREEN 3 — HEART RATE RECOVERY (Part 7)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HRRScreen(
    onBack: () -> Unit,
    viewModel: HRRViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heart Rate Recovery", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background, titleContentColor = OnBackground
                )
            )
        },
        containerColor = Background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RecoveryAccent)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero card with HRR1
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Latest HRR₁", color = OnSurfaceDim, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))

                        if (state.latestHrr1 != null) {
                            // Animated score ring
                            val animatedProgress = remember { Animatable(0f) }
                            LaunchedEffect(state.latestHrr1) {
                                animatedProgress.animateTo(
                                    targetValue = (state.latestHrr1!! / 60f).coerceIn(0f, 1f),
                                    animationSpec = tween(1200, easing = FastOutSlowInEasing)
                                )
                            }
                            val ringColor = when {
                                state.latestHrr1!! >= 40 -> ActivityAccent
                                state.latestHrr1!! >= 25 -> RecoveryAccent
                                state.latestHrr1!! >= 12 -> StressAccent
                                else -> AlertRed
                            }

                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                                Canvas(modifier = Modifier.size(140.dp)) {
                                    // Background ring
                                    drawArc(
                                        color = SurfaceL3,
                                        startAngle = -90f, sweepAngle = 360f,
                                        useCenter = false,
                                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                    // Progress ring
                                    drawArc(
                                        color = ringColor,
                                        startAngle = -90f,
                                        sweepAngle = 360f * animatedProgress.value,
                                        useCenter = false,
                                        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${state.latestHrr1}", color = OnBackground,
                                        fontWeight = FontWeight.Bold, fontSize = 36.sp)
                                    Text("bpm/min", color = OnSurfaceDim, fontSize = 12.sp)
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Text(state.assessment, color = ringColor,
                                fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                            // Trend indicator
                            val trendIcon = when (state.trend) {
                                "IMPROVING" -> "↑ Improving"
                                "DECLINING" -> "↓ Declining"
                                else -> "→ Stable"
                            }
                            val trendColor = when (state.trend) {
                                "IMPROVING" -> ActivityAccent
                                "DECLINING" -> AlertRed
                                else -> OnSurfaceDim
                            }
                            Text(trendIcon, color = trendColor, fontSize = 14.sp)
                        } else {
                            Text("No HRR data yet", color = OnSurfaceMuted, fontSize = 18.sp)
                            Text("Complete a workout to see your heart rate recovery",
                                color = OnSurfaceMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                }

                // HRR2 and Baseline
                if (state.latestHrr1 != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("HRR₂", color = OnSurfaceDim, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.latestHrr2?.let { "$it bpm" } ?: "—",
                                    color = RecoveryAccent, fontWeight = FontWeight.Bold, fontSize = 22.sp
                                )
                                Text("2-min recovery", color = OnSurfaceMuted, fontSize = 11.sp)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Baseline", color = OnSurfaceDim, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.personalBaseline?.let { "$it bpm" } ?: "—",
                                    color = StressAccent, fontWeight = FontWeight.Bold, fontSize = 22.sp
                                )
                                Text("30-day average", color = OnSurfaceMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Explanation card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("About Heart Rate Recovery", color = OnBackground,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Heart Rate Recovery (HRR) measures how quickly your heart rate drops " +
                                    "after exercise. A faster recovery indicates better cardiovascular fitness.\n\n" +
                                    "• HRR₁ > 40 bpm = Excellent\n" +
                                    "• HRR₁ 25-40 bpm = Good\n" +
                                    "• HRR₁ 12-25 bpm = Normal\n" +
                                    "• HRR₁ < 12 bpm = Below Normal\n\n" +
                                    "Improving HRR over time is a strong indicator of increasing fitness.",
                            color = OnSurfaceDim, fontSize = 13.sp, lineHeight = 20.sp
                        )
                    }
                }

                // History chart (simple bar representation)
                if (state.history.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("30-Day History", color = OnSurfaceDim, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))

                            val maxHrr = (state.history.maxOfOrNull { it.second } ?: 40).coerceAtLeast(40)
                            state.history.takeLast(10).forEach { (date, hrr1) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(date, color = OnSurfaceMuted, fontSize = 11.sp,
                                        modifier = Modifier.width(40.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(14.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(SurfaceL2)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction = (hrr1.toFloat() / maxHrr).coerceIn(0f, 1f))
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(RecoveryAccent)
                                        )
                                    }
                                    Text("$hrr1", color = OnBackground, fontSize = 11.sp,
                                        modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SCREEN 4 — PERSONAL BASELINES (Part 5)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaselinesScreen(
    onBack: () -> Unit,
    viewModel: BaselinesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Baselines", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background, titleContentColor = OnBackground
                )
            )
        },
        containerColor = Background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RecoveryAccent)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Calibration status card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = SurfaceL1
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val calibColor = when {
                            state.overallCalibrationDays >= 30 -> ActivityAccent
                            state.overallCalibrationDays >= 14 -> RecoveryAccent
                            state.overallCalibrationDays >= 7 -> StressAccent
                            else -> AlertRed
                        }

                        // Progress ring
                        val progress = (state.overallCalibrationDays / 30f).coerceIn(0f, 1f)
                        val animatedProgress = remember { Animatable(0f) }
                        LaunchedEffect(progress) {
                            animatedProgress.animateTo(
                                targetValue = progress,
                                animationSpec = tween(1000, easing = FastOutSlowInEasing)
                            )
                        }

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
                            Canvas(modifier = Modifier.size(100.dp)) {
                                drawArc(SurfaceL3, -90f, 360f, false,
                                    style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                                drawArc(calibColor, -90f, 360f * animatedProgress.value, false,
                                    style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${state.overallCalibrationDays}", color = OnBackground,
                                    fontWeight = FontWeight.Bold, fontSize = 24.sp)
                                Text("days", color = OnSurfaceDim, fontSize = 11.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(state.calibrationStatus, color = calibColor,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            if (state.overallCalibrationDays >= 30)
                                "Your scores are fully personalized to your body"
                            else
                                "${30 - state.overallCalibrationDays} more days until full personalization",
                            color = OnSurfaceDim, fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    }
                }

                // Baseline items
                if (state.baselines.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Keep wearing your watch to build baselines",
                                color = OnSurfaceMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    state.baselines.forEach { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceL1),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.name, color = OnBackground,
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                                    val confColor = when (item.confidence) {
                                        "HIGH" -> ActivityAccent
                                        "MEDIUM" -> StressAccent
                                        else -> AlertRed
                                    }
                                    Text(item.confidence, color = confColor, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold)
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Current", color = OnSurfaceDim, fontSize = 11.sp)
                                        Text(item.currentValue, color = OnBackground,
                                            fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Baseline", color = OnSurfaceDim, fontSize = 11.sp)
                                        Text(item.baselineValue, color = RecoveryAccent,
                                            fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Deviation", color = OnSurfaceDim, fontSize = 11.sp)
                                        val devColor = if (item.deviationPercent > 10) AlertRed
                                        else if (item.deviationPercent < -10) RecoveryAccent
                                        else OnBackground
                                        Text("${item.deviation} ${item.trend}", color = devColor,
                                            fontWeight = FontWeight.Medium, fontSize = 16.sp)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Text("${item.calibrationDays} days of data",
                                    color = OnSurfaceMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// SCREEN 5 — DATA SOURCES (Part 17, screen 19)
// ═════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSourcesScreen(
    onBack: () -> Unit,
    viewModel: DataSourcesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        viewModel.refreshAccess()
        if (granted.any { it in HealthConnectManager.MINIMUM_PERMISSIONS }) {
            viewModel.readData()
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    VitalScreenScaffold(topBar = {
        VitalTopBar(title = "Data Sources", subtitle = "Access and measurement freshness", accent = RecoveryAccent, onBack = onBack)
    }) {
        if (state.isLoading) {
            item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RecoveryAccent)
                }
            }
        } else {
            item {
                VitalCard(modifier = Modifier.fillMaxWidth(), accent = RecoveryAccent) {
                    Text("HEALTH CONNECT", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                    Spacer(Modifier.height(12.dp))
                    Text(state.connectionLabel, style = MaterialTheme.typography.titleMedium, color = OnBackground)
                    Spacer(Modifier.height(8.dp))
                    Text("A read checks the data already shared with Health Connect. It cannot make a wearable upload new measurements.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    Spacer(Modifier.height(8.dp))
                    Text("For Galaxy Watch data, enable Health Connect sharing in Samsung Health and sync the watch there first.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(
                                HealthConnectManager.ALL_PERMISSIONS
                            )
                        },
                        enabled = state.available == true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Grant or update VitalCore access") }
                    Button(
                        onClick = viewModel::readData,
                        enabled = state.available == true && !state.isSyncing && !state.isForceResyncing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(if (state.isSyncing) "Reading data…" else "Read data now")
                    }
                    OutlinedButton(
                        onClick = viewModel::forceFullResync,
                        enabled = state.available == true && !state.isSyncing && !state.isForceResyncing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (state.isForceResyncing) "Re-reading last 30 days…"
                            else "Force full re-sync (last 30 days)"
                        )
                    }
                    OutlinedButton(onClick = viewModel::openHealthConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (state.available == true) "Manage Health Connect access" else "Open or install Health Connect")
                    }
                    TextButton(onClick = viewModel::refreshAccess, enabled = !state.isChecking, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.isChecking) "Checking access…" else "Recheck access")
                    }
                    state.lastSyncMs?.let { ms ->
                        val syncLabel = sourceTime(ms)
                        Text(
                            "Last successful sync: $syncLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    } ?: Text(
                        "Last successful sync: Never recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                    state.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                    state.accessError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = StressAccent)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
                    Text("Background reads: ${state.backgroundAccess}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    Spacer(Modifier.height(8.dp))
                    Text("Extended history: ${state.historyAccess}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
            }
            item {
                VitalCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Source and device", style = MaterialTheme.typography.titleSmall, color = OnBackground)
                    Spacer(Modifier.height(8.dp))
                    Text("Source apps are taken from record metadata when available. The wearable model is unknown; VitalCore does not establish a direct watch connection.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
            }
            item { SectionHeader("Read outcomes") }
            items(state.sources, key = { it.recordType }) { source -> DataSourceReadCard(source) }
            item {
                VitalCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Manual entries · always available", style = MaterialTheme.typography.titleSmall, color = OnBackground)
                    Spacer(Modifier.height(8.dp))
                    Text("Check-ins, journal entries and workout notes are saved on this device and do not require Health Connect access.",
                        style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                }
            }
        }
    }
}

@Composable
private fun DataSourceReadCard(source: DataSourceItem) {
    val accent = when (source.status) {
        DataReadStatus.READ -> ActivityAccent
        DataReadStatus.FAILED, DataReadStatus.PERMISSION_DENIED -> StressAccent
        DataReadStatus.DELAYED -> SleepAccent
        else -> OnSurfaceDim
    }
    VitalCard(modifier = Modifier.fillMaxWidth()) {
        Text(source.name, style = MaterialTheme.typography.titleMedium, color = OnBackground)
        Spacer(Modifier.height(4.dp))
        Text(source.statusLabel, style = MaterialTheme.typography.labelLarge, color = accent)
        Spacer(Modifier.height(10.dp))
        Text(source.explanation, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        source.dataNotes.forEach { note ->
            Spacer(Modifier.height(8.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = OnBackground)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DividerColor)
        val sources = source.sourceApps.joinToString().ifBlank { "Unknown · no origin recorded" }
        Text("Source app: $sources", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(6.dp))
        Text("Last attempted read: ${sourceTime(source.lastAttemptMs)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(6.dp))
        Text("Last successful read: ${sourceTime(source.lastSuccessfulReadMs)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(6.dp))
        Text("Latest known measurement: ${sourceTime(source.latestMeasurementMs)}", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(12.dp))
        Text(source.nextAction, style = MaterialTheme.typography.bodySmall, color = OnBackground)
    }
}

private fun sourceTime(timestampMs: Long?): String = timestampMs?.takeIf { it > 0 }?.let {
    Instant.ofEpochMilli(it).atZone(VitalTime.zone()).format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))
} ?: "Not recorded"

// ─── Shared helpers ─────────────────────────────────────────────────────────

private fun exerciseIconForType(type: String): ImageVector = when {
    type.contains("RUN", ignoreCase = true)  -> Icons.AutoMirrored.Filled.DirectionsRun
    type.contains("BIKE", ignoreCase = true) ||
    type.contains("CYCL", ignoreCase = true) -> Icons.AutoMirrored.Filled.DirectionsBike
    type.contains("SWIM", ignoreCase = true) -> Icons.Filled.Pool
    type.contains("WALK", ignoreCase = true) -> Icons.AutoMirrored.Filled.DirectionsWalk
    type.contains("YOGA", ignoreCase = true) ||
    type.contains("MEDITAT", ignoreCase = true) -> Icons.Filled.SelfImprovement
    type.contains("STRENGTH", ignoreCase = true) ||
    type.contains("WEIGHT", ignoreCase = true) -> Icons.Filled.FitnessCenter
    else -> Icons.Filled.FitnessCenter
}

private fun exerciseColor(type: String): Color = when {
    type.contains("RUN", ignoreCase = true)  -> StrainAccent
    type.contains("BIKE", ignoreCase = true) ||
    type.contains("CYCL", ignoreCase = true) -> RecoveryAccent
    type.contains("SWIM", ignoreCase = true) -> SleepAccent
    type.contains("WALK", ignoreCase = true) -> ActivityAccent
    type.contains("YOGA", ignoreCase = true) -> SleepAccent
    type.contains("STRENGTH", ignoreCase = true) -> StressAccent
    else -> RecoveryAccent
}
