package com.example.vitalcoreai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.theme.OnBackground
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.SleepAccent
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.SurfaceL2
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.VitalShapes
import com.example.vitalcoreai.theme.recoveryTierColor
import com.example.vitalcoreai.theme.recoveryTierLabel
import com.example.vitalcoreai.theme.strainTierColor
import com.example.vitalcoreai.theme.strainZoneLabel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object RingMotion {
    val Easing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)
    const val HeroDurationMs = 900
    const val CompactDurationMs = 450
}

object RingDefaults {
    val HeroSize = 200.dp
    val HeroStroke = 10.dp
    val LargeSize = 152.dp
    val LargeStroke = 8.dp
    val MediumSize = 108.dp
    val MediumStroke = 6.dp
    val CompactSize = 44.dp
    val CompactStroke = 3.dp
    val TrackColor = SurfaceL2
    const val GlowStrokeMultiplier = 1.6f
    const val GlowAlpha = 0.06f
    const val StrainSegments = 21
    const val StrainGapDegrees = 2f
}

private const val DialStart = 126f
private const val DialSweep = 288f

private fun Float?.bounded(max: Float): Float? =
    this?.takeIf { it.isFinite() }?.coerceIn(0f, max)

@Composable
private fun ringProgress(target: Float, animate: Boolean, duration: Int): Float {
    val progress = remember { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target, animate) {
        if (animate) progress.animateTo(target, tween(duration, easing = RingMotion.Easing))
        else progress.snapTo(target)
    }
    return progress.value
}

private fun Modifier.ringSemantics(description: String, onClick: (() -> Unit)?): Modifier =
    clearAndSetSemantics {
        contentDescription = description
        if (onClick != null) {
            role = Role.Button
            this.onClick { onClick(); true }
        }
    }

@Composable
private fun Modifier.ringClickable(onClick: (() -> Unit)?, accent: Color): Modifier =
    if (onClick == null) this else clip(CircleShape).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(color = accent),
        onClick = onClick
    )

/** One calibrated, open dial shared by the overview and metric detail screens. */
@Composable
private fun PerformanceDial(
    fraction: Float,
    accent: Color,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
    showGlow: Boolean = false,
    targetFractions: List<Float> = emptyList(),
) {
    Canvas(modifier) {
        val sw = strokeWidth.toPx()
        // Reserve room for target markers and round caps on all four sides.
        val diameter = (min(size.width, size.height) - sw - 12.dp.toPx()).coerceAtLeast(0f)
        val radius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = center - Offset(radius, radius)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        val progressSweep = DialSweep * fraction.coerceIn(0f, 1f)

        drawArc(
            color = RingDefaults.TrackColor,
            startAngle = DialStart, sweepAngle = DialSweep, useCenter = false,
            topLeft = topLeft, size = arcSize, style = stroke
        )

        // Fine inner indices lend the dial a quiet instrument-like rhythm.
        val tickOuter = radius - sw / 2f - 5.dp.toPx()
        if (tickOuter > 16.dp.toPx()) {
            for (tick in 0..24) {
                val angle = DialStart + DialSweep * tick / 24f
                val tickLength = if (tick % 6 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                drawLine(
                    color = OnSurfaceMuted.copy(alpha = if (tick % 6 == 0) 0.48f else 0.25f),
                    start = pointOnDial(center, tickOuter - tickLength, angle),
                    end = pointOnDial(center, tickOuter, angle),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        if (progressSweep > 0.1f) {
            if (showGlow) {
                drawArc(
                    color = accent.copy(alpha = RingDefaults.GlowAlpha),
                    startAngle = DialStart, sweepAngle = progressSweep, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(sw * RingDefaults.GlowStrokeMultiplier, cap = StrokeCap.Round)
                )
            }
            drawArc(
                color = accent,
                startAngle = DialStart, sweepAngle = progressSweep, useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
        }

        targetFractions.forEach { target ->
            val angle = DialStart + DialSweep * target.coerceIn(0f, 1f)
            drawLine(
                color = OnBackground.copy(alpha = 0.9f),
                start = pointOnDial(center, radius - sw / 2f - 2.dp.toPx(), angle),
                end = pointOnDial(center, radius + sw / 2f + 3.dp.toPx(), angle),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun pointOnDial(center: Offset, radius: Float, angle: Float): Offset {
    val radians = Math.toRadians(angle.toDouble())
    return Offset(
        center.x + (radius * cos(radians)).toFloat(),
        center.y + (radius * sin(radians)).toFloat()
    )
}

/** A percentage dial. Missing or invalid samples remain visibly unavailable. */
@Composable
fun PercentRing(
    value: Float?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = RingDefaults.HeroSize,
    strokeWidth: Dp = RingDefaults.HeroStroke,
    accent: Color = RecoveryAccent,
    useTierColors: Boolean = true,
    caption: String? = null,
    showUnit: Boolean = true,
    showGlow: Boolean = true,
    animate: Boolean = true,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val score = value.bounded(100f)
    val progress = ringProgress(score ?: 0f, animate, RingMotion.HeroDurationMs)
    val ringColor = if (useTierColors) recoveryTierColor(score) else accent
    val resolvedCaption = caption ?: if (score == null) "No data" else recoveryTierLabel(score)
    val description = contentDescription ?: if (score == null) "$label, no data recorded yet"
        else "$label ${score.roundToInt()} percent, $resolvedCaption"

    BoxWithConstraints(
        modifier = modifier.size(size).ringClickable(onClick, ringColor)
            .ringSemantics(description, onClick),
        contentAlignment = Alignment.Center
    ) {
        PerformanceDial(progress / 100f, ringColor, strokeWidth, Modifier.fillMaxSize(), showGlow)
        RingCenter(
            valueText = if (score == null) "—" else progress.roundToInt().toString(),
            unit = if (showUnit && score != null) "%" else null,
            size = minOf(size, maxWidth, maxHeight),
            label = label,
            caption = resolvedCaption,
            available = score != null,
            accent = ringColor
        )
    }
}

/** Strain always uses the native 0–21 scale, including its optional target band. */
@Composable
fun StrainRing(
    strain: Float?,
    modifier: Modifier = Modifier,
    size: Dp = RingDefaults.HeroSize,
    strokeWidth: Dp = RingDefaults.HeroStroke,
    label: String = "STRAIN",
    caption: String? = null,
    exertionMinutes: Float? = null,
    targetLow: Float? = null,
    targetHigh: Float? = null,
    isProxyEstimate: Boolean = false,
    animate: Boolean = true,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val score = strain.bounded(21f)
    val progress = ringProgress(score ?: 0f, animate, RingMotion.HeroDurationMs)
    val baseCaption = caption ?: if (score == null) "No data" else strainZoneLabel(score)
    val resolvedCaption = if (isProxyEstimate && score != null) "$baseCaption · estimated" else baseCaption
    val description = contentDescription ?: strainDescription(label, score, isProxyEstimate)

    BoxWithConstraints(
        modifier = modifier.size(size).ringClickable(onClick, StrainAccent)
            .ringSemantics(description, onClick),
        contentAlignment = Alignment.Center
    ) {
        PerformanceDial(
            fraction = progress / 21f,
            accent = StrainAccent,
            strokeWidth = strokeWidth,
            modifier = Modifier.fillMaxSize(),
            targetFractions = listOfNotNull(targetLow.bounded(21f), targetHigh.bounded(21f)).map { it / 21f }
        )
        RingCenter(
            valueText = if (score == null) "—" else fmtStrain(progress),
            unit = if (score != null) "/ 21" else null,
            size = minOf(size, maxWidth, maxHeight),
            label = label,
            caption = resolvedCaption,
            available = score != null,
            accent = StrainAccent,
            footnote = exertionMinutes?.takeIf { it.isFinite() }?.let { "${it.roundToInt()} exertion-min" }
        )
    }
}

private fun fmtStrain(value: Float): String {
    val scaled = (value * 10f).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

private fun strainDescription(label: String, value: Float?, isProxy: Boolean): String =
    if (value == null) "$label, no data recorded yet" else buildString {
        append("$label ${fmtStrain(value)} out of 21, ${strainZoneLabel(value)}")
        if (isProxy) append(", estimated from steps and exercise minutes")
    }

@Composable
private fun RingCenter(
    valueText: String,
    unit: String?,
    size: Dp,
    label: String,
    caption: String?,
    available: Boolean,
    accent: Color,
    footnote: String? = null,
) {
    val compact = size < 108.dp
    val numeralSize = (size.value * 0.28f).coerceIn(18f, 58f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = size * 0.14f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = valueText,
                style = VitalCoreType.metricHero.copy(fontSize = numeralSize.sp, lineHeight = (numeralSize + 2).sp),
                color = if (available) OnBackground else OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (unit != null && !compact) {
                Text(
                    text = unit,
                    style = VitalCoreType.metricUnit,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(start = 3.dp, top = numeralSize.dp * 0.2f)
                )
            }
        }
        if (label.isNotBlank() && !compact) {
            Spacer(Modifier.height(4.dp))
            Text(label.uppercase(), style = VitalCoreType.eyebrow, color = OnSurfaceDim,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!caption.isNullOrBlank() && !compact) {
            Spacer(Modifier.height(4.dp))
            Text(caption, style = VitalCoreType.monoTiny, color = if (available) accent else OnSurfaceMuted,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (footnote != null && size >= RingDefaults.LargeSize) {
            Spacer(Modifier.height(4.dp))
            Text(footnote, style = VitalCoreType.monoTiny, color = OnSurfaceMuted,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact full-circle version for rows, where calibration marks would add noise. */
@Composable
fun CompactRing(
    value: Float?,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    size: Dp = RingDefaults.CompactSize,
    strokeWidth: Dp = RingDefaults.CompactStroke,
    color: Color? = null,
    decimals: Int = 0,
    showValue: Boolean = true,
    animate: Boolean = true,
    contentDescription: String? = null,
) {
    val ceiling = max.takeIf { it.isFinite() && it > 0f } ?: 100f
    val score = value.bounded(ceiling)
    val progress = ringProgress(score ?: 0f, animate, RingMotion.CompactDurationMs)
    val resolved = color ?: if (ceiling <= 21.5f) strainTierColor(score) else recoveryTierColor(score)
    val text = if (score == null) "—" else if (decimals >= 1) fmtStrain(progress) else progress.roundToInt().toString()
    val description = contentDescription ?: if (score == null) "No data"
        else "${if (decimals >= 1) fmtStrain(score) else score.roundToInt()} of ${ceiling.roundToInt()}"

    Box(
        modifier = modifier.size(size).ringSemantics(description, null),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val diameter = (min(this.size.width, this.size.height) - sw).coerceAtLeast(0f)
            val topLeft = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            val stroke = Stroke(sw, cap = StrokeCap.Round)
            drawArc(RingDefaults.TrackColor, -90f, 360f, false, topLeft, arcSize, style = stroke)
            if (progress > 0f) {
                drawArc(resolved, -90f, 360f * progress / ceiling, false, topLeft, arcSize, style = stroke)
            }
        }
        if (showValue) {
            Text(text, style = VitalCoreType.metricSmall,
                color = if (score == null) OnSurfaceMuted else OnBackground, maxLines = 1)
        }
    }
}

/** The overview's primary visual: three equally weighted, responsive performance dials. */
@Composable
fun HeroRingRow(
    recovery: Float?,
    strain: Float?,
    sleep: Float?,
    onRecoveryClick: () -> Unit,
    onStrainClick: () -> Unit,
    onSleepClick: () -> Unit,
    modifier: Modifier = Modifier,
    recoveryConfidence: Confidence = Confidence.HIGH,
    strainConfidence: Confidence = Confidence.HIGH,
    sleepConfidence: Confidence = Confidence.HIGH,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HeroRingCell(
            value = sleep.bounded(100f), max = 100f, label = "SLEEP", accent = SleepAccent,
            status = "Performance", confidence = sleepConfidence,
            onClick = onSleepClick, modifier = Modifier.weight(1f)
        )
        HeroRingCell(
            value = recovery.bounded(100f), max = 100f, label = "RECOVERY", accent = recoveryTierColor(recovery),
            status = recoveryTierLabel(recovery), confidence = recoveryConfidence,
            onClick = onRecoveryClick, modifier = Modifier.weight(1f)
        )
        HeroRingCell(
            value = strain.bounded(21f), max = 21f, label = "STRAIN", accent = StrainAccent,
            status = strainZoneLabel(strain), confidence = strainConfidence,
            onClick = onStrainClick, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HeroRingCell(
    value: Float?,
    max: Float,
    label: String,
    accent: Color,
    status: String,
    confidence: Confidence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = ringProgress(value ?: 0f, true, RingMotion.HeroDurationMs)
    val strain = max == 21f
    val confidenceText = when (confidence) {
        Confidence.HIGH -> null
        Confidence.MEDIUM -> "Estimated"
        Confidence.LOW -> "Low confidence"
    }
    val description = if (value == null) "$label, no data recorded yet. Open details."
        else buildString {
            append(if (strain) "$label ${fmtStrain(value)} out of 21" else "$label ${value.roundToInt()} percent")
            append(", $status")
            confidenceText?.let { append(", $it") }
            append(". Open details.")
        }
    Column(
        modifier = modifier.clip(VitalShapes.Tile).clickable(onClick = onClick)
            .ringSemantics(description, onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = maxWidth.coerceAtMost(142.dp)
            // Large figures can scale a little, but must stay inside the dial.
            // Labels below remain fully governed by the user's text-size setting.
            val numeralSize = (diameter.value * 0.29f).coerceIn(21f, 40f) /
                (LocalDensity.current.fontScale / 1.2f).coerceAtLeast(1f)
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                PerformanceDial(
                    fraction = progress / max, accent = accent,
                    strokeWidth = (diameter.value * 0.058f).dp,
                    modifier = Modifier.fillMaxSize()
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = if (value == null) "—" else if (strain) fmtStrain(progress) else progress.roundToInt().toString(),
                            style = VitalCoreType.metricMedium.copy(
                                fontSize = numeralSize.sp, lineHeight = (numeralSize + 2f).sp,
                                fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp
                            ),
                            color = if (value == null) OnSurfaceMuted else OnBackground,
                            maxLines = 1
                        )
                        if (!strain && value != null) {
                            Text("%", style = VitalCoreType.metricUnit.copy(fontSize = 11.sp),
                                color = OnSurfaceDim, modifier = Modifier.padding(start = 1.dp, bottom = 3.dp))
                        }
                    }
                    if (strain && value != null) {
                        Text("OF 21", style = VitalCoreType.monoTiny.copy(fontSize = 8.sp), color = OnSurfaceMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = VitalCoreType.eyebrow.copy(letterSpacing = 1.1.sp), color = OnBackground,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        Text(
            text = if (value == null) "No data yet" else status,
            style = VitalCoreType.monoTiny.copy(fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.sp),
            color = if (value == null) OnSurfaceMuted else accent,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (confidenceText != null && value != null) {
            Spacer(Modifier.height(4.dp))
            Text(confidenceText, style = VitalCoreType.monoTiny.copy(letterSpacing = 0.sp),
                color = OnSurfaceDim, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
