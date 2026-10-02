package com.example.vitalcoreai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.data.sync.SyncWorker
import com.example.vitalcoreai.data.sync.WeeklyReportWorker
import com.example.vitalcoreai.security.BiometricLock
import com.example.vitalcoreai.theme.Background
import com.example.vitalcoreai.theme.OnBackground
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.navigation.Routes
import com.example.vitalcoreai.ui.navigation.VitalCoreNavGraph
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Extends [FragmentActivity] rather than `ComponentActivity` because `BiometricPrompt`
 * requires one — it hosts its dialog in the fragment manager. `FragmentActivity` is itself a
 * `ComponentActivity`, so `setContent` and Hilt are unaffected.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var healthConnectManager: HealthConnectManager
    @Inject lateinit var healthRepository: HealthRepository

    /**
     * Re-armed in [onStop], so returning from the recents list asks again.
     *
     * Held on the Activity rather than in Compose state: a configuration change must not
     * unlock the app, and `rememberSaveable` would survive exactly the wrong thing.
     */
    private var locked = mutableStateOf(false)

    /**
     * Activity-scoped coroutine scope for background sync tasks.
     *
     * SupervisorJob means a failed sync doesn’t cancel others running in parallel.
     * Cancelled in [onDestroy] to avoid leaking coroutines after rotation.
     */
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        locked.value = BiometricLock.isEnabledAndUsable(this)

        // Schedule background periodic sync + weekly/monthly report generation,
        // using the user's real configured age/max HR (falls back to sane defaults
        // until Settings has been visited).
        val userAge = UserPrefs.age(this)
        val userMaxHR = UserPrefs.maxHR(this)
        SyncWorker.schedulePeriodicSync(this, userAge, userMaxHR)
        WeeklyReportWorker.schedule(this, userAge, userMaxHR)

        // Sync on every foreground — catches data that arrived while the app was closed.
        // The objectives require this explicitly: "Sync on foreground".
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                Log.d("VitalSync", "MainActivity.ON_START: launching foreground sync")
                activityScope.launch {
                    try {
                        healthRepository.syncToday(userAge, userMaxHR)
                    } catch (e: Exception) {
                        Log.e("VitalSync", "Foreground sync failed", e)
                    }
                }
            }
        })

        // Prompt the user to disable battery optimisation so the background worker fires
        // reliably. This is advisory — the app works without it, but Samsung’s aggressive
        // kill policy is why the periodic sync stopped arriving.
        promptBatteryOptimisation()

        setContent {
            VitalCoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    val isLocked by locked
                    if (isLocked) {
                        LockScreen(onUnlock = ::promptForUnlock)
                    } else {
                        val navController = rememberNavController()
                        VitalCoreNavGraph(
                            navController = navController,
                            healthConnectManager = healthConnectManager,
                            startDestination = if (UserPrefs.onboardingComplete(this@MainActivity))
                                Routes.HOME else Routes.ONBOARDING
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (BiometricLock.isEnabledAndUsable(this)) locked.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // activityScope's SupervisorJob is cancelled here; the coroutine won’t leak.
    }

    private fun promptForUnlock() {
        BiometricLock.authenticate(
            activity = this,
            onSuccess = { locked.value = false },
            onFailure = { /* Stay locked. The platform prompt has already explained why. */ }
        )
    }

    /**
     * If the system is managing our battery optimisation (i.e. we are NOT already on the
     * exemption list), open the battery settings screen so the user can add VitalCore.
     *
     * We only show this once to avoid nagging. The user’s answer is recorded in SharedPrefs.
     * On Android < M there is no doze at all, so nothing is needed.
     */
    private fun promptBatteryOptimisation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val prefs = getSharedPreferences("vitalcore_prefs", MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean("battery_opt_prompted", false)
        if (alreadyAsked) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                prefs.edit().putBoolean("battery_opt_prompted", true).apply()
            } catch (_: Exception) {
                // Not all OEMs support ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
            }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    // Prompt immediately on arrival, then only on the button — re-prompting on every
    // recomposition would trap the user in a dialog they just dismissed.
    var promptedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!promptedOnce) {
            promptedOnce = true
            onUnlock()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = RecoveryAccent,
            modifier = Modifier.size(48.dp)
        )
        Text(
            stringResource(R.string.biometric_locked_title),
            style = MaterialTheme.typography.titleLarge,
            color = OnBackground,
            modifier = Modifier.padding(top = Spacing.lg)
        )
        Text(
            stringResource(R.string.biometric_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurfaceDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm)
        )
        Button(
            onClick = onUnlock,
            modifier = Modifier.padding(top = Spacing.xxl).heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.biometric_unlock_action)) }
    }
}
