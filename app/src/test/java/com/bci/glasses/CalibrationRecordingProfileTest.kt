package com.bci.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationRecordingProfileTest {
    @Test
    fun supportsBothInstalledRecorderMontagesAndFindsOccipitalChannels() {
        val eight = DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage()
        val thirtyTwo = DeviceProfilePresets.neuracleRecorder1000()

        assertNull(CalibrationRecordingProfile.incompatibilityReason(eight))
        assertNull(CalibrationRecordingProfile.incompatibilityReason(thirtyTwo))
        assertArrayEquals(intArrayOf(1, 2, 6, 5, 7), CalibrationRecordingProfile.occipitalRawIndices(eight))
        assertArrayEquals(intArrayOf(27, 28, 30, 29, 31), CalibrationRecordingProfile.occipitalRawIndices(thirtyTwo))
        assertEquals(listOf("PO3", "PO4", "O1", "Oz", "O2"), CalibrationRecordingProfile.occipitalRawChannelNames(thirtyTwo))
        assertEquals("Event", CalibrationRecordingProfile.rawChannelNames(thirtyTwo).last())
    }

    @Test
    fun rejectsRecorderStreamWithoutTheRequiredEventChannel() {
        val profile = DeviceProfilePresets.neuracleRecorder1000().copy(
            amplifier = DeviceProfilePresets.neuracleRecorder1000().amplifier.copy(
                eventChannelCount = 0,
                channelCount = 32,
            ),
        )

        assertTrue(CalibrationRecordingProfile.incompatibilityReason(profile)!!.contains("1 路 event"))
    }
}
