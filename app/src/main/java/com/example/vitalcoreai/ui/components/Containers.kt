package com.example.vitalcoreai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.coach.CoachEngine
import com.example.vitalcoreai.theme.ActivityAccent
import com.example.vitalcoreai.theme.AlertRed
import com.example.vitalcoreai.theme.Alphas
import com.example.vitalcoreai.theme.Background
import com.example.vitalcoreai.theme.DividerColor
import com.example.vitalcoreai.theme.Domain
import com.example.vitalcoreai.theme.HairlineColor
import com.example.vitalcoreai.theme.OnBackground
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.SleepAccent
import com.example.vitalcoreai.theme.Sizes
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.StrainAccent
import com.example.vitalcoreai.theme.SurfaceL1
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.VitalShapes
import com.example.vitalcoreai.theme.domainAccent
import com.example.vitalcoreai.analytics.TrendDirection

// ═══════════════════════════════════════════════════════════════════════════
// CARD + LAYOUT SYSTEM
//
// Depth comes from a subtle surface step and a quiet hairline. Colour belongs
// to the health signals; neutral containers let those signals lead the screen.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The only card in the app. `Card {}` from Material 3 is banned in screen code.
 *
 * @param accent   null → neutral HairlineColor border.
 *                 non-null → vertical-gradient border accent@30% → HairlineColor.
 * @param topStrip true (requires accent != null) → 3dp horizontal-gradient strip
 *                 accent → accent@30% flush to the top inside the clip.
 */
@Composable
fun VitalCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    topStrip: Boolean = false,
    shape: Shape = VitalShapes.Card,
    surface: Color = SurfaceL1,
    contentPadding: PaddingValues = PaddingValues(Spacing.xl),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neutralBorder = HairlineColor.copy(alpha = 0.65f)
    val borderBrush = if (accent != null) {
        Brush.verticalGradient(listOf(accent.copy(alpha = Alphas.tintedBorder), neutralBorder))
    } else {
        Brush.verticalGradient(listOf(HairlineColor, neutralBorder))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(OnBackground.copy(alpha = 0.018f).compositeOver(surface), surface)
                )
            )
            .border(width = Sizes.hairline, brush = borderBrush, shape = shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = accent ?: OnBackground),
                    role = Role.Button,
                    onClick = onClick
                ) else Modifier
            )
    ) {
        if (topStrip && accent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Sizes.accentStrip)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent, accent.copy(alpha = Alphas.tintedBorder))
                        )
                    )
            )
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * VitalCard with a built-in header row: eyebrow title left, optional trailing slot
 * right, 12dp gap, then content. Use for "Score Breakdown", "14-Day Trend", etc.
 * The title must state the window and unit — "Resting HR · 30 days · bpm" — because
 * the chart inside will not.
 */
@Composable
fun VitalSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.xl),
    content: @Composable ColumnScope.() -> Unit,
) {
    VitalCard(modifier = modifier, accent = null, contentPadding = contentPadding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = VitalCoreType.sectionTitle,
                    color = OnBackground,
                    modifier = Modifier.semantics { heading() }
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                }
            }
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(Spacing.lg))
        content()
    }
}

/**
 * Grouped-list container: a VitalCard with zero content padding whose children are
 * VitalListRows separated by inset dividers. The More hub and Settings are built
 * entirely from these.
 */
@Composable
fun VitalListCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    VitalCard(
        modifier = modifier,
        accent = accent,
        contentPadding = PaddingValues(0.dp),
        content = content
    )
}

/** The divider that belongs between two [VitalListRow]s inside a [VitalListCard]. */
@Composable
fun VitalListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = DividerColor,
        thickness = Sizes.hairline,
        modifier = modifier.padding(start = 64.dp)
    )
}

// ───────────────────────────────────────────────────────────────────────────
// Section header
// ───────────────────────────────────────────────────────────────────────────

/**
 * A clear section title with a small domain marker and an optional action.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    action: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = Spacing.xs, start = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(VitalShapes.Bar)
                .background(accent)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = title,
            style = VitalCoreType.sectionTitle,
            color = OnBackground,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        if (action != null) action()
    }
}

// ───────────────────────────────────────────────────────────────────────────
// List row
// ───────────────────────────────────────────────────────────────────────────

@Composable
fun VitalListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = OnSurfaceDim,
    trailingText: String? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    badgeCount: Int = 0,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.listRowMinHeight)
            .then(
                if (onClick != null && enabled) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = leadingTint),
                    role = Role.Button,
                    onClick = onClick
                ) else Modifier
            )
            .alpha(if (enabled) 1f else Alphas.disabled)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(Sizes.leadingIconBox)
                    .clip(VitalShapes.IconBox)
                    .background(leadingTint.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = leadingTint,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
            Spacer(Modifier.width(Spacing.md))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailingContent != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailingContent()
        }
        if (trailingText != null) {
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceDim,
                maxLines = 1
            )
        }
        if (badgeCount > 0) {
            Spacer(Modifier.width(Spacing.sm))
            AccentPill(text = badgeCount.toString(), color = RecoveryAccent)
        }
        if (showChevron) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = OnSurfaceMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Top bar + screen scaffold
// ───────────────────────────────────────────────────────────────────────────

/**
 * The single app bar. Container is [Background] so it merges with the page with no
 * seam; the back arrow is neutral white, never the domain accent. The accent
 * is reserved for the data and actions inside the screen.
 */
@Composable
fun VitalTopBar(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.topBarHeight)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = OnBackground
                    )
                }
            } else {
                Spacer(Modifier.width(Spacing.md))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            actions()
            Spacer(Modifier.width(Spacing.xs))
        }
        HorizontalDivider(
            color = DividerColor.copy(alpha = 0.6f),
            thickness = Sizes.hairline
        )
    }
}

/**
 * The screen skeleton every screen must use — guarantees identical gutters, bottom
 * bar clearance and scroll padding across all 14 screens. This is a Column plus a
 * LazyColumn, deliberately NOT a Scaffold: only `VitalCoreApp` declares one.
 */
@Composable
fun VitalScreenScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(Background)) {
        topBar()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = Spacing.lg,
                bottom = Spacing.listBottom
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            content = content
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Metric tiles — the one stat card
// ───────────────────────────────────────────────────────────────────────────

/**
 * Replaces both `MetricStatCard` (hardcoded red — why weekly averages rendered red)
 * and `ActivityStatCard` (hardcoded green — why strain read green). Colour is now
 * always a caller-supplied domain.
 *
 * @param value pre-formatted by `Fmt.*` — the tile never formats anything itself.
 */
data class MetricTileData(
    val label: String,
    val value: String,
    val unit: String? = null,
    val domain: Domain = Domain.NEUTRAL,
    val delta: String? = null,
    val deltaDirection: TrendDirection = TrendDirection.NEUTRAL,
    val deltaHigherIsBetter: Boolean = true,
    val route: String? = null,
)

@Composable
fun MetricTile(
    data: MetricTileData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = domainAccent(data.domain)
    VitalCard(
        modifier = modifier.heightIn(min = Sizes.tileMinHeight),
        accent = null,
        shape = VitalShapes.Tile,
        contentPadding = PaddingValues(Spacing.lg),
        onClick = onClick
    ) {
        Text(
            text = data.label.uppercase(),
            style = VitalCoreType.eyebrow,
            color = OnSurfaceDim,
            maxLines = 2,
            modifier = Modifier.heightIn(min = 28.dp),
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = data.value,
            style = VitalCoreType.metricMedium,
            color = if (data.value == "—") OnSurfaceMuted else OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!data.unit.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = data.unit,
                style = VitalCoreType.metricUnit,
                color = accent,
                maxLines = 2
            )
        }
        if (data.delta != null && data.delta.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            DeltaChip(
                text = data.delta,
                direction = data.deltaDirection,
                higherIsBetter = data.deltaHigherIsBetter
            )
        }
    }
}

/**
 * Tiles keep a readable minimum width, including when system text is enlarged.
 * Compact phones use two columns; wider layouts can fit three summary metrics.
 */
@Composable
fun MetricTileRow(
    tiles: List<MetricTileData>,
    modifier: Modifier = Modifier,
    onNavigate: ((String) -> Unit)? = null,
) {
    if (tiles.isEmpty()) return

    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val minTileWidth = 128.dp * fontScale.coerceAtLeast(1f)
        val maximumColumns = if (tiles.size <= 3) tiles.size else 2
        val columns = ((maxWidth + Spacing.sm) / (minTileWidth + Spacing.sm))
            .toInt().coerceIn(1, maximumColumns)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            tiles.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    row.forEach { tile ->
                        MetricTile(
                            data = tile,
                            modifier = Modifier.weight(1f),
                            onClick = tile.route?.let { r -> onNavigate?.let { nav -> { nav(r) } } }
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Insight card
// ───────────────────────────────────────────────────────────────────────────

/** Coach insight — 3dp left accent strip, title, body. The strip colour carries
 *  the insight type, so a warning is legible before the words are read. */
@Composable
fun InsightCard(
    title: String,
    body: String,
    type: CoachEngine.InsightType,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val accent = insightAccent(type)
    VitalCard(
        modifier = modifier.fillMaxWidth(),
        accent = accent,
        contentPadding = PaddingValues(Spacing.lg),
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(Sizes.accentStrip)
                    .height(44.dp)
                    .clip(VitalShapes.Bar)
                    .background(
                        Brush.verticalGradient(
                            listOf(accent, accent.copy(alpha = Alphas.tintedBorder))
                        )
                    )
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
            if (trailing != null) trailing()
        }
    }
}

fun insightAccent(type: CoachEngine.InsightType): Color = when (type) {
    CoachEngine.InsightType.WARNING   -> AlertRed
    CoachEngine.InsightType.RECOVERY  -> RecoveryAccent
    CoachEngine.InsightType.SLEEP     -> SleepAccent
    CoachEngine.InsightType.TRAINING  -> StrainAccent
    CoachEngine.InsightType.ACTIVITY  -> ActivityAccent
    CoachEngine.InsightType.READINESS -> com.example.vitalcoreai.theme.ReadinessAccent
    CoachEngine.InsightType.GENERAL   -> OnSurfaceDim
}
