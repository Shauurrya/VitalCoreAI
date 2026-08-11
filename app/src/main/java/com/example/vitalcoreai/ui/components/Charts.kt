package com.example.vitalcoreai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitalcoreai.theme.Alphas
import com.example.vitalcoreai.theme.ChartGuideline
import com.example.vitalcoreai.theme.HairlineColor
import com.example.vitalcoreai.theme.Motion
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.SurfaceL2
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.VitalShapes
import com.example.vitalcoreai.theme.recoveryTierColor
import com.example.vitalcoreai.theme.strainTierColor
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.continuous
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shader.toShaderProvider
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// CHARTS
//
// Every chart lives inside a VitalSectionCard whose title states the window and
// the unit ("Resting HR · 30 days · bpm"). No chart draws its own background.
// ═══════════════════════════════════════════════════════════════════════════

private val LineChartHeight = 148.dp
private val StrainChartHeight = 160.dp

/**
 * Line + gradient area, one series, domain-accented, WITH a labelled Y axis and
 * three guidelines — the fix for charts that previously had no axes, gridlines,
 * labels or units anywhere.
 *
 * Empty or single-point input renders a centred [emptyMessage] at the chart's own
 * height. It must never draw a flat line through one point.
 */
@Composable
fun TrendLineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    height: Dp = LineChartHeight,
    yRange: ClosedFloatingPointRange<Float>? = null,
    xLabels: List<String> = emptyList(),
    showYAxis: Boolean = true,
    valueFormat: (Float) -> String = { it.roundToInt().toString() },
    emptyMessage: String = "Not enough data yet",
) {
    if (values.size < 2) {
        ChartPlaceholder(message = emptyMessage, height = height, modifier = modifier)
        return
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction {
            lineSeries { series(values.map { it.toDouble() }) }
        }
    }

    val rangeProvider = remember(yRange) {
        if (yRange == null) {
            CartesianLayerRangeProvider.auto()
        } else {
            CartesianLayerRangeProvider.fixed(
                minY = yRange.start.toDouble(),
                maxY = yRange.endInclusive.toDouble()
            )
        }
    }

    val startAxis = if (!showYAxis) null else VerticalAxis.rememberStart(
        line = null,
        tick = null,
        guideline = rememberAxisGuidelineComponent(
            fill = fill(ChartGuideline),
            thickness = 1.dp
        ),
        label = rememberAxisLabelComponent(color = OnSurfaceMuted, textSize = 10.sp),
        itemPlacer = remember { VerticalAxis.ItemPlacer.count({ 3 }) },
        valueFormatter = CartesianValueFormatter { _, v, _ -> valueFormat(v.toFloat()) }
    )

    val bottomAxis = if (xLabels.isEmpty()) null else HorizontalAxis.rememberBottom(
        line = null,
        tick = null,
        guideline = null,
        label = rememberAxisLabelComponent(color = OnSurfaceMuted, textSize = 10.sp),
        itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
        valueFormatter = CartesianValueFormatter { _, v, _ ->
            xLabels.getOrElse(v.roundToInt()) { "" }
        }
    )

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(fill(accent)),
                        stroke = LineCartesianLayer.LineStroke.continuous(thickness = 2.5.dp),
                        areaFill = LineCartesianLayer.AreaFill.single(
                            fill(
                                Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = Alphas.areaFillTop),
                                        accent.copy(alpha = 0f)
                                    )
                                ).toShaderProvider()
                            )
                        )
                    )
                ),
                rangeProvider = rangeProvider
            ),
            startAxis = startAxis,
            bottomAxis = bottomAxis
        ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(height)
    )
}

@Composable
private fun ChartPlaceholder(message: String, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
// StrainBarChart — hand-rolled, deliberately not Vico
//
// Per-bar colouring on the 0–21 heat ramp needs a colour per *entry*, and Vico's
// ColumnProvider.series fixes one colour per *series*. Gap days also need to be a
// hollow stub rather than a zero-height bar. Both are trivial in Compose.
// ───────────────────────────────────────────────────────────────────────────

private val StrainZoneRules = listOf(6f, 10f, 14f, 18f)

/**
 * Daily strain history, oldest → newest.
 *
 * A null entry renders a 3dp hollow stub — visually distinct from a genuine 0.0
 * rest day, which is a real and meaningful value.
 */
@Composable
fun StrainBarChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    height: Dp = StrainChartHeight,
    xLabels: List<String> = emptyList(),
    highlightIndex: Int? = null,
    showZoneRules: Boolean = true,
    onBarClick: ((Int) -> Unit)? = null,
) {
    if (values.isEmpty()) {
        ChartPlaceholder("No strain history yet", height, modifier)
        return
    }
    val maxStrain = 21f

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(height)) {
            if (showZoneRules) {
                StrainZoneRules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(bottom = height * (rule / maxStrain)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .background(HairlineColor)
                        )
                        Text(
                            text = rule.roundToInt().toString(),
                            style = VitalCoreType.monoTiny,
                            color = OnSurfaceMuted,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                values.forEachIndexed { index, strain ->
                    StrainBar(
                        strain = strain,
                        index = index,
                        maxStrain = maxStrain,
                        chartHeight = height,
                        isHighlighted = index == highlightIndex,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 6.dp)
                            .fillMaxHeight()
                            .then(
                                if (onBarClick != null) {
                                    Modifier.clickable { onBarClick(index) }
                                } else Modifier
                            )
                    )
                }
            }
        }

        if (xLabels.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                values.indices.forEach { index ->
                    Text(
                        text = xLabels.getOrElse(index) { "" },
                        style = VitalCoreType.monoTiny,
                        color = OnSurfaceMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StrainBar(
    strain: Float?,
    index: Int,
    maxStrain: Float,
    chartHeight: Dp,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetFraction = ((strain ?: 0f) / maxStrain).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(
            durationMillis = Motion.barMs,
            delayMillis = 18 * index,
            easing = Motion.barEasing
        ),
        label = "strain_bar_$index"
    )

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        if (strain == null) {
            // Hollow stub — "no data", never confusable with a 0.0 rest day.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(VitalShapes.ChartBar)
                    .background(SurfaceL2)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineColor)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((chartHeight * fraction).coerceAtLeast(2.dp))
                    .clip(VitalShapes.ChartBar)
                    .background(
                        strainTierColor(strain).copy(alpha = if (isHighlighted) 1f else 0.72f)
                    )
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// WeekdayBarRow
// ───────────────────────────────────────────────────────────────────────────

/**
 * Exactly 7 values, Monday → Sunday, for the Weekly Report. Pad with nulls at the
 * call site; a null renders a stub, not a zero.
 */
@Composable
fun WeekdayBarRow(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    accent: Color = RecoveryAccent,
    useTierColors: Boolean = true,
    height: Dp = 96.dp,
    labels: List<String> = listOf("M", "T", "W", "T", "F", "S", "S"),
) {
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom
    ) {
        values.take(7).forEachIndexed { index, value ->
            val barColor = when {
                value == null -> SurfaceL2
                !useTierColors -> accent
                max <= 21.5f -> strainTierColor(value)
                else -> recoveryTierColor(value)
            }
            val fraction by animateFloatAsState(
                targetValue = ((value ?: 0f) / max).coerceIn(0f, 1f),
                animationSpec = tween(
                    durationMillis = Motion.barMs,
                    delayMillis = 18 * index,
                    easing = Motion.barEasing
                ),
                label = "weekday_bar_$index"
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            if (value == null) 3.dp
                            else ((height - 18.dp) * fraction).coerceAtLeast(2.dp)
                        )
                        .clip(VitalShapes.ChartBar)
                        .background(barColor)
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = labels.getOrElse(index) { "" },
                    style = VitalCoreType.monoTiny,
                    color = OnSurfaceMuted,
                    maxLines = 1
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// MiniSparkline
// ───────────────────────────────────────────────────────────────────────────

/** Canvas polyline with a dot on the final point. Fewer than 2 points → nothing. */
@Composable
fun MiniSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    height: Dp = 32.dp,
    strokeWidth: Dp = 2.dp,
) {
    if (values.size < 2) return

    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0.0001f } ?: 1f

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val stepX = size.width / (values.size - 1).toFloat()
        val points = values.mapIndexed { i, v ->
            Offset(
                x = i * stepX,
                y = size.height - ((v - min) / span) * size.height
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = accent,
                start = points[i],
                end = points[i + 1],
                strokeWidth = strokeWidth.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = accent, radius = 3.dp.toPx(), center = points.last())
    }
}
