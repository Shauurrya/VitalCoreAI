# Data reliability and daily guidance

Scope: roadmap R01, the read-outcome foundation of R02, R04, G01 and G02. The existing visual redesign is preserved. This is a local debug build; no public release has been published.

## Delivered behavior

- Settings' full-history refresh re-reads existing days and reports refreshed, skipped, partially refreshed and failed days. User check-ins, journals and workout annotations remain separate from imported data.
- Health Connect reads distinguish records, successful empty results, denied permission, unsupported records and failures. Failed fields retain cached values with stale markers; stale inputs are excluded from score computation. Cancellation propagates.
- Data Sources checks current availability and permissions on return to the screen, shows per-record outcomes, source apps when known, last successful read and latest known measurement times, and explains the next action. It no longer assumes a particular watch model.
- Ask Coach exposes the five supported local questions, dates and confidence, evidence links and sparse/stale-history explanations. It uses the persisted scores displayed elsewhere in the app and requires no network.
- Home offers a primary focus, activity or rest choice, reasons, confidence and an adjustable sleep target. Activity and sleep actions support saving, completion, dismissal and restoration. Check-in changes to “View/edit check-in” after completion.
- Daily choices are stored independently for each date and survive refreshes. Updated recommendation evidence prompts review without overwriting a saved choice. Selecting an alternative alone does not trigger a changed-guidance warning. Plans do not create workout records.
- Room schema 8 adds read status and recommendation metadata with a non-destructive migration from schema 7.

## Validation

The debug app builds successfully. The local unit-test suite passes (357 tests, as recorded by the completed build), and `lintDebug` completes successfully. The source includes Room migration coverage and instrumentation tests for data outcomes, daily guidance and screen behavior, but those instrumentation tests were not run in this pass: `adb devices` reported no attached emulator or phone. A real navigation and full-restart persistence check therefore remains unverified, as does live Health Connect ingestion on a device.

## Remaining roadmap scope

This batch does not implement change-token deletion reconciliation (R03), calendar-gap chart work (R05), backup/restore (R06), reminder delivery changes (G03), dashboard customization or a general-purpose/cloud chatbot. Live Health Connect ingestion and a physical watch require a separate device pass; synthetic screen and repository tests do not establish upstream watch compatibility.
