package com.example.vitalcoreai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vitalcoreai.theme.ActivityAccent
import com.example.vitalcoreai.theme.AlertRed
import com.example.vitalcoreai.theme.Motion
import com.example.vitalcoreai.theme.OnSurfaceDim
import com.example.vitalcoreai.theme.OnSurfaceMuted
import com.example.vitalcoreai.theme.RecoveryAccent
import com.example.vitalcoreai.theme.HairlineColor
import com.example.vitalcoreai.theme.Sizes
import com.example.vitalcoreai.theme.SkeletonColor
import com.example.vitalcoreai.theme.Spacing
import com.example.vitalcoreai.theme.SurfaceL1
import com.example.vitalcoreai.theme.VitalCoreType
import com.example.vitalcoreai.theme.VitalShapes
import com.example.vitalcoreai.ui.viewmodel.OpResult

// ═══════════════════════════════════════════════════════════════════════════
// FEEDBACK STATES
//
// Loading rule: every screen honours isLoading. Hero regions render a
// VitalSkeleton at the exact size of the real content; list regions render 3
// skeleton rows. CircularProgressIndicator is forbidden outside Onboarding and
// inline button spinners.
//
// Empty-state rule: every empty state is VitalEmptyState with a Material icon.
// No emoji anywhere in UI chrome.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * The em-dash. THE only representation of "we do not have this value".
 * Never render 0, never render a fabricated default.
 */
@Composable
fun NoDataValue(
    modifier: Modifier = Modifier,
    style: TextStyle = VitalCoreType.metricMedium,
) {
    Text(text = "—", style = style, color = OnSurfaceMuted,
        modifier = modifier.clearAndSetSemantics { contentDescription = "No data available" })
}

/** Shimmering placeholder. Hero areas use this, NOT a spinner. */
@Composable
fun VitalSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = VitalShapes.Tile,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.60f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.skeletonMs),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .alpha(alpha)
            .background(SkeletonColor)
            .clearAndSetSemantics { }
    )
}

/** Three stacked skeleton rows — the standard placeholder for a list region. */
@Composable
fun VitalSkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    rowHeight: androidx.compose.ui.unit.Dp = Sizes.listRowMinHeight,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(rows) {
            VitalSkeleton(modifier = Modifier.fillMaxWidth().height(rowHeight))
        }
    }
}

/** Full-screen centred progress. Permitted ONLY on OnboardingScreen. */
@Composable
fun VitalLoading(
    modifier: Modifier = Modifier,
    accent: Color = RecoveryAccent,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent, strokeWidth = 3.dp)
    }
}

/** Empty state. Material icon, never an emoji. */
@Composable
fun VitalEmptyState(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = OnSurfaceDim,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(VitalShapes.Card)
                .background(SurfaceL1)
                .border(Sizes.hairline, HairlineColor, VitalShapes.Card),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = com.example.vitalcoreai.theme.OnBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp)
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** Sync-failure banner. Red is reserved for states like this one. */
@Composable
fun VitalErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Banner(
        message = message,
        icon = Icons.Outlined.ErrorOutline,
        color = AlertRed,
        modifier = modifier,
        action = onRetry?.let { retry ->
            {
                TextButton(onClick = retry) {
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelLarge,
                        color = AlertRed
                    )
                }
            }
        }
    )
}

/** Result of a user-triggered operation (backfill, CSV export). Replaces "✓ …" / "✗ …". */
@Composable
fun VitalResultBanner(
    result: OpResult,
    modifier: Modifier = Modifier,
) {
    Banner(
        message = result.message,
        icon = if (result.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
        color = if (result.success) ActivityAccent else AlertRed,
        modifier = modifier
    )
}

@Composable
private fun Banner(
    message: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(VitalShapes.Tile)
            .background(color.copy(alpha = 0.08f))
            .border(Sizes.hairline, color.copy(alpha = 0.20f), VitalShapes.Tile)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(Sizes.iconMd)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = com.example.vitalcoreai.theme.OnBackground,
            modifier = Modifier.weight(1f)
        )
        if (action != null) action()
    }
}
