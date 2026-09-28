package com.example.vitalcoreai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.analytics.Confidence
import com.example.vitalcoreai.analytics.ScoreFactor
import com.example.vitalcoreai.analytics.TrendDirection
import com.example.vitalcoreai.theme.Alphas
import com.example.vitalcoreai.theme.ConfidenceHigh
import com.example.vitalcoreai.theme.ConfidenceLow
import com.example.vitalcoreai.theme.ConfidenceMedium
import com.example.vitalcoreai.theme.Motion
import com.example.vitalcoreai.theme.NegativeDelta
import com.example.vitalcoreai.theme.NeutralDelta
import com.example.vitalcoreai.theme.OnBackground
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.PositiveDelta
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.SleepAccent
import com.example.vitalcoreai.theme.SleepAwake
import com.example.vitalcoreai.theme.SleepDeep
import com.example.vitalcoreai.theme.SleepLight
import com.example.vitalcoreai.theme.SleepRem
import com.example.vitalcoreai.theme.Sizes
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.SurfaceL2
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.VitalShapes
import com.example.vitalcoreai.theme.recoveryTierColor
import com.example.vitalcoreai.ui.viewmodel.Momentum
import kotlin.math.abs
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// INDICATORS — trend, delta, confidence, breakdown, proportion bars
// ═══════════════════════════════════════════════════════════════════════════

/** U+2212 MINUS SIGN. A hyphen is narrower than a plus and makes deltas jitter. */
private const val MINUS = "−"

/**
 * Rotating trend arrow.
 *
 * @param higherIsBetter false for Stress and Resting HR — flips the colour mapping
 *        so a falling value is green.
 */
@Composable
fun TrendArrow(
    direction: TrendDirection,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.iconSm,
    higherIsBetter: Boolean = true,
) {
    val icon = when (direction) {
        TrendDirection.UP      -> Icons.AutoMirrored.Filled.TrendingUp
        TrendDirection.DOWN    -> Icons.AutoMirrored.Filled.TrendingDown
        TrendDirection.NEUTRAL -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    Icon(
        imageVector = icon,
        contentDescription = trendDescription(direction),
        tint = trendColor(direction, higherIsBetter),
        modifier = modifier.size(size)
    )
}

private fun trendColor(direction: TrendDirection, higherIsBetter: Boolean): Color = when (direction) {
    TrendDirection.NEUTRAL -> NeutralDelta
    TrendDirection.UP      -> if (higherIsBetter) PositiveDelta else NegativeDelta
    TrendDirection.DOWN    -> if (higherIsBetter) NegativeDelta else PositiveDelta
}

private fun trendDescription(direction: TrendDirection): String = when (direction) {
    TrendDirection.UP      -> "Trending up"
    TrendDirection.DOWN    -> "Trending down"
    TrendDirection.NEUTRAL -> "Stable"
}

/** "+3" / "−2" in a quiet tinted chip. A null delta renders nothing. */
@Composable
fun DeltaChip(
    delta: Float?,
    modifier: Modifier = Modifier,
    unit: String = "",
    decimals: Int = 0,
    higherIsBetter: Boolean = true,
) {
    if (delta == null || !delta.isFinite()) return
    val direction = when {
        delta > 0.05f  -> TrendDirection.UP
        delta < -0.05f -> TrendDirection.DOWN
        else           -> TrendDirection.NEUTRAL
    }
    val magnitude = abs(delta)
    val body = if (decimals >= 1) {
        val scaled = (magnitude * 10f).roundToInt()
        "${scaled / 10}.${scaled % 10}"
    } else {
        magnitude.roundToInt().toString()
    }
    val sign = when (direction) {
        TrendDirection.UP      -> "+"
        TrendDirection.DOWN    -> MINUS
        TrendDirection.NEUTRAL -> ""
    }
    DeltaChip(
        text = "$sign$body${if (unit.isNotEmpty()) " $unit" else ""}",
        direction = direction,
        modifier = modifier,
        higherIsBetter = higherIsBetter
    )
}

/** Pre-formatted variant — the ViewModel already produced the string via `Fmt.delta`. */
@Composable
fun DeltaChip(
    text: String,
    direction: TrendDirection,
    modifier: Modifier = Modifier,
    higherIsBetter: Boolean = true,
) {
    if (text.isBlank()) return
    val color = trendColor(direction, higherIsBetter)
    Row(
        modifier = modifier
            .clip(VitalShapes.Pill)
            .background(color.copy(alpha = Alphas.tintedFill))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/**
 * Confidence badge. LOW is grey, never red — low confidence means "we do not know
 * enough yet", which is not an error state and must not read as one.
 */
@Composable
fun ConfidenceBadge(
    confidence: Confidence,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val (word, color) = when (confidence) {
        Confidence.HIGH   -> "High" to ConfidenceHigh
        Confidence.MEDIUM -> "Medium" to ConfidenceMedium
        Confidence.LOW    -> "Low" to ConfidenceLow
    }
    AccentPill(
        text = if (compact) word else "$word confidence",
        color = color,
        modifier = modifier
    )
}

/** Compact status pill with a soft fill and a restrained outline. */
@Composable
fun AccentPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(VitalShapes.Pill)
            .background(color.copy(alpha = Alphas.tintedFill))
            .border(Sizes.hairline, color.copy(alpha = 0.14f), VitalShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(Sizes.iconSm)
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * One [ScoreFactor]: name · sub-score in its tier colour · weight, then an animated
 * 4dp track, then the factor's own description and delta.
 */
@Composable
fun BreakdownRow(
    factor: ScoreFactor,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    scoreColor: Color = recoveryTierColor(factor.score),
) {
    val bar = remember(factor.name) { Animatable(0f) }
    LaunchedEffect(factor.score) {
        bar.animateTo(
            (factor.score / 100f).coerceIn(0f, 1f),
            tween(Motion.barMs, easing = Motion.barEasing)
        )
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = factor.name,
                style = MaterialTheme.typography.titleSmall,
                color = OnBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = factor.score.roundToInt().toString(),
                style = VitalCoreType.metricSmall,
                color = scoreColor
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "(${factor.contribution.roundToInt()}%)",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(VitalShapes.Bar)
                .background(SurfaceL2)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bar.value)
                    .fillMaxHeight()
                    .clip(VitalShapes.Bar)
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.60f), accent))
                    )
            )
        }
        if (factor.description.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = factor.description,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
        factor.delta?.let { d ->
            Spacer(Modifier.height(Spacing.xxs))
            Text(text = d, style = VitalCoreType.monoTiny, color = OnSurfaceDim)
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Proportion bars
// ───────────────────────────────────────────────────────────────────────────

data class ZoneSegment(val label: String, val fraction: Float, val color: Color)

/**
 * Stacked horizontal proportion bar. Fractions are normalised internally, and a
 * zero-sum list renders a plain track rather than dividing by zero.
 */
@Composable
fun ZoneBar(
    segments: List<ZoneSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    showLegend: Boolean = false,
) {
    val validSegments = segments.filter { it.fraction.isFinite() && it.fraction > 0f }
    val total = validSegments.sumOf { it.fraction.toDouble() }.toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(VitalShapes.Bar)
                .background(SurfaceL2)
                .semantics {
                    contentDescription = if (total <= 0.0001f) "No distribution data"
                    else validSegments.joinToString { "${it.label}, ${((it.fraction / total) * 100f).roundToInt()} percent" }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (total > 0.0001f) {
                validSegments.forEach { seg ->
                    Box(
                        modifier = Modifier
                            .weight(seg.fraction / total)
                            .fillMaxHeight()
                            .background(seg.color)
                    )
                }
            }
        }
        if (showLegend && total > 0.0001f) {
            Spacer(Modifier.height(Spacing.md))
            validSegments.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    pair.forEach { seg ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(seg.color)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = seg.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${((seg.fraction / total) * 100f).roundToInt()}%",
                                style = VitalCoreType.monoTiny,
                                color = OnSurfaceMuted
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Sleep stage proportion bar.
 *
 * Renders NOTHING when every stage is null or zero. A stage bar of all-zeros is
 * exactly the fabricated-data failure this design system exists to prevent —
 * the Watch Active 2 frequently reports a sleep session with no stage detail.
 */
@Composable
fun SleepStageBar(
    deepMin: Int?,
    remMin: Int?,
    lightMin: Int?,
    awakeMin: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    showLegend: Boolean = true,
) {
    val deep = deepMin ?: 0
    val rem = remMin ?: 0
    val light = lightMin ?: 0
    val awake = awakeMin ?: 0
    if (deep + rem + light + awake <= 0) return

    val segments = listOf(
        ZoneSegment("Deep ${deep}m", deep.toFloat(), SleepDeep),
        ZoneSegment("REM ${rem}m", rem.toFloat(), SleepRem),
        ZoneSegment("Light ${light}m", light.toFloat(), SleepLight),
        ZoneSegment("Awake ${awake}m", awake.toFloat(), SleepAwake),
    ).filter { it.fraction > 0f }

    ZoneBar(segments = segments, modifier = modifier, height = height, showLegend = showLegend)
}

// ───────────────────────────────────────────────────────────────────────────
// Momentum
// ───────────────────────────────────────────────────────────────────────────

/** Three momentum chips. Renders nothing when every domain is UNKNOWN. */
@Composable
fun MomentumRow(
    recovery: Momentum,
    sleep: Momentum,
    strain: Momentum,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Triple("Recovery", recovery, RecoveryAccent),
        Triple("Sleep", sleep, SleepAccent),
        Triple("Strain", strain, StrainAccent),
    ).filter { it.second != Momentum.UNKNOWN }

    if (items.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items.forEach { (label, momentum, accent) ->
            MomentumChip(
                label = label,
                momentum = momentum,
                accent = accent,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MomentumChip(
    label: String,
    momentum: Momentum,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val direction = when (momentum) {
        Momentum.IMPROVING -> TrendDirection.UP
        Momentum.DECLINING -> TrendDirection.DOWN
        else               -> TrendDirection.NEUTRAL
    }
    val word = when (momentum) {
        Momentum.IMPROVING -> "Improving"
        Momentum.DECLINING -> "Declining"
        Momentum.STABLE    -> "Stable"
        Momentum.UNKNOWN   -> "—"
    }

    VitalCard(
        modifier = modifier,
        accent = null,
        shape = VitalShapes.Tile,
        contentPadding = PaddingValues(
            horizontal = Spacing.md,
            vertical = 10.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TrendArrow(direction = direction, size = Sizes.iconSm)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = label.uppercase(),
                    style = VitalCoreType.eyebrow,
                    color = OnSurfaceMuted,
                    maxLines = 1
                )
                Text(
                    text = word,
                    style = MaterialTheme.typography.labelMedium,
                    color = trendColor(direction, higherIsBetter = true).takeIf {
                        direction != TrendDirection.NEUTRAL
                    } ?: accent,
                    maxLines = 1
                )
            }
        }
    }
}
