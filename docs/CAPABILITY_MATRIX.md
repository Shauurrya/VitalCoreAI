# Phase 0 — Galaxy Watch Active 2 Technical Feasibility Audit

**Device:** Samsung Galaxy Watch Active 2 (Tizen-based wearable OS)
**Companion app:** VitalCore AI (Android phone app)
**Audit date:** August 2026
**Method:** Samsung Developer documentation, Android Health Connect documentation,
and Samsung developer-forum reports. Nothing below is assumed — where the evidence
is contested it is marked as such and the code degrades gracefully around it.

---

## 1. The two hard blockers

These determine the entire architecture, so they come first.

### Blocker 1 — You cannot ship an app to this watch

The Active 2 runs Tizen, not Wear OS. Samsung has wound Tizen watch support down:

| Date | What ended |
|---|---|
| 30 Sep 2024 | Galaxy Store stopped selling paid Tizen watch content |
| 31 May 2025 | New downloads of free Tizen watch content ended |
| Jun 2025 | Third-party services for Tizen hardware ended outside Galaxy Store |
| 30 Sep 2025 | Galaxy Store downloads for Tizen watches ended entirely |

Samsung's developer site states that teams can no longer register new or updated
Tizen-based watch apps.

**Consequence:** there is no supported path to run our own code on the watch.
Every "collect raw sensor data from the watch" feature in the original brief —
raw accelerometer, raw gyroscope, barometer, watch GPS, custom activity
classification, fall detection — is **NOT AVAILABLE**. Not difficult: unavailable.

### Blocker 2 — HRV, IBI and raw PPG are not accessible

Raw PPG waveform, inter-beat intervals and ECG are exposed only through the
**Samsung Privileged Health SDK**, which:

- supports **Galaxy Watch4 and later running Wear OS powered by Samsung** — the
  Active 2 is neither, and
- requires acceptance into the Samsung Partner Program.

**Consequence:** HRV cannot be computed for this device by any supported means.
The architecture is built with **no HRV dependency anywhere**. The app requests the
Health Connect HRV permission opportunistically so that a future device which does
write RMSSD is picked up with no code change, but nothing degrades if it is never
present — and on this watch it never will be.

---

## 2. The one viable pipeline

```
Galaxy Watch Active 2  (Tizen, closed to us)
        │  Samsung's own sync — we have no control over it
        ▼
Samsung Health app on the phone
        │  Samsung Health → Health Connect sync (needs Samsung Health ≥ 6.22.5)
        ▼
Health Connect  (OS-level on Android 14+, separate app on 13 and below)
        │  androidx.health.connect:connect-client 1.1.0
        ▼
VitalCore AI  →  Room (local cache)  →  Analytics  →  UI
```

Samsung Health Data SDK is a possible *supplementary* phone-side path (it exposes
heart rate, steps, sleep, exercise and blood oxygen), but it needs Samsung Health
≥ 6.30.2 **and app registration with Samsung**. It offers nothing Health Connect
does not already give us for this device, so it is not used. Reassess only if
Samsung Health stops writing a type we depend on.

---

## 3. Capability matrix

Legend: **A** available · **P** partially available · **N** not available

| Data | HW exists | 3rd-party access | Official API | Status | Used by VitalCore |
|---|---|---|---|---|---|
| Current heart rate | Yes | Via Samsung Health sync | Health Connect `HeartRateRecord` | **A** | Yes — core signal |
| Historical heart rate | Yes | Yes | `HeartRateRecord` | **A** | Yes — all HR analytics |
| Continuous HR | Yes | Sync to phone is delayed to save battery | `HeartRateRecord` | **P** | Yes, with wear/density checks |
| Resting heart rate | Yes | Samsung Health shows it but is reported **not to write it** to Health Connect | `RestingHeartRateRecord` | **P** | Yes — **with derived fallback** |
| Raw PPG waveform | Yes | No | Privileged SDK (Watch4+ only) | **N** | No |
| Beat-to-beat / IBI / R-R | Yes | No | Privileged SDK (Watch4+ only) | **N** | No |
| HRV | Sensor exists | Not derivable without IBI | `HeartRateVariabilityRmssdRecord` | **N** | Requested opportunistically; nothing depends on it |
| Accelerometer (raw) | Yes | No — needs a watch app | Tizen (closed) | **N** | No |
| Gyroscope (raw) | Yes | No — needs a watch app | Tizen (closed) | **N** | No |
| Steps | Yes | Yes | `StepsRecord` | **A** | Yes |
| Distance | Yes | Yes | `DistanceRecord` | **A** | Yes |
| Calories | Yes | Yes | `TotalCaloriesBurnedRecord`, `ActiveCaloriesBurnedRecord` | **A** | Yes |
| Workouts / exercise | Yes | Yes | `ExerciseSessionRecord` | **A** | Yes — training load |
| GPS route | Yes (watch GPS) | Only as part of a synced session | `ExerciseRoute` | **P** | Permission requested; route rendering not implemented |
| Speed / pace | Yes | Derived or `SpeedRecord` | `SpeedRecord` | **P** | Distance/duration derived |
| Barometer (raw) | Yes | No | Tizen (closed) | **N** | No |
| Floors climbed | Yes (barometer) | Yes, when Samsung Health writes it | `FloorsClimbedRecord` | **P** | Read, null-tolerant |
| Elevation gain | Yes | Yes, when written | `ElevationGainedRecord` | **P** | Read, null-tolerant |
| Sleep duration/times | Yes | Yes | `SleepSessionRecord` | **A** | Yes — core signal |
| Sleep stages | Yes | Yes when present; not always attached | `SleepSessionRecord.stages` | **P** | Yes, with a no-stages fallback |
| SpO₂ | Yes | Written inconsistently | `OxygenSaturationRecord` | **P** | Optional bonus input |
| Weight / body fat | Manual or scale | Yes | `WeightRecord`, `BodyFatRecord` | **P** | Optional |
| VO₂ max | Estimated by Samsung | Yes when written | `Vo2MaxRecord` | **P** | Preferred over our estimate when present |
| Skin temperature | No | — | — | **N** | No |

### Permissions that are easy to miss and break everything

| Permission | Why it matters |
|---|---|
| `READ_HEALTH_DATA_IN_BACKGROUND` | Health Connect serves reads to **foreground apps only** without it. The WorkManager sync would silently return zero records — the app would only ever hold data from moments the user had it open. |
| `READ_HEALTH_DATA_HISTORY` | Without it reads are capped at **30 days before the grant**. The 30-day backfill sits exactly on that boundary and 28-day personal baselines could never be built on a fresh install. |

Both are now declared and requested. Both are treated as optional at runtime: if
declined, the app says so plainly in onboarding and on the Data Sources screen
rather than failing silently.

---

## 4. Consequences designed into the code

**Resting HR has a derived fallback.** Samsung Health is repeatedly reported to
read but not write `RestingHeartRateRecord`. Since RHR feeds the baseline →
recovery → readiness → stress chain, a null there would empty most of the app.
`HealthConnectManager.deriveRestingHR` estimates it from overnight samples inside
the sleep window (or 00:00–06:00 when no session exists) by averaging the lowest
20% of plausible readings — a trimmed low tail rather than a single minimum, which
one spurious reading would otherwise define. It requires ≥10 samples, is flagged
`restingHRDerived = true`, and is labelled as an estimate in the UI.

**Every read is individually failure-tolerant.** Health Connect grants permissions
per record type, so any single read can throw `SecurityException`. Reads go through
a `safeRead` wrapper returning a fallback, so one declined type cannot abort a sync
that has already gathered everything else.

**Onboarding gates on a minimum, not on everything.** Requiring the full permission
set meant declining any single type — SpO₂, which Samsung Health writes
inconsistently anyway — trapped the user on the onboarding screen permanently. The
gate is now heart rate **or** steps; everything else degrades confidence instead.

**Missing data lowers confidence rather than scoring as zero.** A night with no
sleep record is "unknown", not "zero minutes of sleep".

---

## 5. Honest statement of limits

This app cannot replicate WHOOP's continuous physiological monitoring, and does not
claim to. On this hardware it has no HRV, no raw PPG, no direct sensor access, and
continuous HR that reaches the phone on Samsung's schedule rather than ours.

What it does instead: derive as much as is genuinely supportable from heart rate,
resting HR, sleep, activity and workouts; combine that with the user's own
subjective check-ins; compare everything against the user's personal baselines
rather than population averages; and state its confidence honestly whenever the
underlying data is thin.

Every score is an estimate. None of this is a medical device.

---

## Sources

- [Galaxy Watch for Tizen — developer notice](https://developer.samsung.com/galaxy-watch-tizen/notice.html)
- [Samsung Privileged Health SDK — overview](https://developer.samsung.com/health/privileged/overview.html)
- [Accessing Samsung Health data through Health Connect](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)
- [Samsung Health Data SDK — data permissions](https://developer.samsung.com/health/data/guide/features/data-permission.html)
- [Health Connect — data types reference](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)
- [Health Connect — reading data, background and history reads](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)
- [Samsung developer forum — resting heart rate not syncing to Health Connect](https://forum.developer.samsung.com/t/health-is-not-syncing-the-resting-heart-rate-data-in-health-connect/25800)
- [Galaxy Store support ending for legacy Tizen watches](https://www.androidpolice.com/samsung-is-pulling-tizen-os-smartwatch-content-from-the-galaxy-store-soon/)
