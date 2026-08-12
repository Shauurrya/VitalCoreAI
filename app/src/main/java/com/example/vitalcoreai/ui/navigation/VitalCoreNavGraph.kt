package com.example.vitalcoreai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.vitalcoreai.data.healthconnect.HealthConnectManager
import com.example.vitalcoreai.ui.screens.*

object Routes {
    const val ONBOARDING       = "onboarding"
    const val HOME             = "home"
    const val RECOVERY         = "recovery"
    const val READINESS        = "readiness"
    const val SLEEP            = "sleep"
    const val HEART            = "heart"
    const val STRESS           = "stress"
    const val ACTIVITY         = "activity"
    const val TRAINING         = "training"
    const val BIOLOGICAL_AGE   = "biological_age"
    const val INSIGHTS         = "insights"
    const val HISTORY          = "history"
    const val WEEKLY_REPORT    = "weekly_report"
    const val MONTHLY_REPORT   = "monthly_report"
    const val SETTINGS         = "settings"
    const val CHECK_IN         = "check_in"          // Part 12
    const val JOURNAL          = "journal"            // Part 13
    const val MUSCLE_RECOVERY  = "muscle_recovery"    // Part 9
    const val ENERGY_BANK      = "energy_bank"        // Part 11
    const val WORKOUT_HISTORY  = "workout_history"    // Part 17 #6
    const val WORKOUT_DETAIL   = "workout_detail"     // Part 17 #7
    const val HRR              = "hrr"                // Part 7  / Part 17 #11
    const val BASELINES        = "baselines"          // Part 5  / Part 17 #16
    const val DATA_SOURCES     = "data_sources"       // Part 21 / Part 17 #19
    const val FORECAST         = "forecast"           // T-13 — tomorrow's readiness range
    const val DEBUG            = "debug"              // T-14 — developer screen

    /** Workout Detail is the only route that carries an argument. */
    const val WORKOUT_DETAIL_ARG = "sessionStartMs"
    fun workoutDetail(sessionStartMs: Long) = "$WORKOUT_DETAIL/$sessionStartMs"
}

@Composable
fun VitalCoreNavGraph(
    navController: NavHostController,
    healthConnectManager: HealthConnectManager,
    startDestination: String = Routes.ONBOARDING
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                healthConnectManager = healthConnectManager,
                onPermissionsGranted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(onNavigate = { route -> navController.navigate(route) })
        }

        composable(Routes.RECOVERY) {
            RecoveryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.READINESS) {
            ReadinessScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SLEEP) {
            SleepScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HEART) {
            HeartScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.STRESS) {
            StressScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ACTIVITY) {
            ActivityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TRAINING) {
            TrainingLoadScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BIOLOGICAL_AGE) {
            BiologicalAgeScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.INSIGHTS) {
            InsightsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.WEEKLY_REPORT) {
            WeeklyReportScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MONTHLY_REPORT) {
            MonthlyReportScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // Part 12 — Morning Check-In
        composable(Routes.CHECK_IN) {
            CheckInScreen(onBack = { navController.popBackStack() })
        }

        // Part 13 — Journal / Habit Tracking
        composable(Routes.JOURNAL) {
            JournalScreen(onBack = { navController.popBackStack() })
        }

        // Part 9 — Muscle Recovery
        composable(Routes.MUSCLE_RECOVERY) {
            MuscleRecoveryScreen(onBack = { navController.popBackStack() })
        }

        // Part 11 — Energy Bank
        composable(Routes.ENERGY_BANK) {
            EnergyBankScreen(onBack = { navController.popBackStack() })
        }

        // ── Screens that were fully built but had no route, so nothing could
        //    ever reach them. Parts 5, 7, 17 (#6, #7, #11, #16) and 21.

        composable(Routes.WORKOUT_HISTORY) {
            WorkoutHistoryScreen(
                onBack = { navController.popBackStack() },
                onWorkoutClick = { sessionStartMs ->
                    navController.navigate(Routes.workoutDetail(sessionStartMs))
                }
            )
        }

        composable(
            route = "${Routes.WORKOUT_DETAIL}/{${Routes.WORKOUT_DETAIL_ARG}}",
            arguments = listOf(
                navArgument(Routes.WORKOUT_DETAIL_ARG) { type = NavType.LongType }
            )
        ) {
            // WorkoutDetailViewModel reads sessionStartMs from its SavedStateHandle.
            WorkoutDetailScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HRR) {
            HRRScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BASELINES) {
            BaselinesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DATA_SOURCES) {
            DataSourcesScreen(onBack = { navController.popBackStack() })
        }

        // T-13 — the forecast in full: range, every driver, risks and opportunities.
        composable(Routes.FORECAST) {
            ForecastScreen(onBack = { navController.popBackStack() })
        }

        // T-14 — developer screen. The route is always registered so a deep link cannot
        // 404 in a debug build; the SCREEN itself refuses to load synthetic data outside
        // one, and the only way to reach it is a gesture guarded by BuildConfig.DEBUG.
        composable(Routes.DEBUG) {
            DebugScreen(onBack = { navController.popBackStack() })
        }
    }
}
