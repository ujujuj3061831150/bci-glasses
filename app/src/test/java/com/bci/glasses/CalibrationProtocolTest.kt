package com.bci.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProtocolTest {
    @Test
    fun tenTrialsOnlyUseElevenHertzWithFixedPhaseLengths() {
        val plan = CalibrationPlanFactory.create(20260923L)
        assertEquals(1, plan.formalBlocks.size)
        assertEquals(10, plan.formalTrialCount)
        assertEquals((1..10).toList(), plan.formalBlocks.single().trials.map { it.trialId })
        assertTrue(plan.formalBlocks.single().trials.all {
            it.condition == CalibrationCondition.HZ_11 &&
                it.condition.targetHz == 11.0 &&
                it.baselineMillis == 4_000L && !it.practice
        })
        assertEquals(60_000L, CalibrationPlanFactory.ACTIVE_MILLIS)
        assertEquals(6_000L, CalibrationPlanFactory.REST_MILLIS)
    }

    @Test
    fun sameSeedProducesIdenticalPlan() {
        assertEquals(CalibrationPlanFactory.create(42L), CalibrationPlanFactory.create(42L))
    }

    @Test
    fun sampleClockUsesSequenceAndReportsPacketUncertainty() {
        val estimator = SampleClockEstimator(sampleRateHz = 1000, packetSamples = 40)
        estimator.addPacket(40, 1_000_000_000L)
        estimator.addPacket(80, 1_040_000_000L)
        estimator.addPacket(120, 1_080_000_000L)
        val estimate = estimator.estimate(1_060_000_000L, sequenceFloor = 80)
        assertEquals(100L, estimate.estimatedSequence)
        assertTrue(estimate.uncertaintySamples >= 40L)
    }
}
