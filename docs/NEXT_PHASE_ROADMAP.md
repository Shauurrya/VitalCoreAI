# VitalCore — next-phase project plan

Prepared 23 September 2026 from the current working tree, including the uncommitted UI redesign. This is a proposed roadmap; its tasks have not been implemented by this review.

## Recommended direction

Make VitalCore a dependable daily guide: understand today's signals, choose a realistic action, record what happened, and review progress over time.

The next release should prioritize reliable data, understandable guidance, and a complete daily loop. Preserve local computation and the existing visual design. Add larger planning and intelligence features after their inputs are trustworthy.

The default planning assumption is a balanced product roadmap for an individual using wearable data. Release names below are suggestions, not commitments. Effort estimates describe engineering scope, not delivery dates: **S** = roughly 1–3 focused days, **M** = roughly 4–8 days, **L** = a multi-week feature. Device validation and unfamiliar platform behavior may increase these estimates.

## What already exists

Do not rebuild these as new features:

- Recovery, readiness, sleep, strain, training load, energy bank and fitness-age estimates.
- Personal baselines, confidence indicators, score factors, anomaly detection, readiness forecasts and trend attribution.
- Workout recommendations, workout history/details, RPE, sets/reps logging and muscle recovery.
- Morning check-ins, an 11-habit journal, habit correlations and statistical insight discovery.
- Weekly/monthly reports, achievements, CSV export, onboarding and an optional biometric lock.
- A local coach answer engine, synthetic-data scenarios and a developer screen.
- The redesigned dashboard and shared components, including compact-layout checks.

`docs/UI_REDESIGN_HANDOFF.md` records 298 passing unit tests, a 19-test emulator run, and subsequent focused visual checks. It explicitly does not establish a live Health Connect or physical-watch test. Those are prior recorded results; this planning review did not rerun the app or tests. The older audit and V1.1 plan contain superseded status statements and should not be treated as the current backlog.

## Phase 1 — data users can trust

### R01. Make history refresh do what its label promises — S

**Finding:** `SettingsViewModel.forceBackfill()` calls `backfillHistory()` without `force = true` (`ui/viewmodel/ViewModels.kt:602`). The repository already supports the flag, but its default skips existing days (`data/repository/HealthRepository.kt:180`). Normal sync already repairs gaps and refreshes a trailing window; this task concerns the explicit full-history action.

**Build:** pass the force flag, report refreshed/skipped/failed days, and distinguish unavailable Health Connect from successful completion. Preserve check-ins, journal entries and workout annotations.

**Done when:** a correction to an existing day outside the trailing window appears after the Settings action; a partial failure produces a partial result; running it twice creates no duplicates.

### R02. Distinguish empty data, denied access and failed reads — L

**Finding:** `HealthConnectManager.safeRead()` collapses failures into null/empty results (`data/healthconnect/HealthConnectManager.kt:249`). Daily persistence can then replace previously populated fields (`data/repository/HealthRepository.kt:296`). This makes reliable recovery from transient failures difficult.

**Build:** structured outcomes per record type: successful with data, successful empty, permission denied, unsupported, and failed. Retain last-good cached values on failure, explicitly mark them stale, and ensure score computation does not silently treat stale values as fresh readings. Commit related day updates atomically where appropriate.

**Done when:** revoking one optional permission or simulating one failed read leaves other metrics usable; cached readings remain identifiable as stale; regranting access repairs the affected fields; sync success reflects actual outcomes. Keep cancellation distinct from an ordinary read failure.

### R03. Keep corrections and deletions consistent — L; depends on R02

**Finding:** a day with all data removed returns early without clearing its old cache; old heart-rate rows are replaced only when new samples exist; exercise sessions are upserted without deletion reconciliation (`data/repository/HealthRepository.kt:254`, `:329`, `:360`). Deleted source workouts can therefore remain in local history.

**Build:** preserve Health Connect IDs, origin and modification metadata; process updates and deletions; recalculate affected scores and dependent later windows, recommendations and reports. Handle expired change tokens through a bounded, repeatable reconciliation. An authoritative empty read may remove cached source data; a failed read must not. Preserve user-authored annotations separately and explain orphaned annotations if their source workout disappears.

**Done when:** a corrected or deleted workout/sleep record disappears or changes locally, dependent results update, token expiry recovers, and interruption/retry does not duplicate records. This follows the platform's change-token and deletion model. [Android synchronization guide](https://developer.android.com/health-and-fitness/health-connect/sync-data).

### R04. Repair Data Sources and explain freshness — M; shares R02

**Finding:** `DataSourcesViewModel` derives permission and connection state from `sync_state`, and hardcodes a Galaxy Watch Active2 (`ui/viewmodel/GapViewModels.kt:402`). The production sync path injects the DAO but does not write it. Historical sync rows would not prove current permission even if populated.

**Build:** use live permission/availability checks and persisted per-type read outcomes. Show source app when known, last successful read, latest measurement time, missing fields and a next action. Represent unknown device metadata honestly. Distinguish “read successfully, no new records” from “new watch data arrived.” Check background/history feature availability before offering those capabilities. [Android read-data guide](https://developer.android.com/health-and-fitness/health-connect/read-data).

**Done when:** a successful sync, denied permission, delayed upstream sync and an unsupported metric each produce a distinct, accurate state. Returning from permission settings updates the screen without restarting the app.

### R05. Keep calendar dates in charts, history and forecast inputs — M

**Finding:** score viewmodels remove null readings before charting (`ui/viewmodel/ViewModels.kt:50`, `:74`, `:111`, `:202`, `:237`); line charts use ordinal positions (`ui/components/Charts.kt:110`). The redesign correctly labels these as recorded scores, but calendar gaps and original dates are lost.

**Build:** date/value/quality chart points, visible gaps, date/value selection, and 7/30/90-day windows with coverage counts. Add a day-detail destination reachable from History; show that day's readings, factors, workouts, check-in and journal context. Also retain dates when preparing forecast inputs: `analytics/ScorePipeline.kt:670` drops dates before `analytics/ReadinessForecastEngine.kt:323` calculates changes between consecutive remaining values. Only genuine adjacent-day pairs should inform overnight volatility.

**Done when:** missing Wednesday stays visibly missing between Tuesday and Thursday, a Monday-to-Friday gap is not interpreted as an overnight change, dates remain correct across midnight/timezone changes, a real zero differs from no data, and an accessible text summary conveys the same information.

### R06. Make backup behavior deliberate — M for policy; L for restore

**Finding:** `AndroidManifest.xml` enables backup, the legacy rules are a template, and the modern extraction-rules file is not linked. An app without Internet permission can still participate in Android-managed backup. The current CSV export contains metrics, scores and exercise sessions for a default 90-day window, not a restorable copy of every user entry (`data/export/HealthDataExporter.kt:145`).

**Build:** align cloud-backup and device-transfer rules with the stated local-data promise. Recommended initial policy: explicitly exclude health records from automatic cloud backup and explain supported transfer choices. Add user-initiated, versioned, encrypted backup/restore covering journal entries, check-ins, workout annotations and settings. Keep “rebuild imported cache” distinct from “erase my data”; explain that local deletion does not delete the upstream Health Connect store.

**Done when:** both Android backup-rule formats are verified; a restore round trip preserves user-authored records; malformed/unsupported backups leave existing data intact; restored device-specific sync/permission state is rechecked. Android documents database/preferences inclusion by default and separate modern extraction rules. [Android backup guide](https://developer.android.com/identity/data/autobackup).

## Phase 2 — useful guidance every day

### G01. Ship Ask Coach using the existing local engine — M; after R04

**Existing foundation:** `coach/CoachAnswerEngine.kt:43` answers five question types and produces evidence/follow-ups. `debug/CoachContextFactory.kt:34` assembles context, but ordinary navigation has no Ask Coach screen.

**Build:** move reusable context assembly into production infrastructure; add question chips for low readiness, tiredness, training choice, what to do tonight and weekly review. Show concise answers, the date of the evidence, confidence, and links to supporting readings. Start with the engine's supported questions.

**Done when:** all supported questions work with adequate and sparse histories; explanations match persisted scores; stale or absent evidence is explicit; no network is required.

### G02. Turn Home into a daily action plan — M; after G01

**Existing foundation:** Home already shows a recommendation and a check-in action (`ui/screens/HomeScreen.kt:176`). The check-in action is displayed even after completion. The recommendation engine already returns confidence, rationale and alternatives (`analytics/RecommendationEngine.kt:61`), but the stored/UI representation does not carry all of that information through.

**Build:** one primary focus, a suggested activity/rest choice, a sleep target and a context-aware check-in state. Preserve recommendation confidence and supporting reasons through persistence/context assembly to the screen; provide “Why this today?” and an alternative. Let users save, adjust, complete or dismiss actions. Retain optional access to the detailed metrics and offer dashboard-card preferences after testing the daily plan.

**Done when:** users can identify the next useful action quickly; a completed check-in changes to “View/edit check-in”; provisional guidance is visibly identified when inputs are missing; decisions persist across restart.

### G03. Finish reminders and notification navigation — M

**Findings:** the sync worker can post a “morning” summary on every successful periodic/manual sync (`data/sync/SyncWorker.kt:64`). Destination extras are written (`notifications/NotificationScheduler.kt:189`) but not consumed by `MainActivity`. The check-in reminder preference is collected without a delivery path. Notification coaching also rebuilds inputs separately, including a hardcoded 480-minute sleep target (`data/sync/SyncWorker.kt:94`).

**Build:** shared coach context; once-per-day summary logic; check-in reminders that stop after completion; quiet hours and per-category controls; correct report/check-in destinations on cold start and while running. Preserve the intended destination through onboarding and the app lock. Present background timing as approximate.

**Done when:** repeated syncs do not repeat the same daily notification; disabled reminders stay disabled; notification and in-app advice agree; each tap opens the intended screen after required access checks.

## Phase 3 — progress users can explore and share

### P01. Extend reports with archives, coverage and sharing — M; after R05

Weekly/monthly reports already exist. Add historical period selection, comparable-period coverage, contributors to change, and a user-selected shareable summary/PDF. Show when a comparison is too sparse. Let users preview and omit sensitive fields before exporting. Reuse existing export infrastructure.

**Done when:** an older period can be reopened, sparse weeks are not treated as complete weeks, and the shared result matches the selected period and fields.

### P02. Add a workout planner around existing recommendations — L; after R03, G02

Recommendations already include workout type, intensity, volume adjustment and muscle groups. Add duration, equipment, available days, editable session templates and a weekly calendar. Connect planned sessions to imported workouts or explicit manual completion, then collect RPE/soreness feedback.

**Done when:** a recommended session can be saved, changed and completed; postponed sessions do not inflate recorded training load; duplicate imported sessions cannot complete multiple plans; a lower-readiness day produces an explained adjustment the user can accept.

### P03. Upgrade the journal into personal experiments — L

First correct the foundations: quantity entries are currently averaged by date (`ui/viewmodel/NewFeatureViewModels.kt:189`) and outcomes are paired on identical dates (`analytics/HabitCorrelationEngine.kt:82`). Daily cups/minutes need habit-specific aggregation; evening behaviors may relate to the following night's sleep rather than the sleep already recorded that morning.

Add edit/backdate support, explicit zero versus unlogged, appropriate same-day/next-day alignment, and a single-change experiment with baseline, adherence and review. Use minimum-data and uncertainty rules; do not promise a conclusion after a fixed short duration. Extend the statistical safeguards already present in insight discovery to the journal path where appropriate.

**Done when:** two one-cup entries total two cups, unlogged days are not counted as zero, sleep-day attribution is verified, and noisy or insufficient evidence produces “inconclusive.” Describe associations without claiming a proven cause.

## Phase 4 — improve the intelligence with evidence

### I01. Measure forecast usefulness — M/L; after R05

Store each forecast as issued, its issue/target dates, range, input completeness and calculation version. Compare with the next day's available score using rolling, past-only evaluation; report sample count, error, interval coverage and width. Compare against a simple recent-history baseline. Ensure missing calendar days are not treated as adjacent days when estimating volatility.

This evaluates predictions of VitalCore's own computed score; it does not validate the score as a measure of physiology. Do not label a range “90% confidence” without calibration supporting that meaning.

**Done when:** an old forecast is not overwritten by the outcome it predicted, sparse dates are handled correctly, and evaluation cannot use future inputs.

### I02. Make what-if planning forward-looking — M

The app already has an improvement simulator. Audit every scenario so it changes an input the user can still act on. In particular, today's rest should not be modeled by removing yesterday's completed activity (`analytics/ImprovementSimulator.kt:140`). Correct or temporarily remove that misleading card during the reliability patch; the larger interactive planner can follow here. Connect feasible choices to a clearly labeled future scenario, with assumptions and uncertainty.

**Done when:** scenario changes leave recorded history untouched and show which future input changed. Avoid presenting estimated score changes as promised benefits.

### I03. Learn preferences from user feedback — M/L; after P02

Collect whether guidance was useful, accepted, completed or too demanding. Use this to improve session preferences and presentation. Keep conservative intensity constraints independent of engagement feedback. Any adaptation of recovery estimates should be evaluated separately from whether users followed recommendations.

**Done when:** feedback visibly affects relevant preferences, a reason for changes is available, and personalization can be reset.

## Optional later features

| Idea | Value | Prerequisite / reason to defer |
| --- | --- | --- |
| Home-screen widget / quick check-in | A glanceable daily summary with fewer app opens | Accurate freshness and local-data visibility controls |
| Goal programs | A multi-week consistency or training plan with weekly review | Daily plan and workout planner proven useful first |
| Custom dashboard, light theme and localization | Better fit for user preferences and accessibility | Preserve the current redesign; validate readability and translation coverage |
| Workout route/pace views | Richer workout review | Confirm source records and permission support on the actual device; do not assume routes exist |
| Additional device/source support | More users and richer optional readings | Capability discovery and provenance; optional HRV must not become a required input |
| On-device language model | More flexible phrasing while staying local | Measure model size, supported phones, response quality and battery cost before committing |
| Optional cloud coach | More open-ended conversation | Separate product decision; explicit consent, minimal context, secured credentials, cost controls and deletion policy; deterministic scores stay authoritative |

A general chatbot, social feed, subscriptions, broad nutrition tracking or a new watch app would substantially expand scope. Revisit them after the core daily experience and device compatibility are established.

## Release order and validation

| Milestone | Proposed scope | Exit condition |
| --- | --- | --- |
| Reliability patch | R01, initial R02/R04, backup-policy portion of R06, misleading simulator-card correction from I02 | Refresh, failure states, connection status, scenario wording and backup behavior are truthful |
| Complete data reliability | Finish R02/R03 and R05 | Updates/deletions, gaps and dependent recomputation pass integration checks |
| Daily guidance release | G01–G03 | Ask Coach, completed check-ins and reminders form a coherent daily flow |
| Progress release | Full R06, P01–P03 | Restore, report history, planning and habit interpretation pass end-to-end checks |
| Intelligence experiments | I01–I03, then selected optional features | Evaluation demonstrates usefulness before expanding claims or complexity |

The first implementation batch should be **R01 + R04 + the read-outcome foundation of R02**. R05 and G01 can then progress in parallel once their data contracts are stable. G03 can be developed alongside that work. Do not put every optional idea into the next release.

Validation should target behavior, building on the existing tests:

- Repeat a sync, interrupt it, retry it, revoke/regrant one permission, and introduce a late correction or deletion. Verify database state and the user's explanation.
- Use controlled records in Health Connect to test ingestion, then run an opt-in physical-phone/watch smoke test for actual availability and upstream delay. The [Health Connect Toolbox](https://developer.android.com/health-and-fitness/health-connect/test/health-connect-toolbox) can create/read controlled records; it does not replace a watch test.
- Check same-day versus next-day habit outcomes, gaps in dates, midnight/timezone changes, forecast issue dates and propagation after corrected history.
- Retain compact-layout tests; add TalkBack, large-text and chart-summary checks on the flows being changed.
- Exercise backup/restore and schema migrations with real database fixtures before releasing storage changes.
- Add repeatable CI for build/unit tests/lint and the relevant emulator checks. Existing local test evidence is valuable but does not establish an automated release gate.
- Before wider distribution, validate a signed release build, upgrade path, application identity and provider authorities. The current application ID is `com.example.vitalcoreai`; any identity change needs a deliberate migration/distribution plan for existing installs.

Suggested success measures, initially local or collected through explicit user feedback: sync failure/partial-result rate, unexplained stale readings, time to a useful daily action, completed check-ins, recommendation helpfulness, report use, and successful restore trials. Do not add telemetry merely to measure the roadmap.

## Decisions that can wait

The plan can start without another product decision. Before their relevant milestones, settle: personal-use versus public distribution; preferred training audience and equipment; backup/transfer expectations; and whether a language model adds enough value to justify its device or cloud costs. The proposed default is local deterministic coaching, a small daily plan and selective feature expansion.
