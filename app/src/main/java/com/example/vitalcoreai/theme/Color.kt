package com.example.vitalcoreai.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// VitalCore AI palette. Neutral charcoal keeps the health signals in focus.
//
// This file is the ONLY place in the app permitted to construct Color(0x…).
// ═══════════════════════════════════════════════════════════════════════════

// ─── Base surface ladder ───────────────────────────────────────────────────
// Neutral near-black rather than a blue-tinted one, matching the reference:
// the rings carry all the colour, the surfaces carry none.
val Background     = Color(0xFF111214)   // page
val SurfaceL1      = Color(0xFF1B1D20)   // cards
val SurfaceL2      = Color(0xFF292C31)   // ring tracks, inner wells, chart plot
val SurfaceL3      = Color(0xFF33373D)   // bottom sheets, dialogs, pressed state
val SurfaceBar     = Color(0xFF161719)   // top/bottom bar fill
val HairlineColor  = Color(0xFF34373C)   // 1dp card + bar borders
val DividerColor   = Color(0xFF2C2F34)   // in-card separators
val ScrimColor     = Color(0xCC050607)   // 80% scrim behind dialogs

// ─── Text ──────────────────────────────────────────────────────────────────
val OnBackground   = Color(0xFFF6F7F8)   // primary
val OnSurfaceDim   = Color(0xFFB0B4BC)   // secondary / labels
val OnSurfaceMuted = Color(0xFF858C97)   // tertiary / disabled / em-dash
val OnAccent       = Color(0xFF0B0C0E)   // text on a filled accent

// ─── Domain accents ────────────────────────────────────────────────────────
// Matched to the reference dashboard: each of the three headline metrics owns
// one fixed hue, and nothing else in the app is allowed to borrow it.
//   Sleep    — soft periwinkle
//   Recovery — signal yellow at mid tier, greening as it improves (see tier ramp)
//   Strain   — electric blue
val RecoveryAccent  = Color(0xFFFFD84A)  // signal yellow
val ReadinessAccent = Color(0xFF36B9EF)  // blue — shares Strain's family
val SleepAccent     = Color(0xFFB9BCF7)  // periwinkle
val StrainAccent    = Color(0xFF36B9EF)  // electric blue
val StressAccent    = Color(0xFFF5A623)  // orange — the reference's "HIGH" marker
val ActivityAccent  = Color(0xFF33DD87)  // green
val HeartAccent     = Color(0xFFE85D9B)  // rose — used only on heart screens
val BioAgeAccent    = Color(0xFF8CA6C7)  // steel — deliberately low-chroma
val AlertRed        = Color(0xFFFF453A)  // alert — warnings ONLY

// ─── Recovery / percentage tier ramp (0–100, three honest bands) ───────────
// Green / yellow / red, exactly as the reference treats a recovery percentage.
val TierHigh       = Color(0xFF33DD87)   // ≥ 67
val TierModerate   = Color(0xFFFFD84A)   // 34–66
val TierLow        = Color(0xFFFF453A)   // < 34
private val TierHighGlow     = Color(0xFF4BE79A)
private val TierModerateGlow = Color(0xFFFFE477)
private val TierLowGlow      = Color(0xFFFF8078)

// ─── Strain tier ramp (0–21, five Borg-style zones) ────────────────────────
// One hue family, deepening with effort — strain is a blue metric in the
// reference, so the ramp runs pale-cyan → deep-blue rather than into orange.
val StrainLight     = Color(0xFF96D9F3)  //  0.0 – < 6.0
val StrainModerate  = Color(0xFF66CCF3)  //  6.0 – <10.0
val StrainStrenuous = Color(0xFF36B9EF)  // 10.0 – <14.0
val StrainHard      = Color(0xFF2B9BDF)  // 14.0 – <18.0
val StrainAllOut    = Color(0xFF3684D1)  // 18.0 – 21.0

// ─── Sleep stages ──────────────────────────────────────────────────────────
val SleepDeep  = Color(0xFF757BD6)
val SleepRem   = Color(0xFFB9BCF7)
val SleepLight = Color(0xFFDDDEF8)
val SleepAwake = Color(0xFF454A56)

// ─── HR zones (used by the day/session zone bar) ───────────────────────────
val ZoneBelow1 = Color(0xFF2E333A)
val Zone1      = Color(0xFF36B9EF)
val Zone2      = Color(0xFF33DD87)
val Zone3      = Color(0xFFFFD84A)
val Zone4      = Color(0xFFF5A623)
val Zone5      = Color(0xFFFF453A)

// ─── Semantic ──────────────────────────────────────────────────────────────
val PositiveDelta  = ActivityAccent
val NegativeDelta  = AlertRed
val NeutralDelta   = OnSurfaceDim
val ConfidenceHigh   = ActivityAccent
val ConfidenceMedium = StressAccent
val ConfidenceLow    = OnSurfaceDim   // grey, NOT red — low confidence is not an error
val ChartGuideline   = Color(0xFF30343A)
val SkeletonColor    = Color(0xFF353A42)

// ═══════════════════════════════════════════════════════════════════════════
// Tier functions
// ═══════════════════════════════════════════════════════════════════════════

/** Three-band colour for any 0–100 metric where higher is better. */
fun recoveryTierColor(score: Float?): Color = when {
    score == null || !score.isFinite() -> OnSurfaceMuted
    score >= 67f  -> TierHigh
    score >= 34f  -> TierModerate
    else          -> TierLow
}

/** Ring arc gradient (start, end) for a 0–100 metric. */
fun recoveryTierGradient(score: Float): Pair<Color, Color> = when {
    score >= 67f -> TierHigh     to TierHighGlow
    score >= 34f -> TierModerate to TierModerateGlow
    else         -> TierLow      to TierLowGlow
}

/** "Optimal" / "Adequate" / "Low" / "—". */
fun recoveryTierLabel(score: Float?): String = when {
    score == null || !score.isFinite() -> "—"
    score >= 67f  -> "Optimal"
    score >= 34f  -> "Adequate"
    else          -> "Low"
}

/** Stress bands match the 30 / 60 / 80 boundaries in its recorded explanation. */
fun stressTierColor(score: Float?): Color = when {
    score == null || !score.isFinite() -> OnSurfaceMuted
    score >= 80f  -> TierLow
    score >= 60f  -> StressAccent
    score >= 30f  -> TierModerate
    else          -> TierHigh
}

/** The same four stress levels reported by TrendCalculators. */
fun stressTierLabel(score: Float?): String = when {
    score == null || !score.isFinite() -> "—"
    score >= 80f  -> "High"
    score >= 60f  -> "Elevated"
    score >= 30f  -> "Moderate"
    else          -> "Low"
}

/** Colour for a point on the 0–21 strain scale. Boundaries: 6 / 10 / 14 / 18. */
fun strainTierColor(strain: Float?): Color = when {
    strain == null || !strain.isFinite() -> OnSurfaceMuted
    strain < 6f    -> StrainLight
    strain < 10f   -> StrainModerate
    strain < 14f   -> StrainStrenuous
    strain < 18f   -> StrainHard
    else           -> StrainAllOut
}

fun strainTierGradient(strain: Float): Pair<Color, Color> {
    val c = strainTierColor(strain)
    return c.copy(alpha = 0.80f) to c
}

/** "Light" / "Moderate" / "Strenuous" / "Hard" / "All Out" / "—". */
fun strainZoneLabel(strain: Float?): String = when {
    strain == null || !strain.isFinite() -> "—"
    strain < 6f    -> "Light"
    strain < 10f   -> "Moderate"
    strain < 14f   -> "Strenuous"
    strain < 18f   -> "Hard"
    else           -> "All Out"
}

/** Ordered zone colours, index 0 = below-zone-1 … index 5 = zone 5. */
val hrZonePalette: List<Color> = listOf(ZoneBelow1, Zone1, Zone2, Zone3, Zone4, Zone5)
