package com.example.vitalcoreai.coach

import android.content.Context
import com.example.vitalcoreai.analytics.MuscleRecoveryEngine
import com.example.vitalcoreai.analytics.RecommendationEngine
import com.example.vitalcoreai.analytics.RecoveryTrendEngine
import com.example.vitalcoreai.analytics.ScorePipeline
import com.example.vitalcoreai.analytics.SleepDebtCalculator
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.db.dao.CheckInDao
import com.example.vitalcoreai.data.db.dao.DailyMetricsDao
import com.example.vitalcoreai.data.repository.HealthRepository
import com.example.vitalcoreai.data.model.freshForScoring
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Production context for local coaching. Displayed scores and the training choice come
 * from the same persisted row as Home; typed history details come from the local pipeline.
 * A day without readings still returns context so every question can explain the gap.
 */
class CoachContextFactory @Inject constructor(
    private val repository: HealthRepository,
    private val dailyMetricsDao: DailyMetricsDao,
    private val checkInDao: CheckInDao,
    @param:ApplicationContext private val context: Context
) {

    /** Below this many days of history the app describes itself as still calibrating. */
    private val calibrationDays = 14

    suspend fun build(today: Long = VitalTime.todayEpochDay()): CoachContext {
        val output = repository.runPipeline(
            today, UserPrefs.age(context), UserPrefs.maxHR(context),
            overnightHRGapHours = 0.0, todayHrPoints = emptyList()
        ) ?: ScorePipeline.Output()
        val cachedMetrics = dailyMetricsDao.getForDay(today)
        val metrics = cachedMetrics?.freshForScoring()
        val scoreHistory = repository.scoresFrom(today - 29).first()
            .filter { it.dateEpochDay in (today - 29)..today }.associateBy { it.dateEpochDay }
        val scores = scoreHistory[today]
        val weekScores = scoreHistory.values
            .count { it.dateEpochDay in (today - 6)..today && it.readinessScore != null }
        val metricHistory = dailyMetricsDao.getRange(today - 29, today)
            .associate { it.dateEpochDay to it.freshForScoring() }
        // An ephemeral pipeline run can use a different HR sample set than the saved
        // score. Trends describe the scores the user can actually inspect in History.
        // Include calendar gaps so a seven-day review cannot reach into an earlier week.
        val trends = RecoveryTrendEngine.analyseAll((today - 29..today).map { day ->
            val dayScores = scoreHistory[day]
            val dayMetrics = metricHistory[day]
            RecoveryTrendEngine.DayInput(
                dateEpochDay = day,
                recoveryScore = dayScores?.recoveryScore,
                readinessScore = dayScores?.readinessScore,
                sleepMinutes = dayMetrics?.sleepDurationMinutes,
                bedtimeMinuteOfDay = dayMetrics?.bedtimeMinuteOfDay,
                wakeTimeMinuteOfDay = dayMetrics?.wakeTimeMinuteOfDay,
                restingHR = dayMetrics?.restingHR,
                strain = dayScores?.strain,
                hadHighLoad = (dayScores?.trainingLoadNormalized ?: 0f) > SleepDebtCalculator.HIGH_LOAD_THRESHOLD
            )
        })
        val checkIn = checkInDao.getForDay(today)
        val daysOfHistory = repository.daysOfHistory()

        val journalEntries = repository.getJournalForDay(today)
        val trackedHabits = repository.getAllTrackedHabitIds()

        return CoachContext(
            today = CoachContext.TodayBlock(
                dateEpochDay = today,
                dayOfWeek = VitalTime.dayOfWeek(today).name,
                userName = UserPrefs.userName(context),
                daysOfHistory = daysOfHistory,
                isCalibrating = daysOfHistory < calibrationDays
            ),
            readiness = scores?.readinessScore?.let { score ->
                CoachContext.ScoreBlock(
                    value = score,
                    label = readinessLabel(score),
                    confidence = scores.readinessConfidence ?: "LOW",
                    explanation = scores.readinessExplanation,
                    breakdown = com.example.vitalcoreai.analytics
                        .decodeScoreFactors(scores.readinessBreakdown)
                        .map {
                            CoachContext.FactorBlock(
                                name = it.name,
                                contributionPercent = it.contribution,
                                rawValue = it.rawValue,
                                subScore = it.score,
                                description = it.description
                            )
                        },
                    // Deviation from the user's own recent average, not from a population
                    // norm — the coach must never imply a comparison to other people.
                    vsBaseline = null
                )
            },
            sleep = metrics?.takeIf { it.sleepDurationMinutes != null }?.let {
                CoachContext.SleepBlock(
                    durationMinutes = it.sleepDurationMinutes,
                    score = scores?.sleepScore,
                    debtMinutes = output.sleepDebtMinutes,
                    consistencyScore = scores?.sleepConsistencyScore,
                    consistencyLabel = scores?.sleepConsistencyLabel ?: "Not enough nights",
                    typicalBedtime = output.sleepConsistency?.typicalBedtime?.let(VitalTime::formatClock),
                    typicalWakeTime = output.sleepConsistency?.typicalWakeTime?.let(VitalTime::formatClock),
                    stagesAvailable = it.sleepStagesAvailable ?: false
                )
            },
            restingHR = metrics?.restingHR?.let { bpm ->
                val baseline = output.restingHRBaselineBpm
                CoachContext.MetricBlock(
                    name = "Resting HR",
                    current = "$bpm BPM",
                    baseline = baseline?.let { "${it.toInt()} BPM" } ?: "—",
                    deviation = baseline?.let { signed(bpm - it.toInt()) + " BPM" } ?: "—",
                    trend = "INSUFFICIENT_DATA",
                    confidence = output.dataQualityLevel ?: "LOW"
                )
            },
            trainingLoad = scores?.takeIf { it.strain != null || it.acwr != null }?.let { CoachContext.TrainingLoadBlock(
                strain = it.strain,
                strainZone = it.strainZone,
                acwr = it.acwr,
                acwrZone = it.acwrZone,
                acwrIsMeaningful = it.acwrIsMeaningful ?: false,
                consecutiveTrainingDays = output.consecutiveTrainingDays,
                daysSinceLastWorkout = output.daysSinceLastWorkout
            ) },
            muscleRecovery = output.muscleStatuses.takeIf { it.isNotEmpty() }?.let { statuses ->
                CoachContext.MuscleBlock(
                    ready = statuses.filter { it.status == MuscleRecoveryEngine.RecoveryStatus.READY }
                        .map { it.group.displayName },
                    recovering = statuses.filter { it.status == MuscleRecoveryEngine.RecoveryStatus.RECOVERING }
                        .map { it.group.displayName },
                    fatigued = statuses.filter { it.status == MuscleRecoveryEngine.RecoveryStatus.FATIGUED }
                        .map { it.group.displayName },
                    learnedEstimates = output.learnedRecovery.values
                        .filter { it.isReliable }
                        .map { MuscleRecoveryEngine.describeLearned(it) }
                )
            },
            energy = scores?.energyBankScore?.let {
                CoachContext.ScoreBlock(
                    value = it,
                    label = energyLabel(it),
                    confidence = scores.dataQualityLevel ?: "LOW",
                    explanation = scores.energyBankExplanation,
                    breakdown = emptyList(),
                    vsBaseline = null
                )
            },
            journal = CoachContext.JournalBlock(
                trackedHabits = trackedHabits,
                todayEntries = journalEntries.map { "${it.habitId} ${it.value}${it.unit}" },
                checkInEnergy = checkIn?.energy,
                checkInStress = checkIn?.stress,
                checkInSoreness = checkIn?.soreness,
                checkInMood = checkIn?.mood
            ),
            trends = trends.filter { it.isMeaningful }.map { t ->
                CoachContext.TrendBlock(
                    window = t.window.label,
                    direction = t.direction.name,
                    averageEarlier = t.averageEarlier,
                    averageRecent = t.averageRecent,
                    contributors = t.contributors.map { it.description },
                    confidence = t.confidence.name
                )
            },
            anomalies = output.anomalies.map { a ->
                CoachContext.AnomalyBlock(
                    metric = a.metric.displayName,
                    severity = a.severity.name,
                    title = a.title,
                    detail = a.body,
                    consecutiveDays = a.consecutiveDays
                )
            },
            forecast = scores?.takeIf { it.forecastLow != null && it.forecastHigh != null }?.let { f ->
                CoachContext.ForecastBlock(
                    low = requireNotNull(f.forecastLow),
                    high = requireNotNull(f.forecastHigh),
                    confidence = f.forecastConfidence ?: "LOW",
                    positiveDrivers = ScorePipeline.decodeDrivers(f.forecastDrivers).filter { it.points > 0 }.map { it.description },
                    negativeDrivers = ScorePipeline.decodeDrivers(f.forecastDrivers).filter { it.points < 0 }.map { it.description },
                    risks = ScorePipeline.decodeTextList(f.forecastRisks),
                    opportunities = output.forecast?.opportunities.orEmpty()
                )
            },
            recommendation = scores?.takeIf { it.recommendationType != null }?.let { r ->
                CoachContext.RecommendationBlock(
                    type = workoutName(r.recommendationType),
                    intensity = r.recommendationIntensity?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Low",
                    volumeAdjustmentPercent = r.recommendationVolumePct ?: 0,
                    readyRegions = emptyList(),
                    avoidRegions = emptyList(),
                    rationale = ScorePipeline.decodeTextList(r.recommendationRationale)
                        .ifEmpty { listOfNotNull(r.recommendationDetail) },
                    recoveryActions = output.recommendation?.recoveryActions.orEmpty(),
                    confidence = r.recommendationConfidence ?: "LOW",
                    alternative = r.recommendationAlternative?.let(::workoutName)
                )
            },
            insights = output.insights.map { i ->
                CoachContext.InsightBlock(
                    title = i.title,
                    body = i.body,
                    strength = i.strength.name,
                    observations = i.observations
                )
            },
            confidence = CoachContext.ConfidenceBlock(
                overall = scores?.dataQualityLevel ?: "LOW",
                percent = scores?.dataQualityPercent ?: 0,
                present = ScorePipeline.decodeTextList(scores?.dataQualityPositives),
                missing = output.dataQuality.reasons.ifEmpty {
                    if (scores == null) listOf("No scored readings for this date") else emptyList()
                },
                factors = ScorePipeline.decodeQualityFactors(scores?.dataQualityFactors)
            ),
            evidence = CoachContext.EvidenceBlock(
                dateEpochDay = today,
                latestMeasurementMs = cachedMetrics?.newestRecordTimestampMs,
                staleMetrics = cachedMetrics?.staleRecordTypes.orEmpty().split('|')
                    .filter { it.isNotBlank() }.map { it.removeSuffix("Record").replace(Regex("([a-z])([A-Z])"), "$1 $2") },
                scoredDaysThisWeek = weekScores
            )
        )
    }

    private fun signed(v: Int) = if (v >= 0) "+$v" else "$v"

    private fun workoutName(value: String?): String = RecommendationEngine.WorkoutType.entries
        .firstOrNull { it.name == value }?.displayName ?: value.orEmpty()

    private fun readinessLabel(score: Float): String = when {
        score >= 80f -> "High"
        score >= 65f -> "Good"
        score >= 50f -> "Moderate"
        score >= 35f -> "Low"
        else -> "Very low"
    }

    private fun energyLabel(score: Float): String = when {
        score >= 80f -> "High capacity"
        score >= 60f -> "Moderate reserves"
        score >= 40f -> "Running low"
        else -> "Depleted"
    }
}
