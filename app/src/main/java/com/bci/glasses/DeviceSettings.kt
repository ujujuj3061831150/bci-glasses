package com.bci.glasses

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File

data class DeviceSettings(
    val profile: DeviceProfile,
) {
    val neuracleHosts: List<String> get() = profile.amplifier.hosts
    val neuraclePort: Int get() = profile.amplifier.port
    val neuracleStreamFormat: NeuracleStreamFormat get() = profile.amplifier.streamFormat
    val neuracleEegChannelCount: Int get() = profile.amplifier.eegChannelCount
    val neuracleEventChannelCount: Int get() = profile.amplifier.eventChannelCount
    val neuracleChannelCount: Int get() = profile.amplifier.channelCount
    val neuraclePacketSamples: Int get() = profile.amplifier.packetSamples
    val neuracleChannelMap: Map<Int, Int> get() = profile.amplifier.channelMap

    fun save(context: Context) {
        val validation = DeviceProfileValidator.validate(profile)
        validation.requireValid()
        val amplifier = profile.amplifier
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE_JSON, DeviceProfileJson.encode(profile))
            .putString(KEY_HOSTS, amplifier.hosts.joinToString(","))
            .putInt(KEY_NEURACLE_PORT, amplifier.port)
            .putString(KEY_NEURACLE_STREAM_FORMAT, amplifier.streamFormat.preferenceId)
            .putInt(KEY_NEURACLE_EEG_CHANNEL_COUNT, amplifier.eegChannelCount)
            .putInt(KEY_NEURACLE_EVENT_CHANNEL_COUNT, amplifier.eventChannelCount)
            .putInt(KEY_NEURACLE_CHANNEL_COUNT, amplifier.channelCount)
            .putInt(KEY_NEURACLE_PACKET_SAMPLES, amplifier.packetSamples)
            .putString(KEY_NEURACLE_CHANNEL_MAP, NeuracleChannelMapping.format(amplifier.channelMap))
            .remove(KEY_UDP_IP)
            .remove(KEY_UDP_PORT)
            .remove(KEY_UDP_SHARED_SECRET)
            .apply()
    }

    companion object {
        private const val TAG = "DeviceSettings"
        private const val PREFERENCES = "bci_device_settings"
        private const val KEY_PROFILE_JSON = "device_profile_json_v1"
        private const val KEY_HOSTS = "neuracle_hosts"
        private const val KEY_NEURACLE_PORT = "neuracle_port"
        private const val KEY_NEURACLE_STREAM_FORMAT = "neuracle_stream_format"
        private const val KEY_NEURACLE_EEG_CHANNEL_COUNT = "neuracle_eeg_channel_count"
        private const val KEY_NEURACLE_EVENT_CHANNEL_COUNT = "neuracle_event_channel_count"
        private const val KEY_NEURACLE_CHANNEL_COUNT = "neuracle_channel_count"
        private const val KEY_NEURACLE_PACKET_SAMPLES = "neuracle_packet_samples"
        private const val KEY_NEURACLE_CHANNEL_MAP = "neuracle_channel_map"
        private const val KEY_UDP_IP = "udp_bridge_ip"
        private const val KEY_UDP_PORT = "udp_bridge_port"
        private const val KEY_UDP_SHARED_SECRET = "udp_shared_secret"

        fun load(context: Context): DeviceSettings {
            removeLegacyModels(context)
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            preferences.getString(KEY_PROFILE_JSON, null)?.let { json ->
                runCatching { DeviceProfileJson.decode(json) }
                .onSuccess { profile ->
                        return DeviceSettings(profile).also { loaded ->
                            if (json != DeviceProfileJson.encode(profile)) loaded.save(context)
                        }
                    }
                    .onFailure { Log.e(TAG, "Stored profile is invalid; migrating legacy settings", it) }
            }

            val profile = if (hasLegacySettings(preferences)) {
                migrateLegacySettings(preferences)
            } else {
                // The installed SSVEP-8 Recorder montage is the default capture source.
                DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
            }
            return DeviceSettings(profile).also { it.save(context) }
        }

        fun presets(): List<DeviceProfile> = DeviceProfilePresets.all()

        fun isRunningOnEmulator(): Boolean = isEmulator()

        private fun removeLegacyModels(context: Context) {
            listOf(
                "control_ssvep_shift_aug_v3.onnx",
                "ssvep_5ch_public_v1.onnx",
                "ssvep_5ch_public_v1.onnx.data",
                "ssvep_eegnet_v1.onnx",
                "pretrain_best_alpha1_5.onnx",
            ).forEach { name ->
                val file = File(context.filesDir, name)
                if (file.exists() && !file.delete()) Log.w(TAG, "Could not remove legacy model: $name")
            }
        }

        private fun hasLegacySettings(preferences: SharedPreferences): Boolean {
            return listOf(
                KEY_HOSTS,
                KEY_NEURACLE_PORT,
                KEY_NEURACLE_STREAM_FORMAT,
                KEY_NEURACLE_EEG_CHANNEL_COUNT,
                KEY_NEURACLE_EVENT_CHANNEL_COUNT,
                KEY_NEURACLE_CHANNEL_COUNT,
                KEY_NEURACLE_PACKET_SAMPLES,
                KEY_NEURACLE_CHANNEL_MAP,
                KEY_UDP_IP,
                KEY_UDP_PORT,
                KEY_UDP_SHARED_SECRET,
            ).any(preferences::contains)
        }

        private fun migrateLegacySettings(preferences: SharedPreferences): DeviceProfile {
            val base = if (isEmulator()) DeviceProfilePresets.emulator()
                else DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
            val defaultHosts = base.amplifier.hosts.joinToString(",")
            val hosts = preferences.getString(KEY_HOSTS, defaultHosts)
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .ifEmpty { base.amplifier.hosts }
            val port = preferences.getInt(KEY_NEURACLE_PORT, base.amplifier.port).coerceIn(1, 65535)
            val streamFormat = NeuracleStreamFormat.fromPreference(
                preferences.getString(KEY_NEURACLE_STREAM_FORMAT, null)
            ) ?: NeuracleStreamFormat.inferFromPort(port)
            val channelCount = preferences.getInt(KEY_NEURACLE_CHANNEL_COUNT, base.amplifier.channelCount)
                .coerceIn(1, 256)
            val configuredEegCount = preferences.getInt(KEY_NEURACLE_EEG_CHANNEL_COUNT, channelCount)
                .coerceIn(1, channelCount)
            val configuredEventCount = preferences.getInt(
                KEY_NEURACLE_EVENT_CHANNEL_COUNT,
                channelCount - configuredEegCount,
            ).coerceIn(0, channelCount - configuredEegCount)
            val eegChannelCount: Int
            val eventChannelCount: Int
            if (configuredEegCount + configuredEventCount == channelCount) {
                eegChannelCount = configuredEegCount
                eventChannelCount = configuredEventCount
            } else {
                eegChannelCount = channelCount
                eventChannelCount = 0
            }
            val defaultChannelMap = NeuracleChannelMapping.format(base.amplifier.channelMap)
            val channelMap = runCatching {
                NeuracleChannelMapping.parse(
                    preferences.getString(KEY_NEURACLE_CHANNEL_MAP, defaultChannelMap) ?: defaultChannelMap,
                    eegChannelCount,
                )
            }.getOrElse { base.amplifier.channelMap }
            val migrated = base.copy(
                profileId = "migrated-v043",
                displayName = "从 v0.4.3 迁移的配置",
                amplifier = base.amplifier.copy(
                    adapter = if (isEmulator()) {
                        AmplifierAdapter.MOCK_NEURACLE_TCP
                    } else {
                        AmplifierAdapter.NEURACLE_TCP
                    },
                    hosts = hosts,
                    port = port,
                    streamFormat = streamFormat,
                    eegChannelCount = eegChannelCount,
                    eventChannelCount = eventChannelCount,
                    channelCount = channelCount,
                    packetSamples = preferences.getInt(
                        KEY_NEURACLE_PACKET_SAMPLES,
                        base.amplifier.packetSamples,
                    ).coerceIn(1, 1000),
                    channelMap = channelMap,
                ),
            )
            return if (DeviceProfileValidator.validate(migrated).isValid) migrated
                else DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
        }

        private fun isEmulator(): Boolean {
            val identity = listOf(
                Build.FINGERPRINT,
                Build.MODEL,
                Build.MANUFACTURER,
                Build.HARDWARE,
                Build.PRODUCT,
            ).joinToString(" ").lowercase()
            return Build.SUPPORTED_ABIS.any { it.startsWith("x86") } ||
                listOf("generic", "emulator", "mumu", "netease", "vbox", "goldfish", "ranchu")
                    .any(identity::contains)
        }
    }
}
