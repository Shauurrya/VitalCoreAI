package com.example.vitalcoreai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.theme.*
import dagger.hilt.android.AndroidEntryPoint


/**
 * Health Connect requires every app that requests health permissions to expose
 * a real, user-readable Privacy Policy / Data Rationale activity.
 *
 * Two manifest entries point here:
 *   1. <activity> with intent-filter for androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE
 *      → satisfies Android 13 and below (standalone Health Connect app).
 *   2. <activity-alias> for android.intent.action.VIEW_PERMISSION_USAGE +
 *      android.intent.category.HEALTH_PERMISSIONS
 *      → satisfies Android 14+ (built-in Health Connect).
 *
 * Without BOTH entries the app will NOT appear in Health Connect's "App permissions"
 * list and the permission dialog will NEVER be shown to the user.
 */
@AndroidEntryPoint
class HealthPrivacyRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VitalCoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(32.dp))

                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = VitalBlue,
                            modifier = Modifier.size(56.dp)
                        )

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "VitalCore AI — Privacy & Health Data",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = OnBackground
                        )

                        Spacer(Modifier.height(24.dp))

                        RationaleSection(
                            title = "What data does VitalCore AI read?",
                            body = """
VitalCore AI reads the following data types from Health Connect, synced from your Samsung Galaxy Watch:

• Heart Rate — used to compute Recovery Score and detect training load
• Resting Heart Rate — baseline cardiovascular health indicator
• Sleep Sessions — duration, stages (REM, deep, light, awake), efficiency
• Steps & Distance — daily activity scoring
• Total Calories Burned — activity intensity and energy balance
• Exercise Sessions — training load and ACWR calculation
• Oxygen Saturation (SpO₂) — stress and recovery quality
• Weight & Body Fat — Biological Age and VO₂ Max estimation

HRV (Heart Rate Variability) and Skin Temperature are explicitly NOT read.
                            """.trimIndent()
                        )

                        Spacer(Modifier.height(16.dp))

                        RationaleSection(
                            title = "How is your data used?",
                            body = """
All data is processed locally on your device. VitalCore AI:

• Computes daily Recovery, Readiness, Sleep, Stress, and Activity scores
• Tracks 7/14/30-day baselines to personalise scores to your body
• Generates rule-based coaching insights from your own trends
• Produces weekly and monthly health reports
• Awards achievement milestones for streaks and personal bests

Your data is NEVER sent to any server, cloud service, or third party.
There is no account, no login, and no subscription.
                            """.trimIndent()
                        )

                        Spacer(Modifier.height(16.dp))

                        RationaleSection(
                            title = "Is your data shared?",
                            body = """
No. All health data stays on your phone inside the app's private database.

• No analytics SDK, no crash reporting that leaks health data
• No advertising SDK
• No Firebase, no cloud backup of health records
• The only network request is the Health Connect SDK itself (reading from the Health Connect database on your phone)

You can revoke access at any time via Health Connect → App permissions → VitalCore AI.
                            """.trimIndent()
                        )

                        Spacer(Modifier.height(32.dp))

                        Button(
                            onClick = { finish() },
                            colors = ButtonDefaults.buttonColors(containerColor = VitalBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Got it", color = Background, style = MaterialTheme.typography.labelLarge)
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RationaleSection(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceL1),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = VitalBlue
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }
}
