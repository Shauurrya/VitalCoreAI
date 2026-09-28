package com.example.vitalcoreai.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark-only, and deliberately so: this product is read in bed and mid-workout on
 * an OLED panel. No dynamic colour — importing the OEM palette would destroy the
 * domain-accent semantics that the whole design system is built on.
 */
private val VitalCoreColorScheme = darkColorScheme(
    primary              = OnBackground,
    onPrimary            = OnAccent,
    primaryContainer     = SurfaceL3,
    onPrimaryContainer   = OnBackground,
    secondary            = SleepAccent,
    onSecondary          = OnAccent,
    tertiary             = StrainAccent,
    onTertiary           = OnAccent,
    background           = Background,
    onBackground         = OnBackground,
    surface              = SurfaceL1,
    onSurface            = OnBackground,
    surfaceVariant       = SurfaceL2,
    onSurfaceVariant     = OnSurfaceDim,
    surfaceDim           = Background,
    surfaceBright        = SurfaceL3,
    surfaceContainerLowest = Background,
    surfaceContainerLow  = SurfaceL1,
    surfaceContainer     = SurfaceL2,
    surfaceContainerHigh = SurfaceL3,
    surfaceContainerHighest = SurfaceL3,
    surfaceTint          = OnBackground,
    inverseSurface       = OnBackground,
    inverseOnSurface     = Background,
    inversePrimary       = Background,
    outline              = HairlineColor,
    outlineVariant       = DividerColor,
    error                = AlertRed,
    onError              = OnAccent,
    scrim                = ScrimColor,
)

@Composable
fun VitalCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VitalCoreColorScheme,
        typography  = VitalCoreTypography,
        shapes      = VitalCoreShapes,
        content     = content
    )
}
