package com.bci.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTimingTest {
    private val evaluator = StimulusTimingEvaluator()

    @Test
    fun acceptsStableSixtyHertzDisplay() {
        val result = evaluator.evaluate(
            refreshRateHz = 60.0,
            renderedFrames = 119,
            droppedFrames = 1,
            durationNanos = 2_000_000_000,
        )

        assertTrue(result.healthy)
    }

    @Test
    fun rejectsLowRefreshRateOrExcessiveDrops() {
        assertFalse(
            evaluator.evaluate(30.0, 60, 0, 2_000_000_000).healthy
        )
        assertFalse(
            evaluator.evaluate(60.0, 110, 10, 2_000_000_000).healthy
        )
    }

    @Test
    fun detectsExcessiveDropsDuringSixtySecondStimulus() {
        assertTrue(evaluator.evaluate(120.0, 7_198, 2, 60_000_000_000L).healthy)
        assertFalse(evaluator.evaluate(120.0, 6_950, 250, 60_000_000_000L).healthy)
    }
}
