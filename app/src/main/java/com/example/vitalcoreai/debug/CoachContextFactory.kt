package com.example.vitalcoreai.debug

import android.content.Context
import com.example.vitalcoreai.analytics.MuscleRecoveryEngine
import com.example.vitalcoreai.coach.CoachContext
import com.example.vitalcoreai.core.time.VitalTime
import com.example.vitalcoreai.data.UserPrefs
import com.example.vitalcoreai.data.db.dao.CheckInDao
import com.example.vitalcoreai.data.db.dao.DailyMetricsDao
import com.example.vitalcoreai.data.repository.HealthRepository

/**
 * Assembles a [CoachContext] from real data.
 *
 * The handoff describes `CoachContext` as "LLM-ready", and it is — but nothing in the app
 * built one from the database, so the claim was untested. This is the missing half: it runs
 * the pipeline for a day and maps its typed results onto the blocks, which means the debug
 * screen's AI-context preview shows the exact payload an external model would receive rather
 * than a hand-written sample.
 *
 * Nothing here sends anything anywhere. The app declares no INTERNET permission and has no
 * HTTP client in its dependency tree; that stays true in v1.1 by design.
 */
class CoachContextFactory(
    private val repository: HealthRepository,
    private val dailyMetricsDao: DailyMetricsDao,
    private val checkInDao: CheckInDao,
    private val context: Context
) {

    /** Below this many days of history the app describes itself as still calibrating. */
    private val calibrationDays = 14

    suspend fun build(today: Long = VitalTime.todayEpochDay()): CoachContext? {
        val output = repository.runPipeline(today) ?: return null
        val metrics = dailyMetricsDao.getForDay(today)
        val checkIn = checkInDao.getForDay(today)
        val daysOfHistory = repository.daysOfHistory()

        val journalEntries = runCatching { repository.getJournalForDay(today) }.getOrDefault(emptyList())
        val trackedHabits = runCatching { repository.getAllTrackedHabitIds() }.getOrDefault(emptyList())

        return CoachContext(
            today = CoachContext.TodayBlock(
                dateEpochDay = today,
                dayOfWeek = VitalTime.dayOfWeek(today).name,
                userName = UserPrefs.userName(context),
                daysOfHistory = daysOfHistory,
                isCalibrating = daysOfHistory < calibrationDays
            ),
            readiness = output.readinessScore?.let { score ->
                CoachContext.ScoreBlock(
                    value = score,
                    label = readinessLabel(score),
                    confidence = output.readinessConfidence ?: "LOW",
                    explanation = output.readinessExplanation,
                    breakdown = com.example.vitalcoreai.analytics
                        .decodeScoreFactors(output.readinessBreakdown)
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
            sleep = metrics?.let {
                CoachContext.SleepBlock(
                    durationMinutes = it.sleepDurationMinutes,
                    score = output.sleepScore,
                    debtMinutes = output.sleepDebtMinutes,
                    consistencyScore = output.sleepConsistencyScore,
                    consistencyLabel = output.sleepConsistencyLabel ?: "Not enough nights",
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
                    trend = output.trend14Direction ?: "INSUFFICIENT_DATA",
                    confidence = output.dataQualityLevel ?: "LOW"
                )
            },
            trainingLoad = CoachContext.TrainingLoadBlock(
                strain = output.strain,
                strainZone = output.strainZone,
                acwr = output.acwr,
                acwrZone = output.acwrZone,
                acwrIsMeaningful = output.acwrIsMeaningful ?: false,
                consecutiveTrainingDays = output.consecutiveTrainingDays,
                daysSinceLastWorkout = output.daysSinceLastWorkout
            ),
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
            energy = output.energyBankScore?.let {
                CoachContext.ScoreBlock(
                    value = it,
                    label = energyLabel(it),
                    confidence = output.dataQualityLevel ?: "LOW",
                    explanation = output.energyBankExplanation,
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
            trends = output.trends.filter { it.isMeaningful }.map { t ->
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
            forecast = output.forecast?.takeIf { it.available }?.let { f ->
                CoachContext.ForecastBlock(
                    low = f.low,
                    high = f.high,
                    confidence = f.confidence.name,
                    positiveDrivers = f.drivers.filter { it.isPositive }.map { it.description },
                    negativeDrivers = f.drivers.filterNot { it.isPositive }.map { it.description },
                    risks = f.risks,
                    opportunities = f.opportunities
                )
            },
            recommendation = output.recommendation?.let { r ->
                CoachContext.RecommendationBlock(
                    type = r.primaryType.displayName,
                    intensity = r.intensity.displayName,
                    volumeAdjustmentPercent = r.volumeAdjustmentPercent,
                    readyRegions = r.readyRegions.map { it.displayName },
                    avoidRegions = r.avoidRegions.map { it.displayName },
                    rationale = r.rationale,
                    recoveryActions = r.recoveryActions
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
                overall = output.dataQuality.level.name,
                percent = output.dataQuality.confidencePercent,
                present = output.dataQuality.positives,
                missing = output.dataQuality.reasons,
                factors = output.dataQuality.factors
            )
        )
    }

    private fun signed(v: Int) = if (v >= 0) "+$v" else "$v"

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
