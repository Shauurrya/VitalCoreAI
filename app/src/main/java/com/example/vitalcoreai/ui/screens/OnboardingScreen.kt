package com.example.vitalcoreai.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.example.vitalcoreai.R
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.VitalCard
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * T-18 — the nine-step onboarding.
 *
 * ## What each step is for
 *
 * Steps 1, 6 and 7 exist to set expectations, and they are the reason this is nine screens
 * rather than one. A user who does not know that scores are relative to *their own* baseline
 * reads a 62 on day three as a verdict; one who has been told it takes two to four weeks to
 * settle reads it as a work in progress. The same goes for the privacy claim: "no internet
 * permission" is a checkable fact, and stating it up front is worth more than a policy link.
 *
 * Only step 2 can block. Every other step is skippable and writes a preference the engines
 * read later — the training goal nudges the workout recommendation, and the check-in
 * preference drives the reminder.
 */

/**
 * Turn a granted-permission set into plain language about what the user gave up.
 * Only the ones with a visible consequence are named — listing every declined
 * record type would be noise.
 */
private fun describeMissing(granted: Set<String>): List<String> = buildList {
    if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
        add("background sync off — open the app to refresh")
    }
    if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY !in granted) {
        add("history limited to 30 days")
    }
    if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
        add("no sleep data")
    }
    if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) {
        add("no workouts")
    }
    if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) !in granted) {
        add("no SpO₂")
    }
}

private enum class Step { WELCOME, PERMISSIONS, GOAL, WEARABLE, CHECK_IN, BASELINE, COACH, NOTIFICATIONS, READY }

/** The four goals `RecommendationEngine.Goal` understands, with copy for each. */
private val GOALS = listOf(
    "GENERAL_FITNESS" to ("General fitness" to "A balanced mix, adjusted to how recovered you are"),
    "STRENGTH" to ("Strength" to "Favours resistance work and longer recovery between hard sessions"),
    "ENDURANCE" to ("Endurance" to "Favours aerobic volume and watches training load more closely"),
    "WEIGHT_LOSS" to ("Weight loss" to "Favours steady, repeatable sessions over peaks")
)

@Composable
fun OnboardingScreen(
    healthConnectManager: HealthConnectManager,
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    var showInstallPrompt by remember { mutableStateOf(false) }
    var permissionsRequested by remember { mutableStateOf(false) }
    var missingCapabilities by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasMinimumPermissions by rememberSaveable { mutableStateOf(false) }

    var goal by rememberSaveable { mutableStateOf(UserPrefs.trainingGoal(context)) }
    var checkInReminder by rememberSaveable { mutableStateOf(UserPrefs.checkInReminderEnabled(context)) }
    var notifications by rememberSaveable { mutableStateOf(UserPrefs.notificationsEnabled(context)) }

    // B3/B4 notification permission (Android 13+) — optional, requested at the notification
    // step and never blocking; the Settings toggles simply have no effect until granted.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications are optional */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Gate on the MINIMUM set only. Requiring every permission meant that declining any
        // single one — SpO₂, say, which Samsung Health writes inconsistently anyway —
        // trapped the user on this screen forever.
        hasMinimumPermissions = granted.any { it in HealthConnectManager.MINIMUM_PERMISSIONS }
        missingCapabilities = describeMissing(granted)
        permissionsRequested = true
        if (hasMinimumPermissions) step = Step.GOAL
    }

    LaunchedEffect(Unit) {
        showInstallPrompt = healthConnectManager.getInstallIntent() != null
        if (!showInstallPrompt && healthConnectManager.isAvailable()) {
            hasMinimumPermissions = healthConnectManager.hasMinimumPermissions()
        }
    }

    fun finish() {
        UserPrefs.setTrainingGoal(context, goal)
        UserPrefs.setCheckInReminderEnabled(context, checkInReminder)
        UserPrefs.setNotificationsEnabled(context, notifications)
        UserPrefs.setOnboardingComplete(context, true)
        onPermissionsGranted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = Spacing.gutter)
    ) {
        StepIndicator(current = step.ordinal + 1, total = Step.entries.size)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                Step.WELCOME -> StepBody(
                    icon = Icons.Filled.FavoriteBorder,
                    accent = RecoveryAccent,
                    title = stringResource(R.string.onboarding_welcome_title),
                    body = stringResource(R.string.onboarding_welcome_body)
                )

                Step.PERMISSIONS -> StepBody(
                    icon = Icons.Filled.HealthAndSafety,
                    accent = ActivityAccent,
                    title = stringResource(R.string.onboarding_permissions_title),
                    body = stringResource(R.string.onboarding_permissions_body)
                ) {
                    if (showInstallPrompt) {
                        Text(
                            "Health Connect needs to be installed to sync your wearable data.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StressAccent
                        )
                        Spacer(Modifier.height(Spacing.md))
                        Button(
                            onClick = {
                                healthConnectManager.getInstallIntent()?.let(context::startActivity)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StressAccent)
                        ) { Text("Install Health Connect") }
                    } else {
                        Button(
                            onClick = { permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RecoveryAccent)
                        ) { Text("Grant access", color = Background) }
                    }

                    AnimatedVisibility(
                        visible = permissionsRequested && !hasMinimumPermissions,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        Text(
                            "VitalCore needs at least heart rate or steps to compute anything. " +
                                "Everything else is optional and can be granted later in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertRed,
                            modifier = Modifier.padding(top = Spacing.md)
                        )
                    }
                    if (missingCapabilities.isNotEmpty()) {
                        Text(
                            "Running with reduced data: ${missingCapabilities.joinToString(", ")}. " +
                                "Affected scores will show lower confidence.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StressAccent,
                            modifier = Modifier.padding(top = Spacing.md)
                        )
                    }
                }

                Step.GOAL -> StepBody(
                    icon = Icons.Filled.Insights,
                    accent = StrainAccent,
                    title = stringResource(R.string.onboarding_goal_title),
                    body = stringResource(R.string.onboarding_goal_body)
                ) {
                    Column(Modifier.selectableGroup()) {
                        GOALS.forEach { (id, copy) ->
                            val (label, description) = copy
                            ChoiceRow(
                                label = label,
                                description = description,
                                selected = goal == id,
                                onSelect = { goal = id }
                            )
                        }
                    }
                }

                Step.WEARABLE -> StepBody(
                    icon = Icons.Filled.Watch,
                    accent = HeartAccent,
                    title = stringResource(R.string.onboarding_wearable_title),
                    body = stringResource(R.string.onboarding_wearable_body)
                )

                Step.CHECK_IN -> StepBody(
                    icon = Icons.Filled.CheckCircle,
                    accent = ActivityAccent,
                    title = stringResource(R.string.onboarding_checkin_title),
                    body = stringResource(R.string.onboarding_checkin_body)
                ) {
                    ToggleRow(
                        label = "Remind me each morning",
                        checked = checkInReminder,
                        onCheckedChange = { checkInReminder = it }
                    )
                }

                Step.BASELINE -> StepBody(
                    icon = Icons.Filled.Bedtime,
                    accent = SleepAccent,
                    title = stringResource(R.string.onboarding_baseline_title),
                    body = stringResource(R.string.onboarding_baseline_body)
                ) {
                    VitalCard(accent = SleepAccent) {
                        Text(
                            stringResource(R.string.empty_baseline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                    }
                }

                Step.COACH -> StepBody(
                    icon = Icons.Filled.Psychology,
                    accent = ReadinessAccent,
                    title = stringResource(R.string.onboarding_coach_title),
                    body = stringResource(R.string.onboarding_coach_body)
                ) {
                    // Deliberately display-only. The consent this describes is not being
                    // collected here, because there is nothing yet to consent to: v1.1 ships
                    // no network layer at all. Offering the switch would record a permission
                    // for a feature that does not exist.
                    VitalCard(accent = ReadinessAccent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = ReadinessAccent,
                                modifier = Modifier.size(Sizes.iconMd)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "On-device coach · no network permission",
                                style = MaterialTheme.typography.bodyLarge,
                                color = OnBackground
                            )
                        }
                    }
                }

                Step.NOTIFICATIONS -> StepBody(
                    icon = Icons.Filled.Notifications,
                    accent = StressAccent,
                    title = stringResource(R.string.onboarding_notifications_title),
                    body = stringResource(R.string.onboarding_notifications_body)
                ) {
                    ToggleRow(
                        label = "Send me notifications",
                        checked = notifications,
                        onCheckedChange = {
                            notifications = it
                            if (it) requestNotificationPermissionIfNeeded()
                        }
                    )
                }

                Step.READY -> StepBody(
                    icon = Icons.Filled.CheckCircle,
                    accent = ActivityAccent,
                    title = stringResource(R.string.onboarding_ready_title),
                    body = stringResource(R.string.onboarding_ready_body)
                ) {
                    VitalCard {
                        Text(
                            stringResource(R.string.disclaimer_not_medical),
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurfaceDim
                        )
                    }
                }
            }
        }

        NavigationRow(
            step = step,
            canAdvance = step != Step.PERMISSIONS || hasMinimumPermissions,
            onBack = { step = Step.entries[(step.ordinal - 1).coerceAtLeast(0)] },
            onNext = {
                if (step == Step.READY) finish()
                else step = Step.entries[step.ordinal + 1]
            }
        )
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            repeat(total) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(Sizes.barTrack)
                        .clip(VitalShapes.Bar)
                        .background(if (index < current) RecoveryAccent else SurfaceL3)
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        // The count in words as well as the bar — the bar alone is colour-only progress.
        Text(
            "Step $current of $total",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim
        )
    }
}

@Composable
private fun StepBody(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(Spacing.lg))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = OnBackground,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(Spacing.md))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceDim)
        Spacer(Modifier.height(Spacing.xl))
        extra()
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $description" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = OnBackground,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NavigationRow(
    step: Step,
    canAdvance: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (step != Step.WELCOME) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Back") }
        }
        Button(
            onClick = onNext,
            enabled = canAdvance,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RecoveryAccent)
        ) {
            Text(if (step == Step.READY) "Start" else "Next", color = Background)
        }
    }
}
