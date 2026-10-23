package com.bci.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class NeuracleStreamFormat(
    val preferenceId: String,
    val statusLabel: String,
) {
    RECORDER_FLOAT32_SAMPLE_MAJOR(
        preferenceId = "recorder_float32_sample_major",
        statusLabel = "Recorder F32 sample-major",
    ),
    LEGACY_INT32_CHANNEL_MAJOR(
        preferenceId = "legacy_int32_channel_major",
        statusLabel = "Legacy I32 channel-major",
    );

    companion object {
        fun fromPreference(value: String?): NeuracleStreamFormat? {
            return entries.firstOrNull { it.preferenceId == value }
        }

        fun inferFromPort(port: Int): NeuracleStreamFormat {
            return if (port == 8712 || port == 8713) {
                RECORDER_FLOAT32_SAMPLE_MAJOR
            } else {
                LEGACY_INT32_CHANNEL_MAJOR
            }
        }
    }
}

class NeuraclePacketDecoder(
    private val nChannels: Int,
    private val eegChannelCount: Int = nChannels,
    private val packetSamples: Int,
    deviceTo32Map: Map<Int, Int>,
    val streamFormat: NeuracleStreamFormat,
) {
    private val channelMap = deviceTo32Map.toMap()
    val packetBytes: Int = nChannels * packetSamples * Float.SIZE_BYTES

    init {
        require(nChannels > 0) { "nChannels must be positive" }
        require(eegChannelCount in 1..nChannels) {
            "eegChannelCount must be within the total stream channel count"
        }
        require(packetSamples > 0) { "packetSamples must be positive" }
        require(channelMap.keys.all { it in 0 until eegChannelCount }) {
            "Device channel map contains an event or out-of-range EEG channel"
        }
        require(channelMap.values.all { it in 0 until 32 }) {
            "Device channel map contains an out-of-range model channel"
        }
    }

    fun decode(packet: ByteArray, offset: Int = 0): Array<DoubleArray> {
        require(offset >= 0 && packet.size - offset >= packetBytes) {
            "Incomplete Neuracle packet: need $packetBytes bytes at offset $offset"
        }
        val values = ByteBuffer.wrap(packet, offset, packetBytes)
            .slice()
            .order(ByteOrder.LITTLE_ENDIAN)
        val selected = Array(32) { DoubleArray(packetSamples) }

        for ((sourceChannel, modelChannel) in channelMap) {
            for (sample in 0 until packetSamples) {
                val valueIndex = when (streamFormat) {
                    NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR ->
                        sample * nChannels + sourceChannel
                    NeuracleStreamFormat.LEGACY_INT32_CHANNEL_MAJOR ->
                        sourceChannel * packetSamples + sample
                }
                val byteIndex = valueIndex * Float.SIZE_BYTES
                selected[modelChannel][sample] = when (streamFormat) {
                    NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR ->
                        values.getFloat(byteIndex).toDouble()
                    NeuracleStreamFormat.LEGACY_INT32_CHANNEL_MAJOR ->
                        values.getInt(byteIndex) * LEGACY_MICROVOLTS_PER_COUNT
                }
            }
        }
        return selected
    }

    companion object {
        const val LEGACY_MICROVOLTS_PER_COUNT = 2.5e6 / (49.9 * 49.9) / 7864320.0
    }
}
