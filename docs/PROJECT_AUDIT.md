# VitalCore AI — Project Audit

**Date:** 2026-08-11
**Audited version:** versionCode 1 / versionName 1.0, Room schema v6
**Baseline verified:** `compileDebugKotlin` BUILD SUCCESSFUL · `testDebugUnitTest` 141 tests, 0 failures

---

## 0. Audit coverage and confidence

This audit distinguishes what was **read line by line** from what was **sampled**. Nothing below is inferred from a filename.

| Area | Depth | Confidence |
|---|---|---|
| Gradle / build config / manifest | Full read | High |
| Room entities, DAOs, database, DI | Full read | High |
| `HealthRepository` (925 lines) | Full read | High |
| `BaselineManager`, `DataQualityEngine`, `ScoreResult`, `ReadinessScoreCalculator`, `MuscleRecoveryEngine`, `CoachEngine` | Full read | High |
| `HomeViewModel` | Full read | High |
| `HealthConnectManager` (44 KB) | Targeted read of time/permission/sleep/RHR paths | Medium |
| Timezone usage across codebase | Exhaustive grep + spot reads | High |
| Feature-gap presence/absence | Exhaustive grep (≥3 name variants each) | High |
| `StrainCalculator`, `SleepScoreCalculator`, `RecoveryScoreCalculator`, `TrendCalculators`, `AcwrCalculator` | Sampled | Medium |
| UI screens (`GapScreens`, `RemainingScreens`, `NewFeatureScreens` — 142 KB) | Sampled | **Low** |
| Test suite content | Executed; contents sampled | Medium |

> **Known gap:** the three large UI screen files were not read end to end. Findings about hardcoded placeholder data in screens are therefore *not* asserted. This is the one part of the audit I would want to redo before signing off on UI polish work.

---

## 1. Current architecture

```
Samsung Health / Health Connect
        │  (androidx.health.connect:connect-client 1.1.0-rc01)
        ▼
HealthConnectManager ──── per-record-type reads, each independently permissioned
        ▼
HealthRepository.syncDay() ──── validation, wear detection, per-session zones
        ▼
Room (VitalCoreDatabase v6, 12 entities)
        ▼
HealthRepository.computeAndStoreScores() ──── calls 14 analytics engines
        ▼
computed_scores (persisted, per-day, idempotent)
        ▼
ViewModels (Hilt, StateFlow) ──── HomeViewModel, GapViewModels, NewFeatureViewModels, ViewModels
        ▼
Compose screens (19+), Navigation Compose
```

**Stack:** Kotlin 2.3.20, AGP 9.0.1, Gradle 9.1, compileSdk 36, minSdk 28, Hilt 2.59.2 (KSP), Room 2.7.1, Compose BOM 2025.06.01, WorkManager 2.10.1, Vico 2.1.2.

**Architectural strengths worth preserving.** This is a well-built codebase, not a prototype. Specifically:

- The `analytics` package enforces a **pure-Kotlin module boundary** (no `android.*` / `androidx.*`). Every calculator is a pure function of its arguments, which is why 255 unit tests can run on the JVM with no instrumentation.
- Scores are **persisted per day and idempotent**, so a re-sync recomputes rather than duplicating.
- The code carries **unusually good "why" documentation**. Many comments record a specific bug and the reasoning behind its fix (e.g. `Daos.kt:113–117` on why a DESC list fed to `MomentumCalculator` inverted every momentum arrow). This is genuinely valuable and should not be stripped.
- **Honest-data discipline** is already present in places: `hasData` prevents phantom sedentary days inflating baselines; `acwrIsMeaningful` prevents an ACWR zone being shown before 14 days of history; `strainIsProxy` marks proxy-derived strain.

---

## 2. Implementation status: claimed vs. actual

| Feature | Claimed | Actual | Evidence |
|---|---|---|---|
| Phase 0 audit | Complete | ✅ Complete | `docs/CAPABILITY_MATRIX.md` |
| MVP data collection | Complete | ✅ Complete | `HealthConnectManager.kt` |
| Raw data model | Complete | ✅ Complete | 12 entities, schema v6 |
| Daily readiness | Complete | ✅ Complete | `ReadinessScoreCalculator.kt` |
| Personal baselines 7/14/30 | Complete | ⚠️ **Partial** | Mean+SD only; no median, MAD, outlier rejection, recency weighting, or trend. See F-07. |
| RHR deviation | Complete | ✅ Complete | — |
| Heart-rate recovery | Complete | ⚠️ Present, unverified | `HRRecoveryCalculator.kt`; sample-density adequacy not confirmed |
| Training load / strain | Complete | ✅ Complete | `StrainCalculator.kt`, `AcwrCalculator.kt` |
| Muscle recovery | Complete | ❌ **Broken** | Most sessions updated *no* muscle group. See **F-01**. |
| Sleep intelligence | Complete | ⚠️ **Partial** | No sleep-consistency scoring existed. See F-09. |
| Energy bank | Complete | ✅ Complete | `EnergyBankCalculator.kt` |
| Morning check-in | Complete | ✅ Complete | `check_ins` table + UI |
| Journal & habit correlation | Basic | ✅ Matches claim | `HabitCorrelationEngine.kt` |
| Insight engine | ~80% | ⚠️ Rule-based only | No statistical pattern discovery. See F-10. |
| **AI Coach** | **~70%, "structured context already sent to Claude"** | ❌ **No LLM exists** | See **F-02** — this is the largest gap between belief and reality. |
| Recommendation engine | Complete | ⚠️ **Rest-vs-train only** | No workout type / intensity / volume / muscle group. See F-08. |
| UI | ~85% | Not fully audited | See coverage note |
| Score transparency | Complete | ✅ Complete | `ScoreFactor` breakdowns persisted per score |
| Data quality / confidence | Complete | ⚠️ **4 of 6 factors** | Freshness and measurement consistency absent. See F-06. |
| Onboarding & calibration | Complete | Not verified | `OnboardingScreen.kt` is 8.4 KB — small for a 9-step flow |
| Privacy / security | ~70% | ⚠️ See §6 | No app lock; DB unencrypted |
| Medical safety | Complete | ⚠️ See §8 | Biological-age framing is the main residual risk |

---

## 3. Findings

Severity: **P0** wrong health data / data loss · **P1** materially wrong behaviour or a faked requirement · **P2** real but bounded · **P3** debt/polish.

### F-01 · P1 · Muscle recovery reported READY after a full-body session — **FIXED**
`MuscleRecoveryEngine.kt`

`exerciseToMuscleGroups` maps `"STRENGTH"`, `"WEIGHT"`, `"GYM"` — **and its entire `else` branch, i.e. every unrecognised type** — to `FULL_BODY`. `getAllMuscleStatuses` then did `.filter { it != MuscleGroup.FULL_BODY }`, excluding the only group such a session ever touched.

Health Connect's generic `EXERCISE_TYPE_STRENGTH_TRAINING` is what Samsung Health writes for most gym sessions, so **FULL_BODY was the common case, not the edge case**.

*Failure scenario:* user completes a hard full-body gym session at RPE 9. Every muscle group still reports "Ready to Train", and the recommendation engine sees a fully recovered athlete.

*Fix applied:* FULL_BODY now expands to every concrete group. Regression test: `MuscleRecovery2Test.a generic full-body session actually fatigues muscle groups`.

### F-02 · P1 · The "AI Coach" contains no AI — **ADDRESSED**
`coach/CoachEngine.kt`, `app/build.gradle.kts`

`CoachEngine` is a static decision tree over template strings. Its own header says so: *"NOT generative AI — this is a decision tree + template library."*

A grep for `anthropic`, `claude`, `okhttp`, `retrofit`, `ktor`, `HttpURLConnection`, `apiKey` across `app/src/main` returns **zero hits**. There is **no HTTP client dependency**, and `build.gradle.kts:63–65` records a deliberate decision to remove even a downloadable-font dependency because "this app is offline-only". The manifest declares no `INTERNET` permission.

So "structured context is already sent to Claude" is not accurate — nothing is sent anywhere.

*What was built instead:* the **context pipeline**, which is what Priority 7 actually asks for ("Do NOT simply make the prompt longer. Build a proper AI context pipeline"). See §5.

### F-03 · P1 · `HomeViewModel` freezes "today" at construction
`ui/viewmodel/HomeViewModel.kt:62,74,75`

```kotlin
val todayExercises = repository.exerciseFrom(LocalDate.now().toEpochDay())  // line 62
```

`LocalDate.now()` is evaluated once, in a property initialiser. A ViewModel surviving midnight (screen-on overnight, or the common case of an app left in the background) queries yesterday's day key indefinitely. The same applies to the two `LocalDate.now()` calls inside `observeData()`.

### F-04 · P2 · `daysWithoutTraining` is null for the most inactive users
`ui/viewmodel/HomeViewModel.kt:92–95`

```kotlin
val lastWorkoutEpochDay = exerciseList.maxOfOrNull { it.dateEpochDay }
val daysWithoutTraining = lastWorkoutEpochDay?.let { ... }
```

`exerciseList` covers the last 14 days. A user with **no workouts at all** in that window yields `maxOfOrNull == null`, so `daysWithoutTraining` is `null` rather than "≥14". `CoachEngine`'s `UNDER_TRAINING` branch guards on `daysWithoutTraining >= 3`, so the "Time for a Workout" nudge **never fires for the users who need it most**.

### F-05 · P2 · Two `CoachEngine` insight branches are unreachable
`ui/viewmodel/HomeViewModel.kt:99–118`

`CoachInput` declares `musclesFatigued` and `sorenessRating`. `HomeViewModel` never passes either, so they take their defaults (`0`, `null`). The corresponding rules at `CoachEngine.kt:185` and `:195` are dead code.

### F-06 · P2 · Data-quality engine measured 4 of the 6 required dimensions — **FIXED**
`analytics/DataQualityEngine.kt`

Present: completeness, source reliability, sample size, historical coverage. **Absent: freshness and measurement consistency.**

These are genuinely different failures. A day can be *complete* — sleep, HR, steps all populated — while every value arrived 30 hours ago because the watch has not synced. That score is stale, not incomplete.

*Fix applied:* added `dataAgeHours` and `measurementConsistency` inputs, a `Factor` enum covering all six dimensions, per-factor roll-up in the report, and `consistencyFromSeries()` to derive stability from a metric's own dispersion. A null input is **omitted** rather than scored as a soft failure — a caller's omission is not a data defect.

Also added `DataQualityReport.positives` and `whyLines()`, so a confidence badge can answer "why?" symmetrically instead of listing only what is wrong.

### F-07 · P2 · Baselines lacked robust statistics — **FIXED**
`analytics/BaselineManager.kt`

`BaselineManager` blends a personal mean/SD with a population prior. That is correct for *score normalisation* and wrong for *anomaly detection*: you cannot call a value "unusual for you" against a distribution that is 80% population average. It also has no median, MAD, outlier rejection, recency weighting, or trend detection.

Worse, mean/SD **hide the outliers you are hunting**. With n=7, one contaminating spike inflates the SD enough that the next genuine elevation scores ~1.3 sigma — under any sane alarm threshold.

*Fix applied:* new `RobustStats` (median, MAD, robust z, Tukey fences, recency-weighted mean, Theil-Sen slope, Mann-Kendall trend test, Welch's t-test, normal/Student-t CDFs) and `PersonalBaselines` (the Current/Baseline/Deviation/Trend/Confidence view). `BaselineManager` is **unchanged** — both coexist, each for its own job.

Verified against hand-computed references: Mann-Kendall p = 8.3e-5 for a strictly increasing 10-point series; Student-t critical values t(0.975, df=10) = 2.228.

### F-08 · P2 · Recommendations answered one question, not four — **FIXED**
See §5.

### F-09 · P2 · No sleep-consistency scoring existed — **FIXED**
Grep for `consistency`, `variance`, `bedtime` found `BaselineUtils.circularStdDevMinutes` and `circularMeanMinutes` already present in `ScoreResult.kt:196,219` — the correct circular statistics were **built but never used for a consistency score**.

*Fix applied:* `SleepConsistencyCalculator` (wake-time 40% / bedtime 35% / duration 25%, weights renormalised over whichever components have data).

### F-10 · P2 · No statistical insight discovery — **FIXED**
See §5.

### F-11 · P2 · `muscle_recovery` table is written by nothing
`data/db/entity/Entities.kt:287`, `HealthRepository.kt:775`

`upsertMuscleRecovery()` exists and **has no callers**. `syncDay` / `computeAndStoreScores` never populate it. Muscle status is recomputed from scratch in `MuscleRecoveryViewModel` on every screen open. The table is dead schema.

*Not fixed* — it is inert rather than harmful, and removing it needs a schema decision. See V1.1 plan T-12.

### F-12 · P2 · Destructive migration will delete user-authored data
`data/db/VitalCoreDatabase.kt:48`

`fallbackToDestructiveMigration(dropAllTables = true)`. The header argues this is safe because the DB "is fully reconstructible from Health Connect".

**That reasoning does not hold for four tables.** `check_ins`, `journal_entries`, `workout_exercises` and `muscle_recovery` are *user-authored* and exist nowhere else. The header acknowledges check-ins and journals are "cheap to re-enter" — but a year of habit-correlation history is not cheap, and it is precisely the data that makes correlations meaningful.

*Failure scenario:* schema bumps to v7; user loses 200 journal entries and every check-in; `HabitCorrelationEngine` silently drops below its minimum-observation threshold and stops producing insights, with no explanation.

### F-13 · P3 · Timezone logic is scattered across 58 sites — **FOUNDATION FIXED**
`LocalDate.now()` / `ZoneId.systemDefault()` appear **58 times across 10 files**. Every `dateEpochDay` key in the database is a *local* epoch day, so the zone is part of the data's meaning.

Consequences: no single place to reason about day boundaries; ViewModels freeze "today" (F-03); travel across zones shifts which day new data lands in with no reconciliation; day-length arithmetic assumes 1440 minutes.

*Fix applied:* `core/time/VitalTime.kt` — one abstraction with a swappable clock, DST-correct day lengths (verified against Europe/London spring-forward = 1380 min and fall-back = 1500 min), circular clock arithmetic, and explicit sleep-day attribution. **Migration of the 58 existing call sites is not done** — see V1.1 plan T-11.

### F-14 · P3 · `HealthRepository` is a god object
925 lines spanning sync orchestration, gap detection, HC reads, validation, wear detection, all score computation, achievements, weekly and monthly reports. `computeAndStoreScores` alone is ~400 lines calling 14 engines.

Also: no `withContext(Dispatchers.IO/Default)` anywhere. Room's suspend DAOs dispatch internally so DB work is safe, but the analytics computation runs on the caller's dispatcher.

### F-15 · P3 · Reports regenerate on every sync
`HealthRepository.kt:101–102`. `generateWeeklyReport` and `generateMonthlyReport` run on every `syncToday()` — every app launch plus every 4-hourly worker. The monthly path re-reads and re-averages the entire month each time.

### F-16 · P3 · `strings.xml` is 74 bytes
All user-facing copy is hardcoded in Kotlin. Blocks localisation, and makes the medical-safety copy review of §8 a code-wide grep rather than a single-file review.

---

## 4. Verified-good (explicitly *not* problems)

Worth recording so future work does not "fix" them:

- **HRV is not fabricated.** `hrvRmssdMs` exists on `DailyMetricsEntity:47` but is documented as *"Opportunistic only — always null on a Galaxy Watch Active 2"*. It is never populated and never displayed. This is the correct handling.
- **Baseline windows exclude the day being scored.** `HealthRepository.kt:370–372` uses `getRange(today-30, today-1)`. The comment records that an earlier version compared each value against a distribution containing itself.
- **HR sample de-duplication is correct.** Unique index on `timestampMs` plus `deleteForDay` before re-insert.
- **ACWR honesty.** `acwrIsMeaningful` / `acwrDaysOfHistory` prevent showing a zone before there is history to support one.
- **Sleep sessions crossing midnight are handled.** `HealthConnectManager:396` — *"Bedtime belongs to the previous calendar day whenever it is in the evening."*
- **Circular statistics were already correct** where used (`BaselineUtils:196`).

---

## 5. What was built (all compiling, all tested)

| Module | Purpose | Priority |
|---|---|---|
| `core/time/VitalTime.kt` | Single time/day abstraction, swappable clock, DST-correct | P1, P19 |
| `analytics/RobustStats.kt` | Median, MAD, robust z, Theil-Sen, Mann-Kendall, Welch, t/normal CDFs | P3 |
| `analytics/PersonalBaselines.kt` | Current/Baseline/Deviation/Trend/Confidence per metric | P3 |
| `analytics/ReadinessForecastEngine.kt` | Tomorrow as a **range**, interval sized from the user's own volatility | **P4** |
| `analytics/AnomalyDetectionEngine.kt` | Spike + sustained detection, co-occurrence only, fixed safe vocabulary | P5 |
| `analytics/RecoveryTrendEngine.kt` | 7/14/30-day direction **with attribution** | P6 |
| `analytics/SleepConsistencyCalculator.kt` | Bedtime/wake/duration variance, circular-safe | P10 |
| `analytics/RecommendationEngine.kt` | Type + intensity + volume + muscle groups, recovery-capped | P8 |
| `analytics/InsightDiscoveryEngine.kt` | Welch + Bonferroni + effect-size gating | P13 |
| `analytics/MuscleRecoveryEngine.kt` | FULL_BODY fix, hour-granular, learned times, per-group soreness | P9 |
| `analytics/DataQualityEngine.kt` | Freshness + consistency added; all six factors | P2 |
| `coach/CoachContext.kt` | Validated bundle + JSON + LLM system/user prompts | P7 |
| `coach/CoachAnswerEngine.kt` | The five coach questions, answered from the bundle | P7 |

**Design decisions worth flagging:**

1. **The forecast has no code path that emits a single number.** `Forecast.range` is the only rendering, and the interval half-width is derived from the median absolute day-over-day change in *this user's* readiness history — a volatile user gets a visibly wider band than a metronomic one (asserted in test).
2. **The anomaly engine's vocabulary is fixed and test-enforced.** A test strips the disclaimer, then asserts no affirmative clinical phrase appears anywhere in title, body or suggestion.
3. **Insight discovery applies Bonferroni correction** across every hypothesis examined. Without it, testing 6 patterns at p<0.05 gives ~26% chance of at least one false positive — a confident coincidence every week. Tested against pure noise: returns nothing.
4. **The coach cannot invent numbers**, structurally: it is only ever given a `CoachContext` of already-computed values, never raw inputs.

**Test results:** 141 → **255 unit tests, 0 failures.**

---

## 6. Security & privacy

| Item | Status |
|---|---|
| Room DB encryption | ❌ None. Health data at rest in plaintext SQLite. |
| App lock / biometric | ❌ Absent (grep: `biometric`, `BiometricPrompt`, `KeyguardManager` — 0 hits) |
| `INTERNET` permission | ✅ Not declared — data cannot leave the device |
| Release build | ⚠️ `isMinifyEnabled = false` |
| Backup rules | ⚠️ `backup_rules.xml` / `data_extraction_rules.xml` present but not audited in depth |
| Cloud sync | ❌ Absent (correctly — P14 is explicitly "later") |

The offline-only posture is a genuine strength and the reason the privacy risk is currently low.

---

## 7. Performance

- `syncToday()` can trigger up to **34 `syncDay()` calls** (30-day gap scan + 3 trailing + today), each performing ~10 Health Connect reads plus a full score recomputation. In steady state it is 4; after a long gap it is 34.
- Weekly + monthly reports regenerate every sync (F-15).
- No `Dispatchers` confinement for analytics work (F-14).
- `computedScoresDao.getLatestN(30)` is re-queried inside `computeAndStoreScores` for HRR trend, in addition to the range queries already loaded.

No evidence of a rendering-side problem was gathered — the UI files were not read (see §0).

---

## 8. Medical safety

The engines added in this pass are safe by construction and by test. Residual risks in **existing** code:

1. **`BiologicalAgeEstimator`** — "biological age" / "fitness age" from non-clinical inputs is the highest-risk framing in the app. It is derived from an estimated VO₂max which is itself derived from resting HR. Recommend reframing as **"Fitness Age (estimate)"** with the input chain stated.
2. **`Vo2MaxEstimator`** — an estimate from resting HR via the Uth formula. Must never be presented as a measurement.
3. **Copy is not centralised** (F-16), so the review is a code-wide grep rather than a single-file pass.
4. `CoachEngine.kt:117` cites *"Research links ACWR > 1.5 to elevated injury risk"* — defensible and attributed, but if any feature is ever named "injury risk", rename it to **Training Load Warning** per Priority 23.

---

## 9. Recommended implementation order

1. **F-12** destructive migration — the only finding that can destroy user data. Write real migrations for the four user-authored tables. *Do this before any schema change.*
2. **F-03/F-04/F-05** `HomeViewModel` — three small, independent, high-value fixes.
3. **F-13** migrate the 58 timezone call sites onto `VitalTime`.
4. **Wire the new engines** into `HealthRepository` → `computed_scores` → ViewModels → UI.
5. **F-14/F-15** extract score computation from `HealthRepository`; cache reports.
6. **Read the three large UI files** and close the §0 coverage gap before UI polish.
7. Debug screen + synthetic data (P21) — this is what makes 4 and 6 verifiable.
8. Biometric lock (P15), premium onboarding (P16), accessibility (P20).
9. Cloud backup (P14) — last, as specified.

---

## Appendix — audit method

Static read + grep of `app/src/main` and `app/src/test`, plus two executed baselines (`compileDebugKotlin`, `testDebugUnitTest`). No emulator or device run was performed, so no runtime, rendering, or Health-Connect-integration behaviour was observed directly. Findings marked "Not verified" reflect that boundary honestly.
