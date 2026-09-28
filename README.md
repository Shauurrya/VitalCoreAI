# VitalCore AI

[![Latest Release](https://img.shields.io/github/v/release/Shauurrya/VitalCoreAI?color=blue&label=Download%20APK)](https://github.com/Shauurrya/VitalCoreAI/releases/latest)
[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![Health Connect](https://img.shields.io/badge/Integration-Health%20Connect-4285F4?style=flat-square&logo=google&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=flat-square)](#)

**VitalCore AI** is a biometric analytics and recovery engine for Android. It seamlessly interfaces with **Android Health Connect** (and Samsung Health / Google Fit) to deliver physiological insights — including **Readiness, Recovery, Day Strain, Sleep Debt, Biological Age, and ACWR Training Load** — without requiring high-cost subscription wearables.

---

## 📲 Direct APK Download

You can download the pre-compiled Android APK directly using the link below:

- **🚀 [Download Latest APK (GitHub Release v1.1.0)](https://github.com/Shauurrya/VitalCoreAI/releases/download/v1.1.0/VitalCoreAI-v1.1.0-debug.apk)** *(Recommended — fast CDN direct download)*

---

## ✨ Key Features

- **⚡ Readiness & Recovery Scores**: Calculates daily recovery metrics by benchmarking Resting Heart Rate (RHR), sleep duration, and activity against your 28-day personal baselines.
- **😴 Sleep Debt & Stage Analytics**: Detailed breakdown of sleep efficiency, sleep debt accumulation, and sleep architecture (Deep, REM, Light, Awake).
- **🔥 Day Strain & ACWR Workload Engine**: Uses Acute-to-Chronic Workload Ratio (ACWR) to monitor training intensity and safeguard against overtraining and injury risks.
- **🧬 Fitness Age (estimate)**: Computes cardiovascular resilience and functional fitness indicators from VO₂ Max, resting-HR trend and activity. An estimate from published research — never presented as a measurement.
- **🔄 Health Connect Synchronization**: Full integration with Android Health Connect to ingest metrics from Samsung Galaxy Watches, Pixel Watches, Fitbit, and Garmin hardware.
- **🛡️ Adaptive Data Quality Engine**: Intelligent fallback algorithms (e.g., trimmed overnight RHR estimation) when certain hardware metrics (like raw beat-to-beat IBI) are restricted by wearable firmware.

### New in v1.1

- **🔮 Tomorrow's Readiness — as a range, never a point**: seven named, signed drivers move the centre; the band's *width* comes from your own day-to-day volatility, so a steady month narrows it and an erratic one widens it. A single number would imply precision the data does not support.
- **📈 7 / 14 / 30-day trends with attribution**: Mann-Kendall significance plus a Theil-Sen slope, and the top three contributors behind each direction — not just an arrow.
- **⚠️ Personal anomaly detection**: deviations measured against *your* cleaned baseline, never a population norm. Co-occurrence is reported; causation never is. A favourable move (a lower resting HR) is never flagged.
- **🏋️ Training recommendation**: type, intensity and a signed volume adjustment, capped so no combination of good signals can produce a hard session on a day the recovery data disagrees.
- **🛌 Sleep consistency**: bedtime and wake regularity scored with circular statistics, so drifting either side of midnight is not read as chaos.
- **💪 Muscle recovery that learns**: per-group recovery times derived from the gaps you actually take, excluding cycles that followed a high soreness report.
- **🔐 Optional biometric lock**, **🚀 nine-step onboarding**, and a hidden **developer screen** (triple-tap the title, debug builds only) with nine deterministic 30-day scenarios and a live preview of the AI-context payload.

### Daily guidance (local development build)

- **Ask Coach** answers five supported questions offline, with dated evidence, confidence and links to the relevant readings.
- **Daily action plan** lets you choose activity or rest, adjust a sleep target, save or complete actions, and return to an existing check-in. Choices persist separately for each day.
- **Reliable refresh feedback** distinguishes empty reads, permission problems and failed reads, retains failed readings as stale, and shows current access and measurement freshness in Data Sources.

See [the implementation and validation handoff](docs/DAILY_GUIDANCE_HANDOFF.md) for this build's scope and remaining device checks. The published APK linked above predates these changes.

---

## 🛠️ Technology Stack

| Layer | Technology |
|---|---|
| **UI Framework** | Jetpack Compose (Material3 Design System) |
| **Language** | 100% Kotlin |
| **Architecture** | MVVM with Clean Architecture |
| **Data Persistence** | Room Database with Flow streaming |
| **Background Sync** | Android WorkManager |
| **Biometric Ingestion** | Android Health Connect API (`androidx.health.connect:connect-client`) |
| **Device Lock** | `androidx.biometric` (optional; delegates to the platform prompt) |
| **Asynchronous Engine** | Kotlin Coroutines & StateFlow |

---

## 📥 How to Install

1. Download the [`VitalCoreAI-v1.1.0-debug.apk`](https://github.com/Shauurrya/VitalCoreAI/releases/download/v1.1.0/VitalCoreAI-v1.1.0-debug.apk) file to your Android phone.
2. Open the file on your device and approve **"Install from unknown sources"** if prompted by Android.
3. Launch **VitalCore AI** and follow the onboarding steps to grant **Health Connect** permissions.
4. Ensure your smartwatch sync app (e.g. Samsung Health, Google Fit, or Garmin Connect) is set to sync with Health Connect.

---

## 📁 Repository Structure

```
VitalCoreAI/
├── app/
│   └── src/main/java/com/example/vitalcoreai/
│       ├── analytics/               # Biological Age, RHR, Strain, ACWR & Sleep Calculators
│       ├── coach/                   # AI Coaching Insight Engine
│       ├── data/                    # Room DB, Health Connect Manager, Repository
│       ├── notifications/           # Scheduled Health Notifications
│       ├── theme/                   # Custom Jetpack Compose Styling & Tokens
│       └── ui/                      # Jetpack Compose Screens & ViewModels
└── docs/                            # Technical Architecture & Feasibility Audits
    ├── ANALYTICS_MODULE.md
    └── CAPABILITY_MATRIX.md
```

---

## 🔒 Privacy

VitalCore has **no `INTERNET` permission**, no HTTP client in its dependency tree, no account
and no server. Every score is computed on the device from data already in Health Connect.
Both facts are asserted by a unit test (`MedicalSafetyTest`), so adding a network path would
be a deliberate act rather than an accident.

The AI coach answers from a rules engine running locally. `CoachContext` can produce the exact
JSON payload a cloud model would receive — the developer screen renders it — but nothing sends
it anywhere.

---

## 📄 License & Medical Disclaimer

This project is licensed under the MIT License.

> **Disclaimer**: *VitalCore AI is designed solely for personal fitness, wellness tracking, and research purposes. It is not a medical device, nor does it provide medical diagnosis, prevention, or treatment advice.*
