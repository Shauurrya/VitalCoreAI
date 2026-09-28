package com.example.vitalcoreai.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.example.vitalcoreai.BuildConfig
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.db.entity.ExerciseSessionEntity
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.*
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.viewmodel.RecommendationState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
internal fun DashboardTopBar(onRefresh: () -> Unit, onNavigate: (String) -> Unit, isSyncing: Boolean) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    Row(
        Modifier.fillMaxWidth().background(Background).statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).pointerInput(onNavigate) {
                detectTapGestures {
                    if (BuildConfig.DEBUG) {
                        val now = SystemClock.uptimeMillis()
                        taps = if (now - lastTap < 400) taps + 1 else 1
                        lastTap = now
                        if (taps >= 3) { taps = 0; onNavigate(Routes.DEBUG) }
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Keep the wordmark on one line while controls retain their touch targets.
            // Branding has a fixed visual size; health data follows the user's text scale.
            Text("VITALCORE", style = MaterialTheme.typography.titleLarge.copy(
                fontSize = (18f / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp,
                letterSpacing = (2f / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp
            ), color = OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRefresh, enabled = !isSyncing) {
            val rotation = if (isSyncing) {
                val transition = rememberInfiniteTransition(label = "Health data sync")
                val angle by transition.animateFloat(0f, 360f,
                    infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "Sync rotation")
                angle
            } else 0f
            Icon(Icons.Filled.Sync, if (isSyncing) "Syncing health data" else "Sync health data",
                tint = OnSurfaceDim, modifier = Modifier.size(21.dp).rotate(rotation))
        }
        IconButton(onClick = { onNavigate(Routes.SETTINGS) }) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(SurfaceL2).border(1.dp, HairlineColor, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PersonOutline, "Settings and profile", tint = OnBackground, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun DashboardNavigation(showMore: Boolean, onNavigate: (String) -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SurfaceBar).navigationBarsPadding()) {
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp, vertical = 5.dp)) {
            DashboardNavItem("Overview", Icons.Filled.GridView, !showMore, Modifier.weight(1f)) { }
            DashboardNavItem("Trends", Icons.AutoMirrored.Filled.TrendingUp, false, Modifier.weight(1f)) { onNavigate(Routes.HISTORY) }
            DashboardNavItem("Insights", Icons.Filled.AutoAwesome, false, Modifier.weight(1f)) { onNavigate(Routes.INSIGHTS) }
            DashboardNavItem("More", Icons.Filled.Apps, showMore, Modifier.weight(1f), onMore)
        }
    }
}

@Composable
private fun DashboardNavItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .heightIn(min = 58.dp).padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (selected) OnBackground else OnSurfaceMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) OnBackground else OnSurfaceMuted)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(width = 14.dp, height = 2.dp).clip(CircleShape).background(if (selected) OnBackground else Color.Transparent))
    }
}

@Composable
internal fun DashboardSection(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = VitalCoreType.sectionTitle, color = OnBackground,
            modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = onClick, contentPadding = PaddingValues(start = 8.dp)) {
            Text(action, style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceDim, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun DashboardActionCard(icon: ImageVector, accent: Color, eyebrow: String, title: String, body: String, onClick: () -> Unit) {
    VitalCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.09f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(eyebrow, style = VitalCoreType.eyebrow.copy(fontSize = 9.sp), color = OnSurfaceDim)
                Spacer(Modifier.height(5.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = OnBackground)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceDim, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun DashboardMonitor(label: String, icon: ImageVector, status: String, caption: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    VitalCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceMuted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(label, style = VitalCoreType.eyebrow, color = OnSurfaceDim)
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.titleLarge, color = accent)
        Spacer(Modifier.height(5.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardMetric(label: String, value: String?, unit: String, icon: ImageVector, accent: Color, modifier: Modifier, caption: String, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick).padding(16.dp)) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.Center) {
            Text(value ?: "—", style = VitalCoreType.metricMedium, color = OnBackground)
            if (unit.isNotEmpty() && value != null) Text(unit, style = VitalCoreType.metricUnit, color = OnSurfaceDim, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(if (value == null) "Awaiting data" else caption, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
    }
}

@Composable
internal fun DashboardActivity(icon: ImageVector, accent: Color, title: String, duration: String, timeRange: String, onClick: () -> Unit) {
    VitalCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = OnBackground)
                if (timeRange.isNotBlank()) Text(timeRange, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(duration, style = VitalCoreType.metricSmall, color = OnBackground)
                Text("DURATION", style = VitalCoreType.eyebrow.copy(fontSize = 8.sp), color = OnSurfaceMuted)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceMuted, modifier = Modifier.size(17.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardRecommendation(recommendation: RecommendationState?, onClick: () -> Unit) {
    VitalCard(modifier = Modifier.fillMaxWidth(), accent = StrainAccent, contentPadding = PaddingValues(20.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = StrainAccent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("YOUR DAILY DIRECTION", style = VitalCoreType.eyebrow, color = StrainAccent, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = OnSurfaceDim, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(recommendation?.type ?: "Build your baseline.", style = MaterialTheme.typography.headlineMedium, color = OnBackground)
        Spacer(Modifier.height(8.dp))
        Text(recommendation?.detail?.takeIf { it.isNotBlank() }
            ?: if (recommendation == null) "Sync your watch to unlock guidance shaped around your recovery, sleep and activity."
            else "A training recommendation based on your latest scores.",
            style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        if (recommendation != null) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentPill("${recommendation.intensity} intensity", StrainAccent)
                recommendation.volumePct?.takeIf { it != 0 }?.let {
                    AccentPill("${kotlin.math.abs(it)}% ${if (it > 0) "more" else "less"} volume", OnSurfaceDim)
                }
            }
        }
    }
}

private data class ExploreDestination(val title: String, val subtitle: String, val route: String, val icon: ImageVector, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreSheet(onDismiss: () -> Unit, onNavigate: (String) -> Unit) {
    val sections = listOf(
        "YOUR HEALTH" to listOf(
            ExploreDestination("Recovery", "How your body is recovering", Routes.RECOVERY, Icons.Filled.FavoriteBorder, RecoveryAccent),
            ExploreDestination("Readiness", "Your capacity for the day ahead", Routes.READINESS, Icons.Filled.Bolt, ReadinessAccent),
            ExploreDestination("Sleep", "Duration, quality and consistency", Routes.SLEEP, Icons.Filled.Bedtime, SleepAccent),
            ExploreDestination("Heart", "Resting heart rate and trends", Routes.HEART, Icons.Filled.MonitorHeart, HeartAccent),
            ExploreDestination("Stress", "Your daily stress score", Routes.STRESS, Icons.Filled.Waves, StressAccent),
            ExploreDestination("Fitness age", "Your estimated fitness age", Routes.BIOLOGICAL_AGE, Icons.Filled.Timeline, BioAgeAccent),
            ExploreDestination("Energy bank", "Your estimated reserves", Routes.ENERGY_BANK, Icons.Filled.BatteryChargingFull, RecoveryAccent)
        ),
        "TRAINING & ROUTINES" to listOf(
            ExploreDestination("Activity", "Steps, movement and energy", Routes.ACTIVITY, Icons.AutoMirrored.Filled.DirectionsWalk, ActivityAccent),
            ExploreDestination("Strain & training", "Daily effort and training load", Routes.TRAINING, Icons.Filled.FitnessCenter, StrainAccent),
            ExploreDestination("Workouts", "Your recorded sessions", Routes.WORKOUT_HISTORY, Icons.AutoMirrored.Filled.DirectionsRun, StrainAccent),
            ExploreDestination("Muscle recovery", "Recovery by muscle group", Routes.MUSCLE_RECOVERY, Icons.Filled.AccessibilityNew, StrainAccent),
            ExploreDestination("Heart rate recovery", "Your response after exercise", Routes.HRR, Icons.Filled.FavoriteBorder, HeartAccent),
            ExploreDestination("Morning check-in", "Add context to your day", Routes.CHECK_IN, Icons.Filled.WbSunny, RecoveryAccent),
            ExploreDestination("Journal", "Connect habits with your health", Routes.JOURNAL, Icons.Filled.EditNote, SleepAccent)
        ),
        "THE BIGGER PICTURE" to listOf(
            ExploreDestination("Insights", "Patterns in your health data", Routes.INSIGHTS, Icons.Filled.AutoAwesome, RecoveryAccent),
            ExploreDestination("History", "Explore your daily scores", Routes.HISTORY, Icons.Filled.CalendarMonth, OnSurfaceDim),
            ExploreDestination("Forecast", "Tomorrow's readiness range", Routes.FORECAST, Icons.Filled.WbTwilight, ReadinessAccent),
            ExploreDestination("Weekly report", "Your week in perspective", Routes.WEEKLY_REPORT, Icons.Filled.DateRange, StrainAccent),
            ExploreDestination("Monthly report", "Your longer-term progress", Routes.MONTHLY_REPORT, Icons.Filled.CalendarMonth, SleepAccent),
            ExploreDestination("Personal baselines", "What is typical for you", Routes.BASELINES, Icons.Filled.Tune, BioAgeAccent),
            ExploreDestination("Data sources", "Health Connect and sync", Routes.DATA_SOURCES, Icons.Filled.Watch, OnSurfaceDim),
            ExploreDestination("Settings", "Make VitalCore yours", Routes.SETTINGS, Icons.Filled.Settings, OnSurfaceDim)
        )
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Background, contentColor = OnBackground) {
        val sheetView = LocalView.current
        SideEffect {
            // Material 3 1.3.2 follows the device theme for this separate dialog window.
            // Our sheet is always dark, so its status and navigation icons must stay light.
            (sheetView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, sheetView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Explore VitalCore", style = MaterialTheme.typography.headlineMedium, color = OnBackground)
                Text("Your health, in greater detail.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
            sections.forEach { (title, destinations) ->
                item(key = title) {
                    Text(title, style = VitalCoreType.eyebrow, color = OnSurfaceMuted, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    VitalListCard {
                        destinations.forEachIndexed { index, destination ->
                            if (index > 0) VitalListDivider()
                            VitalListRow(title = destination.title, subtitle = destination.subtitle, leadingIcon = destination.icon,
                                leadingTint = destination.color, showChevron = true, onClick = { onNavigate(destination.route) })
                        }
                    }
                }
            }
        }
    }
}

internal fun exerciseIcon(type: String): ImageVector = when {
    type.contains("run", ignoreCase = true) -> Icons.AutoMirrored.Filled.DirectionsRun
    type.contains("walk", ignoreCase = true) || type.contains("hik", ignoreCase = true) -> Icons.AutoMirrored.Filled.DirectionsWalk
    type.contains("cycl", ignoreCase = true) || type.contains("bik", ignoreCase = true) -> Icons.AutoMirrored.Filled.DirectionsBike
    type.contains("swim", ignoreCase = true) -> Icons.Filled.Pool
    type.contains("yoga", ignoreCase = true) -> Icons.Filled.SelfImprovement
    else -> Icons.Filled.FitnessCenter
}

internal fun sleepTimeRange(bedMinute: Int?, wakeMinute: Int?): String {
    if (bedMinute == null || wakeMinute == null) return "Recorded sleep"
    val format = DateTimeFormatter.ofPattern("h:mm a")
    return "${LocalTime.ofSecondOfDay(Math.floorMod(bedMinute, 1440).toLong() * 60).format(format)} – ${LocalTime.ofSecondOfDay(Math.floorMod(wakeMinute, 1440).toLong() * 60).format(format)}"
}

internal fun sessionTimeRange(session: ExerciseSessionEntity): String {
    val format = DateTimeFormatter.ofPattern("h:mm a")
    return "${VitalTime.zonedOf(session.startMs).format(format)} – ${VitalTime.zonedOf(session.endMs).format(format)}"
}
