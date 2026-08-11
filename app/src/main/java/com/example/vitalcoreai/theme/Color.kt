package com.example.vitalcoreai.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════
// VitalCore AI palette — original values, authored for this app.
// Base is a cool near-black; every surface step holds the same 215° hue so
// the ladder reads as one material rather than four greys.
//
// This file is the ONLY place in the app permitted to construct Color(0x…).
// ═══════════════════════════════════════════════════════════════════════════

// ─── Base surface ladder ───────────────────────────────────────────────────
// Neutral near-black rather than a blue-tinted one, matching the reference:
// the rings carry all the colour, the surfaces carry none.
val Background     = Color(0xFF0B0C0E)   // page — deepest layer, OLED-true
val SurfaceL1      = Color(0xFF16181C)   // cards
val SurfaceL2      = Color(0xFF212429)   // ring tracks, inner wells, chart plot
val SurfaceL3      = Color(0xFF2A2E34)   // bottom sheets, dialogs, pressed state
val SurfaceBar     = Color(0xFF101216)   // top/bottom bar fill (over Background)
val HairlineColor  = Color(0xFF24282E)   // 1dp card + bar borders
val DividerColor   = Color(0xFF1D2126)   // in-card separators (subtler than hairline)
val ScrimColor     = Color(0xCC050607)   // 80% scrim behind dialogs

// ─── Text ──────────────────────────────────────────────────────────────────
val OnBackground   = Color(0xFFFFFFFF)   // primary — the reference uses true white
val OnSurfaceDim   = Color(0xFF9BA1AA)   // secondary / labels
val OnSurfaceMuted = Color(0xFF5C636C)   // tertiary / disabled / em-dash
val OnAccent       = Color(0xFF0B0C0E)   // text on a filled accent

// ─── Domain accents ────────────────────────────────────────────────────────
// Matched to the reference dashboard: each of the three headline metrics owns
// one fixed hue, and nothing else in the app is allowed to borrow it.
//   Sleep    — steel blue, calm and desaturated
//   Recovery — signal yellow at mid tier, greening as it improves (see tier ramp)
//   Strain   — bright cyan, the only high-chroma blue in the palette
val RecoveryAccent  = Color(0xFFFFD427)  // signal yellow
val ReadinessAccent = Color(0xFF00A9E0)  // cyan — shares Strain's family
val SleepAccent     = Color(0xFF89AECB)  // steel blue
val StrainAccent    = Color(0xFF00A9E0)  // cyan
val StressAccent    = Color(0xFFF5A623)  // orange — the reference's "HIGH" marker
val ActivityAccent  = Color(0xFF00D46A)  // green
val HeartAccent     = Color(0xFFE85D9B)  // rose — used only on heart screens
val BioAgeAccent    = Color(0xFF8CA6C7)  // steel — deliberately low-chroma
val AlertRed        = Color(0xFFFF453A)  // alert — warnings ONLY

// ─── Recovery / percentage tier ramp (0–100, three honest bands) ───────────
// Green / yellow / red, exactly as the reference treats a recovery percentage.
val TierHigh       = Color(0xFF00D46A)   // ≥ 67
val TierModerate   = Color(0xFFFFD427)   // 34–66
val TierLow        = Color(0xFFFF453A)   // < 34
private val TierHighGlow     = Color(0xFF4BE79A)
private val TierModerateGlow = Color(0xFFFFE477)
private val TierLowGlow      = Color(0xFFFF8078)

// ─── Strain tier ramp (0–21, five Borg-style zones) ────────────────────────
// One hue family, deepening with effort — strain is a blue metric in the
// reference, so the ramp runs pale-cyan → deep-blue rather than into orange.
val StrainLight     = Color(0xFF7FD4F0)  //  0.0 – < 6.0
val StrainModerate  = Color(0xFF35BCE8)  //  6.0 – <10.0
val StrainStrenuous = Color(0xFF00A9E0)  // 10.0 – <14.0
val StrainHard      = Color(0xFF0086C3)  // 14.0 – <18.0
val StrainAllOut    = Color(0xFF0063A6)  // 18.0 – 21.0

// ─── Sleep stages ──────────────────────────────────────────────────────────
val SleepDeep  = Color(0xFF3F6C8F)
val SleepRem   = Color(0xFF89AECB)
val SleepLight = Color(0xFFB9D0E1)
val SleepAwake = Color(0xFF39404A)

// ─── HR zones (used by the day/session zone bar) ───────────────────────────
val ZoneBelow1 = Color(0xFF2E333A)
val Zone1      = Color(0xFF00A9E0)
val Zone2      = Color(0xFF00D46A)
val Zone3      = Color(0xFFFFD427)
val Zone4      = Color(0xFFF5A623)
val Zone5      = Color(0xFFFF453A)

// ─── Semantic ──────────────────────────────────────────────────────────────
val PositiveDelta  = ActivityAccent
val NegativeDelta  = AlertRed
val NeutralDelta   = OnSurfaceDim
val ConfidenceHigh   = ActivityAccent
val ConfidenceMedium = StressAccent
val ConfidenceLow    = OnSurfaceDim   // grey, NOT red — low confidence is not an error
val ChartGuideline   = Color(0xFF1F232A)
val SkeletonColor    = Color(0xFF1A1D22)

// ═══════════════════════════════════════════════════════════════════════════
// Tier functions
// ═══════════════════════════════════════════════════════════════════════════

/** Three-band colour for any 0–100 metric where higher is better. */
fun recoveryTierColor(score: Float?): Color = when {
    score == null -> OnSurfaceMuted
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
    score == null -> "—"
    score >= 67f  -> "Optimal"
    score >= 34f  -> "Adequate"
    else          -> "Low"
}

/** Colour for a metric where a HIGH value is BAD (stress, resting-HR deviation). */
fun invertedTierColor(score: Float?): Color = when {
    score == null -> OnSurfaceMuted
    score >= 67f  -> TierLow
    score >= 34f  -> TierModerate
    else          -> TierHigh
}

/** "Low" / "Moderate" / "Elevated" / "—" — the inverted-polarity caption. */
fun invertedTierLabel(score: Float?): String = when {
    score == null -> "—"
    score >= 67f  -> "Elevated"
    score >= 34f  -> "Moderate"
    else          -> "Low"
}

/** Colour for a point on the 0–21 strain scale. Boundaries: 6 / 10 / 14 / 18. */
fun strainTierColor(strain: Float?): Color = when {
    strain == null -> OnSurfaceMuted
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
    strain == null -> "—"
    strain < 6f    -> "Light"
    strain < 10f   -> "Moderate"
    strain < 14f   -> "Strenuous"
    strain < 18f   -> "Hard"
    else           -> "All Out"
}

/** Ordered zone colours, index 0 = below-zone-1 … index 5 = zone 5. */
val hrZonePalette: List<Color> = listOf(ZoneBelow1, Zone1, Zone2, Zone3, Zone4, Zone5)
