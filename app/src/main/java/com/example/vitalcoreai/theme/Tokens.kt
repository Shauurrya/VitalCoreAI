package com.example.vitalcoreai.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.dp

/** Spacing scale. Screens never invent their own padding numbers. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    val gutter     = 16.dp   // screen horizontal padding — every screen
    val cardGap    = 12.dp   // vertical gap between cards in a LazyColumn
    val sectionGap = 24.dp   // gap before a SectionHeader
    val listBottom = 32.dp   // extra bottom contentPadding above the bottom bar
}

/** Fixed component sizes. */
object Sizes {
    val topBarHeight     = 64.dp
    val bottomBarHeight  = 64.dp
    val listRowMinHeight = 64.dp
    val tileMinHeight    = 96.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val leadingIconBox   = 36.dp
    val hairline         = 1.dp
    val accentStrip      = 3.dp
    val barTrack         = 4.dp
}

/** Animation durations. Rings have their own timings in RingMotion. */
object Motion {
    const val fadeMs     = 220
    const val barMs      = 650
    const val tabFadeMs  = 180
    const val pushMs     = 240
    const val skeletonMs = 900
    val barEasing = FastOutSlowInEasing
}

/** Named alpha values — no magic 0.12f scattered through screen code. */
object Alphas {
    const val glow         = 0.20f
    const val tintedFill   = 0.12f   // icon container / badge background
    const val tintedBorder = 0.22f   // accent hairline
    const val areaFillTop  = 0.28f   // chart gradient top stop
    const val disabled     = 0.38f
    const val pressed      = 0.85f
}
