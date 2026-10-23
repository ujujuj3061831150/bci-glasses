package com.bci.glasses

import kotlin.math.abs
import kotlin.math.round

data class DisplayModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRateHz: Double,
)

class SsvepDisplayModeSelector(
    private val stimulusFrequenciesHz: DoubleArray,
    private val preferredRefreshRateHz: Double? = null,
    private val minimumRefreshRateHz: Double = 59.0,
    private val minimumFramesPerCycle: Double = 4.0,
) {
    init {
        require(stimulusFrequenciesHz.isNotEmpty()) { "At least one stimulus frequency is required" }
        require(stimulusFrequenciesHz.all { it > 0.0 }) { "Stimulus frequencies must be positive" }
    }

    fun select(current: DisplayModeSpec, supported: List<DisplayModeSpec>): DisplayModeSpec {
        val requiredRefreshRate = maxOf(
            minimumRefreshRateHz,
            stimulusFrequenciesHz.max() * minimumFramesPerCycle,
        )
        val eligible = supported.filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight &&
                it.refreshRateHz >= requiredRefreshRate
        }
        if (eligible.isEmpty()) return current

        preferredRefreshRateHz?.let { preferred ->
            eligible.minByOrNull { abs(it.refreshRateHz - preferred) }?.let { closest ->
                if (abs(closest.refreshRateHz - preferred) <= 1.0) return closest
            }
        }

        val preferred = preferredRefreshRateHz ?: 120.0
        return eligible.minWith(
            compareBy<DisplayModeSpec> { frameLockError(it.refreshRateHz) }
                .thenBy { abs(it.refreshRateHz - preferred) }
                .thenByDescending { it.refreshRateHz }
        )
    }

    fun frameLockError(refreshRateHz: Double): Double {
        return stimulusFrequenciesHz.sumOf { frequency ->
            val framesPerCycle = refreshRateHz / frequency
            abs(framesPerCycle - round(framesPerCycle))
        }
    }
}

enum class AndroidRuntimeFamily {
    ROKID_MASTER,
    ROKID_SPRITE,
    ROKID_UNKNOWN,
    EMULATOR,
    GENERIC_ANDROID,
}

data class AndroidRuntimeProfile(
    val family: AndroidRuntimeFamily,
    val identity: String,
    val regularApkSupport: Boolean?,
) {
    val summary: String
        get() = "${family.name} · APK=${regularApkSupport ?: "unverified"} · $identity"
}

object AndroidRuntimeProfileDetector {
    fun detect(
        brand: String,
        manufacturer: String,
        model: String,
        product: String,
        hardware: String,
        fingerprint: String,
        supportedAbis: List<String> = emptyList(),
    ): AndroidRuntimeProfile {
        val identity = (listOf(brand, manufacturer, model, product, hardware, fingerprint) + supportedAbis)
            .joinToString(" ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val normalized = identity.lowercase()
        val family = when {
            normalized.contains("rokid") &&
                listOf("station", "master", "max pro", "max2", "max 2").any(normalized::contains) ->
                AndroidRuntimeFamily.ROKID_MASTER
            normalized.contains("rokid") &&
                listOf("sprite", "glasses", "glass").any(normalized::contains) ->
                AndroidRuntimeFamily.ROKID_SPRITE
            normalized.contains("rokid") -> AndroidRuntimeFamily.ROKID_UNKNOWN
            listOf("generic", "emulator", "mumu", "netease", "vbox", "goldfish", "ranchu")
                .any(normalized::contains) || supportedAbis.any { it.startsWith("x86") } ->
                AndroidRuntimeFamily.EMULATOR
            else -> AndroidRuntimeFamily.GENERIC_ANDROID
        }
        val apkSupport = when (family) {
            AndroidRuntimeFamily.ROKID_MASTER -> true
            AndroidRuntimeFamily.EMULATOR,
            AndroidRuntimeFamily.GENERIC_ANDROID -> true
            AndroidRuntimeFamily.ROKID_SPRITE,
            AndroidRuntimeFamily.ROKID_UNKNOWN -> null
        }
        return AndroidRuntimeProfile(family, identity, apkSupport)
    }
}
