package com.example.vitalcoreai.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val VitalCoreShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Semantic shapes. Nothing outside this file may construct a RoundedCornerShape.
 * If a screen needs a corner radius it does not find here, the shape is missing
 * from the design system — add it here rather than inlining it.
 */
object VitalShapes {
    val Card     = RoundedCornerShape(20.dp)
    val RingCard = RoundedCornerShape(24.dp)
    val Tile     = RoundedCornerShape(16.dp)
    val Chip     = RoundedCornerShape(10.dp)
    val Pill     = RoundedCornerShape(percent = 50)
    val Bar      = RoundedCornerShape(3.dp)
    val IconBox  = RoundedCornerShape(10.dp)
    val Sheet    = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val ChartBar = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)

    /** Top-only rounding matching [Card] — used by the 3dp accent strip. */
    val CardTopStrip = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}
