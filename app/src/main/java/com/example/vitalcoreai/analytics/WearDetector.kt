package com.example.vitalcoreai.analytics

// MODULE BOUNDARY — PURE KOTLIN ONLY. See ScoreResult.kt header.

/**
 * A4 — Wear Detection
 *
 * Analyses available sensor signals to determine whether the user wore their
 * watch during the period of interest. Critically: missing data is never
 * interpreted as poor health — it is interpreted as missing data.
 *
 * Detection heuristics:
 * - **Watch removed:** expected wear window has a continuous HR gap > [GAP_THRESHOLD_HOURS]
 * - **Charging / overnight removal:** no HR data during sleep window (00:00–07:00)
 * - **Phone-only tracking:** steps/calories present but no HR data
 * - **Partial day:** activity data starts after [LATE_START_HOUR] or ends before [EARLY_END_HOUR]
 * - **Sensor interruption:** multiple small HR gaps spread through the day
 *
 * All thresholds are declared as constants for easy tuning without touching logic.
 */
object WearDetector {

    // ── Thresholds ───────────────────────────────────────────────────────────

    private const val GAP_THRESHOLD_HOURS = 2.0        // single gap > 2h → likely removed
    private const val OVERNIGHT_WINDOW_START = 0       // 00:00 (minutes of day)
    private const val OVERNIGHT_WINDOW_END   = 7 * 60  // 07:00
    private const val LATE_START_HOUR       = 10       // data starting after 10 AM → partial
    private const val EARLY_END_HOUR        = 20       // data ending before 8 PM → partial
    private const val MIN_HR_POINTS_PER_HOUR = 1.0     // below this → sparse, not continuous

    // ── Data model ───────────────────────────────────────────────────────────

    /**
     * A detected gap in sensor data.
     * [startMinuteOfDay] and [endMinuteOfDay] are minutes since midnight.
     */
    data class TimeGap(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val durationMinutes: Int = endMinuteOfDay - startMinuteOfDay
    )

    /** Confidence that the wear status detection itself is reliable. */
    enum class WearConfidence { HIGH, MEDIUM, LOW }

    /**
     * Result of wear detection for a single day.
     *
     * @param worn                 true if the watch appears to have been worn for the majority of the day
     * @param confidence           how certain we are of the [worn] determination
     * @param scenario             human-readable detected scenario (shown in "Not enough data" UI)
     * @param gaps                 detected HR gaps (empty if worn = true with no gaps)
     * @param phoneOnlyTracking    true if only phone sensors were active (no watch HR)
     * @param partialDay           true if data doesn't cover the expected daily window
     */
    data class WearStatus(
        val worn: Boolean,
        val confidence: WearConfidence,
        val scenario: String,
        val gaps: List<TimeGap> = emptyList(),
        val phoneOnlyTracking: Boolean = false,
        val partialDay: Boolean = false
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Determine wear status from available signals.
     *
     * @param hrPointsWithMinuteOfDay list of (minuteOfDay, bpm) pairs — empty if no HR data
     * @param hasSteps                true if step data was recorded today
     * @param hasCalories             true if calorie data was recorded today
     * @param firstDataMinuteOfDay    minute of day when any data first appears (null = no data)
     * @param lastDataMinuteOfDay     minute of day when any data last appears (null = no data)
     * @param hasSleep                true if a sleep session was recorded
     */
    fun detect(
        hrPointsWithMinuteOfDay: List<Pair<Int, Int>>,   // (minuteOfDay, bpm)
        hasSteps: Boolean = false,
        hasCalories: Boolean = false,
        firstDataMinuteOfDay: Int? = null,
        lastDataMinuteOfDay: Int? = null,
        hasSleep: Boolean = false
    ): WearStatus {

        val hasHR = hrPointsWithMinuteOfDay.isNotEmpty()

        // ── Phone-only: steps/calories but no HR ──────────────────────────
        if (!hasHR && (hasSteps || hasCalories)) {
            return WearStatus(
                worn = false,
                confidence = WearConfidence.HIGH,
                scenario = "Phone-only tracking detected — watch HR data unavailable",
                phoneOnlyTracking = true
            )
        }

        // ── No data at all ────────────────────────────────────────────────
        if (!hasHR && !hasSteps && !hasCalories && !hasSleep) {
            return WearStatus(
                worn = false,
                confidence = WearConfidence.LOW,
                scenario = "No sensor data for this day"
            )
        }

        if (!hasHR) {
            return WearStatus(
                worn = false,
                confidence = WearConfidence.MEDIUM,
                scenario = "No heart rate data — watch may not have been worn"
            )
        }

        // ── Detect HR gaps ────────────────────────────────────────────────
        val sortedPoints = hrPointsWithMinuteOfDay.sortedBy { it.first }
        val gaps = detectGaps(sortedPoints)
        val largeGaps = gaps.filter { it.durationMinutes >= (GAP_THRESHOLD_HOURS * 60).toInt() }

        // ── Overnight gap → watch removed during sleep ────────────────────
        // An overnight gap can be: (a) a large intra-day gap overlapping midnight→07:00,
        // OR (b) the leading gap before the first data point that starts after 07:00
        val firstPointMinute = sortedPoints.firstOrNull()?.first ?: Int.MAX_VALUE
        val hasLeadingOvernightGap = firstPointMinute >= OVERNIGHT_WINDOW_END   // data starts ≥ 07:00
        val hasIntraOvernightGap = largeGaps.any { gap ->
            gap.startMinuteOfDay < OVERNIGHT_WINDOW_END &&
                    gap.endMinuteOfDay > OVERNIGHT_WINDOW_START
        }
        // A sleep session existing across that window means Samsung Health DID supply the
        // night — the watch was simply charging after wake, which is a completely normal
        // Watch Active 2 routine given its battery life. Flagging that as "watch removed
        // overnight" at HIGH confidence permanently suppressed the data-quality score of
        // anyone who charges overnight, every single day.
        val overnightGapDetected = (hasLeadingOvernightGap || hasIntraOvernightGap) && !hasSleep
        val overnightGapMinutes = if (hasLeadingOvernightGap) firstPointMinute else
            largeGaps.firstOrNull { gap ->
                gap.startMinuteOfDay < OVERNIGHT_WINDOW_END && gap.endMinuteOfDay > OVERNIGHT_WINDOW_START
            }?.durationMinutes ?: 0

        // ── Partial day check ─────────────────────────────────────────────
        val lateStart = firstDataMinuteOfDay != null &&
                firstDataMinuteOfDay > LATE_START_HOUR * 60
        val earlyEnd  = lastDataMinuteOfDay != null &&
                lastDataMinuteOfDay < EARLY_END_HOUR * 60
        val partialDay = lateStart || earlyEnd

        // ── HR density ────────────────────────────────────────────────────
        // Denominator is ELAPSED hours, not hours-that-already-contain-a-sample. Dividing
        // by the latter made the metric "samples per active hour", which is structurally
        // incapable of detecting sparse coverage: a watch worn 3 hours with 6 samples each
        // scored 6.0 pts/h and was classified "worn — continuous sensor data".
        val elapsedMinutes = (sortedPoints.last().first - sortedPoints.first().first)
            .coerceAtLeast(60)
        val avgPointsPerHour = sortedPoints.size.toDouble() / (elapsedMinutes / 60.0)

        return when {
            overnightGapDetected -> WearStatus(
                worn = false,
                confidence = WearConfidence.HIGH,
                scenario = "Watch appears removed overnight (${(overnightGapMinutes / 60.0).format1dp()}h HR gap)",
                gaps = largeGaps,
                partialDay = partialDay
            )
            largeGaps.isNotEmpty() -> WearStatus(
                worn = false,
                confidence = WearConfidence.MEDIUM,
                scenario = "${largeGaps.size} HR gap(s) > ${GAP_THRESHOLD_HOURS.toInt()}h detected — watch may have been removed",
                gaps = largeGaps,
                partialDay = partialDay
            )
            partialDay -> WearStatus(
                worn = true,
                confidence = WearConfidence.MEDIUM,
                scenario = "Partial day — data coverage incomplete",
                partialDay = true
            )
            avgPointsPerHour < MIN_HR_POINTS_PER_HOUR -> WearStatus(
                worn = true,
                confidence = WearConfidence.LOW,
                scenario = "Sparse HR data (${avgPointsPerHour.format1dp()} pts/h) — sensor interruptions possible"
            )
            else -> WearStatus(
                worn = true,
                confidence = WearConfidence.HIGH,
                scenario = "Watch worn — continuous sensor data"
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectGaps(sortedPoints: List<Pair<Int, Int>>): List<TimeGap> {
        if (sortedPoints.size < 2) return emptyList()
        val gaps = mutableListOf<TimeGap>()
        for (i in 1 until sortedPoints.size) {
            val gapMinutes = sortedPoints[i].first - sortedPoints[i - 1].first
            if (gapMinutes >= (GAP_THRESHOLD_HOURS * 60).toInt()) {
                gaps += TimeGap(
                    startMinuteOfDay = sortedPoints[i - 1].first,
                    endMinuteOfDay = sortedPoints[i].first,
                    durationMinutes = gapMinutes
                )
            }
        }
        return gaps
    }

    private fun Double.format1dp(): String = "%.1f".format(this)
}
