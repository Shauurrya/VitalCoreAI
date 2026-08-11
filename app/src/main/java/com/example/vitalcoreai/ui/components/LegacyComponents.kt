package com.example.vitalcoreai.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.analytics.ScoreFactor
import com.example.vitalcoreai.analytics.TrendDirection
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.SleepAccent
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.StressAccent
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.recoveryTierColor
import com.example.vitalcoreai.ui.viewmodel.Momentum

// ═══════════════════════════════════════════════════════════════════════════
// MIGRATION SHIM — DELETE THIS FILE IN THE FINAL CLEANUP COMMIT.
//
// Pre-redesign component names, kept alive only so screens that have not yet
// been rewritten still compile. Each forwards to its replacement. No agent may
// introduce a NEW call to anything in this file; when compileDebugKotlin reports
// zero DEPRECATION warnings, this file has no callers left and must be removed.
// ═══════════════════════════════════════════════════════════════════════════

@Deprecated(
    "Use PercentRing (0–100) or StrainRing (0–21). The centerContent slot is not " +
        "carried forward — the new rings own their centre so numerals stay tabular.",
    ReplaceWith("PercentRing(value = score, label = label, size = size, strokeWidth = strokeWidth)")
)
@Composable
fun AnimatedScoreRing(
    score: Float,
    label: String,
    size: Dp = 168.dp,
    strokeWidth: Dp = 14.dp,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") centerContent: @Composable (BoxScope.() -> Unit)? = null,
) {
    PercentRing(
        value = score,
        label = label,
        modifier = modifier,
        size = size,
        strokeWidth = strokeWidth,
        caption = ""
    )
}

@Deprecated(
    "Use PercentRing",
    ReplaceWith("PercentRing(value = score, label = label, size = size, strokeWidth = strokeWidth)")
)
@Composable
fun HeroRing(
    score: Float,
    label: String,
    size: Dp = 200.dp,
    strokeWidth: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    PercentRing(
        value = score,
        label = label,
        modifier = modifier,
        size = size,
        strokeWidth = strokeWidth
    )
}

@Deprecated("Use CompactRing", ReplaceWith("CompactRing(value = score, size = size, strokeWidth = strokeWidth)"))
@Composable
fun CompactScoreRing(
    score: Float,
    size: Dp = 56.dp,
    strokeWidth: Dp = 5.dp,
    modifier: Modifier = Modifier,
) {
    CompactRing(value = score, modifier = modifier, size = size, strokeWidth = strokeWidth)
}

@Deprecated(
    "Use BreakdownRow(factor) — the ScoreFactor overload also renders rawValue and delta",
    ReplaceWith("BreakdownRow(factor)")
)
@Composable
fun BreakdownRow(
    name: String,
    weight: Float,
    subScore: Float,
    description: String,
    modifier: Modifier = Modifier,
) {
    BreakdownRow(
        factor = ScoreFactor(
            name = name,
            contribution = weight,
            rawValue = "",
            score = subScore,
            description = description
        ),
        modifier = modifier
    )
}

@Deprecated("Use TrendLineChart — it draws a labelled axis and guidelines", ReplaceWith("TrendLineChart(values = values, accent = color)"))
@Composable
fun VicoPrimaryChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = RecoveryAccent,
    @Suppress("UNUSED_PARAMETER") showLabels: Boolean = true,
) {
    TrendLineChart(values = values, modifier = modifier, accent = color, height = 160.dp)
}

@Deprecated("Use MetricTile / VitalListRow", ReplaceWith("MetricTile(data)"))
@Composable
fun ScoreCard(
    title: String,
    score: Float?,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    trendDirection: TrendDirection = TrendDirection.NEUTRAL,
    onClick: (() -> Unit)? = null,
) {
    VitalCard(
        modifier = modifier.fillMaxWidth(),
        accent = null,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 14.dp),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (score != null) {
                CompactRing(value = score, size = 48.dp, strokeWidth = 5.dp)
                Spacer(Modifier.width(Spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title.uppercase(), style = VitalCoreType.eyebrow, color = OnSurfaceDim)
                if (score != null) {
                    Text(
                        text = score.toInt().toString(),
                        style = VitalCoreType.metricMedium,
                        color = recoveryTierColor(score)
                    )
                } else {
                    NoDataValue()
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                        maxLines = 1
                    )
                }
            }
            TrendArrow(direction = trendDirection)
        }
    }
}

@Deprecated(
    "Use MomentumRow, which takes the Momentum enum instead of raw strings",
    ReplaceWith("MomentumRow(Momentum.parse(recoveryMomentum), Momentum.parse(sleepMomentum), Momentum.parse(trainingMomentum))")
)
@Composable
fun MomentumStrip(
    recoveryMomentum: String?,
    sleepMomentum: String?,
    trainingMomentum: String?,
    modifier: Modifier = Modifier,
) {
    MomentumRow(
        recovery = Momentum.parse(recoveryMomentum),
        sleep = Momentum.parse(sleepMomentum),
        strain = Momentum.parse(trainingMomentum),
        modifier = modifier
    )
}

/**
 * Superseded by the bottom bar plus the More hub, which together make a floating
 * shortcut strip redundant. Route strings are literals here on purpose: this shim
 * must not depend on the `Routes` object while that file is being rewritten.
 */
@Deprecated("Deleted by design — the bottom bar and More hub replace it")
@Composable
fun QuickActionsRow(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = listOf(
        Triple("Check-In", Icons.Outlined.EditNote, RecoveryAccent) to "check_in",
        Triple("Journal", Icons.Outlined.BookmarkBorder, SleepAccent) to "journal",
        Triple("Muscles", Icons.Outlined.FitnessCenter, StrainAccent) to "muscle_recovery",
        Triple("Report", Icons.Outlined.Assessment, StressAccent) to "weekly_report",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)
    ) {
        actions.forEach { (action, route) ->
            val (label, icon, color) = action
            VitalCard(
                modifier = Modifier.weight(1f),
                accent = null,
                shape = com.example.vitalcoreai.theme.VitalShapes.Tile,
                contentPadding = PaddingValues(vertical = 14.dp),
                onClick = { onNavigate(route) }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = com.example.vitalcoreai.theme.OnBackground,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Deprecated("Use VitalCard with a HeroRingRow cell", ReplaceWith("HeroRingRow(...)"))
@Composable
fun HeroScoreCard(
    title: String,
    score: Float?,
    label: String,
    modifier: Modifier = Modifier,
) {
    VitalCard(
        modifier = modifier,
        accent = recoveryTierColor(score),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 18.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PercentRing(
                value = score,
                label = if (label.isNotBlank()) label else title,
                size = 120.dp,
                strokeWidth = 11.dp
            )
        }
    }
}

@Deprecated("Use InsightCard or a VitalCard with topStrip = true", ReplaceWith("VitalCard(accent = accentColor, topStrip = true) { }"))
@Composable
fun RecommendationCard(
    title: String,
    body: String,
    accentColor: Color = RecoveryAccent,
    modifier: Modifier = Modifier,
) {
    VitalCard(
        modifier = modifier.fillMaxWidth(),
        accent = accentColor,
        topStrip = true,
        contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = 18.dp, bottom = Spacing.lg)
    ) {
        Text(text = "TODAY'S RECOMMENDATION", style = VitalCoreType.eyebrow, color = accentColor)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = com.example.vitalcoreai.theme.OnBackground
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    }
}
