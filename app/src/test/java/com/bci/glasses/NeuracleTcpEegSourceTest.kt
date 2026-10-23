package com.bci.glasses

import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuracleTcpEegSourceTest {
    @Test
    fun decodesOfficialRecorderFloat32SampleMajorPackets() {
        val decoder = NeuraclePacketDecoder(
            nChannels = 3,
            packetSamples = 2,
            deviceTo32Map = mapOf(0 to 28, 1 to 29),
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        )
        val packet = ByteBuffer.allocate(decoder.packetBytes).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(1.5f, 2.5f, 99f, 3.5f, 4.5f, 100f).forEach(packet::putFloat)

        val decoded = decoder.decode(packet.array())

        assertEquals(1.5, decoded[28][0], 1e-6)
        assertEquals(3.5, decoded[28][1], 1e-6)
        assertEquals(2.5, decoded[29][0], 1e-6)
        assertEquals(4.5, decoded[29][1], 1e-6)
    }

    @Test
    fun decodesW32RecorderSamplesWhileIgnoringTrailingEventChannel() {
        val decoder = NeuraclePacketDecoder(
            nChannels = 33,
            eegChannelCount = 32,
            packetSamples = 10,
            deviceTo32Map = mapOf(0 to 28, 31 to 29),
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
        )
        val packet = ByteBuffer.allocate(decoder.packetBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(10) { sample ->
            repeat(32) { channel -> packet.putFloat((sample * 100 + channel).toFloat()) }
            packet.putFloat(if (sample == 4) 7f else 0f)
        }

        val decoded = decoder.decode(packet.array())

        assertEquals(0.0, decoded[28][0], 1e-6)
        assertEquals(900.0, decoded[28][9], 1e-6)
        assertEquals(31.0, decoded[29][0], 1e-6)
        assertEquals(931.0, decoded[29][9], 1e-6)
    }

    @Test
    fun decoderRejectsMappingTheTrailingEventChannelAsEeg() {
        assertThrows(IllegalArgumentException::class.java) {
            NeuraclePacketDecoder(
                nChannels = 33,
                eegChannelCount = 32,
                packetSamples = 10,
                deviceTo32Map = mapOf(32 to 0),
                streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
            )
        }
    }

    @Test
    fun decodesLegacyInt32ChannelMajorPackets() {
        val decoder = NeuraclePacketDecoder(
            nChannels = 2,
            packetSamples = 3,
            deviceTo32Map = mapOf(0 to 28, 1 to 29),
            streamFormat = NeuracleStreamFormat.LEGACY_INT32_CHANNEL_MAJOR,
        )
        val packet = ByteBuffer.allocate(decoder.packetBytes).order(ByteOrder.LITTLE_ENDIAN)
        intArrayOf(10, 20, 30, 40, 50, 60).forEach(packet::putInt)

        val decoded = decoder.decode(packet.array())
        val scale = NeuraclePacketDecoder.LEGACY_MICROVOLTS_PER_COUNT

        assertEquals(10 * scale, decoded[28][0], 1e-12)
        assertEquals(30 * scale, decoded[28][2], 1e-12)
        assertEquals(40 * scale, decoded[29][0], 1e-12)
        assertEquals(60 * scale, decoded[29][2], 1e-12)
    }

    @Test
    fun streamRateEvaluatorRejectsWrongProtocolRate() {
        val evaluator = StreamRateEvaluator(expectedSampleRateHz = 1000)

        assertTrue(evaluator.evaluate(2_000, 2_000_000_000).healthy)
        assertTrue(evaluator.evaluate(1_960, 2_000_000_000).healthy)
        assertFalse(evaluator.evaluate(1_900, 2_000_000_000).healthy)
        assertFalse(evaluator.evaluate(1_000, 2_000_000_000).healthy)
    }

    @Test
    fun providesFreshWindowThenInvalidatesItAfterDisconnect() {
        val server = ServerSocket(0)
        val releaseConnection = CountDownLatch(1)
        val serverExecutor = Executors.newSingleThreadExecutor()
        serverExecutor.execute {
            server.accept().use { socket ->
                val packet = ByteBuffer.allocate(30 * 40 * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(100) { packetIndex ->
                    packet.clear()
                    repeat(40) { sample ->
                        repeat(30) { channel ->
                            packet.putFloat((packetIndex * 40 + sample + channel / 100.0).toFloat())
                        }
                    }
                    socket.getOutputStream().write(packet.array())
                }
                socket.getOutputStream().flush()
                releaseConnection.await(5, TimeUnit.SECONDS)
            }
        }

        val source = NeuracleTcpEegSource(
            hosts = listOf("127.0.0.1"),
            port = server.localPort,
            nChannels = 30,
            packetSamples = 40,
            rawSampleRateHz = 1000,
            deviceTo32Map = (0 until 30).associateWith { it },
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
            freshnessTimeoutMillis = 250,
        )

        try {
            var liveWindow: EegWindow? = null
            repeat(100) {
                val candidate = source.nextWindow(4.0)
                if (candidate.dataAvailable) {
                    liveWindow = candidate
                    return@repeat
                }
                Thread.sleep(20)
            }
            assertTrue("Expected a complete fresh TCP window", liveWindow?.dataAvailable == true)
            assertTrue(liveWindow?.channels?.firstOrNull()?.size == 4000)

            releaseConnection.countDown()
            Thread.sleep(500)
            val disconnected = source.nextWindow(4.0)
            assertFalse("Disconnected source must not reuse the old window", disconnected.dataAvailable)
        } finally {
            releaseConnection.countDown()
            source.close()
            server.close()
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun acceptsLargeSocketReadsWhenConfiguredPacketsAreSmall() {
        val server = ServerSocket(0)
        val releaseConnection = CountDownLatch(1)
        val serverExecutor = Executors.newSingleThreadExecutor()
        serverExecutor.execute {
            server.accept().use { socket ->
                val payload = ByteBuffer.allocate(5_000 * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(5_000) { payload.putFloat(it.toFloat()) }
                socket.getOutputStream().write(payload.array())
                socket.getOutputStream().flush()
                releaseConnection.await(5, TimeUnit.SECONDS)
            }
        }

        val source = NeuracleTcpEegSource(
            hosts = listOf("127.0.0.1"),
            port = server.localPort,
            nChannels = 1,
            packetSamples = 1,
            rawSampleRateHz = 1000,
            deviceTo32Map = mapOf(0 to 0),
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
            freshnessTimeoutMillis = 1_000,
        )

        try {
            var window: EegWindow? = null
            repeat(100) {
                val candidate = source.nextWindow(0.004)
                if (candidate.dataAvailable) {
                    window = candidate
                    return@repeat
                }
                Thread.sleep(20)
            }
            assertTrue("Expected small packets to survive a socket read larger than 4096 bytes", window?.dataAvailable == true)
            assertEquals(4, window?.channels?.firstOrNull()?.size)
        } finally {
            releaseConnection.countDown()
            source.close()
            server.close()
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun providesFourSecondWindowFromOfficialStyle250HzW32Stream() {
        val server = ServerSocket(0)
        val releaseConnection = CountDownLatch(1)
        val serverExecutor = Executors.newSingleThreadExecutor()
        serverExecutor.execute {
            server.accept().use { socket ->
                val packet = ByteBuffer.allocate(33 * 10 * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(100) { packetIndex ->
                    packet.clear()
                    repeat(10) { sampleInPacket ->
                        val sample = packetIndex * 10 + sampleInPacket
                        repeat(32) { channel ->
                            packet.putFloat((sample * 100 + channel).toFloat())
                        }
                        packet.putFloat(if (sample == 500) 1f else 0f)
                    }
                    socket.getOutputStream().write(packet.array())
                }
                socket.getOutputStream().flush()
                releaseConnection.await(5, TimeUnit.SECONDS)
            }
        }

        val source = NeuracleTcpEegSource(
            hosts = listOf("127.0.0.1"),
            port = server.localPort,
            nChannels = 33,
            eegChannelCount = 32,
            eventChannelCount = 1,
            packetSamples = 10,
            rawSampleRateHz = 250,
            deviceTo32Map = mapOf(0 to 0, 31 to 30),
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
            freshnessTimeoutMillis = 1_000,
        )

        try {
            var window: EegWindow? = null
            repeat(100) {
                val candidate = source.nextWindow(4.0)
                if (candidate.dataAvailable) {
                    window = candidate
                    return@repeat
                }
                Thread.sleep(20)
            }
            assertTrue("Expected a complete 250 Hz TCP window", window?.dataAvailable == true)
            assertEquals(1_000, window?.channels?.firstOrNull()?.size)
            assertEquals(0.0, window?.channels?.get(0)?.get(0) ?: -1.0, 1e-6)
            assertEquals(99_900.0, window?.channels?.get(0)?.get(999) ?: -1.0, 1e-6)
            assertEquals(99_931.0, window?.channels?.get(30)?.get(999) ?: -1.0, 1e-6)
        } finally {
            releaseConnection.countDown()
            source.close()
            server.close()
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun acceptsInstalledSsvep8RecorderStreamShape() {
        val server = ServerSocket(0)
        val releaseConnection = CountDownLatch(1)
        val serverExecutor = Executors.newSingleThreadExecutor()
        serverExecutor.execute {
            server.accept().use { socket ->
                val packet = ByteBuffer.allocate(9 * 40 * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                repeat(100) { packetIndex ->
                    packet.clear()
                    repeat(40) { sampleInPacket ->
                        val sample = packetIndex * 40 + sampleInPacket
                        repeat(8) { channel ->
                            packet.putFloat((sample * 10 + channel).toFloat())
                        }
                        packet.putFloat(0f)
                    }
                    socket.getOutputStream().write(packet.array())
                }
                socket.getOutputStream().flush()
                releaseConnection.await(5, TimeUnit.SECONDS)
            }
        }

        val source = NeuracleTcpEegSource(
            hosts = listOf("127.0.0.1"),
            port = server.localPort,
            nChannels = 9,
            eegChannelCount = 8,
            eventChannelCount = 1,
            packetSamples = 40,
            rawSampleRateHz = 1000,
            deviceTo32Map = NeuracleChannelMapping.recorderSsvep8ToModel,
            streamFormat = NeuracleStreamFormat.RECORDER_FLOAT32_SAMPLE_MAJOR,
            freshnessTimeoutMillis = 1_000,
        )

        try {
            var window: EegWindow? = null
            repeat(100) {
                val candidate = source.nextWindow(4.0)
                if (candidate.dataAvailable) {
                    window = candidate
                    return@repeat
                }
                Thread.sleep(20)
            }
            assertTrue("Expected a complete 8 EEG + 1 event window", window?.dataAvailable == true)
            assertEquals(4_000, window?.channels?.firstOrNull()?.size)
            assertEquals(39_991.0, window?.channels?.get(26)?.get(3_999) ?: -1.0, 1e-6)
            assertEquals(39_997.0, window?.channels?.get(30)?.get(3_999) ?: -1.0, 1e-6)
        } finally {
            releaseConnection.countDown()
            source.close()
            server.close()
            serverExecutor.shutdownNow()
        }
    }
}
