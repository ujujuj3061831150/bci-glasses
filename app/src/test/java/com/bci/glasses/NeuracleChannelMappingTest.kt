package com.bci.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NeuracleChannelMappingTest {
    @Test
    fun defaultMappingRoundTripsWithoutChangingChannelOrder() {
        val specification = NeuracleChannelMapping.format(
            NeuracleChannelMapping.defaultDeviceToModel
        )

        val parsed = NeuracleChannelMapping.parse(specification, deviceChannelCount = 30)

        assertEquals(NeuracleChannelMapping.defaultDeviceToModel, parsed)
        assertEquals(29, parsed[0])
        assertEquals(28, parsed[1])
        assertEquals(30, parsed[2])
        assertEquals(26, parsed[3])
        assertEquals(27, parsed[4])
    }

    @Test
    fun acceptsCommaSemicolonAndLineSeparatedEntries() {
        val parsed = NeuracleChannelMapping.parse("0:29, 1:28;2:30\n3:26", 30)

        assertEquals(mapOf(0 to 29, 1 to 28, 2 to 30, 3 to 26), parsed)
    }

    @Test
    fun rejectsMalformedOrAmbiguousMappings() {
        assertThrows(IllegalArgumentException::class.java) {
            NeuracleChannelMapping.parse("0-29", 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NeuracleChannelMapping.parse("30:29", 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NeuracleChannelMapping.parse("0:29,1:29", 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NeuracleChannelMapping.parse("0:31", 30)
        }
    }

    @Test
    fun eegCap32V7KnownBadChannelsHaveStableReplacementDonors() {
        val replacements = NeuracleChannelMapping.badChannelReplacements(
            NeuracleChannelMapping.eegCap32V7ToModel,
        )

        assertEquals(setOf(1, 3, 4), replacements[0])
        assertEquals(setOf(3, 7, 11), replacements[2])
        assertEquals(setOf(4, 8, 10, 14), replacements[9])
        assertEquals(emptyMap<Int, Set<Int>>(), NeuracleChannelMapping.badChannelReplacements(emptyMap()))
    }
}
