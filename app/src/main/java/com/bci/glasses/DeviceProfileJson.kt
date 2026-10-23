package com.bci.glasses

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object DeviceProfileJson {
    const val CURRENT_SCHEMA_VERSION = 6

    fun encode(profile: DeviceProfile): String {
        DeviceProfileValidator.validate(profile).requireValid()
        return JSONObject()
            .put("schemaVersion", profile.schemaVersion)
            .put("profileId", profile.profileId)
            .put("displayName", profile.displayName)
            .put("kind", profile.kind.preferenceId)
            .put("amplifier", encodeAmplifier(profile.amplifier))
            .put("display", encodeDisplay(profile.display))
            .put("user", encodeUser(profile.user))
            .toString(2)
    }

    fun decode(json: String): DeviceProfile {
        val profile = try {
            val root = JSONObject(json)
            val sourceSchemaVersion = root.getInt("schemaVersion")
            require(sourceSchemaVersion in 1..CURRENT_SCHEMA_VERSION) {
                "不支持的 schemaVersion：$sourceSchemaVersion"
            }
            val profileId = root.getString("profileId")
            val decoded = DeviceProfile(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                profileId = profileId,
                displayName = root.getString("displayName"),
                kind = DeviceProfileKind.fromPreference(root.getString("kind"))
                    ?: throw IllegalArgumentException("未知档案类型：${root.getString("kind")}"),
                amplifier = decodeAmplifier(
                    root.getJSONObject("amplifier"),
                    sourceSchemaVersion,
                    profileId,
                ),
                display = decodeDisplay(root.getJSONObject("display")),
                user = decodeUser(root.getJSONObject("user")),
            )
            migrateKnownRecorderDefaults(decoded, sourceSchemaVersion)
        } catch (exception: JSONException) {
            throw IllegalArgumentException("配置 JSON 格式错误：${exception.message}", exception)
        }
        DeviceProfileValidator.validate(profile).requireValid()
        return profile
    }

    private fun encodeAmplifier(profile: AmplifierProfile): JSONObject {
        val hosts = JSONArray().apply { profile.hosts.forEach(::put) }
        val channelMap = JSONArray().apply {
            profile.channelMap.toSortedMap().forEach { (device, model) ->
                put(JSONObject().put("device", device).put("model", model))
            }
        }
        return JSONObject()
            .put("adapter", profile.adapter.preferenceId)
            .put("hosts", hosts)
            .put("port", profile.port)
            .put("streamFormat", profile.streamFormat.preferenceId)
            .put("rawSampleRateHz", profile.rawSampleRateHz)
            .put("eegChannelCount", profile.eegChannelCount)
            .put("eventChannelCount", profile.eventChannelCount)
            .put("channelCount", profile.channelCount)
            .put("packetSamples", profile.packetSamples)
            .put("channelMap", channelMap)
    }

    private fun decodeAmplifier(
        json: JSONObject,
        sourceSchemaVersion: Int,
        profileId: String,
    ): AmplifierProfile {
        val adapterId = json.getString("adapter")
        val formatId = json.getString("streamFormat")
        val channelCount = json.getInt("channelCount")
        val (eegChannelCount, eventChannelCount) = if (sourceSchemaVersion >= 4) {
            json.getInt("eegChannelCount") to json.getInt("eventChannelCount")
        } else if (
            profileId in setOf("neuracle-recorder-f32-250", "neuracle-recorder-f32-1000") &&
            channelCount == 33
        ) {
            32 to 1
        } else {
            channelCount to 0
        }
        val hostsJson = json.getJSONArray("hosts")
        val mapJson = json.getJSONArray("channelMap")
        val channelMap = linkedMapOf<Int, Int>()
        for (index in 0 until mapJson.length()) {
            val item = mapJson.getJSONObject(index)
            val device = item.getInt("device")
            require(device !in channelMap) { "设备通道重复：$device" }
            channelMap[device] = item.getInt("model")
        }
        return AmplifierProfile(
            adapter = AmplifierAdapter.fromPreference(adapterId)
                ?: throw IllegalArgumentException("未知放大器适配器：$adapterId"),
            hosts = List(hostsJson.length()) { hostsJson.getString(it) },
            port = json.getInt("port"),
            streamFormat = NeuracleStreamFormat.fromPreference(formatId)
                ?: throw IllegalArgumentException("未知数据档案：$formatId"),
            rawSampleRateHz = json.getInt("rawSampleRateHz"),
            eegChannelCount = eegChannelCount,
            eventChannelCount = eventChannelCount,
            channelCount = channelCount,
            packetSamples = json.getInt("packetSamples"),
            channelMap = channelMap,
        )
    }

    private fun encodeDisplay(profile: DisplayProfile): JSONObject {
        return JSONObject()
            .put("deviceFamily", profile.deviceFamily)
            .put("preferredRefreshRateHz", profile.preferredRefreshRateHz ?: JSONObject.NULL)
            .put("cueDurationMillis", profile.cueDurationMillis)
            .put("activeDurationMillis", profile.activeDurationMillis)
            .put("feedbackDurationMillis", profile.feedbackDurationMillis)
            .put("targetWidthRatio", profile.targetWidthRatio)
            .put("targetHeightRatio", profile.targetHeightRatio)
            .put("debugOverlayDefault", profile.debugOverlayDefault)
    }

    private fun decodeDisplay(json: JSONObject): DisplayProfile {
        return DisplayProfile(
            deviceFamily = json.getString("deviceFamily"),
            preferredRefreshRateHz = if (json.isNull("preferredRefreshRateHz")) {
                null
            } else {
                json.getDouble("preferredRefreshRateHz")
            },
            cueDurationMillis = PersonalCaptureSpec.PREPARE_MILLIS,
            activeDurationMillis = PersonalCaptureSpec.ACTIVE_MILLIS,
            feedbackDurationMillis = PersonalCaptureSpec.REST_MILLIS,
            targetWidthRatio = PersonalCaptureSpec.TARGET_WIDTH_RATIO,
            targetHeightRatio = PersonalCaptureSpec.TARGET_HEIGHT_RATIO,
            debugOverlayDefault = json.getBoolean("debugOverlayDefault"),
        )
    }

    private fun encodeUser(profile: UserCalibrationProfile): JSONObject {
        return JSONObject().put("userId", profile.userId)
    }

    private fun decodeUser(json: JSONObject): UserCalibrationProfile {
        return UserCalibrationProfile(
            userId = json.optString("userId", "default"),
        )
    }

    private fun migrateKnownRecorderDefaults(
        profile: DeviceProfile,
        sourceSchemaVersion: Int,
    ): DeviceProfile {
        if (sourceSchemaVersion >= 3) return profile
        val amplifier = profile.amplifier
        val isUnmodifiedRecorderTemplate =
            amplifier.adapter == AmplifierAdapter.NEURACLE_TCP &&
                amplifier.streamFormat == NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR &&
                amplifier.channelCount == 30 &&
                amplifier.packetSamples == 40 &&
                amplifier.channelMap == NeuracleChannelMapping.defaultDeviceToModel
        if (!isUnmodifiedRecorderTemplate) return profile

        val migratedAmplifier = when {
            profile.profileId == "neuracle-recorder-f32-250" &&
                amplifier.rawSampleRateHz == 250 -> amplifier.copy(
                eegChannelCount = 32,
                eventChannelCount = 1,
                channelCount = 33,
                packetSamples = 10,
            )
            profile.profileId == "neuracle-recorder-f32-1000" &&
                amplifier.rawSampleRateHz == 1000 -> amplifier.copy(
                eegChannelCount = 32,
                eventChannelCount = 1,
                channelCount = 33,
            )
            else -> amplifier
        }
        return profile.copy(amplifier = migratedAmplifier)
    }

}
