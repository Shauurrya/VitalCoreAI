package com.example.vitalcoreai.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.theme.*

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

@Composable
fun OnboardingScreen(
    healthConnectManager: HealthConnectManager,
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current
    var showInstallPrompt by remember { mutableStateOf(false) }
    var permissionsRequested by remember { mutableStateOf(false) }

    // B3/B4 notification permission (Android 13+) — optional, requested alongside Health
    // Connect access but never blocks onboarding if the user declines it; the Settings
    // toggles simply have no effect until it's granted.
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

    // Which optional capabilities the user ended up without, so we can say so plainly
    // instead of pretending everything is fine or refusing to start.
    var missingCapabilities by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Gate on the MINIMUM set only. Requiring every permission meant that
        // declining any single one — SpO₂, say, which Samsung Health writes
        // inconsistently anyway — trapped the user on this screen forever.
        val hasMinimum = granted.any { it in HealthConnectManager.MINIMUM_PERMISSIONS }
        missingCapabilities = describeMissing(granted)
        if (hasMinimum) {
            requestNotificationPermissionIfNeeded()
            onPermissionsGranted()
        } else {
            permissionsRequested = true
        }
    }

    LaunchedEffect(Unit) {
        val installIntent = healthConnectManager.getInstallIntent()
        when {
            // Health Connect not installed (Android 13-) — prompt to install it
            installIntent != null -> showInstallPrompt = true

            // Enough already granted to compute something → straight into the app
            healthConnectManager.isAvailable() && healthConnectManager.hasMinimumPermissions() -> {
                requestNotificationPermissionIfNeeded()
                onPermissionsGranted()
            }

            // HC available but nothing granted yet → launch the dialog immediately
            healthConnectManager.isAvailable() ->
                permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = VitalBlue,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "VitalCore AI",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = OnBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Offline health analytics powered by your Galaxy Watch data",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceDim,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))

            if (showInstallPrompt) {
                Text(
                    text = "Health Connect needs to be installed to sync your wearable data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VitalAmber,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = healthConnectManager.getInstallIntent()
                        if (intent != null) context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VitalAmber)
                ) {
                    Text("Install Health Connect")
                }
            } else {
                if (permissionsRequested) {
                    Text(
                        text = "VitalCore needs at least heart rate or steps to compute anything. " +
                            "Everything else is optional — you can grant more later in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VitalRed,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (missingCapabilities.isNotEmpty()) {
                    Text(
                        text = "Running with reduced data: ${missingCapabilities.joinToString(", ")}. " +
                            "Affected scores will show lower confidence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VitalAmber,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = {
                        permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VitalBlue)
                ) {
                    Text("Connect Health Data", color = Background)
                }
            }
        }
    }
}
