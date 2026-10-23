package com.bci.glasses

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
enum class CalibrationCondition(
    val targetId: Int,
    val targetHz: Double?,
    val cueCode: Int,
    val activeCode: Int,
    val offsetCode: Int,
    val trainingLabel: Int,
    val flickerDuringActive: Boolean,
) {
    HZ_11(1, PersonalCaptureSpec.TARGET_FREQUENCY_HZ, 11, 101, 201, 0, true),
}

enum class CalibrationVisualPhase {
    PREPARE,
    ACTIVE,
    REST,
}

data class CalibrationVisualState(
    val blockId: Int,
    val trialId: Int,
    val totalTrialsInBlock: Int,
    val condition: CalibrationCondition,
    val phase: CalibrationVisualPhase,
    val practice: Boolean,
)

data class CalibrationFrameEvent(
    val visualState: CalibrationVisualState,
    val eventType: String,
    val eventCode: Int,
    val frameTimeNanos: Long = 0L,
)

data class CalibrationTrial(
    val blockId: Int,
    val trialId: Int,
    val condition: CalibrationCondition,
    val baselineMillis: Long,
    val practice: Boolean,
)

data class CalibrationBlock(
    val blockId: Int,
    val practice: Boolean,
    val trials: List<CalibrationTrial>,
)

data class CalibrationPlan(
    val seed: Long,
    val formalBlocks: List<CalibrationBlock>,
) {
    val formalTrialCount: Int get() = formalBlocks.sumOf { it.trials.size }
}

object CalibrationPlanFactory {
    const val FORMAL_BLOCKS = 1
    const val TRIALS_PER_BLOCK = PersonalCaptureSpec.TRIAL_COUNT
    const val CUE_MILLIS = PersonalCaptureSpec.PREPARE_MILLIS
    const val ACTIVE_MILLIS = PersonalCaptureSpec.ACTIVE_MILLIS
    const val REST_MILLIS = PersonalCaptureSpec.REST_MILLIS

    fun create(seed: Long): CalibrationPlan {
        val block = CalibrationBlock(
            blockId = 1,
            practice = false,
            trials = (1..TRIALS_PER_BLOCK).map { trialId ->
                CalibrationTrial(1, trialId, CalibrationCondition.HZ_11, CUE_MILLIS, false)
            },
        )
        return CalibrationPlan(seed, listOf(block))
    }
}

data class SampleSequenceEstimate(
    val sequenceFloor: Long,
    val estimatedSequence: Long,
    val uncertaintySamples: Long,
)

class SampleClockEstimator(
    private val sampleRateHz: Int,
    private val packetSamples: Int,
    private val maxPoints: Int = 64,
) {
    private data class Point(val timeNanos: Long, val sequenceEnd: Long)

    private val points = ArrayDeque<Point>()

    @Synchronized
    fun addPacket(sequenceEnd: Long, arrivalNanos: Long) {
        if (points.isNotEmpty() && sequenceEnd <= points.last().sequenceEnd) return
        points.addLast(Point(arrivalNanos, sequenceEnd))
        while (points.size > maxPoints) points.removeFirst()
    }

    @Synchronized
    fun estimate(frameTimeNanos: Long, sequenceFloor: Long): SampleSequenceEstimate {
        if (points.size < 2) {
            return SampleSequenceEstimate(sequenceFloor, sequenceFloor, packetSamples.toLong())
        }
        val origin = points.first().timeNanos
        val xs = points.map { (it.timeNanos - origin) / 1_000_000_000.0 }
        val ys = points.map { it.sequenceEnd.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var variance = 0.0
        for (index in xs.indices) {
            covariance += (xs[index] - meanX) * (ys[index] - meanY)
            variance += (xs[index] - meanX) * (xs[index] - meanX)
        }
        val fittedSlope = if (variance > 0.0) covariance / variance else sampleRateHz.toDouble()
        val slope = fittedSlope.coerceIn(sampleRateHz * 0.97, sampleRateHz * 1.03)
        val intercept = meanY - slope * meanX
        val frameX = (frameTimeNanos - origin) / 1_000_000_000.0
        val estimated = (intercept + slope * frameX).roundToLong()
        val residuals = xs.indices.map { index ->
            kotlin.math.abs(ys[index] - (intercept + slope * xs[index]))
        }.sorted()
        val p95Index = max(0, ceil(residuals.size * 0.95).toInt() - 1)
        val uncertainty = max(packetSamples.toLong(), ceil(residuals[p95Index]).toLong())
        return SampleSequenceEstimate(
            sequenceFloor = sequenceFloor,
            estimatedSequence = estimated.coerceAtLeast(0L),
            uncertaintySamples = uncertainty,
        )
    }
}

object CalibrationEventCodes {
    const val EXPERIMENT_START = 1
    const val BLOCK_START = 2
    const val BLOCK_END = 3
    const val EXPERIMENT_END = 9
    const val REST_END = 202
    const val CANCELLED = 250
}
