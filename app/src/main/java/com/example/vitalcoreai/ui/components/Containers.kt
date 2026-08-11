package com.example.vitalcoreai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
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
// Elevation doctrine: no Material elevation, no shadows. On a #060A10 page a
// shadow is invisible and costs a full-screen overdraw pass. Depth comes from
// exactly three things — a surface step, a 1dp hairline, and an optional 3dp
// top accent strip.
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
    val borderBrush = if (accent != null) {
        Brush.verticalGradient(listOf(accent.copy(alpha = Alphas.tintedBorder), HairlineColor))
    } else {
        Brush.verticalGradient(listOf(HairlineColor, HairlineColor))
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(surface)
            .border(width = Sizes.hairline, brush = borderBrush, shape = shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = accent ?: RecoveryAccent),
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
                    text = title.uppercase(),
                    style = VitalCoreType.eyebrow,
                    color = accent
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                }
            }
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(Spacing.md))
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
 * A 3dp × 14dp rounded accent bar, 8dp gap, then the uppercase eyebrow.
 * Replaces the old under-title rule, which was always cyan regardless of domain.
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
            .padding(top = Spacing.xxl, bottom = 10.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(Sizes.accentStrip)
                .height(14.dp)
                .clip(VitalShapes.Bar)
                .background(accent)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = title.uppercase(),
            style = VitalCoreType.eyebrow,
            color = OnSurfaceDim,
            modifier = Modifier.weight(1f)
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
                    .background(leadingTint.copy(alpha = Alphas.tintedFill)),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
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
 * appears only as a 2dp bottom rule that fades out to the right.
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
                    overflow = TextOverflow.Ellipsis
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
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
                top = Spacing.sm,
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
        contentPadding = PaddingValues(14.dp),
        onClick = onClick
    ) {
        Text(
            text = data.label.uppercase(),
            style = VitalCoreType.eyebrow,
            color = OnSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.value,
                style = VitalCoreType.metricMedium,
                color = if (data.value == "—") OnSurfaceMuted else accent,
                maxLines = 1
            )
            if (data.unit != null) {
                Text(
                    text = data.unit,
                    style = VitalCoreType.metricUnit,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
                    maxLines = 1
                )
            }
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
 * 1–3 tiles → one row of equal weights. 4 or more → rows of two: at 360dp width a
 * 4-across tile is 74dp and truncates its own value.
 */
@Composable
fun MetricTileRow(
    tiles: List<MetricTileData>,
    modifier: Modifier = Modifier,
    onNavigate: ((String) -> Unit)? = null,
) {
    if (tiles.isEmpty()) return

    if (tiles.size <= 3) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            tiles.forEach { tile ->
                MetricTile(
                    data = tile,
                    modifier = Modifier.weight(1f),
                    onClick = tile.route?.let { r -> onNavigate?.let { nav -> { nav(r) } } }
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            tiles.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    pair.forEach { tile ->
                        MetricTile(
                            data = tile,
                            modifier = Modifier.weight(1f),
                            onClick = tile.route?.let { r -> onNavigate?.let { nav -> { nav(r) } } }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
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
                    style = MaterialTheme.typography.titleSmall,
                    color = OnBackground
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
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
