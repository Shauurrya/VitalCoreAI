package com.example.vitalcoreai.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.vitalcoreai.R

// Manrope is bundled with its OFL licence, so the interface keeps its character
// offline. Condensed Android numerals give the data an athletic counterpoint.
@OptIn(ExperimentalTextApi::class)
val VitalCoreFontFamily: FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            resId = R.font.manrope_variable,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
        )
    }
)
val VitalCoreMetricFontFamily: FontFamily = FontFamily(
    android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
)

private const val TNUM = "tnum, lnum"

val VitalCoreTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 76.sp, letterSpacing = (-1.5).sp, lineHeight = 78.sp,
        fontFeatureSettings = TNUM
    ),
    displayMedium = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 56.sp, letterSpacing = (-1.0).sp, lineHeight = 58.sp,
        fontFeatureSettings = TNUM
    ),
    displaySmall = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, letterSpacing = (-0.5).sp, lineHeight = 44.sp,
        fontFeatureSettings = TNUM
    ),
    headlineLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, letterSpacing = (-0.8).sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, letterSpacing = (-0.5).sp, lineHeight = 30.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, letterSpacing = (-0.3).sp, lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, letterSpacing = (-0.25).sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, letterSpacing = 0.sp, lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, letterSpacing = 0.sp, lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, letterSpacing = 0.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, letterSpacing = 0.1.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = 0.4.sp, lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, letterSpacing = 0.5.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, letterSpacing = 0.8.sp, lineHeight = 14.sp
    ),
)

/** Tabular metric numerals stay steady as values animate or refresh. */
object VitalCoreType {
    val metricHero = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 76.sp, letterSpacing = (-1.5).sp, lineHeight = 78.sp,
        fontFeatureSettings = TNUM
    )
    val metricLarge = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, letterSpacing = (-0.8).sp, lineHeight = 48.sp,
        fontFeatureSettings = TNUM
    )
    val metricMedium = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, letterSpacing = (-0.3).sp, lineHeight = 33.sp,
        fontFeatureSettings = TNUM
    )
    val metricSmall = TextStyle(
        fontFamily = VitalCoreMetricFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 19.sp, letterSpacing = 0.sp, lineHeight = 22.sp,
        fontFeatureSettings = TNUM
    )
    val metricUnit = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 0.1.sp, lineHeight = 16.sp,
        fontFeatureSettings = TNUM
    )
    val sectionTitle = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, letterSpacing = (-0.2).sp, lineHeight = 22.sp
    )
    val eyebrow = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 1.4.sp, lineHeight = 14.sp
    )
    val monoTiny = TextStyle(
        fontFamily = VitalCoreFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, letterSpacing = 0.3.sp, lineHeight = 14.sp,
        fontFeatureSettings = TNUM
    )
}
