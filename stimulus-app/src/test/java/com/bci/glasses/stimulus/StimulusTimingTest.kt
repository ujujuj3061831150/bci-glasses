package com.bci.glasses.stimulus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class StimulusTimingTest {
    @Test
    fun squareWaveUsesElapsedTimeInsteadOfFrameCount() {
        assertTrue(StimulusTiming.squareWaveOn(10.0, 0L))
        assertTrue(StimulusTiming.squareWaveOn(10.0, 49_000_000L))
        assertFalse(StimulusTiming.squareWaveOn(10.0, 51_000_000L))
        assertTrue(StimulusTiming.squareWaveOn(10.0, 100_000_000L))
    }

    @Test
    fun miTrialHasTwoFourThreeSecondPhases() {
        assertEquals(7_000_000_000L, StimulusTiming.MI_TRIAL_NANOS)
        assertEquals(0L, StimulusTiming.miTrialIndex(6_999_999_999L))
        assertEquals(1L, StimulusTiming.miTrialIndex(7_000_000_000L))
        assertEquals(0L, StimulusTiming.miTrialOffset(7_000_000_000L))
    }

    @Test
    fun sineBrightnessRespectsPcPhaseConfiguration() {
        assertEquals(0.5f, StimulusTiming.sineBrightness(8.0, 0.0, 0L), 0.0001f)
        assertEquals(1.0f, StimulusTiming.sineBrightness(8.0, Math.PI / 2.0, 0L), 0.0001f)
    }
}
