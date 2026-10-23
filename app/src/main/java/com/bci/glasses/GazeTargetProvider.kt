package com.bci.glasses

/**
 * Contract for an external eye-gaze implementation to report the currently
 * observed stimulus target. The target index follows the dashboard order 0..3.
 */
data class GazeTargetSample(
    val targetIndex: Int?,
    val confidence: Float,
    val timestampNanos: Long,
) {
    init {
        require(targetIndex == null || targetIndex in 0..3) {
            "targetIndex must be 0..3 or null"
        }
        require(confidence in 0f..1f) {
            "confidence must be within 0..1"
        }
    }
}

interface GazeTargetProvider {
    fun start(onSample: (GazeTargetSample) -> Unit)

    fun stop()
}
