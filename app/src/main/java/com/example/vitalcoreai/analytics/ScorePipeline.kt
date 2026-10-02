package com.example.vitalcoreai.analytics

import com.example.vitalcoreai.data.model.DailyActivityData
import com.example.vitalcoreai.data.model.HRZone
import com.example.vitalcoreai.data.model.HeartRatePoint
import com.example.vitalcoreai.data.model.RestingHRData
import com.example.vitalcoreai.data.model.SleepData
import com.example.vitalcoreai.data.model.TrainingLoadData

// MODULE BOUNDARY — PURE KOTLIN ONLY
// No android.* imports permitted in this package.
// No androidx.* imports permitted in this package.
// No Context, Activity, or Fragment references permitted.
//
// That rule is why this file does NOT take or return Room entities: `ComputedScoresEntity`
// carries androidx.room annotations, and depending on it here would drag the persistence
// layer into the one package that is guaranteed testable without it. The repository owns
// the entity↔[Input]/[Output] mapping instead; it is a straight field copy with no logic,
// which is exactly the part that belongs next to the database.

/**
 * The whole daily score computation, as one pure function.
 *
 * ## Why this exists
 *
 * `HealthRepository.computeAndStoreScores()` was ~400 lines of engine orchestration
 * interleaved with DAO reads. Nothing in it could be exercised without Room, an Android
 * runtime and an instrumentation test, so the part of the app most worth testing — the
 * arithmetic that produces every number the user sees — was the part hardest to test.
 *
 * Everything the computation needs is now an explicit field of [Input], and everything it
 * produces is an explicit field of [Output]. [compute] reads no clock, touches no database
 * and performs no I/O, so a JVM unit test can drive it directly and a second call with the
 * same [Input] returns an identical [Output].
 *
 * ## Idempotency
 *
 * The repository re-runs the trailing three days on every sync, so a day is scored many
 * times. Every history field on [Input] is therefore documented as **strictly excluding
 * the day being scored**: when a series needs today's value the pipeline appends the value
 * it just computed rather than reading back the row a previous run wrote. Reading today's
 * own persisted row back in would make the second sync of a day disagree with the first.
 */
object ScorePipeline {

    // ─────────────────────────────────────────────────────────────────────────
    // Pure mirrors of the persisted rows
    // ─────────────────────────────────────────────────────────────────────────

    /** One day of raw metrics — the pure mirror of `DailyMetricsEntity`. */
    data class DayMetrics(
        val dateEpochDay: Long,
        val restingHR: Int? = null,
        val steps: Int? = null,
        val distanceMeters: Float? = null,
        val caloriesBurned: Int? = null,
        val activeCalories: Int? = null,
        val spO2Percent: Float? = null,
        val spO2ReadingCount: Int? = null,
        val sleepDurationMinutes: Int? = null,
        val sleepEfficiencyPercent: Double? = null,
        val sleepDeepMinutes: Int? = null,
        val sleepRemMinutes: Int? = null,
        val sleepLightMinutes: Int? = null,
        val sleepAwakeMinutes: Int? = null,
        val sleepStagesAvailable: Boolean? = null,
        val bedtimeMinuteOfDay: Int? = null,
        val wakeTimeMinuteOfDay: Int? = null,
        val dataSourceType: String? = null,
        val hrPointsPerHour: Double? = null,
        val partialDayFraction: Double? = null,
        val dataAgeHours: Double? = null
    )

    /** One prior day's computed scores — the pure mirror of the `computed_scores` columns read back. */
    data class DayScores(
        val dateEpochDay: Long,
        val recoveryScore: Float? = null,
        val readinessScore: Float? = null,
        val sleepScore: Float? = null,
        val strain: Float? = null,
        val energyBankScore: Float? = null,
        val hrr1: Int? = null
    )

    /** One exercise session — the pure mirror of `ExerciseSessionEntity`. */
    data class Session(
        val dateEpochDay: Long,
        val startMs: Long,
        val endMs: Long,
        val exerciseType: String,
        val durationMinutes: Int,
        val trainingLoadNormalized: Float? = null,
        val dominantZone: String? = null,
        val hasHeartRateData: Boolean = true,
        val maxHR: Int? = null,
        val rpe: Int? = null,
        /** Minutes past local midnight the session ended — hour-granular muscle recovery needs this. */
        val endMinuteOfDay: Int = 12 * 60
    )

    /** One morning check-in — the pure mirror of `CheckInEntity`. */
    data class CheckIn(
        val dateEpochDay: Long,
        val energy: Int,
        val stress: Int,
        val soreness: Int,
        val sleepQuality: Int,
        val mood: Int
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Input
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Everything the day's computation depends on.
     *
     * @param todayEpochDay          the local day being scored
     * @param nowMinuteOfDay         minutes past local midnight, for hour-granular muscle recovery
     * @param today                  today's raw metrics
     * @param history                `[today−30, today−1]` ascending — **excludes today**
     * @param priorScores            up to 30 previously computed days, ascending, **all strictly before today**
     * @param sessions               exercise sessions in `[today−27, today]`
     * @param todayHrPoints          today's intraday HR samples
     * @param postWorkoutSamples     `(seconds since session end, bpm)` for today's last workout
     * @param checkIns               check-ins in `[today−13, today]`, ascending
     * @param overnightHRGapHours    longest pre-07:00 sensor gap, from WearDetector
     * @param daysOfHistoryAvailable how many days of genuine data exist, capping the ACWR window
     */
    data class Input(
        val todayEpochDay: Long,
        val nowMinuteOfDay: Int = 12 * 60,
        val userAge: Int = 30,
        val userMaxHR: Int = 190,
        val sleepNeedMinutes: Int = 480,
        val stepGoal: Int = 7500,
        val goal: RecommendationEngine.Goal = RecommendationEngine.Goal.GENERAL_FITNESS,
        val today: DayMetrics,
        val history: List<DayMetrics> = emptyList(),
        val priorScores: List<DayScores> = emptyList(),
        val sessions: List<Session> = emptyList(),
        val todayHrPoints: List<HeartRatePoint> = emptyList(),
        val postWorkoutSamples: List<Pair<Int, Int>> = emptyList(),
        val checkIns: List<CheckIn> = emptyList(),
        val overnightHRGapHours: Double = 0.0,
        val daysOfHistoryAvailable: Int = 0
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Output
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Every value the day produces.
     *
     * The first block is field-for-field identical to the persisted columns, so the
     * repository's mapping is a constructor call with no branching. The second block
     * carries the engines' typed results, which the coach context and the debug screen
     * want in full rather than flattened to a string.
     */
    data class Output(
        // ── persisted: existing v6 columns ──────────────────────────────────
        val recoveryScore: Float? = null,
        val recoveryConfidence: String? = null,
        val recoveryExplanation: String? = null,
        val recoveryBreakdown: String? = null,
        val readinessScore: Float? = null,
        val readinessConfidence: String? = null,
        val readinessExplanation: String? = null,
        val readinessBreakdown: String? = null,
        val sleepScore: Float? = null,
        val sleepConfidence: String? = null,
        val sleepExplanation: String? = null,
        val sleepBreakdown: String? = null,
        val stressScore: Float? = null,
        val stressConfidence: String? = null,
        val stressExplanation: String? = null,
        val stressBreakdown: String? = null,
        val activityScore: Float? = null,
        val consistencyScore: Float? = null,
        val lifestyleScore: Float? = null,
        val trainingLoadNormalized: Float? = null,
        val acwr: Float? = null,
        val acwrZone: String? = null,
        val acwrIsMeaningful: Boolean? = null,
        val acwrDaysOfHistory: Int? = null,
        val vo2MaxEstimate: Float? = null,
        val biologicalAge: Int? = null,
        val recoveryMomentum: String? = null,
        val sleepMomentum: String? = null,
        val trainingMomentum: String? = null,
        val dataQualityLevel: String? = null,
        val dataQualityPercent: Int? = null,
        val energyBankScore: Float? = null,
        val energyBankExplanation: String? = null,
        val energyBankBreakdown: String? = null,
        val hrr1: Int? = null,
        val hrr2: Int? = null,
        val hrrTrend: String? = null,
        val strain: Float? = null,
        val dailyExertionMinutes: Float? = null,
        val strainConfidence: String? = null,
        val strainExplanation: String? = null,
        val strainBreakdown: String? = null,
        val strainIsProxy: Boolean? = null,
        val strainZone: String? = null,

        // ── persisted: v7 columns (the V1.1 engines) ────────────────────────
        val forecastLow: Int? = null,
        val forecastHigh: Int? = null,
        val forecastConfidence: String? = null,
        val forecastDrivers: String? = null,
        val forecastRisks: String? = null,
        val sleepConsistencyScore: Float? = null,
        val sleepConsistencyLabel: String? = null,
        val bedtimeSdMinutes: Int? = null,
        val wakeSdMinutes: Int? = null,
        val trend7Direction: String? = null,
        val trend14Direction: String? = null,
        val trend30Direction: String? = null,
        val trendContributors: String? = null,
        val anomaliesEncoded: String? = null,
        val anomalyCount: Int? = null,
        val recommendationType: String? = null,
        val recommendationIntensity: String? = null,
        val recommendationVolumePct: Int? = null,
        val recommendationDetail: String? = null,
        val recommendationConfidence: String? = null,
        val recommendationRationale: String? = null,
        val recommendationAlternative: String? = null,
        val dataQualityFactors: String? = null,
        val dataQualityPositives: String? = null,

        // ── not persisted: typed engine results ─────────────────────────────
        val forecast: ReadinessForecastEngine.Forecast? = null,
        val trends: List<RecoveryTrendEngine.TrendReport> = emptyList(),
        val anomalies: List<AnomalyDetectionEngine.Anomaly> = emptyList(),
        val sleepConsistency: SleepConsistencyCalculator.ConsistencyResult? = null,
        val recommendation: RecommendationEngine.Recommendation? = null,
        val muscleStatuses: List<MuscleRecoveryEngine.MuscleStatus> = emptyList(),
        val learnedRecovery: Map<MuscleRecoveryEngine.MuscleGroup, MuscleRecoveryEngine.LearnedRecovery> = emptyMap(),
        val insights: List<InsightDiscoveryEngine.Insight> = emptyList(),
        val dataQuality: DataQualityReport = DataQualityReport.UNKNOWN,
        val sleepDebtMinutes: Int = 0,
        val consecutiveTrainingDays: Int = 0,
        val daysSinceLastWorkout: Int? = null,
        val restingHRBaselineBpm: Double? = null
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Column encoding
    // ─────────────────────────────────────────────────────────────────────────
    //
    // A pipe-delimited list of colon-delimited records, matching the convention the rest of
    // the schema already uses (WeeklyReportEntity.highlights, ExerciseSessionEntity.muscleGroups).
    // Encoders live beside the decoders so the two can never drift: the trailing free-text
    // field is the only one allowed to contain a colon, and decoding splits with a limit so
    // it survives. Pipes are stripped from every field because they are the record separator.

    const val LIST_SEP = "|"
    const val FIELD_SEP = ":"

    private fun String.noPipes(): String = replace(LIST_SEP, "/")
    private fun String.noSeparators(): String = replace(LIST_SEP, "/").replace(FIELD_SEP, "-")

    /** "name:points:description" per driver. */
    fun encodeDrivers(drivers: List<ReadinessForecastEngine.Driver>): String? =
        drivers.takeIf { it.isNotEmpty() }?.joinToString(LIST_SEP) { d ->
            listOf(
                d.name.noSeparators(),
                formatSigned(d.points),
                d.description.noPipes()
            ).joinToString(FIELD_SEP)
        }

    data class DecodedDriver(val name: String, val points: Float, val description: String)

    fun decodeDrivers(encoded: String?): List<DecodedDriver> =
        splitRecords(encoded).mapNotNull { record ->
            val parts = record.split(FIELD_SEP, limit = 3)
            if (parts.size < 3) return@mapNotNull null
            DecodedDriver(parts[0], parts[1].toFloatOrNull() ?: 0f, parts[2])
        }

    /** "metric:severity:title" per anomaly. */
    fun encodeAnomalies(anomalies: List<AnomalyDetectionEngine.Anomaly>): String? =
        anomalies.takeIf { it.isNotEmpty() }?.joinToString(LIST_SEP) { a ->
            listOf(
                a.metric.displayName.noSeparators(),
                a.severity.name.noSeparators(),
                a.title.noPipes()
            ).joinToString(FIELD_SEP)
        }

    data class DecodedAnomaly(val metric: String, val severity: String, val title: String) {
        /** Human-readable severity, so a badge never relies on colour alone. */
        val severityLabel: String
            get() = AnomalyDetectionEngine.Severity.entries
                .firstOrNull { it.name == severity }?.label ?: severity
    }

    fun decodeAnomalies(encoded: String?): List<DecodedAnomaly> =
        splitRecords(encoded).mapNotNull { record ->
            val parts = record.split(FIELD_SEP, limit = 3)
            if (parts.size < 3) return@mapNotNull null
            DecodedAnomaly(parts[0], parts[1], parts[2])
        }

    /** "FACTOR=0.85" per dimension. */
    fun encodeQualityFactors(factors: Map<String, Float>): String? =
        factors.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedBy { it.key }
            ?.joinToString(LIST_SEP) { "${it.key.noPipes()}=${"%.2f".format(it.value)}" }

    fun decodeQualityFactors(encoded: String?): Map<String, Float> =
        splitRecords(encoded).mapNotNull { record ->
            val idx = record.lastIndexOf('=')
            if (idx <= 0) return@mapNotNull null
            val value = record.substring(idx + 1).toFloatOrNull() ?: return@mapNotNull null
            record.substring(0, idx) to value
        }.toMap()

    /** Plain "a|b|c" text lists — contributors, risks, positives. */
    fun encodeTextList(items: List<String>): String? =
        items.takeIf { it.isNotEmpty() }?.joinToString(LIST_SEP) { it.noPipes() }

    fun decodeTextList(encoded: String?): List<String> = splitRecords(encoded)

    private fun splitRecords(encoded: String?): List<String> =
        if (encoded.isNullOrBlank()) emptyList()
        else encoded.split(LIST_SEP).filter { it.isNotBlank() }

    private fun formatSigned(points: Float): String =
        if (points >= 0) "+${"%.1f".format(points)}" else "%.1f".format(points)

    // ─────────────────────────────────────────────────────────────────────────
    // The computation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The window an engine is allowed to look back over. Kept here rather than at each call
     * site so the repository's queries and the engines' expectations cannot drift apart.
     */
    const val BASELINE_DAYS = 30
    const val SLEEP_BASELINE_DAYS = 14
    const val TREND_DAYS = 30
    const val INSIGHT_DAYS = 90
    const val ACWR_WINDOW_DAYS = 28

    fun compute(input: Input): Output {
        val today = input.todayEpochDay
        val metrics = input.today

        // History is documented as excluding today, but a caller that ranges a DAO
        // inclusively is an easy mistake and would silently z-score today against itself.
        val history = input.history.filter { it.dateEpochDay < today }.sortedBy { it.dateEpochDay }
        val priorScores = input.priorScores.filter { it.dateEpochDay < today }.sortedBy { it.dateEpochDay }
        val sessions = input.sessions.sortedBy { it.dateEpochDay }
        val checkIns = input.checkIns.sortedBy { it.dateEpochDay }

        val past30 = history.filter { it.dateEpochDay >= today - BASELINE_DAYS }
        val past14 = history.filter { it.dateEpochDay >= today - SLEEP_BASELINE_DAYS }
        val past7 = history.filter { it.dateEpochDay >= today - 7 }

        val restingHRBaseline30 = past30.mapNotNull { m ->
            m.restingHR?.let { RestingHRData(m.dateEpochDay, it) }
        }
        val sleepBaseline14 = past14.mapNotNull { it.toSleepData() }
        val sleepPrior7 = past7.mapNotNull { it.toSleepData() }

        val todaySleep = metrics.toSleepData()
        val todayRHR = metrics.restingHR?.let { RestingHRData(today, it) }

        // ── Per-day training load ────────────────────────────────────────────
        val loadByDay: Map<Long, Float> = sessions
            .groupBy { it.dateEpochDay }
            .mapValues { (_, group) ->
                group.sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat()
            }

        val priorDaySessions = sessions.filter { it.dateEpochDay == today - 1 }
        val priorDayLoad: TrainingLoadData = if (priorDaySessions.isEmpty()) {
            // An explicit zero, not null: a day with no sessions is genuine rest, which is
            // information, rather than missing data.
            TrainingLoadData(today - 1, 0f, 0, HRZone.BELOW_ZONE1, hasHeartRateData = false)
        } else {
            TrainingLoadData(
                dateEpochDay = today - 1,
                normalizedLoad = priorDaySessions
                    .sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat().coerceAtMost(1f),
                durationMinutes = priorDaySessions.sumOf { it.durationMinutes },
                dominantZone = priorDaySessions
                    .maxByOrNull { it.trainingLoadNormalized ?: 0f }
                    ?.dominantZone?.let { runCatching { HRZone.valueOf(it) }.getOrNull() }
                    ?: HRZone.ZONE2,
                hasHeartRateData = priorDaySessions.any { it.hasHeartRateData }
            )
        }

        val todaySessions = sessions.filter { it.dateEpochDay == today }
        val todayLoadNormalized = todaySessions
            .sumOf { (it.trainingLoadNormalized ?: 0f).toDouble() }.toFloat().coerceAtMost(1f)
        val todayExerciseMinutes = todaySessions.sumOf { it.durationMinutes }

        // ACWR over a zero-filled DAILY series — rest days must enter the denominator.
        val acwrResult = AcwrCalculator.calculate(
            AcwrCalculator.buildDailySeries(
                loadByDay + mapOf(today to todayLoadNormalized), today, windowDays = ACWR_WINDOW_DAYS
            ).let { series ->
                // Only report as many days as we genuinely have history for.
                series.takeLast(input.daysOfHistoryAvailable.coerceIn(0, ACWR_WINDOW_DAYS))
            }
        )

        // ── Data quality ─────────────────────────────────────────────────────
        val qualityInput = DataQualityEngine.QualityInput(
            hasSleepToday = todaySleep != null,
            hasHRToday = todayRHR != null,
            hasWorkoutToday = todaySessions.isNotEmpty(),
            hrPointsPerHour = metrics.hrPointsPerHour ?: 0.0,
            sleepHistoryDays = sleepBaseline14.size,
            hrHistoryDays = restingHRBaseline30.size,
            overnightHRGapHours = input.overnightHRGapHours,
            dataSourceType = when (metrics.dataSourceType) {
                "WATCH_SENSOR" -> DataQualityEngine.DataSourceType.WATCH_SENSOR
                "PHONE_SENSOR" -> DataQualityEngine.DataSourceType.PHONE_SENSOR
                "MANUAL" -> DataQualityEngine.DataSourceType.MANUAL
                else -> DataQualityEngine.DataSourceType.UNKNOWN
            },
            partialDayFraction = metrics.partialDayFraction ?: 1.0,
            hasStepsToday = (metrics.steps ?: 0) > 0,
            activityHistoryDays = past30.count { it.steps != null },
            dataAgeHours = metrics.dataAgeHours,
            measurementConsistency = DataQualityEngine.consistencyFromSeries(
                restingHRBaseline30.map { it.bpm.toDouble() }
            )
        )

        // ── Recovery / sleep / readiness / stress / activity ─────────────────
        val recoveryResult = if (todayRHR != null) {
            RecoveryScoreCalculator.calculate(
                todaySleep = todaySleep,
                sleepBaseline14Days = sleepBaseline14,
                todayRestingHR = todayRHR,
                restingHRBaseline30Days = restingHRBaseline30,
                priorDayTrainingLoad = priorDayLoad,
                spO2Percent = metrics.spO2Percent,
                qualityInput = qualityInput,
                personalSleepNeedMinutes = input.sleepNeedMinutes,
                spO2ReadingCount = metrics.spO2ReadingCount ?: 0
            )
        } else null
        val recoveryScore = recoveryResult?.score
        // Hoisted: inside `recoveryScore?.let { }` below Kotlin smart-casts recoveryResult to
        // non-null, so a safe call there is flagged as unnecessary. One binding, used everywhere.
        val recoveryQuality = recoveryResult?.dataQuality ?: DataQualityReport.UNKNOWN

        val sleepResult = todaySleep?.let {
            SleepScoreCalculator.calculate(
                todaySleep = it,
                last7Days = sleepPrior7,
                personalSleepNeedMinutes = input.sleepNeedMinutes,
                qualityInput = qualityInput
            )
        }
        val sleepScore = sleepResult?.score

        val restingBaselineMean = BaselineManager
            .restingHRBaseline(restingHRBaseline30.map { it.bpm.toDouble() }).mean
        val strainQuality = DataQualityEngine.evaluate(qualityInput, DataQualityEngine.MetricProfile.STRAIN)
        val strainResult = StrainCalculator.calculate(
            hrPoints = input.todayHrPoints,
            restingHRBaseline = restingBaselineMean,
            maxHR = input.userMaxHR,
            steps = metrics.steps,
            exerciseMinutes = todayExerciseMinutes,
            dataQuality = strainQuality
        )

        // Part 7 — Heart Rate Recovery from today's latest workout.
        // The historical series comes from days strictly before today, so re-syncing a day
        // cannot feed its own previous HRR back into its own trend.
        val latestSession = todaySessions.maxByOrNull { it.endMs }
        val hrrResult = if (latestSession?.maxHR != null && input.postWorkoutSamples.isNotEmpty()) {
            HRRecoveryCalculator.calculate(
                peakHR = latestSession.maxHR,
                postWorkoutSamples = input.postWorkoutSamples,
                historicalHrr1 = priorScores.mapNotNull { it.hrr1 }.reversed()
            )
        } else null

        // ── Sleep debt ───────────────────────────────────────────────────────
        val highLoadDays = sessions
            .filter { (it.trainingLoadNormalized ?: 0f) > SleepDebtCalculator.HIGH_LOAD_THRESHOLD }
            .map { it.dateEpochDay }
            .toSet()
        val sleepDebtResult = SleepDebtCalculator.calculate(
            recentSleep = sleepPrior7,
            highLoadDays = highLoadDays,
            todayIsHighLoad = today in highLoadDays
        )
        val sleepDebt = sleepDebtResult.debtMinutes

        val readinessResult = recoveryScore?.let {
            ReadinessScoreCalculator.calculate(
                recoveryScore = it,
                sleepDebtMinutes = sleepDebt,
                acwrResult = acwrResult,
                recoveryQuality = recoveryQuality,
                nightsCounted = sleepPrior7.size.coerceAtLeast(1)
            )
        }
        val readinessScore = readinessResult?.score

        val stressResult = todayRHR?.let {
            TrendCalculators.calculateStressScore(
                todayRestingHR = it.bpm,
                baselineHR30Days = restingHRBaseline30,
                priorDayHR = history.lastOrNull { m -> m.dateEpochDay == today - 1 }?.restingHR,
                qualityInput = qualityInput
            )
        }
        val stressScore = stressResult?.score

        val past30Activity = past30.mapNotNull { it.toActivity() }
        val activityResult = metrics.toActivity()?.let { todayActivity ->
            TrendCalculators.calculateActivityScore(
                today = todayActivity,
                last30 = past30Activity,
                priorDayActivity = past30.lastOrNull { it.dateEpochDay == today - 1 }?.toActivity(),
                qualityInput = qualityInput
            )
        }
        val activityScore = activityResult?.score
        val consistencyScore = TrendCalculators.calculateConsistencyScore(past30Activity, input.stepGoal)

        val vo2Max = todayRHR?.let {
            Vo2MaxEstimator.estimateFromRestingHR(it.bpm, input.userMaxHR, input.userAge).vo2Max
        }
        val bioAgeResult = vo2Max?.let {
            BiologicalAgeEstimator.estimate(
                chronologicalAge = input.userAge,
                vo2Max = it,
                restingHR30Days = restingHRBaseline30,
                activityData30Days = past30Activity,
                stepGoal = input.stepGoal
            )
        }
        val bioAge = bioAgeResult?.takeIf { !it.insufficientData }?.estimatedAge

        // ── B1 — Momentum ────────────────────────────────────────────────────
        // Ascending, and today's freshly computed value is APPENDED rather than read back
        // from the row a previous run of this same day wrote. Reading it back would make a
        // re-sync disagree with the first sync.
        val recent4 = priorScores.takeLast(4)
        val last5Recovery = (recent4.mapNotNull { it.recoveryScore } + listOfNotNull(recoveryScore))
        val last5Sleep = (recent4.mapNotNull { it.sleepScore } + listOfNotNull(sleepScore))
        val last5Strain = (recent4.mapNotNull { it.strain } + listOfNotNull(strainResult.strain))
            .map { it / 21f * 100f }
        val momentum = MomentumCalculator.calculate(last5Recovery, last5Sleep, last5Strain)

        // ── Part 11 — Energy Bank ────────────────────────────────────────────
        val todayCheckIn = checkIns.lastOrNull { it.dateEpochDay == today }
        val exerciseDays = sessions.map { it.dateEpochDay }.toSet()
        val restDaysLast7 = (0..6).count { i -> (today - i) !in exerciseDays }
        val energyBankResult = EnergyBankCalculator.calculate(
            EnergyBankCalculator.EnergyBankInput(
                sleepDurationsLast7 = sleepPrior7.map { it.durationMinutes },
                personalSleepNeedMinutes = input.sleepNeedMinutes,
                bedtimeMinutesOfDay = sleepPrior7.map { it.bedtimeMinuteOfDay },
                restDaysLast7 = restDaysLast7,
                acwr = acwrResult.acwr,
                stressScore = stressScore,
                sorenessRating = todayCheckIn?.soreness,
                rhrDeviationBpm = todayRHR?.bpm?.takeIf { restingHRBaseline30.isNotEmpty() }?.let { bpm ->
                    bpm - restingHRBaseline30.map { it.bpm }.average().toInt()
                },
                recoveryScore = recoveryScore,
                trainingLoadNormalized = todayLoadNormalized
            )
        )

        // ═════════════════════════════════════════════════════════════════════
        // T-12 — the V1.1 intelligence engines
        // ═════════════════════════════════════════════════════════════════════

        // ── Priority 10 — sleep consistency ──────────────────────────────────
        val nights = (history + metrics)
            .filter { it.dateEpochDay >= today - SLEEP_BASELINE_DAYS }
            .map {
                SleepConsistencyCalculator.NightTiming(
                    dateEpochDay = it.dateEpochDay,
                    bedtimeMinuteOfDay = it.bedtimeMinuteOfDay,
                    wakeTimeMinuteOfDay = it.wakeTimeMinuteOfDay,
                    durationMinutes = it.sleepDurationMinutes
                )
            }
        val sleepConsistency = SleepConsistencyCalculator.calculate(nights)

        // ── Priority 9 — muscle recovery, learned from the user's own gaps ───
        val sorenessByDay = checkIns.associate { it.dateEpochDay to it.soreness }
        val sessionInfos = sessions.map { s ->
            MuscleRecoveryEngine.SessionInfo(
                dateEpochDay = s.dateEpochDay,
                exerciseType = s.exerciseType,
                rpe = s.rpe ?: defaultRpeFor(s.trainingLoadNormalized),
                muscleGroups = MuscleRecoveryEngine.exerciseToMuscleGroups(s.exerciseType),
                endMinuteOfDay = s.endMinuteOfDay
            )
        }
        val learnedRecovery = MuscleRecoveryEngine.learnRecoveryTimes(sessionInfos, sorenessByDay)
        val muscleStatuses = MuscleRecoveryEngine.getAllMuscleStatuses(
            recentSessions = sessionInfos,
            todayEpochDay = today,
            sorenessRating = todayCheckIn?.soreness,
            learned = learnedRecovery,
            nowMinuteOfDay = input.nowMinuteOfDay
        )

        // ── Training-history shape shared by the forecast and the recommendation ──
        val consecutiveTrainingDays = countConsecutiveTrainingDays(exerciseDays, today)
        val daysSinceLastWorkout = exerciseDays.filter { it <= today }.maxOrNull()
            ?.let { (today - it).toInt() }

        // ── Priority 6 — recovery trends over 7 / 14 / 30 days ───────────────
        val scoresByDay = priorScores.associateBy { it.dateEpochDay }
        val trendDays = (history.filter { it.dateEpochDay >= today - TREND_DAYS } + metrics)
            .map { m ->
                val s = if (m.dateEpochDay == today) null else scoresByDay[m.dateEpochDay]
                RecoveryTrendEngine.DayInput(
                    dateEpochDay = m.dateEpochDay,
                    recoveryScore = if (m.dateEpochDay == today) recoveryScore else s?.recoveryScore,
                    readinessScore = if (m.dateEpochDay == today) readinessScore else s?.readinessScore,
                    sleepMinutes = m.sleepDurationMinutes,
                    bedtimeMinuteOfDay = m.bedtimeMinuteOfDay,
                    wakeTimeMinuteOfDay = m.wakeTimeMinuteOfDay,
                    restingHR = m.restingHR,
                    strain = if (m.dateEpochDay == today) strainResult.strain else s?.strain,
                    hadHighLoad = (loadByDay[m.dateEpochDay] ?: 0f) > SleepDebtCalculator.HIGH_LOAD_THRESHOLD
                )
            }
        val trends = RecoveryTrendEngine.analyseAll(trendDays)
        val trend7 = trends.firstOrNull { it.window == RecoveryTrendEngine.Window.WEEK }
        val trend14 = trends.firstOrNull { it.window == RecoveryTrendEngine.Window.FORTNIGHT }
        val trend30 = trends.firstOrNull { it.window == RecoveryTrendEngine.Window.MONTH }

        // ── Priority 5 — personal anomalies ──────────────────────────────────
        val checkInByDay = checkIns.associateBy { it.dateEpochDay }
        val anomalyHistory = history
            .filter { it.dateEpochDay >= today - TREND_DAYS }
            .map { m -> m.toDaySample(scoresByDay[m.dateEpochDay], checkInByDay[m.dateEpochDay]) }
        val todaySample = AnomalyDetectionEngine.DaySample(
            dateEpochDay = today,
            restingHR = metrics.restingHR?.toDouble(),
            sleepMinutes = metrics.sleepDurationMinutes?.toDouble(),
            sleepScore = sleepScore?.toDouble(),
            bedtimeMinuteOfDay = metrics.bedtimeMinuteOfDay,
            strain = strainResult.strain?.toDouble(),
            hrr1 = hrrResult?.hrr1?.toDouble(),
            steps = metrics.steps?.toDouble(),
            recovery = recoveryScore?.toDouble(),
            readiness = readinessScore?.toDouble(),
            energyBank = energyBankResult.score.toDouble(),
            checkInEnergy = todayCheckIn?.energy?.toDouble(),
            checkInStress = todayCheckIn?.stress?.toDouble(),
            checkInSoreness = todayCheckIn?.soreness?.toDouble()
        )
        val anomalies = AnomalyDetectionEngine.detect(anomalyHistory, todaySample)

        // ── Priority 4 — tomorrow's readiness RANGE ──────────────────────────
        val readinessHistory = priorScores
            .filter { it.dateEpochDay >= today - TREND_DAYS }
            .mapNotNull { it.readinessScore }
        val strainHistory = priorScores
            .filter { it.dateEpochDay >= today - TREND_DAYS }
            .mapNotNull { it.strain }
        val forecast = ReadinessForecastEngine.forecast(
            ReadinessForecastEngine.ForecastInput(
                todayReadiness = readinessScore,
                readinessHistory = readinessHistory,
                recentSleepMinutes = past7.mapNotNull { it.sleepDurationMinutes },
                personalSleepNeedMinutes = input.sleepNeedMinutes,
                sleepDebtMinutes = sleepDebt,
                todayStrain = strainResult.strain,
                strainHistory = strainHistory,
                restingHR = metrics.restingHR,
                restingHRBaseline = restingBaselineMean,
                restingHRHistory = restingHRBaseline30.map { it.bpm.toDouble() },
                consecutiveTrainingDays = consecutiveTrainingDays,
                checkInStress = todayCheckIn?.stress,
                checkInSoreness = todayCheckIn?.soreness,
                dataQuality = recoveryQuality
            )
        )

        // ── Priority 8 — what to train today ─────────────────────────────────
        val recommendation = RecommendationEngine.recommend(
            RecommendationEngine.RecommendationInput(
                readiness = readinessScore,
                recovery = recoveryScore,
                energyBank = energyBankResult.score,
                acwrZone = acwrResult.zone.name,
                acwrIsMeaningful = acwrResult.isMeaningful,
                muscleStatuses = muscleStatuses,
                consecutiveTrainingDays = consecutiveTrainingDays,
                daysSinceLastWorkout = daysSinceLastWorkout,
                sleepDebtMinutes = sleepDebt,
                lastNightSleepMinutes = metrics.sleepDurationMinutes,
                checkInSoreness = todayCheckIn?.soreness,
                checkInEnergy = todayCheckIn?.energy,
                restingHRDeviationBpm = todayRHR?.bpm?.takeIf { restingHRBaseline30.isNotEmpty() }?.let { bpm ->
                    bpm - restingBaselineMean.toInt()
                },
                goal = input.goal,
                dataQuality = recoveryQuality
            )
        )

        // ── Priority 12 — statistical pattern discovery ──────────────────────
        val insightDays = (history.filter { it.dateEpochDay >= today - INSIGHT_DAYS } + metrics).map { m ->
            val s = if (m.dateEpochDay == today) null else scoresByDay[m.dateEpochDay]
            val c = checkInByDay[m.dateEpochDay]
            val dayLoad = loadByDay[m.dateEpochDay] ?: 0f
            InsightDiscoveryEngine.DayRecord(
                dateEpochDay = m.dateEpochDay,
                readiness = if (m.dateEpochDay == today) readinessScore else s?.readinessScore,
                recovery = if (m.dateEpochDay == today) recoveryScore else s?.recoveryScore,
                sleepMinutes = m.sleepDurationMinutes,
                sleepScore = if (m.dateEpochDay == today) sleepScore else s?.sleepScore,
                bedtimeMinuteOfDay = m.bedtimeMinuteOfDay,
                restingHR = m.restingHR,
                strain = if (m.dateEpochDay == today) strainResult.strain else s?.strain,
                hadWorkout = m.dateEpochDay in exerciseDays,
                hadHighLoad = dayLoad > SleepDebtCalculator.HIGH_LOAD_THRESHOLD,
                checkInEnergy = c?.energy,
                checkInStress = c?.stress,
                isRestDay = m.dateEpochDay !in exerciseDays
            )
        }
        val insights = InsightDiscoveryEngine.discover(insightDays)

        val quality = recoveryResult?.dataQuality
            ?: DataQualityEngine.evaluate(qualityInput, DataQualityEngine.MetricProfile.RECOVERY)

        return Output(
            recoveryScore = recoveryScore,
            recoveryConfidence = recoveryResult?.confidence?.name,
            recoveryExplanation = recoveryResult?.explanation,
            recoveryBreakdown = recoveryResult?.breakdown?.encodeToString(),
            readinessScore = readinessScore,
            readinessConfidence = readinessResult?.confidence?.name,
            readinessExplanation = readinessResult?.explanation,
            readinessBreakdown = readinessResult?.breakdown?.encodeToString(),
            sleepScore = sleepScore,
            sleepConfidence = sleepResult?.confidence?.name,
            sleepExplanation = sleepResult?.explanation,
            sleepBreakdown = sleepResult?.breakdown?.encodeToString(),
            stressScore = stressScore,
            stressConfidence = stressResult?.confidence?.name,
            stressExplanation = stressResult?.explanation,
            stressBreakdown = stressResult?.breakdown?.encodeToString(),
            activityScore = activityScore,
            consistencyScore = consistencyScore,
            lifestyleScore = if (activityScore != null && sleepScore != null)
                TrendCalculators.calculateLifestyleScore(consistencyScore, activityScore, sleepScore).score
            else null,
            trainingLoadNormalized = todayLoadNormalized,
            acwr = acwrResult.acwr,
            acwrZone = acwrResult.zone.name,
            acwrIsMeaningful = acwrResult.isMeaningful,
            acwrDaysOfHistory = acwrResult.daysOfHistory,
            vo2MaxEstimate = vo2Max,
            biologicalAge = bioAge,
            recoveryMomentum = momentum.recovery.direction.name,
            sleepMomentum = momentum.sleep.direction.name,
            trainingMomentum = momentum.training.direction.name,
            dataQualityLevel = quality.level.name,
            dataQualityPercent = quality.confidencePercent,
            energyBankScore = energyBankResult.score,
            energyBankExplanation = energyBankResult.explanation,
            energyBankBreakdown = energyBankResult.breakdown.encodeToString(),
            hrr1 = hrrResult?.hrr1,
            hrr2 = hrrResult?.hrr2,
            hrrTrend = hrrResult?.trend?.name,
            strain = strainResult.strain,
            dailyExertionMinutes = strainResult.exertionMinutes,
            strainConfidence = strainResult.confidence.name,
            strainExplanation = strainResult.explanation,
            strainBreakdown = strainResult.breakdown.encodeToString(),
            strainIsProxy = strainResult.isProxyEstimate,
            strainZone = strainResult.zone?.name,

            // ── v7 ──────────────────────────────────────────────────────────
            forecastLow = forecast.low.takeIf { forecast.available },
            forecastHigh = forecast.high.takeIf { forecast.available },
            forecastConfidence = forecast.confidence.name.takeIf { forecast.available },
            forecastDrivers = if (forecast.available) encodeDrivers(forecast.drivers) else null,
            forecastRisks = if (forecast.available) encodeTextList(forecast.risks) else null,
            sleepConsistencyScore = sleepConsistency.score,
            sleepConsistencyLabel = sleepConsistency.label,
            bedtimeSdMinutes = sleepConsistency.bedtimeSdMinutes,
            wakeSdMinutes = sleepConsistency.wakeSdMinutes,
            trend7Direction = trend7?.direction?.name,
            trend14Direction = trend14?.direction?.name,
            trend30Direction = trend30?.direction?.name,
            trendContributors = encodeTextList(
                (trend14 ?: trend7)?.contributors?.map { it.description } ?: emptyList()
            ),
            anomaliesEncoded = encodeAnomalies(anomalies),
            anomalyCount = anomalies.size,
            recommendationType = recommendation.primaryType.displayName,
            recommendationIntensity = recommendation.intensity.displayName,
            recommendationVolumePct = recommendation.volumeAdjustmentPercent,
            recommendationDetail = recommendation.detail,
            recommendationConfidence = recommendation.confidence.name,
            recommendationRationale = encodeTextList(recommendation.rationale),
            recommendationAlternative = recommendation.alternativeType?.displayName,
            dataQualityFactors = encodeQualityFactors(quality.factors),
            dataQualityPositives = encodeTextList(quality.positives),

            // ── typed ───────────────────────────────────────────────────────
            forecast = forecast,
            trends = trends,
            anomalies = anomalies,
            sleepConsistency = sleepConsistency,
            recommendation = recommendation,
            muscleStatuses = muscleStatuses,
            learnedRecovery = learnedRecovery,
            insights = insights,
            dataQuality = quality,
            sleepDebtMinutes = sleepDebt,
            consecutiveTrainingDays = consecutiveTrainingDays,
            daysSinceLastWorkout = daysSinceLastWorkout,
            restingHRBaselineBpm = restingBaselineMean.takeIf { restingHRBaseline30.isNotEmpty() }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Training days ending at [today], walking backwards. A rest day stops the count.
     *
     * Today counts only if it was actually trained; otherwise the streak is measured up to
     * yesterday, so a rest day correctly reports zero rather than inheriting yesterday's run.
     */
    fun countConsecutiveTrainingDays(exerciseDays: Set<Long>, today: Long): Int {
        if (today !in exerciseDays) return 0
        var n = 0
        var day = today
        while (day in exerciseDays) {
            n++
            day--
        }
        return n
    }

    /**
     * RPE for a session the user never rated.
     *
     * Derived from the normalised training load rather than defaulting to a flat 5, which
     * gave a maximal session and a gentle walk the same 48-hour recovery estimate.
     */
    private fun defaultRpeFor(normalizedLoad: Float?): Int {
        val load = normalizedLoad ?: return 5
        return (load * 10f).toInt().coerceIn(1, 10)
    }

    private fun DayMetrics.toSleepData(): SleepData? = sleepDurationMinutes?.let { dur ->
        SleepData(
            dateEpochDay = dateEpochDay,
            durationMinutes = dur,
            // Genuinely nullable — no fabricated 80.0. See SleepData.efficiencyPercent.
            efficiencyPercent = sleepEfficiencyPercent,
            bedtimeMinuteOfDay = bedtimeMinuteOfDay,
            wakeTimeMinuteOfDay = wakeTimeMinuteOfDay,
            remMinutes = sleepRemMinutes ?: 0,
            deepMinutes = sleepDeepMinutes ?: 0,
            lightMinutes = sleepLightMinutes ?: 0,
            awakeMinutes = sleepAwakeMinutes ?: 0,
            stagesAvailable = sleepStagesAvailable ?: false
        )
    }

    private fun DayMetrics.toActivity(): DailyActivityData? = steps?.let { s ->
        DailyActivityData(
            dateEpochDay = dateEpochDay,
            steps = s,
            distanceMeters = distanceMeters ?: 0f,
            caloriesBurned = caloriesBurned ?: 0,
            activeCalories = activeCalories
        )
    }

    private fun DayMetrics.toDaySample(
        scores: DayScores?,
        checkIn: CheckIn?
    ) = AnomalyDetectionEngine.DaySample(
        dateEpochDay = dateEpochDay,
        restingHR = restingHR?.toDouble(),
        sleepMinutes = sleepDurationMinutes?.toDouble(),
        sleepScore = scores?.sleepScore?.toDouble(),
        bedtimeMinuteOfDay = bedtimeMinuteOfDay,
        strain = scores?.strain?.toDouble(),
        hrr1 = scores?.hrr1?.toDouble(),
        steps = steps?.toDouble(),
        recovery = scores?.recoveryScore?.toDouble(),
        readiness = scores?.readinessScore?.toDouble(),
        energyBank = scores?.energyBankScore?.toDouble(),
        checkInEnergy = checkIn?.energy?.toDouble(),
        checkInStress = checkIn?.stress?.toDouble(),
        checkInSoreness = checkIn?.soreness?.toDouble()
    )
}
