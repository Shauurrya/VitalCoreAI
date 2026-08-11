# VitalCore AI — Analytics Module Documentation

> B11: Architecture reference for the VitalCore AI analytics engine.

---

## Overview

VitalCore AI is a **local-only** health analytics application. Every calculation,
baseline, and score is computed on-device from data already stored in Health Connect.
There is no server, no cloud backend, no authentication, and no paid APIs.

---

## Package Boundary — Pure Kotlin Isolation

```
com.example.vitalcoreai.analytics/
```

**Rule:** No `android.*`, `androidx.*`, `Context`, `Activity`, or `Fragment` references
permitted in any file under this package.

**Enforcement:** Lint rule `AnalyticsModuleBoundaryDetector` (see `lint/BoundaryLint.kt`).

**Why:** The analytics engine must be testable as pure Kotlin unit tests without
Robolectric or an Android emulator.

---

## Score Architecture

```
Health Connect (sensor data)
        │
        ▼
  SyncWorker (WorkManager, 4h periodic)
        │
        ▼
  HealthRepository.syncToday()
        │
        ├──▶ DailyMetricsEntity  ──▶ Room (local cache)
        │
        └──▶ Score computation pipeline:
              │
              ├── DataQualityEngine     (A3)  → DataQualityReport
              ├── WearDetector          (A4)  → WearStatus
              ├── BaselineManager       (A5)  → Baseline (blended personal + population)
              │
              ├── RecoveryScoreCalculator    → ScoreResult
              ├── SleepScoreCalculator       → ScoreResult
              ├── ReadinessScoreCalculator   → ScoreResult
              ├── TrendCalculators           → ScoreResult (stress, activity, lifestyle)
              ├── TrainingLoadCalculator     → ScoreResult
              ├── AcwrCalculator             → AcwrResult
              ├── Vo2MaxEstimator            → Vo2MaxEstimate
              ├── BiologicalAgeEstimator     → BiologicalAgeResult
              │
              ├── MomentumCalculator    (B1)  → MomentumReport
              ├── ImprovementSimulator  (B2)  → List<Suggestion>
              └── AchievementEngine     (B7)  → List<Achievement>
```

---

## Formula Reference

| Score                | Primary Method                | Baseline Window | Key Inputs                             |
|----------------------|-------------------------------|-----------------|----------------------------------------|
| Recovery             | Weighted composite (A2)       | 30d RHR, 14d sleep | Sleep efficiency, RHR, consistency, load |
| Sleep                | Weighted composite (A2)       | 7d history      | Duration, stages, consistency, debt    |
| Readiness            | Second-order (from Recovery)  | Inherited       | Recovery + sleep debt + ACWR           |
| Stress               | HR z-score                    | 30d RHR         | Resting HR vs personal baseline        |
| Activity             | Steps z-score + calories      | 30d steps       | Steps, active calories                 |
| Lifestyle            | Sub-score composite           | —               | Consistency + activity + sleep         |
| ACWR                 | Acute (7d) / Chronic (28d)    | 28d training    | Normalised training load per session   |
| VO₂ Max              | Uth et al. formula (±10%)     | —               | RHR / max HR; or pace/HR              |
| Biological Age       | Regression (HUNT 2013)        | 30d             | VO₂ Max, RHR trend, activity days      |
| Weekly Health Score  | Weighted average of dailies   | 7d              | Recovery + Readiness + Sleep + Activity|

---

## Baseline Blending Schedule (A5)

```
Days 1–2:    0%  personal  (100% population default)
Days 3–6:   20–50% personal (linear ramp)
Days 7–13:  50–80% personal (linear ramp)
Days 14–29: 80–100% personal (linear ramp)
Day 30+:   100% personal
```

Population defaults are literature-derived (see `BaselineManager.PopulationDefaults`).

---

## Data Quality Engine (A3)

7-signal evaluation producing `DataQualityReport`:

| Signal                   | Weight | Low threshold          |
|--------------------------|--------|------------------------|
| Sleep history depth      | 25%    | < 3 days               |
| HR history depth         | 20%    | < 3 days               |
| Today's sleep present    | 20%    | absent                 |
| Today's HR present       | 15%    | absent                 |
| Continuous HR density    | 10%    | < 2 pts/hr             |
| Watch worn detection     | 5%     | gap > 2h overnight     |
| Day coverage fraction    | 5%     | < 70% of day           |

Score ≥ 75 → HIGH · 40–74 → MEDIUM · < 40 → LOW

---

## Coach Engine (Rule-Based, Not Generative)

The Coach is a rule-tree consuming `CoachTrigger` flags from `ScoreResult.coachTriggers`.
Each trigger maps to a specific, canned, fact-based insight. No LLM, no generative AI.

Trigger → Insight mapping lives in `CoachEngine.kt` (B13).

---

## Excluded Data Types

The following Health Connect record types are **explicitly excluded** from the data
model and will never be used in any calculation, UI display, or export:

- `HeartRateVariabilityRmssdRecord` (HRV)
- `SkinTemperatureRecord`

These types are excluded because they are not reliably available on the Galaxy Watch
Active 2 / Fit 3 target devices.

---

## Room Schema — Version History

| Version | Change                                                        |
|---------|---------------------------------------------------------------|
| 1       | Initial schema                                                |
| 2       | + achievements, sync_state tables; momentum + quality columns |

Migration strategy: `fallbackToDestructiveMigration(true)` — safe because the database
is a full re-creatable cache of Health Connect data.

---

## Extension Points

| Item                      | How to extend                                          |
|---------------------------|--------------------------------------------------------|
| New data source (Garmin)  | Implement `HealthDataSource` interface (B12)           |
| New baseline horizon (90d)| Add `DAYS_90` to `BaselineManager.BaselineHorizon`     |
| New coach rule            | Add trigger to `CoachTrigger` enum; add rule to CoachEngine |
| New achievement           | Add to `AchievementEngine.AchievementId` and `ALL_ACHIEVEMENTS` |
| New score component       | Return new `ScoreFactor` from existing calculator      |

---

*Last updated: Section A + B implementation (2026-07-26)*
