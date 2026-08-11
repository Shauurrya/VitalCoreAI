package com.example.vitalcoreai.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// MIGRATION SHIM — DELETE THIS FILE IN THE FINAL CLEANUP COMMIT.
//
// Every symbol here is a pre-redesign name kept alive so screens that have not
// yet been rewritten still compile. No agent may introduce a NEW reference to
// anything in this file. When `./gradlew.bat compileDebugKotlin` reports zero
// DEPRECATION warnings, this file has no callers left and must be removed.
// ═══════════════════════════════════════════════════════════════════════════

@Deprecated("Use RecoveryAccent", ReplaceWith("RecoveryAccent"))
val VitalBlue: Color = RecoveryAccent

@Deprecated("Use ActivityAccent", ReplaceWith("ActivityAccent"))
val VitalGreen: Color = ActivityAccent

@Deprecated("Use StressAccent", ReplaceWith("StressAccent"))
val VitalAmber: Color = StressAccent

@Deprecated("Use AlertRed", ReplaceWith("AlertRed"))
val VitalRed: Color = AlertRed

@Deprecated("Use SleepAccent", ReplaceWith("SleepAccent"))
val VitalPurple: Color = SleepAccent

@Deprecated("Use StrainAccent", ReplaceWith("StrainAccent"))
val VitalOrange: Color = StrainAccent

@Deprecated("Use recoveryTierColor", ReplaceWith("recoveryTierColor(score)"))
fun scoreColor(score: Float): Color = recoveryTierColor(score)

@Deprecated("Use recoveryTierGradient", ReplaceWith("recoveryTierGradient(score)"))
fun scoreGradientColors(score: Float): Pair<Color, Color> = recoveryTierGradient(score)
