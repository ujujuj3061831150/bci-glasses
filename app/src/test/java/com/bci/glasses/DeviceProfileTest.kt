package com.bci.glasses

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun captureProfileRoundTripPreservesDeviceAndUser() {
        val base = DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
        val profile = base.copy(
            amplifier = base.amplifier.copy(hosts = listOf("192.168.31.250")),
            display = base.display.copy(preferredRefreshRateHz = 120.0),
            user = base.user.copy(userId = "participant_01"),
        )
        assertEquals(profile, DeviceProfileJson.decode(DeviceProfileJson.encode(profile)))
        val json = JSONObject(DeviceProfileJson.encode(profile))
        assertFalse(json.has("control"))
        assertFalse(json.getJSONObject("user").has("modelAsset"))
    }

    @Test
    fun legacySchemaFiveDropsModelAndControlWithoutLosingAcquisitionParameters() {
        val profile = DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
        val json = JSONObject(DeviceProfileJson.encode(profile))
        json.put("schemaVersion", 5)
        json.getJSONObject("user")
            .put("modelAsset", "ssvep_5ch_public_v1.onnx")
            .put("decodeWindowMillis", 4000)
            .put("minConfidence", 0.7)
        json.put("control", JSONObject().put("sharedSecret", "0123456789abcdef"))
        json.getJSONObject("display")
            .put("cueDurationMillis", 500)
            .put("activeDurationMillis", 6500)
            .put("feedbackDurationMillis", 1400)
            .put("targetWidthRatio", 0.22)
            .put("targetHeightRatio", 0.25)

        val migrated = DeviceProfileJson.decode(json.toString())
        assertEquals(DeviceProfileJson.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(profile.amplifier, migrated.amplifier)
        assertEquals(profile.user, migrated.user)
        assertEquals(PersonalCaptureSpec.ACTIVE_MILLIS, migrated.display.activeDurationMillis)
        assertFalse(DeviceProfileJson.encode(migrated).contains("ssvep_5ch_public_v1"))
        assertFalse(DeviceProfileJson.encode(migrated).contains("sharedSecret"))
    }

    @Test
    fun mappingCannotIncludeEventChannelAndMustIncludeOccipitalSlots() {
        val base = DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
        val missing = base.copy(amplifier = base.amplifier.copy(channelMap = base.amplifier.channelMap - 7))
        assertFalse(DeviceProfileValidator.validate(missing).isValid)
        assertTrue(DeviceProfileValidator.validate(missing).errors.any { it.contains("枕区") })
        val eventMapped = base.copy(amplifier = base.amplifier.copy(channelMap = base.amplifier.channelMap + (8 to 18)))
        assertFalse(DeviceProfileValidator.validate(eventMapped).isValid)
    }

    @Test
    fun presetsOnlyOfferCompatibleRecorderCaptureSources() {
        assertTrue(DeviceProfilePresets.all().all {
            CalibrationRecordingProfile.incompatibilityReason(it) == null
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureSchema() {
        val json = JSONObject(DeviceProfileJson.encode(DeviceProfilePresets.emulator()))
        json.put("schemaVersion", 99)
        DeviceProfileJson.decode(json.toString())
    }
}
