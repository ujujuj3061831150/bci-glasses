package com.bci.glasses

data class StimulusTimingStatus(
    val healthy: Boolean,
    val refreshRateHz: Double,
    val measuredFps: Double,
    val droppedFrameRate: Double,
    val reason: String,
) {
    companion object {
        fun measuring(refreshRateHz: Double = 0.0): StimulusTimingStatus {
            return StimulusTimingStatus(
                healthy = false,
                refreshRateHz = refreshRateHz,
                measuredFps = 0.0,
                droppedFrameRate = 0.0,
                reason = "正在测量显示时序",
            )
        }
    }
}

class StimulusTimingEvaluator(
    private val minimumRefreshRateHz: Double = 59.0,
    private val maximumDroppedFrameRate: Double = 0.03,
    private val minimumMeasurementSeconds: Double = 1.5,
) {
    fun evaluate(
        refreshRateHz: Double,
        renderedFrames: Long,
        droppedFrames: Long,
        durationNanos: Long,
    ): StimulusTimingStatus {
        val durationSeconds = durationNanos / 1_000_000_000.0
        val measuredFps = if (durationSeconds > 0.0) renderedFrames / durationSeconds else 0.0
        val expectedFrames = renderedFrames + droppedFrames
        val droppedRate = if (expectedFrames > 0) droppedFrames.toDouble() / expectedFrames else 1.0
        val reason = when {
            durationSeconds < minimumMeasurementSeconds -> "正在测量显示时序"
            refreshRateHz < minimumRefreshRateHz -> "刷新率过低：${"%.1f".format(refreshRateHz)}Hz"
            droppedRate > maximumDroppedFrameRate -> "掉帧率过高：${"%.1f".format(droppedRate * 100)}%"
            measuredFps < refreshRateHz * (1.0 - maximumDroppedFrameRate) ->
                "实际帧率不足：${"%.1f".format(measuredFps)}fps"
            else -> "显示时序正常"
        }
        return StimulusTimingStatus(
            healthy = reason == "显示时序正常",
            refreshRateHz = refreshRateHz,
            measuredFps = measuredFps,
            droppedFrameRate = droppedRate,
            reason = reason,
        )
    }
}
