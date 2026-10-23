package com.bci.glasses

object NeuracleChannelMapping {
    val recorderSsvep8ChannelNames: List<String> = listOf(
        "POz", "PO3", "PO4", "PO5", "PO6", "Oz", "O1", "O2",
    )

    val eegCap32V7ChannelNames: List<String> = listOf(
        "Fp1", "Fp2", "Fz", "F3", "F4", "F7", "F8", "FC1",
        "FC2", "FC5", "FC6", "Cz", "C3", "C4", "T3", "T4",
        "A1", "A2", "CP1", "CP2", "CP5", "CP6", "Pz", "P3",
        "P4", "T5", "T6", "PO3", "PO4", "Oz", "O1", "O2",
    )

    private val eegCap32V7BadChannelReplacements: Map<Int, Set<Int>> = mapOf(
        0 to setOf(1, 3, 4), // Fp1 <- mean(Fp2, F3, Fz)
        2 to setOf(3, 7, 11), // F7 <- mean(F3, FC5, T3)
        9 to setOf(4, 8, 10, 14), // FC2 <- mean(Fz, FC1, FC6, C4)
    )

    val defaultDeviceToModel: Map<Int, Int> = linkedMapOf(
        0 to 29, 1 to 28, 2 to 30, 3 to 26, 4 to 27, 5 to 21, 6 to 25, 7 to 23,
        8 to 22, 9 to 24, 10 to 17, 11 to 19, 12 to 16, 13 to 20, 14 to 13, 15 to 12,
        16 to 14, 17 to 11, 18 to 15, 19 to 8, 20 to 9, 21 to 7, 22 to 10, 23 to 4,
        24 to 3, 25 to 5, 26 to 2, 27 to 6, 28 to 0, 29 to 1,
    )

    // Installed Recorder configuration: EEGCap32-V7 physical order -> 32-slot channel layout.
    val eegCap32V7ToModel: Map<Int, Int> = linkedMapOf(
        0 to 0,   // Fp1
        1 to 1,   // Fp2
        2 to 4,   // Fz
        3 to 3,   // F3
        4 to 5,   // F4
        5 to 2,   // F7
        6 to 6,   // F8
        7 to 8,   // FC1
        8 to 9,   // FC2
        9 to 7,   // FC5
        10 to 10, // FC6
        11 to 13, // Cz
        12 to 12, // C3
        13 to 14, // C4
        14 to 11, // T3
        15 to 15, // T4
        // Device channels 16 and 17 are A1/A2 references and are intentionally not mapped.
        18 to 17, // CP1
        19 to 19, // CP2
        20 to 16, // CP5
        21 to 20, // CP6
        22 to 23, // Pz
        23 to 22, // P3
        24 to 24, // P4
        25 to 21, // T5
        26 to 25, // T6
        27 to 26, // PO3
        28 to 27, // PO4
        29 to 29, // Oz
        30 to 28, // O1
        31 to 30, // O2
    )

    // Recorder SSVEP montage order: POz, PO3, PO4, PO5, PO6, Oz, O1, O2.
    val recorderSsvep8ToModel: Map<Int, Int> = linkedMapOf(
        1 to 26, // PO3
        2 to 27, // PO4
        5 to 29, // Oz
        6 to 28, // O1
        7 to 30, // O2
    )

    fun badChannelReplacements(deviceToModel: Map<Int, Int>): Map<Int, Set<Int>> {
        return if (deviceToModel == eegCap32V7ToModel) {
            eegCap32V7BadChannelReplacements
        } else {
            emptyMap()
        }
    }

    fun parse(specification: String, deviceChannelCount: Int): Map<Int, Int> {
        require(deviceChannelCount > 0) { "可映射 EEG 通道数必须大于 0" }
        val entries = specification
            .split(',', ';', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(entries.isNotEmpty()) { "通道映射不能为空" }

        val mapping = linkedMapOf<Int, Int>()
        val usedModelChannels = mutableSetOf<Int>()
        entries.forEach { entry ->
            val parts = entry.split(':').map(String::trim)
            require(parts.size == 2) { "映射项格式错误：$entry" }
            val deviceChannel = parts[0].toIntOrNull()
                ?: throw IllegalArgumentException("设备通道不是整数：${parts[0]}")
            val modelChannel = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("模型通道不是整数：${parts[1]}")
            require(deviceChannel in 0 until deviceChannelCount) {
                "设备通道越界：$deviceChannel（可映射 EEG 通道数 $deviceChannelCount）"
            }
            require(modelChannel in 0 until 31) {
                "模型通道越界：$modelChannel（31 为保留 GND 通道）"
            }
            require(deviceChannel !in mapping) { "设备通道重复：$deviceChannel" }
            require(usedModelChannels.add(modelChannel)) { "模型通道重复：$modelChannel" }
            mapping[deviceChannel] = modelChannel
        }
        return mapping
    }

    fun format(mapping: Map<Int, Int>): String {
        return mapping.toSortedMap().entries.joinToString(",") { (device, model) -> "$device:$model" }
    }
}
