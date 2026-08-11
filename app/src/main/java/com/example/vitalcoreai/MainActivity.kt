package com.example.vitalcoreai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.data.sync.SyncWorker
import com.example.vitalcoreai.data.sync.WeeklyReportWorker
import com.example.vitalcoreai.theme.Background
import com.example.vitalcoreai.theme.VitalCoreTheme
import com.example.vitalcoreai.ui.navigation.VitalCoreNavGraph
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var healthConnectManager: HealthConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    val navController = rememberNavController()
                    VitalCoreNavGraph(
                        navController = navController,
                        healthConnectManager = healthConnectManager
                    )
                }
            }
        }
    }
}
