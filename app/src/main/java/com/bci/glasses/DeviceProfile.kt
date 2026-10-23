package com.bci.glasses

enum class DeviceProfileKind(
    val preferenceId: String,
    val label: String,
) {
    SIMULATOR("simulator", "模拟器"),
    MOCK_HARDWARE("mock_hardware", "模拟硬件"),
    HARDWARE_TEMPLATE("hardware_template", "硬件模板");

    companion object {
        fun fromPreference(value: String): DeviceProfileKind? =
            entries.firstOrNull { it.preferenceId == value }
    }
}

enum class AmplifierAdapter(
    val preferenceId: String,
    val label: String,
) {
    NEURACLE_TCP("neuracle_tcp", "博睿康 TCP"),
    MOCK_NEURACLE_TCP("mock_neuracle_tcp", "模拟博睿康 TCP");

    companion object {
        fun fromPreference(value: String): AmplifierAdapter? =
            entries.firstOrNull { it.preferenceId == value }
    }
}

data class AmplifierProfile(
    val adapter: AmplifierAdapter,
    val hosts: List<String>,
    val port: Int,
    val streamFormat: NeuracleStreamFormat,
    val rawSampleRateHz: Int,
    val eegChannelCount: Int,
    val eventChannelCount: Int,
    val channelCount: Int,
    val packetSamples: Int,
    val channelMap: Map<Int, Int>,
)

data class DisplayProfile(
    val deviceFamily: String,
    val preferredRefreshRateHz: Double?,
    val cueDurationMillis: Long,
    val activeDurationMillis: Long,
    val feedbackDurationMillis: Long,
    val targetWidthRatio: Double,
    val targetHeightRatio: Double,
    val debugOverlayDefault: Boolean,
)

data class UserCalibrationProfile(
    val userId: String,
)

data class DeviceProfile(
    val schemaVersion: Int,
    val profileId: String,
    val displayName: String,
    val kind: DeviceProfileKind,
    val amplifier: AmplifierProfile,
    val display: DisplayProfile,
    val user: UserCalibrationProfile,
)

data class DeviceProfileValidation(
    val errors: List<String>,
    val warnings: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun requireValid() {
        require(isValid) { errors.joinToString("；") }
    }
}

object DeviceProfileValidator {
    private val profileIdPattern = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    private val userIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun validate(profile: DeviceProfile): DeviceProfileValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (profile.schemaVersion != DeviceProfileJson.CURRENT_SCHEMA_VERSION) {
            errors += "不支持的 schemaVersion：${profile.schemaVersion}"
        }
        if (!profileIdPattern.matches(profile.profileId)) {
            errors += "profileId 只能包含小写字母、数字、点、下划线和短横线，长度为 3-64"
        }
        if (profile.displayName.isBlank() || profile.displayName.length > 64) {
            errors += "档案名称不能为空且不能超过 64 个字符"
        }

        validateAmplifier(profile.amplifier, errors, warnings)
        validateDisplay(profile.display, errors)
        validateUser(profile.user, errors)
        return DeviceProfileValidation(errors, warnings)
    }

    private fun validateAmplifier(
        amplifier: AmplifierProfile,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (amplifier.hosts.isEmpty()) errors += "放大器主机列表不能为空"
        if (amplifier.hosts.size > 8) errors += "放大器主机不能超过 8 个"
        amplifier.hosts.forEach { host ->
            if (host.isBlank() || host.length > 253 || host.any(Char::isWhitespace) ||
                host.any { it == ',' || it == ';' || it == '/' }
            ) {
                errors += "放大器主机格式无效：$host"
            }
        }
        if (amplifier.port !in 1..65535) errors += "放大器端口必须在 1-65535"
        if (amplifier.rawSampleRateHz !in setOf(250, 1000)) {
            errors += "当前预处理器只支持 250 Hz 或 1000 Hz 原始采样率"
        }
        if (amplifier.eegChannelCount !in 1..256) errors += "EEG 通道数必须在 1-256"
        if (amplifier.eventChannelCount !in 0..32) errors += "event 通道数必须在 0-32"
        if (amplifier.channelCount !in 1..256) errors += "流总通道数必须在 1-256"
        if (amplifier.channelCount != amplifier.eegChannelCount + amplifier.eventChannelCount) {
            errors += "流总通道数必须等于 EEG 通道数与 event 通道数之和"
        }
        if (amplifier.packetSamples !in 1..1000) errors += "每包采样点数必须在 1-1000"
        if (amplifier.channelMap.isEmpty()) {
            errors += "设备到模型的通道映射不能为空"
        } else {
            runCatching {
                    NeuracleChannelMapping.parse(
                        NeuracleChannelMapping.format(amplifier.channelMap),
                        amplifier.eegChannelCount,
                    )
            }.onFailure { errors += it.message ?: "通道映射无效" }
        }
        val mapped = amplifier.channelMap.values.toSet()
        val missingOccipital = PersonalCaptureSpec.OCCIPITAL_MODEL_SLOTS.filter { it !in mapped }
        if (missingOccipital.isNotEmpty()) {
            errors += "个人采集缺少枕区模型槽：${missingOccipital.joinToString()}"
        } else if (amplifier.channelMap.size < 24) {
            warnings += "当前仅映射 ${amplifier.channelMap.size} 个模型通道；质量诊断将优先检查五路枕区通道"
        }
        val availableModelEegSlots = minOf(amplifier.eegChannelCount, 31)
        if (amplifier.channelMap.size < availableModelEegSlots) {
            warnings += "当前读取 ${amplifier.eegChannelCount} 路 EEG，但仅映射 ${amplifier.channelMap.size}/$availableModelEegSlots 路；未映射模型槽保持为 0"
        }
        when {
            amplifier.streamFormat == NeuracleStreamFormat.LEGACY_INT32_CHANNEL_MAJOR ->
                warnings += "Legacy 30 通道仅用于旧工程兼容，4097 协议和物理通道含义尚未获厂商确认"
            amplifier.adapter == AmplifierAdapter.NEURACLE_TCP &&
                amplifier.streamFormat == NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR &&
                amplifier.eventChannelCount == 0 ->
                warnings += "当前选择无 event 的 Recorder 流，仅在抓包确认总流确为 32 路时使用"
        }
    }

    private fun validateDisplay(
        display: DisplayProfile,
        errors: MutableList<String>,
    ) {
        if (display.deviceFamily.isBlank() || display.deviceFamily.length > 64) {
            errors += "显示设备标识不能为空且不能超过 64 个字符"
        }
        display.preferredRefreshRateHz?.let { refreshRate ->
            if (!refreshRate.isFinite() || refreshRate !in 30.0..240.0) {
                errors += "首选刷新率必须在 30-240 Hz"
            }
        }
        if (display.cueDurationMillis != PersonalCaptureSpec.PREPARE_MILLIS) errors += "准备时长必须为 4 秒"
        if (display.activeDurationMillis != PersonalCaptureSpec.ACTIVE_MILLIS) errors += "刺激时长必须为 60 秒"
        if (display.feedbackDurationMillis != PersonalCaptureSpec.REST_MILLIS) errors += "休息时长必须为 6 秒"
        if (display.targetWidthRatio != PersonalCaptureSpec.TARGET_WIDTH_RATIO ||
            display.targetHeightRatio != PersonalCaptureSpec.TARGET_HEIGHT_RATIO
        ) errors += "采集目标尺寸必须使用单目标预置"
    }

    private fun validateUser(user: UserCalibrationProfile, errors: MutableList<String>) {
        if (!userIdPattern.matches(user.userId)) errors += "用户标识格式无效"
    }

}

object DeviceProfilePresets {
    private fun base(
        profileId: String,
        displayName: String,
        kind: DeviceProfileKind,
        adapter: AmplifierAdapter,
        hosts: List<String>,
        port: Int,
        streamFormat: NeuracleStreamFormat,
        deviceFamily: String,
        rawSampleRateHz: Int = 1000,
        eegChannelCount: Int = 30,
        eventChannelCount: Int = 0,
        packetSamples: Int = 40,
        channelMap: Map<Int, Int> = NeuracleChannelMapping.defaultDeviceToModel,
    ): DeviceProfile {
        return DeviceProfile(
            schemaVersion = DeviceProfileJson.CURRENT_SCHEMA_VERSION,
            profileId = profileId,
            displayName = displayName,
            kind = kind,
            amplifier = AmplifierProfile(
                adapter = adapter,
                hosts = hosts,
                port = port,
                streamFormat = streamFormat,
                rawSampleRateHz = rawSampleRateHz,
                eegChannelCount = eegChannelCount,
                eventChannelCount = eventChannelCount,
                channelCount = eegChannelCount + eventChannelCount,
                packetSamples = packetSamples,
                channelMap = channelMap,
            ),
            display = DisplayProfile(
                deviceFamily = deviceFamily,
                preferredRefreshRateHz = null,
                cueDurationMillis = PersonalCaptureSpec.PREPARE_MILLIS,
                activeDurationMillis = PersonalCaptureSpec.ACTIVE_MILLIS,
                feedbackDurationMillis = PersonalCaptureSpec.REST_MILLIS,
                targetWidthRatio = PersonalCaptureSpec.TARGET_WIDTH_RATIO,
                targetHeightRatio = PersonalCaptureSpec.TARGET_HEIGHT_RATIO,
                debugOverlayDefault = false,
            ),
            user = UserCalibrationProfile(
                userId = "default",
            ),
        )
    }

    fun emulator(): DeviceProfile = base(
        profileId = "emulator-safe",
        displayName = "模拟器 Recorder 测试（需真实 TCP）",
        kind = DeviceProfileKind.SIMULATOR,
        adapter = AmplifierAdapter.MOCK_NEURACLE_TCP,
        hosts = listOf("10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-emulator",
        eegChannelCount = 8,
        eventChannelCount = 1,
        channelMap = NeuracleChannelMapping.recorderSsvep8ToModel,
    )

    fun mockRecorder(): DeviceProfile = base(
        profileId = "mock-recorder-f32",
        displayName = "模拟 Recorder F32",
        kind = DeviceProfileKind.MOCK_HARDWARE,
        adapter = AmplifierAdapter.MOCK_NEURACLE_TCP,
        hosts = listOf("10.100.30.46", "10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-emulator",
    )

    fun neuracleRecorder(): DeviceProfile = base(
        profileId = "neuracle-recorder-f32-250",
        displayName = "其他设备 Recorder F32 250 Hz",
        kind = DeviceProfileKind.HARDWARE_TEMPLATE,
        adapter = AmplifierAdapter.NEURACLE_TCP,
        hosts = listOf("10.100.30.46", "10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-generic",
        rawSampleRateHz = 250,
        eegChannelCount = 32,
        eventChannelCount = 1,
        packetSamples = 10,
    )

    fun neuracleRecorderWithoutEvent(): DeviceProfile = base(
        profileId = "neuracle-recorder-f32-250-no-event",
        displayName = "其他设备 Recorder F32 250 Hz（无 event）",
        kind = DeviceProfileKind.HARDWARE_TEMPLATE,
        adapter = AmplifierAdapter.NEURACLE_TCP,
        hosts = listOf("10.100.30.46", "10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-generic",
        rawSampleRateHz = 250,
        eegChannelCount = 32,
        eventChannelCount = 0,
        packetSamples = 10,
    )

    fun neuracleRecorder1000(): DeviceProfile = base(
        profileId = "neuracle-recorder-f32-1000",
        displayName = "NSW2BA0032 Recorder 1000 Hz（32+1 全通道）",
        kind = DeviceProfileKind.HARDWARE_TEMPLATE,
        adapter = AmplifierAdapter.NEURACLE_TCP,
        hosts = listOf("10.100.30.46", "10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-generic",
        eegChannelCount = 32,
        eventChannelCount = 1,
        channelMap = NeuracleChannelMapping.eegCap32V7ToModel,
    )

    fun neuracleNsw2ba0032SsvepMontage(): DeviceProfile = base(
        profileId = "neuracle-nsw2ba0032-ssvep8-1000",
        displayName = "NSW2BA0032 个人采集（8+1）",
        kind = DeviceProfileKind.HARDWARE_TEMPLATE,
        adapter = AmplifierAdapter.NEURACLE_TCP,
        hosts = listOf("10.100.30.46", "10.0.2.2"),
        port = 8712,
        streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        deviceFamily = "android-generic",
        rawSampleRateHz = 1000,
        eegChannelCount = 8,
        eventChannelCount = 1,
        packetSamples = 40,
        channelMap = NeuracleChannelMapping.recorderSsvep8ToModel,
    ).let { profile ->
        profile.copy(displayName = "NSW2BA0032 个人采集（8+1）")
    }

    fun legacy4097(): DeviceProfile = base(
        profileId = "neuracle-legacy-4097",
        displayName = "旧工程 Legacy 30 通道（协议未确认）",
        kind = DeviceProfileKind.HARDWARE_TEMPLATE,
        adapter = AmplifierAdapter.NEURACLE_TCP,
        hosts = listOf("192.168.31.47"),
        port = 4097,
        streamFormat = NeuracleStreamFormat.LEGACY_INT32_CHANNEL_MAJOR,
        deviceFamily = "android-generic",
    )

    /**
     * User-facing presets. Legacy, 32-channel and diagnostic-only profiles remain
     * available to migration/tests but are intentionally hidden from this list.
     */
    fun all(): List<DeviceProfile> = listOf(
        neuracleNsw2ba0032SsvepMontage(),
        neuracleRecorder1000(),
        emulator(),
    )
}
