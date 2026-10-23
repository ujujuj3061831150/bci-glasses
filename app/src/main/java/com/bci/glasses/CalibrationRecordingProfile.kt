package com.bci.glasses

/**
 * Recorder layouts that can produce a personal SSVEP capture session.
 *
 * The recording always preserves the full incoming stream.  The five
 * occipital slots are also persisted so offline processing can recover
 * the same five physical channels from either an 8- or 32-channel montage.
 */
object CalibrationRecordingProfile {
    private val requiredOccipitalSlots = PersonalCaptureSpec.OCCIPITAL_MODEL_SLOTS

    fun incompatibilityReason(profile: DeviceProfile): String? {
        val amplifier = profile.amplifier
        when {
            amplifier.rawSampleRateHz != 1_000 ->
                return "个人采集只支持 1000 Hz 原始采样率"
            amplifier.eegChannelCount !in setOf(8, 32) ->
                return "个人采集只支持 8 EEG + 1 event 或 32 EEG + 1 event"
            amplifier.eventChannelCount != 1 ->
                return "个人采集要求 Recorder 流包含 1 路 event"
            amplifier.channelCount != amplifier.eegChannelCount + amplifier.eventChannelCount ->
                return "Recorder 流总通道数与 EEG/event 通道数不一致"
            amplifier.streamFormat != NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR ->
                return "个人采集要求 Recorder Float32 sample-major 数据流"
        }
        val missingSlots = requiredOccipitalSlots.filter { slot ->
            slot !in amplifier.channelMap.values
        }
        return if (missingSlots.isEmpty()) {
            null
        } else {
            "个人采集缺少枕区通道映射：${missingSlots.joinToString()}"
        }
    }

    fun rawChannelNames(profile: DeviceProfile): List<String> {
        val amplifier = profile.amplifier
        val eegNames = when {
            amplifier.eegChannelCount == 8 &&
                amplifier.channelMap == NeuracleChannelMapping.recorderSsvep8ToModel ->
                NeuracleChannelMapping.recorderSsvep8ChannelNames
            amplifier.eegChannelCount == 32 &&
                amplifier.channelMap == NeuracleChannelMapping.eegCap32V7ToModel ->
                NeuracleChannelMapping.eegCap32V7ChannelNames
            else -> (0 until amplifier.eegChannelCount).map { "EEG$it" }
        }
        val eventNames = (0 until amplifier.eventChannelCount).map { index ->
            if (amplifier.eventChannelCount == 1) "Event" else "Event$index"
        }
        return eegNames + eventNames
    }

    fun occipitalRawIndices(profile: DeviceProfile): IntArray {
        val mapping = profile.amplifier.channelMap
        return requiredOccipitalSlots.map { slot ->
            mapping.entries.singleOrNull { it.value == slot }?.key
                ?: error("缺少枕区模型槽：$slot")
        }.toIntArray()
    }

    fun occipitalRawChannelNames(profile: DeviceProfile): List<String> {
        val names = rawChannelNames(profile)
        return occipitalRawIndices(profile).map(names::get)
    }
}
