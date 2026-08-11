package com.example.vitalcoreai.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ── FONT SOURCING ────────────────────────────────────────────────────────────
 *
 * The downloadable-font provider that used to live here has been removed: it was
 * a network dependency inside an offline-only app, and its failure mode was total
 * (every glyph silently falling back to the system face) and invisible.
 *
 * The design system specifies bundled Manrope. The five static instances are
 * binary assets and are NOT present in this repository, so this file currently
 * resolves to the platform's default sans. Everything else about the type scale
 * — sizes, weights, tracking, tabular numerals — is already final; swapping in
 * the real face is a two-line change and touches nothing else.
 *
 * TO BUNDLE MANROPE (SIL OFL 1.1, redistribution permitted):
 *   1. Drop these five files into `app/src/main/res/font/` (Latin + Latin-Ext
 *      subset, ~45 KB each):
 *        manrope_regular.ttf     (400)
 *        manrope_medium.ttf      (500)
 *        manrope_semibold.ttf    (600)
 *        manrope_bold.ttf        (700)
 *        manrope_extrabold.ttf   (800)
 *   2. Add `app/src/main/assets/licenses/OFL-Manrope.txt` (full OFL text) — the
 *      Settings → "Open source licences" row reads it from assets.
 *   3. Replace the `VitalCoreFontFamily` declaration below with the commented
 *      block beneath it and add `import androidx.compose.ui.text.font.Font` plus
 *      `import com.example.vitalcoreai.R`.
 *
 * There is deliberately no try/catch and no runtime fallback chain: once the
 * files are present, a missing weight is a *build* error, which is the desired
 * behaviour.
 */
val VitalCoreFontFamily: FontFamily = FontFamily.Default

/*
val VitalCoreFontFamily: FontFamily = FontFamily(
    Font(R.font.manrope_regular,   FontWeight.Normal),
    Font(R.font.manrope_medium,    FontWeight.Medium),
    Font(R.font.manrope_semibold,  FontWeight.SemiBold),
    Font(R.font.manrope_bold,      FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)
*/

/**
 * Tabular lining figures. Without this a ring animating 68 → 87 visibly jitters
 * as glyph widths change — the single most visible polish detail in the app.
 * Applied to every metric numeral style, never to prose.
 */
private const val TNUM = "tnum, lnum"

val VitalCoreTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 76.sp, letterSpacing = (-2.0).sp, lineHeight = 78.sp
    ),
    displayMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp, letterSpacing = (-1.5).sp, lineHeight = 58.sp
    ),
    displaySmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, letterSpacing = (-1.0).sp, lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, letterSpacing = (-0.5).sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, letterSpacing = (-0.25).sp, lineHeight = 30.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, letterSpacing = 0.sp, lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, letterSpacing = 0.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, letterSpacing = 0.1.sp, lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 0.1.sp, lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, letterSpacing = 0.15.sp, lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, letterSpacing = 0.2.sp, lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, letterSpacing = 0.25.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 0.6.sp
    ),
    labelMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 1.2.sp
    ),
)

/**
 * Non-Material styles. Metric numerals are tabular so animated values do not
 * change width mid-animation. Use these — never `displayLarge.copy(...)`.
 */
object VitalCoreType {
    val metricHero = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 72.sp, letterSpacing = (-2.5).sp, lineHeight = 72.sp,
        fontFeatureSettings = TNUM
    )
    val metricLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp, letterSpacing = (-1.5).sp, lineHeight = 46.sp,
        fontFeatureSettings = TNUM
    )
    val metricMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, letterSpacing = (-0.8).sp, lineHeight = 30.sp,
        fontFeatureSettings = TNUM
    )
    val metricSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, letterSpacing = (-0.3).sp, lineHeight = 18.sp,
        fontFeatureSettings = TNUM
    )
    val metricUnit = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 0.2.sp, lineHeight = 14.sp,
        fontFeatureSettings = TNUM
    )

    /** ALL-CAPS eyebrow. Callers pass already-uppercased text or use `.uppercase()`. */
    val eyebrow = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 1.4.sp, lineHeight = 12.sp
    )

    val monoTiny = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 9.sp, letterSpacing = 0.4.sp, lineHeight = 11.sp,
        fontFeatureSettings = TNUM
    )
}
