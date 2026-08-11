package com.example.vitalcoreai.theme

import androidx.compose.ui.graphics.Color

/**
 * Which health domain a value belongs to.
 *
 * Deliberately a plain enum with no Compose types so ViewModels can express
 * "this metric is a Sleep metric" without importing a single hex code.
 */
enum class Domain {
    RECOVERY, READINESS, SLEEP, STRAIN, STRESS, ACTIVITY, HEART, BIO_AGE, NEUTRAL
}

fun domainAccent(domain: Domain): Color = when (domain) {
    Domain.RECOVERY  -> RecoveryAccent
    Domain.READINESS -> ReadinessAccent
    Domain.SLEEP     -> SleepAccent
    Domain.STRAIN    -> StrainAccent
    Domain.STRESS    -> StressAccent
    Domain.ACTIVITY  -> ActivityAccent
    Domain.HEART     -> HeartAccent
    Domain.BIO_AGE   -> BioAgeAccent
    Domain.NEUTRAL   -> OnSurfaceDim
}

/** True when a rising value is a good thing. Stress is the exception. */
fun Domain.higherIsBetter(): Boolean = this != Domain.STRESS
