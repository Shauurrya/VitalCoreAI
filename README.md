# VitalCore AI

[![Latest Release](https://img.shields.io/github/v/release/Shauurrya/VitalCoreAI?color=blue&label=Download%20APK)](https://github.com/Shauurrya/VitalCoreAI/releases/latest)
[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![Health Connect](https://img.shields.io/badge/Integration-Health%20Connect-4285F4?style=flat-square&logo=google&logoColor=white)](#)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=flat-square)](#)

**VitalCore AI** is a biometric analytics and recovery engine for Android. It seamlessly interfaces with **Android Health Connect** (and Samsung Health / Google Fit) to deliver physiological insights — including **Readiness, Recovery, Day Strain, Sleep Debt, Biological Age, and ACWR Training Load** — without requiring high-cost subscription wearables.

---

## 📲 Direct APK Download

You can download the pre-compiled Android APK directly using either of the following links:

- **🚀 [Download Latest APK (GitHub Releases v1.0.0)](https://github.com/Shauurrya/VitalCoreAI/releases/download/v1.0.0/VitalCoreAI-debug.apk)** *(Recommended — fast CDN direct download)*
- **📁 [Browse APK in Repository (`/apk/VitalCoreAI-debug.apk`)](https://github.com/Shauurrya/VitalCoreAI/tree/main/apk)**

---

## ✨ Key Features

- **⚡ Readiness & Recovery Scores**: Calculates daily recovery metrics by benchmarking Resting Heart Rate (RHR), sleep duration, and activity against your 28-day personal baselines.
- **😴 Sleep Debt & Stage Analytics**: Detailed breakdown of sleep efficiency, sleep debt accumulation, and sleep architecture (Deep, REM, Light, Awake).
- **🔥 Day Strain & ACWR Workload Engine**: Uses Acute-to-Chronic Workload Ratio (ACWR) to monitor training intensity and safeguard against overtraining and injury risks.
- **🧬 Biological Age Estimation**: Computes cardiovascular resilience and functional fitness indicators to project your physiological age.
- **🔄 Health Connect Synchronization**: Full integration with Android Health Connect to ingest metrics from Samsung Galaxy Watches, Pixel Watches, Fitbit, and Garmin hardware.
- **🛡️ Adaptive Data Quality Engine**: Intelligent fallback algorithms (e.g., trimmed overnight RHR estimation) when certain hardware metrics (like raw beat-to-beat IBI) are restricted by wearable firmware.

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
| **Asynchronous Engine** | Kotlin Coroutines & StateFlow |

---

## 📥 How to Install

1. Download the [`VitalCoreAI-debug.apk`](https://github.com/Shauurrya/VitalCoreAI/releases/download/v1.0.0/VitalCoreAI-debug.apk) file to your Android phone.
2. Open the file on your device and approve **"Install from unknown sources"** if prompted by Android.
3. Launch **VitalCore AI** and follow the onboarding steps to grant **Health Connect** permissions.
4. Ensure your smartwatch sync app (e.g. Samsung Health, Google Fit, or Garmin Connect) is set to sync with Health Connect.

---

## 📁 Repository Structure

```
VitalCoreAI/
├── apk/                             # Downloadable APK area
│   └── VitalCoreAI-debug.apk
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

## 📄 License & Medical Disclaimer

This project is licensed under the MIT License.

> **Disclaimer**: *VitalCore AI is designed solely for personal fitness, wellness tracking, and research purposes. It is not a medical device, nor does it provide medical diagnosis, prevention, or treatment advice.*
