package com.example.vitalcoreai.ui.viewmodel

import com.example.vitalcoreai.analytics.TrendDirection
import com.example.vitalcoreai.data.model.HRZone
import com.example.vitalcoreai.theme.Domain

// ═══════════════════════════════════════════════════════════════════════════
// SHARED UI MODELS — Design System Specification v2 §7.1
//
// Every type here is plain Kotlin plus the `Domain` enum. No Color, no Dp, no
// ImageVector, no androidx.compose.ui.* type may ever appear in a UiState.
//
// Absent data is `null`. Never 0, never 50f, never 80.0.
// All list fields are oldest → newest, always, with no exceptions.
// ═══════════════════════════════════════════════════════════════════════════

enum class Momentum {
    IMPROVING, STABLE, DECLINING, UNKNOWN;

    companion object {
        fun parse(raw: String?): Momentum =
            raw?.let { runCatching { valueOf(it) }.getOrNull() } ?: UNKNOWN
    }
}

/**
 * One day of a single metric. `value == null` means "no data for this day",
 * which is NOT the same as 0 and must render as a gap.
 */
data class DayValue(val epochDay: Long, val value: Float?, val label: String)

data class DayStrain(
    val epochDay: Long,
    val strain: Float?,
    val exertionMinutes: Float?,
    val label: String,
)

data class ZoneMinutes(val zone: HRZone, val minutes: Int, val fraction: Float)

enum class ActivityIcon { RUN, BIKE, SWIM, WALK, HIKE, STRENGTH, YOGA, ROW, ELLIPTICAL, SLEEP, GENERIC }

data class ActivityRowData(
    val id: String,                    // stable LazyColumn key — startMs, or "sleep-<epochDay>"
    val title: String,                 // "Running", "Sleep"
    val subtitle: String,              // "06:12 – 06:58 · 46m"
    val icon: ActivityIcon,
    val domain: Domain,
    val trailingValue: String? = null, // "4.2" strain contribution, "312 kcal"
    val trailingUnit: String? = null,
    val route: String? = null,
)

data class OverviewMetric(
    val id: String,
    val label: String,
    val value: String,                 // pre-formatted, "—" when absent
    val unit: String?,
    val domain: Domain,
    val delta: String? = null,
    val deltaDirection: TrendDirection = TrendDirection.NEUTRAL,
    val route: String? = null,
)

/** Result of a user-triggered operation — backfill, CSV export. */
data class OpResult(val success: Boolean, val message: String)
