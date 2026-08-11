package com.example.vitalcoreai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.SleepAccent
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.SurfaceL2
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.recoveryTierColor
import com.example.vitalcoreai.theme.recoveryTierGradient
import com.example.vitalcoreai.theme.recoveryTierLabel
import com.example.vitalcoreai.theme.strainTierColor
import com.example.vitalcoreai.theme.strainZoneLabel
import com.example.vitalcoreai.ui.accessibility.AccessibilityUtils
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════════════
// RING SYSTEM
//
// Two ring shapes, deliberately different, so the user never has to read the
// number to know which kind of metric they are looking at:
//
//   PercentRing  continuous 360° sweep · "%"   · 0 decimals · one tier gradient
//   StrainRing   21 discrete segments  · "/21" · 1 decimal  · per-segment heat ramp
//
// No springs. spring(dampingRatio = 0.85f) overshoots, drawing an arc past 100%
// or past 21.0 for ~120 ms — visibly wrong on a bounded gauge, and non-
// deterministic under screenshot test.
// ═══════════════════════════════════════════════════════════════════════════

object RingMotion {
    /** Expo-out. Fast commit, long settle — reads as "landing on" a value. */
    val Easing = CubicBezierEasing(0.16f, 1.00f, 0.30f, 1.00f)
    const val HeroDurationMs = 900
    const val CompactDurationMs = 450
}

object RingDefaults {
    val HeroSize = 200.dp;    val HeroStroke = 16.dp      // dedicated screen hero
    val LargeSize = 152.dp;   val LargeStroke = 13.dp     // report cards
    val MediumSize = 108.dp;  val MediumStroke = 10.dp    // Overview 3-up row
    val CompactSize = 44.dp;  val CompactStroke = 4.dp    // list rows, More hub

    val TrackColor = SurfaceL2
    const val GlowStrokeMultiplier = 2.4f
    const val GlowAlpha = 0.20f
    const val StrainSegments = 21
    const val StrainGapDegrees = 2.0f
}

/** Numeral style is chosen from the ring diameter — callers never pass a style. */
private fun ringNumeralStyle(size: Dp): TextStyle = when {
    size >= 180.dp -> VitalCoreType.metricHero
    size >= 130.dp -> VitalCoreType.metricLarge
    size >= 88.dp  -> VitalCoreType.metricMedium
    else           -> VitalCoreType.metricSmall
}

/** How far above the baseline the unit glyph sits, scaled to the numeral. */
private fun unitBaselineInset(size: Dp): Dp = when {
    size >= 180.dp -> 12.dp
    size >= 130.dp -> 8.dp
    size >= 88.dp  -> 5.dp
    else           -> 3.dp
}

/**
 * Bounding box for a stroked ring arc.
 *
 * `drawArc` centres the stroke on its bounding box, so drawing into the full canvas
 * clips half the stroke against all four edges — the ring then reads as a flattened
 * capsule rather than a circle. Inset by the widest stroke that will be drawn (the
 * glow, where there is one) and keep the box square so it stays circular even if the
 * canvas is not.
 */
private fun DrawScope.ringArcBox(widestStrokePx: Float): Pair<Offset, Size> {
    val diameter = (kotlin.math.min(size.width, size.height) - widestStrokePx)
        .coerceAtLeast(0f)
    val topLeft = Offset(
        x = (size.width - diameter) / 2f,
        y = (size.height - diameter) / 2f
    )
    return topLeft to Size(diameter, diameter)
}

private fun ringDurationMs(size: Dp): Int =
    if (size >= RingDefaults.MediumSize) RingMotion.HeroDurationMs else RingMotion.CompactDurationMs

/** Ring-local click wiring. Semantics are cleared and re-declared so TalkBack
 *  reads one sentence instead of four disconnected fragments. */
private fun Modifier.ringSemantics(
    description: String,
    onClick: (() -> Unit)?,
): Modifier = clearAndSetSemantics {
    this.contentDescription = description
    if (onClick != null) {
        this.role = Role.Button
        this.onClick { onClick(); true }
    }
}

@Composable
private fun Modifier.ringClickable(onClick: (() -> Unit)?, accent: Color): Modifier =
    if (onClick == null) this else this
        .clip(CircleShape)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = accent),
            onClick = onClick
        )

// ───────────────────────────────────────────────────────────────────────────
// PercentRing
// ───────────────────────────────────────────────────────────────────────────

/**
 * Continuous 0–100 ring. Recovery, Sleep, Readiness, Stress, Activity, report scores.
 *
 * @param value          0–100. null renders an em-dash, a fully unlit track and caption "No data".
 * @param label          eyebrow under the number, e.g. "RECOVERY". Uppercased by the composable.
 * @param accent         domain colour; used for the glow when [useTierColors] is false.
 * @param useTierColors  true → arc + number use recoveryTierGradient(value);
 *                       false → arc + number use [accent]. Stress passes false and
 *                       supplies invertedTierColor() itself via [accent].
 * @param caption        small line under the label. null → recoveryTierLabel(value).
 *                       Pass "" to suppress it entirely.
 */
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
    val target = value?.coerceIn(0f, 100f) ?: 0f
    val progress = remember { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target, animate) {
        if (animate) {
            progress.animateTo(target, tween(ringDurationMs(size), easing = RingMotion.Easing))
        } else {
            progress.snapTo(target)
        }
    }

    val tier = recoveryTierGradient(target)
    val arcStart = if (useTierColors) tier.first.copy(alpha = 0.80f) else accent.copy(alpha = 0.80f)
    val arcEnd = if (useTierColors) tier.second else accent
    val numberColor = when {
        value == null -> OnSurfaceMuted
        useTierColors -> recoveryTierColor(value)
        else          -> accent
    }
    val glowColor = (if (useTierColors) recoveryTierColor(target) else accent)
        .copy(alpha = RingDefaults.GlowAlpha)

    val resolvedCaption = caption ?: if (value == null) "No data" else recoveryTierLabel(value)
    val description = contentDescription ?: percentRingDescription(label, value)

    Box(
        modifier = modifier
            .size(size)
            .ringClickable(onClick, accent)
            .ringSemantics(description, onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val stroke = Stroke(width = sw, cap = StrokeCap.Round)
            val sweep = 360f * (progress.value / 100f)

            val widestStroke = if (showGlow) sw * RingDefaults.GlowStrokeMultiplier else sw
            val (arcTopLeft, arcSize) = ringArcBox(widestStroke)

            drawArc(
                color = RingDefaults.TrackColor,
                startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke,
                topLeft = arcTopLeft, size = arcSize
            )

            if (sweep > 0.5f) {
                if (showGlow) {
                    drawArc(
                        color = glowColor,
                        startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        style = Stroke(
                            width = sw * RingDefaults.GlowStrokeMultiplier,
                            cap = StrokeCap.Round
                        ),
                        topLeft = arcTopLeft, size = arcSize
                    )
                }
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to arcStart,
                        0.5f to arcEnd,
                        1.0f to arcEnd
                    ),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke,
                    topLeft = arcTopLeft, size = arcSize
                )
            }
        }

        RingCenter(
            valueText = value?.let { progress.value.roundToInt().toString() } ?: "—",
            valueColor = numberColor,
            unit = if (showUnit && value != null) "%" else null,
            size = size,
            label = label,
            caption = resolvedCaption,
        )
    }
}

private fun percentRingDescription(label: String, value: Float?): String =
    if (value == null) {
        AccessibilityUtils.insufficientDataDescription(label, listOf("No data recorded yet"))
    } else {
        "$label ${value.roundToInt()} out of 100, ${recoveryTierLabel(value)}"
    }

// ───────────────────────────────────────────────────────────────────────────
// StrainRing
// ───────────────────────────────────────────────────────────────────────────

/**
 * Segmented 0–21 Borg-style strain ring.
 *
 * Segment i is lit when i < floor(strain); the segment at index floor(strain) is
 * drawn at sweep × frac(strain) so it grows rather than fades. Lit segment i uses
 * strainTierColor(i + 0.5f) — the ring itself is a heat ramp, so a hard day looks
 * hot before a single digit is read.
 *
 * @param strain          0–21. null renders "—" with every segment unlit — a charging
 *                        watch must never read as a rest day.
 * @param targetLow       optimal band lower bound — a radial tick outside the stroke.
 * @param targetHigh      optimal band upper bound.
 * @param isProxyEstimate true → number at 0.75 alpha and caption suffix " · estimated".
 */
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
    val maxStrain = RingDefaults.StrainSegments.toFloat()
    val target = strain?.coerceIn(0f, maxStrain) ?: 0f
    val progress = remember { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target, animate) {
        if (animate) {
            progress.animateTo(target, tween(ringDurationMs(size), easing = RingMotion.Easing))
        } else {
            progress.snapTo(target)
        }
    }

    val numberColor = strainTierColor(strain)
        .copy(alpha = if (isProxyEstimate) 0.75f else 1f)
    val baseCaption = caption ?: if (strain == null) "No data" else strainZoneLabel(strain)
    val resolvedCaption = if (isProxyEstimate) "$baseCaption · estimated" else baseCaption
    val description = contentDescription ?: strainRingDescription(label, strain, isProxyEstimate)

    Box(
        modifier = modifier
            .size(size)
            .ringClickable(onClick, StrainAccent)
            .ringSemantics(description, onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val segStroke = Stroke(width = sw, cap = StrokeCap.Butt)
            val segTotal = 360f / RingDefaults.StrainSegments
            val gap = RingDefaults.StrainGapDegrees
            val lit = progress.value
            val fullSegments = floor(lit).toInt()
            val partial = lit - fullSegments

            // Sized for the 2.2× glow stroke so every arc shares one square, un-clipped box.
            val (arcTopLeft, arcSize) = ringArcBox(sw * 2.2f)

            // Glow under the lit range — one continuous arc, not per segment.
            if (lit > 0.05f) {
                drawArc(
                    color = strainTierColor(lit).copy(alpha = 0.18f),
                    startAngle = -90f,
                    sweepAngle = 360f * lit / maxStrain,
                    useCenter = false,
                    style = Stroke(width = sw * 2.2f, cap = StrokeCap.Round),
                    topLeft = arcTopLeft, size = arcSize
                )
            }

            for (i in 0 until RingDefaults.StrainSegments) {
                val start = -90f + i * segTotal + gap / 2f
                val fullSweep = segTotal - gap

                drawArc(
                    color = RingDefaults.TrackColor,
                    startAngle = start, sweepAngle = fullSweep,
                    useCenter = false, style = segStroke,
                    topLeft = arcTopLeft, size = arcSize
                )

                val fraction = when {
                    i < fullSegments -> 1f
                    i == fullSegments -> partial
                    else -> 0f
                }
                if (fraction > 0.001f) {
                    drawArc(
                        color = strainTierColor(i + 0.5f),
                        startAngle = start, sweepAngle = fullSweep * fraction,
                        useCenter = false, style = segStroke,
                        topLeft = arcTopLeft, size = arcSize
                    )
                }
            }

            // Optimal-band ticks, radial, just outside the stroke.
            val tickRadius = kotlin.math.min(this.size.width, this.size.height) / 2f - sw / 2f
            listOfNotNull(targetLow, targetHigh).forEach { t ->
                val angleDeg = -90.0 + 360.0 * (t.coerceIn(0f, maxStrain) / maxStrain)
                val rad = Math.toRadians(angleDeg)
                val inner = tickRadius + sw / 2f + 3.dp.toPx()
                val outer = inner + 6.dp.toPx()
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                drawLine(
                    color = OnSurfaceDim,
                    start = Offset(cx + (inner * cos(rad)).toFloat(), cy + (inner * sin(rad)).toFloat()),
                    end = Offset(cx + (outer * cos(rad)).toFloat(), cy + (outer * sin(rad)).toFloat()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        RingCenter(
            valueText = strain?.let { fmtStrain(progress.value) } ?: "—",
            valueColor = numberColor,
            unit = if (strain != null) "/21" else null,
            size = size,
            label = label,
            caption = resolvedCaption,
            footnote = exertionMinutes?.let { "${it.roundToInt()} exertion-min" },
        )
    }
}

private fun fmtStrain(v: Float): String {
    val scaled = (v * 10f).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

private fun strainRingDescription(label: String, strain: Float?, isProxy: Boolean): String =
    if (strain == null) {
        AccessibilityUtils.insufficientDataDescription(label, listOf("No data recorded yet"))
    } else {
        buildString {
            append("$label ${fmtStrain(strain)} out of 21, ${strainZoneLabel(strain)}")
            if (isProxy) append(", estimated from steps and exercise minutes")
        }
    }

// ───────────────────────────────────────────────────────────────────────────
// Shared centre column
// ───────────────────────────────────────────────────────────────────────────

@Composable
private fun RingCenter(
    valueText: String,
    valueColor: Color,
    unit: String?,
    size: Dp,
    label: String,
    caption: String?,
    footnote: String? = null,
) {
    val numeralStyle = ringNumeralStyle(size)
    val compact = size < RingDefaults.MediumSize

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = size * 0.14f)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = valueText, style = numeralStyle, color = valueColor, maxLines = 1)
            if (unit != null && !compact) {
                Text(
                    text = unit,
                    style = VitalCoreType.metricUnit,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(start = 2.dp, bottom = unitBaselineInset(size))
                )
            }
        }
        if (label.isNotBlank() && !compact) {
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = label.uppercase(),
                style = VitalCoreType.eyebrow,
                color = OnSurfaceDim,
                maxLines = 1
            )
        }
        if (!caption.isNullOrBlank() && !compact) {
            Text(
                text = caption,
                style = VitalCoreType.monoTiny,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        if (footnote != null && size >= RingDefaults.LargeSize) {
            Text(
                text = footnote,
                style = VitalCoreType.monoTiny,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// CompactRing
// ───────────────────────────────────────────────────────────────────────────

/**
 * Flat single-colour ring for list rows and dense summary cards.
 * No gradient, no glow — these appear 10+ per screen and must not allocate brushes.
 *
 * @param max      100f for percentage metrics, 21f for strain.
 * @param decimals 0 for percentages, 1 for strain.
 * @param color    null → recoveryTierColor(value) when max == 100f,
 *                        strainTierColor(value)  when max == 21f.
 */
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
    val target = value?.coerceIn(0f, max) ?: 0f
    val progress = remember { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target, animate) {
        if (animate) {
            progress.animateTo(target, tween(RingMotion.CompactDurationMs, easing = RingMotion.Easing))
        } else {
            progress.snapTo(target)
        }
    }

    val resolved = color ?: if (max <= 21.5f) strainTierColor(value) else recoveryTierColor(value)
    val text = when {
        value == null -> "—"
        decimals >= 1 -> fmtStrain(progress.value)
        else          -> progress.value.roundToInt().toString()
    }
    val description = contentDescription
        ?: if (value == null) "No data" else "${text.trimEnd()} of ${max.roundToInt()}"

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { this.contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val stroke = Stroke(width = sw, cap = StrokeCap.Round)
            val (arcTopLeft, arcSize) = ringArcBox(sw)
            drawArc(
                color = RingDefaults.TrackColor,
                startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke,
                topLeft = arcTopLeft, size = arcSize
            )
            val sweep = 360f * (progress.value / max).coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    color = resolved,
                    startAngle = -90f, sweepAngle = sweep, useCenter = false, style = stroke,
                    topLeft = arcTopLeft, size = arcSize
                )
            }
        }
        if (showValue) {
            Text(
                text = text,
                style = VitalCoreType.metricSmall,
                color = if (value == null) OnSurfaceMuted else resolved,
                maxLines = 1
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// HeroRingRow — Overview's 3-up anchor
// ───────────────────────────────────────────────────────────────────────────

/**
 * Overview's 3-up hero row: Recovery · Strain · Sleep, each in its own domain-
 * accented card. A confidence badge appears under a ring only when that score is
 * not HIGH confidence — a badge on every ring would be noise.
 */
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
    val cardPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.md)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        VitalCard(
            modifier = Modifier.weight(1f),
            accent = RecoveryAccent,
            shape = com.example.vitalcoreai.theme.VitalShapes.RingCard,
            contentPadding = cardPadding,
            onClick = onRecoveryClick
        ) {
            HeroRingCell(confidence = recoveryConfidence) {
                PercentRing(
                    value = recovery,
                    label = "Recovery",
                    size = RingDefaults.MediumSize,
                    strokeWidth = RingDefaults.MediumStroke,
                    accent = RecoveryAccent,
                    caption = "",
                )
            }
        }
        VitalCard(
            modifier = Modifier.weight(1f),
            accent = StrainAccent,
            shape = com.example.vitalcoreai.theme.VitalShapes.RingCard,
            contentPadding = cardPadding,
            onClick = onStrainClick
        ) {
            HeroRingCell(confidence = strainConfidence) {
                StrainRing(
                    strain = strain,
                    size = RingDefaults.MediumSize,
                    strokeWidth = RingDefaults.MediumStroke,
                    label = "Strain",
                    caption = "",
                )
            }
        }
        VitalCard(
            modifier = Modifier.weight(1f),
            accent = SleepAccent,
            shape = com.example.vitalcoreai.theme.VitalShapes.RingCard,
            contentPadding = cardPadding,
            onClick = onSleepClick
        ) {
            HeroRingCell(confidence = sleepConfidence) {
                PercentRing(
                    value = sleep,
                    label = "Sleep",
                    size = RingDefaults.MediumSize,
                    strokeWidth = RingDefaults.MediumStroke,
                    accent = SleepAccent,
                    caption = "",
                )
            }
        }
    }
}

@Composable
private fun HeroRingCell(confidence: Confidence, ring: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ring()
        if (confidence != Confidence.HIGH) {
            Spacer(Modifier.height(Spacing.sm))
            ConfidenceBadge(confidence = confidence, compact = true)
        }
    }
}
