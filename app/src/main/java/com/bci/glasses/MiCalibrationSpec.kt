package com.bci.glasses

/**
 * 运动想象（Motor Imagery, MI）个人标定采集规范与常量定义。
 *
 * 时序设计严格对齐当前 Python 算法模型（750点 @ 250Hz = 3秒信号窗口）
 * 以及国际标准 BCI Competition IV 2a 范式事件标记。
 */
object MiCalibrationSpec {
    // 阶段时长（毫秒）
    const val PREPARE_MILLIS = 2_000L   // 准备期：注视中心十字，静息放松
    const val CUE_MILLIS = 1_250L       // 提示期：左/右箭头出现，提示想象肢体
    const val ACTIVE_MILLIS = 3_750L    // 任务期：持续想象肢体动作（包含完整的3秒纯想象分析窗）
    const val REST_MILLIS = 2_000L      // 休息期：黑屏/提示休息，允许轻微眨眼

    // 单个试次总耗时 = 2000 + 1250 + 3750 + 2000 = 9000 ms (9秒)
    const val TRIAL_TOTAL_MILLIS = PREPARE_MILLIS + CUE_MILLIS + ACTIVE_MILLIS + REST_MILLIS

    // 默认试次与区块配置（抗疲劳设计）
    const val DEFAULT_BLOCK_COUNT = 2           // 2 个区块
    const val DEFAULT_TRIALS_PER_BLOCK = 20     // 每区块 20 次（左 10、右 10）
    const val PRACTICE_TRIALS = 4               // 练习试次（左 2、右 2）
    const val DEFAULT_FORMAL_TRIALS = DEFAULT_BLOCK_COUNT * DEFAULT_TRIALS_PER_BLOCK // 共 40 次

    // 脑电信号与采样规范
    const val READINESS_WINDOW_MILLIS = 4_000L
    const val OUTPUT_SAMPLE_RATE_HZ = 250

    // 运动区核心电极模型槽位（EEGCap32-V7 对应关系）
    // 12: C3 (左侧运动皮层，对侧对应右手)
    // 13: Cz (中央运动区)
    // 14: C4 (右侧运动皮层，对侧对应左手)
    val MOTOR_MODEL_SLOTS: IntArray = intArrayOf(12, 13, 14)
    val MOTOR_CHANNEL_NAMES: List<String> = listOf("C3", "Cz", "C4")

    // 国际标准事件 Trigger 编码 (对齐 BCI Competition IV 2a)
    object EventCodes {
        const val EXPERIMENT_START = 1      // 实验开始
        const val BLOCK_START = 2           // 区块开始
        const val BLOCK_END = 3             // 区块结束
        const val EXPERIMENT_END = 9        // 实验结束

        const val TRIAL_START = 768         // 准备十字出现 (对齐 BCI 2a 768: Trial start)
        const val CUE_LEFT = 769            // 左箭头提示 (对齐 BCI 2a 769: Class 1 left hand)
        const val CUE_RIGHT = 770           // 右箭头提示 (对齐 BCI 2a 770: Class 2 right hand)
        const val ACTIVE_LEFT = 101         // 左手想象激活开始
        const val ACTIVE_RIGHT = 102        // 右手想象激活开始
        const val ACTIVE_OFFSET = 201       // 想象阶段结束
        const val REST_END = 202            // 休息结束 / 试次结束
        const val CANCELLED = 250           // 中途取消
    }

    /**
     * 检查设备档案是否满足 MI 个人标定采集要求。
     * 必须具备 1000 Hz 采样率、Float32 格式、1路 event、以及运动区核心通道 C3/Cz/C4 (模型槽位 12, 13, 14)。
     */
    fun incompatibilityReason(profile: DeviceProfile): String? {
        val amplifier = profile.amplifier
        when {
            amplifier.rawSampleRateHz != 1_000 ->
                return "MI 采集只支持 1000 Hz 原始采样率"
            amplifier.eventChannelCount != 1 ->
                return "MI 采集要求 Recorder 流包含 1 路 event"
            amplifier.channelCount != amplifier.eegChannelCount + amplifier.eventChannelCount ->
                return "Recorder 流总通道数与 EEG/event 通道数不一致"
            amplifier.streamFormat != NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR ->
                return "MI 采集要求 Recorder Float32 sample-major 数据流"
        }
        val missingSlots = MOTOR_MODEL_SLOTS.filter { slot ->
            slot !in amplifier.channelMap.values
        }
        return if (missingSlots.isEmpty()) {
            null
        } else {
            "MI 采集缺少运动皮层核心通道映射（C3/Cz/C4，槽位：${missingSlots.joinToString()}），请切换为 32 通道电极帽档案"
        }
    }
}
