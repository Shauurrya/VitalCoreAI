package com.example.vitalcoreai

import android.os.Bundle
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
import androidx.navigation.compose.rememberNavController
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
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
import javax.inject.Inject

/**
 * Extends [FragmentActivity] rather than `ComponentActivity` because `BiometricPrompt`
 * requires one — it hosts its dialog in the fragment manager. `FragmentActivity` is itself a
 * `ComponentActivity`, so `setContent` and Hilt are unaffected.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var healthConnectManager: HealthConnectManager

    /**
     * Re-armed in [onStop], so returning from the recents list asks again.
     *
     * Held on the Activity rather than in Compose state: a configuration change must not
     * unlock the app, and `rememberSaveable` would survive exactly the wrong thing.
     */
    private var locked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        locked.value = BiometricLock.isEnabledAndUsable(this)

        // Schedule background periodic sync + weekly/monthly report generation,
        // using the user's real configured age/max HR (falls back to sane defaults
        // until Settings has been visited).
        val userAge = UserPrefs.age(this)
        val userMaxHR = UserPrefs.maxHR(this)
        SyncWorker.schedulePeriodicSync(this, userAge, userMaxHR)
        WeeklyReportWorker.schedule(this, userAge, userMaxHR)

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

    private fun promptForUnlock() {
        BiometricLock.authenticate(
            activity = this,
            onSuccess = { locked.value = false },
            onFailure = { /* Stay locked. The platform prompt has already explained why. */ }
        )
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
