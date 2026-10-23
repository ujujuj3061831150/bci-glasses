package com.bci.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RokidCompatibilityTest {
    private val selector = SsvepDisplayModeSelector(doubleArrayOf(11.0))

    @Test
    fun selectsModeWithBestStimulusFrameLock() {
        val current = mode(1, 60.0)
        val selected = selector.select(
            current,
            listOf(mode(1, 60.0), mode(2, 90.0), mode(3, 120.0), mode(4, 110.0)),
        )

        assertEquals(4, selected.modeId)
        assertEquals(0.0, selector.frameLockError(selected.refreshRateHz), 1e-9)
    }

    @Test
    fun keepsSixtyHertzWhenItIsTheOnlyEligibleMode() {
        val current = mode(1, 60.0)
        val selected = selector.select(
            current,
            listOf(current, mode(2, 30.0), mode(3, 120.0, width = 1280)),
        )

        assertEquals(current, selected)
    }

    @Test
    fun exactProfileRefreshRateOverridesAutomaticSelection() {
        val preferredSelector = SsvepDisplayModeSelector(
            stimulusFrequenciesHz = doubleArrayOf(11.0),
            preferredRefreshRateHz = 90.0,
        )

        val selected = preferredSelector.select(
            mode(1, 60.0),
            listOf(mode(1, 60.0), mode(2, 90.0), mode(3, 120.0)),
        )

        assertEquals(2, selected.modeId)
    }

    @Test
    fun identifiesRokidMasterAndLeavesSpriteApkSupportUnproven() {
        val station = AndroidRuntimeProfileDetector.detect(
            brand = "Rokid",
            manufacturer = "Rokid",
            model = "Station 2",
            product = "YodaOS-Master",
            hardware = "rk",
            fingerprint = "rokid/station",
        )
        val glasses = AndroidRuntimeProfileDetector.detect(
            brand = "Rokid",
            manufacturer = "Rokid",
            model = "Rokid Glasses",
            product = "YodaOS-Sprite",
            hardware = "rk",
            fingerprint = "rokid/glasses",
        )

        assertEquals(AndroidRuntimeFamily.ROKID_MASTER, station.family)
        assertTrue(station.regularApkSupport == true)
        assertEquals(AndroidRuntimeFamily.ROKID_SPRITE, glasses.family)
        assertNull(glasses.regularApkSupport)
    }

    @Test
    fun identifiesSpoofedX86AndroidAsEmulator() {
        val profile = AndroidRuntimeProfileDetector.detect(
            brand = "OPPO",
            manufacturer = "OPPO",
            model = "PGBM10",
            product = "ali",
            hardware = "OPPO",
            fingerprint = "OPPO/ali:user/release-keys",
            supportedAbis = listOf("x86_64", "arm64-v8a"),
        )

        assertEquals(AndroidRuntimeFamily.EMULATOR, profile.family)
    }

    private fun mode(id: Int, refreshRate: Double, width: Int = 1920): DisplayModeSpec {
        return DisplayModeSpec(id, width, 1080, refreshRate)
    }
}
