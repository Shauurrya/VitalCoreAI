package com.example.vitalcoreai.theme

import com.example.vitalcoreai.analytics.TrendCalculators
import org.junit.Assert.assertEquals
import org.junit.Test

class StressPresentationTest {
    @Test
    fun `stress presentation agrees with recorded explanation around every boundary`() {
        // The initial baseline is 62 bpm with an 8 bpm deviation. These measurements
        // land just below, on, and above the 30, 60, and 80 score boundaries.
        val colorByLevel = mapOf(
            "Low" to TierHigh,
            "Moderate" to TierModerate,
            "Elevated" to StressAccent,
            "High" to TierLow
        )
        for (restingHR in listOf(53, 54, 55, 65, 66, 67, 73, 74, 75)) {
            val result = TrendCalculators.calculateStressScore(restingHR, emptyList())
            val recordedLevel = result.explanation.substringBefore(" physiological stress.")

            assertEquals("Caption for score ${result.score}", recordedLevel, stressTierLabel(result.score))
            assertEquals("Color for score ${result.score}", colorByLevel.getValue(recordedLevel), stressTierColor(result.score))
            assertEquals(
                "The HR elevation breakdown must use the same stress band as the hero",
                stressTierColor(result.score),
                stressTierColor(result.breakdown.single().score)
            )
        }
    }
}
