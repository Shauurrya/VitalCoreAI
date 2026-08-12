package com.example.vitalcoreai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.theme.*
import com.example.vitalcoreai.ui.components.AccentPill
import com.example.vitalcoreai.ui.components.VitalCard
import com.example.vitalcoreai.ui.viewmodel.AnomalyState
import com.example.vitalcoreai.ui.viewmodel.ForecastState
import com.example.vitalcoreai.ui.viewmodel.RecommendationState
import com.example.vitalcoreai.ui.viewmodel.SleepConsistencyState
import com.example.vitalcoreai.ui.viewmodel.TrendState

// ═════════════════════════════════════════════════════════════════════════════
// T-13 — the cards that render the V1.1 engines' persisted output.
//
// Three rules every card here follows, because the engines behind them are
// explicitly probabilistic:
//
//  1. A missing value renders a stated reason, never a zero and never a blank.
//     "Forecast available after 5 days of data" is information; "0" is a lie.
//  2. No status is carried by colour alone. Every severity, direction and
//     confidence also appears as a word, so the screen survives greyscale and
//     reads correctly to a screen reader (T-19).
//  3. Body text is at least 16sp and every interactive target at least 48dp.
// ═════════════════════════════════════════════════════════════════════════════

/** The minimum body size the accessibility pass fixes for prose in these cards. */
private val BodyStyle
    @Composable get() = MaterialTheme.typography.bodyLarge

/**
 * Tomorrow's readiness, as a range.
 *
 * [ReadinessForecastEngine][com.example.vitalcoreai.analytics.ReadinessForecastEngine] never
 * emits a point estimate, and neither does this card: the width of the band *is* the message,
 * because it is derived from the user's own day-to-day volatility. Rendering a midpoint would
 * quietly discard the only honest thing the forecast has to say.
 */
@Composable
fun ForecastCard(
    forecast: ForecastState?,
    modifier: Modifier = Modifier,
    placeholder: String = "Forecast available after 5 days of data"
) {
    VitalCard(modifier = modifier.fillMaxWidth(), accent = ReadinessAccent, topStrip = true) {
        CardHeading("Tomorrow's readiness", ReadinessAccent)

        if (forecast == null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(placeholder, style = BodyStyle, color = OnSurfaceDim)
            return@VitalCard
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                forecast.range,
                style = VitalCoreType.metricLarge,
                color = OnBackground,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Projected readiness between ${forecast.low} and ${forecast.high} out of 100"
                }
            )
            Spacer(Modifier.width(Spacing.sm))
            Text("/ 100", style = VitalCoreType.metricUnit, color = OnSurfaceDim)
            Spacer(Modifier.weight(1f))
            forecast.confidence?.let {
                AccentPill(text = "${it.lowercase().replaceFirstChar(Char::uppercase)} confidence", color = ReadinessAccent)
            }
        }

        Spacer(Modifier.height(Spacing.md))
        ForecastRangeBar(low = forecast.low, high = forecast.high)

        Spacer(Modifier.height(Spacing.md))
        Text(
            "A projection from your recent patterns, not a prediction.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted
        )

        if (forecast.drivers.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text("WHAT MOVES IT", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
            Spacer(Modifier.height(Spacing.xs))
            // Signed and named. A driver without its sign is just a label, and the sign is
            // the part that tells the user which way to lean.
            forecast.drivers.take(5).forEach { d ->
                val sign = if (d.points >= 0) "+" else "−"
                val magnitude = kotlin.math.abs(d.points)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxs)
                        .semantics {
                            contentDescription =
                                "${d.name}, ${if (d.points >= 0) "adds" else "removes"} " +
                                    "${"%.1f".format(magnitude)} points. ${d.description}"
                        },
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "$sign${"%.1f".format(magnitude)}",
                        style = VitalCoreType.monoTiny,
                        color = if (d.points >= 0) PositiveDelta else NegativeDelta,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(d.description, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
                }
            }
        }

        if (forecast.risks.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text("WATCH FOR", style = VitalCoreType.eyebrow, color = OnSurfaceDim)
            Spacer(Modifier.height(Spacing.xs))
            forecast.risks.take(3).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
        }
    }
}

/**
 * The 0–100 track with the projected band drawn on it.
 *
 * Decorative: the numbers are already announced by the range text above, so this is hidden
 * from the accessibility tree rather than read out as an unlabelled graphic.
 */
@Composable
private fun ForecastRangeBar(low: Int, high: Int) {
    val safeLow = low.coerceIn(0, 100)
    val safeHigh = high.coerceIn(safeLow, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(VitalShapes.Bar)
            .background(SurfaceL3)
            .clearAndSetSemantics { }
    ) {
        Layout(content = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(VitalShapes.Bar)
                    .background(ReadinessAccent)
            )
        }) { measurables, constraints ->
            val startPx = (constraints.maxWidth * safeLow / 100f).toInt()
            val endPx = (constraints.maxWidth * safeHigh / 100f).toInt()
            // A band of identical low and high would vanish; keep it visible at 4dp.
            val bandWidth = (endPx - startPx)
                .coerceAtLeast(4.dp.roundToPx())
                .coerceAtMost(constraints.maxWidth)
            val placeable = measurables.first().measure(
                constraints.copy(minWidth = bandWidth, maxWidth = bandWidth)
            )
            layout(constraints.maxWidth, placeable.height) {
                placeable.placeRelative(
                    startPx.coerceIn(0, constraints.maxWidth - bandWidth),
                    0
                )
            }
        }
    }
}

/**
 * A 7 / 14 / 30-day direction with the contributors the engine attributed it to.
 *
 * The direction is a word first and an arrow second — [TrendState.directionLabel] exists
 * because an arrow glyph is unreadable to a screen reader and invisible in greyscale.
 */
@Composable
fun TrendCard(trend: TrendState, modifier: Modifier = Modifier) {
    val accent = trendColor(trend.direction)
    VitalCard(modifier = modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${trend.window} trend",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = OnBackground,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${trend.arrow} ${trend.directionLabel}",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                modifier = Modifier.semantics {
                    contentDescription = "${trend.window} trend: ${trend.directionLabel}"
                }
            )
        }

        if (trend.contributors.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            trend.contributors.take(3).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
            }
        }
    }
}

/**
 * One flagged anomaly.
 *
 * Severity appears as text next to the pill, so the badge colour is reinforcement rather
 * than the only signal. The disclaimer is not optional — see AnomalyDetectionEngine, whose
 * own test asserts it is present in every body it produces.
 */
@Composable
fun AnomalyCard(anomaly: AnomalyState, modifier: Modifier = Modifier) {
    val accent = severityColor(anomaly.severity)
    VitalCard(modifier = modifier.fillMaxWidth(), accent = accent, topStrip = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,   // the severity text beside it carries the meaning
                tint = accent,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                anomaly.metric,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = OnBackground,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.weight(1f))
            AccentPill(text = anomaly.severityLabel, color = accent)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(anomaly.title, style = BodyStyle, color = OnBackground)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "A pattern worth monitoring, not a diagnosis.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted
        )
    }
}

/** Today's training recommendation: type, intensity, volume adjustment and the detail line. */
@Composable
fun RecommendationCard(
    recommendation: RecommendationState?,
    modifier: Modifier = Modifier,
    placeholder: String = "A recommendation appears once today's scores are computed"
) {
    VitalCard(modifier = modifier.fillMaxWidth(), accent = StrainAccent, topStrip = true) {
        CardHeading("Today", StrainAccent)

        if (recommendation == null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(placeholder, style = BodyStyle, color = OnSurfaceDim)
            return@VitalCard
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = StrainAccent,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                recommendation.type,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = OnBackground
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AccentPill(text = "${recommendation.intensity} intensity", color = StrainAccent)
            recommendation.volumePct?.takeIf { it != 0 }?.let { pct ->
                // Signed, because "20% volume" reads as a target and "−20%" reads as an
                // adjustment — and it is an adjustment to the user's own normal.
                val label = if (pct > 0) "+$pct% volume" else "$pct% volume"
                AccentPill(text = label, color = if (pct >= 0) PositiveDelta else NegativeDelta)
            }
        }

        recommendation.detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(Spacing.md))
            Text(it, style = BodyStyle, color = OnSurfaceDim)
        }
    }
}

/** Sleep regularity — distinct from duration and from sleep quality. */
@Composable
fun SleepConsistencyCard(state: SleepConsistencyState?, modifier: Modifier = Modifier) {
    if (state?.score == null && state?.label == null) return
    VitalCard(modifier = modifier.fillMaxWidth(), accent = SleepAccent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                tint = SleepAccent,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "Sleep consistency",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = OnBackground,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.weight(1f))
            state.score?.let {
                Text(
                    "${it.toInt()}",
                    style = VitalCoreType.metricSmall,
                    color = SleepAccent,
                    modifier = Modifier.semantics {
                        contentDescription = "Sleep consistency ${it.toInt()} out of 100"
                    }
                )
            }
        }
        state.label?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(it, style = BodyStyle, color = OnSurfaceDim)
        }
        val spread = listOfNotNull(
            state.bedtimeSdMinutes?.let { "bedtime ±${VitalTime.formatDurationMinutes(it)}" },
            state.wakeSdMinutes?.let { "wake ±${VitalTime.formatDurationMinutes(it)}" }
        )
        if (spread.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                spread.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted
            )
        }
    }
}

@Composable
private fun CardHeading(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Insights,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(Sizes.iconSm)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text.uppercase(),
            style = VitalCoreType.eyebrow,
            color = accent,
            modifier = Modifier.semantics { heading() }
        )
    }
}

private fun trendColor(direction: String): Color = when (direction) {
    "IMPROVING" -> PositiveDelta
    "DECLINING" -> NegativeDelta
    "VARIABLE" -> StressAccent
    else -> NeutralDelta
}

private fun severityColor(severity: String): Color = when (severity) {
    "STRONGLY_UNUSUAL" -> AlertRed
    "UNUSUAL" -> StressAccent
    else -> RecoveryAccent
}
