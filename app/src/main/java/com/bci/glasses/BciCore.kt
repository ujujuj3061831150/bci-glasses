package com.bci.glasses

import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.sqrt

data class EegWindow(
    val channels: Array<DoubleArray>,
    val expectedLabel: String?,
    val sourceName: String,
    val sourceStatus: String,
    val dataAvailable: Boolean = true,
    val isLive: Boolean = false,
    val sequenceId: Long = 0L,
    val receivedAtNanos: Long = System.nanoTime(),
    val expectedFrequencyHz: Double? = null,
)

interface EegWindowSource : Closeable {
    fun nextWindow(durationSeconds: Double): EegWindow
    override fun close() = Unit
}

data class WindowDiagnostics(
    val channelCount: Int,
    val sampleCount: Int,
    val mean: Double,
    val std: Double,
    val validChannelCount: Int = 0,
    val evaluatedChannelCount: Int = 0,
    val maxAbsolute: Double = 0.0,
)

data class SignalQualityResult(
    val accepted: Boolean,
    val reason: String?,
    val diagnostics: WindowDiagnostics,
)

class SignalQualityEvaluator(
    private val expectedChannels: Int = 32,
    private val expectedSamples: Int = 4000,
    liveChannelIndices: Set<Int> = (0 until expectedChannels).toSet(),
    minLiveChannels: Int? = null,
    private val flatlineStdThreshold: Double = 1e-4,
    private val maxAbsoluteMicrovolts: Double = 5_000.0,
    private val maxStdMicrovolts: Double = 1_000.0,
) {
    private val liveChannelIndices = liveChannelIndices.toSortedSet()
    private val minLiveChannels = minLiveChannels ?: minOf(
        24, maxOf(1, ceil(this.liveChannelIndices.size * 0.8).toInt()),
    )

    init {
        require(this.liveChannelIndices.isNotEmpty()) { "质量检查通道不能为空" }
        require(this.liveChannelIndices.all { it in 0 until expectedChannels })
        require(this.minLiveChannels in 1..this.liveChannelIndices.size)
    }

    fun evaluate(window: Array<DoubleArray>, isLive: Boolean): SignalQualityResult {
        if (window.size != expectedChannels || window.any { it.size != expectedSamples }) {
            return rejected("输入形状不是 ${expectedChannels}x$expectedSamples", window)
        }
        val indices = if (isLive) liveChannelIndices else window.indices.toSet()
        var sum = 0.0
        var count = 0
        var maxAbsolute = 0.0
        for (index in indices) {
            for (value in window[index]) {
                if (!value.isFinite()) return rejected("脑电数据包含 NaN/Inf", window)
                sum += value
                count++
                maxAbsolute = maxOf(maxAbsolute, kotlin.math.abs(value))
            }
        }
        val mean = sum / count.coerceAtLeast(1)
        var varianceSum = 0.0
        var validChannels = 0
        for (index in indices) {
            val channel = window[index]
            val channelMean = channel.average()
            var channelVariance = 0.0
            for (value in channel) {
                varianceSum += (value - mean) * (value - mean)
                channelVariance += (value - channelMean) * (value - channelMean)
            }
            if (sqrt(channelVariance / channel.size) >= flatlineStdThreshold) validChannels++
        }
        val std = sqrt(varianceSum / count)
        val diagnostics = WindowDiagnostics(
            window.size, expectedSamples, mean, std, validChannels, indices.size, maxAbsolute,
        )
        val reason = when {
            isLive && validChannels < minLiveChannels -> "有效通道不足：$validChannels/$minLiveChannels"
            std < flatlineStdThreshold -> "脑电信号近似平线"
            maxAbsolute > maxAbsoluteMicrovolts || std > maxStdMicrovolts -> "脑电幅值异常"
            else -> null
        }
        return SignalQualityResult(reason == null, reason, diagnostics)
    }

    private fun rejected(reason: String, window: Array<DoubleArray>): SignalQualityResult =
        SignalQualityResult(
            false, reason,
            WindowDiagnostics(window.size, window.firstOrNull()?.size ?: 0, 0.0, 0.0),
        )
}
