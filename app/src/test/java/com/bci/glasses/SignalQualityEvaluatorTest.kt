package com.bci.glasses

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalQualityEvaluatorTest {
    private val mappedChannels = setOf(26, 27, 28, 29, 30)

    @Test
    fun acceptsFiveChannelMapWhenAtLeastFourMappedChannelsAreValid() {
        val window = liveWindow(validMappedChannels = mappedChannels - 30)
        val result = evaluator().evaluate(window, isLive = true)

        assertTrue(result.reason.orEmpty(), result.accepted)
        assertEquals(4, result.diagnostics.validChannelCount)
        assertEquals(5, result.diagnostics.evaluatedChannelCount)
    }

    @Test
    fun rejectsFiveChannelMapWhenOnlyThreeMappedChannelsAreValid() {
        val window = liveWindow(validMappedChannels = setOf(26, 27, 28))
        val result = evaluator().evaluate(window, isLive = true)

        assertFalse(result.accepted)
        assertTrue(result.reason.orEmpty().contains("3/4"))
        assertEquals(3, result.diagnostics.validChannelCount)
        assertEquals(5, result.diagnostics.evaluatedChannelCount)
    }

    @Test
    fun ignoresUnmappedZeroSlotsForLiveStatistics() {
        val result = evaluator().evaluate(liveWindow(mappedChannels), isLive = true)

        assertTrue(result.reason.orEmpty(), result.accepted)
        assertEquals(5, result.diagnostics.validChannelCount)
        assertTrue(result.diagnostics.std > 1.0)
    }

    @Test
    fun ignoresKnownBadChannelsExcludedFromLiveIndices() {
        val window = Array(32) { channel ->
            if (channel == 31) {
                DoubleArray(1000)
            } else {
                DoubleArray(1000) { sample ->
                    20.0 * sin(2.0 * PI * 10.0 * sample / 250.0 + channel * 0.1)
                }
            }
        }
        window[0].fill(20_000.0)
        window[2].fill(18_000.0)
        window[9].fill(15_000.0)
        val result = SignalQualityEvaluator(
            expectedSamples = 1000,
            liveChannelIndices = (1 until 31).toSet() - setOf(2, 9),
        ).evaluate(window, isLive = true)

        assertTrue(result.reason.orEmpty(), result.accepted)
        assertEquals(28, result.diagnostics.evaluatedChannelCount)
    }

    private fun evaluator() = SignalQualityEvaluator(
        expectedSamples = 1000,
        liveChannelIndices = mappedChannels,
    )

    private fun liveWindow(validMappedChannels: Set<Int>): Array<DoubleArray> {
        return Array(32) { channel ->
            if (channel !in validMappedChannels) {
                DoubleArray(1000)
            } else {
                DoubleArray(1000) { sample ->
                    20.0 * sin(2.0 * PI * 10.0 * sample / 250.0 + channel * 0.1)
                }
            }
        }
    }
}
