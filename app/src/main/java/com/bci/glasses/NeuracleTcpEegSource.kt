package com.bci.glasses

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

data class StreamRateStatus(
    val measuredSampleRateHz: Double,
    val healthy: Boolean,
)

data class EegSourceStatus(
    val sourceName: String,
    val sourceStatus: String,
    val dataAvailable: Boolean,
    val isLive: Boolean,
    val sequenceId: Long,
    val receivedAtNanos: Long,
)

class StreamRateEvaluator(
    private val expectedSampleRateHz: Int,
    private val toleranceRatio: Double = 0.03,
) {
    fun evaluate(samplesReceived: Long, elapsedNanos: Long): StreamRateStatus {
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val measured = if (elapsedSeconds > 0.0) samplesReceived / elapsedSeconds else 0.0
        val relativeError = abs(measured - expectedSampleRateHz) / expectedSampleRateHz
        return StreamRateStatus(
            measuredSampleRateHz = measured,
            healthy = elapsedSeconds > 0.0 && relativeError <= toleranceRatio,
        )
    }
}

class NeuracleTcpEegSource(
    private val hosts: List<String>,
    private val port: Int,
    private val nChannels: Int,
    private val eegChannelCount: Int = nChannels,
    private val eventChannelCount: Int = 0,
    private val packetSamples: Int,
    private val rawSampleRateHz: Int,
    private val deviceTo32Map: Map<Int, Int>,
    private val streamFormat: NeuracleStreamFormat,
    private val freshnessTimeoutMillis: Long = 500,
) : EegWindowSource, Closeable {
    private val packetDecoder = NeuraclePacketDecoder(
        nChannels = nChannels,
        eegChannelCount = eegChannelCount,
        packetSamples = packetSamples,
        deviceTo32Map = deviceTo32Map,
        streamFormat = streamFormat,
    )
    private val maxBufferedSamples = rawSampleRateHz * 20
    private val samples = ArrayDeque<DoubleArray>()
    private val lock = Any()
    private val running = AtomicBoolean(true)
    private val receiveThread: Thread
    private val rateEvaluator = StreamRateEvaluator(rawSampleRateHz)
    private val sampleClockEstimator = SampleClockEstimator(rawSampleRateHz, packetSamples)

    @Volatile private var sourceState = "connecting"
    @Volatile private var sourceName = "Neuracle TCP"
    @Volatile private var currentSocket: Socket? = null
    @Volatile private var lastSampleNanos = 0L
    @Volatile private var measuredSampleRateHz = 0.0
    @Volatile private var streamRateHealthy = true
    @Volatile private var rawPacketObserver: NeuracleRawPacketObserver? = null
    private var totalSamplesReceived = 0L
    private var rateWindowStartNanos = 0L
    private var rateWindowSamples = 0L

    init {
        require(eegChannelCount > 0) { "EEG channel count must be positive" }
        require(eventChannelCount >= 0) { "Event channel count must not be negative" }
        require(eegChannelCount + eventChannelCount == nChannels) {
            "Total channels must equal EEG channels plus event channels"
        }
        receiveThread = Thread({ receiveLoop() }, "NeuracleTcpEegSource").apply {
            isDaemon = true
            start()
        }
    }

    fun setRawPacketObserver(observer: NeuracleRawPacketObserver?) {
        rawPacketObserver = observer
    }

    fun estimateSequenceAt(frameTimeNanos: Long): SampleSequenceEstimate {
        val floor = synchronized(lock) { totalSamplesReceived }
        return sampleClockEstimator.estimate(frameTimeNanos, floor)
    }

    fun statusSnapshot(requiredDurationSeconds: Double): EegSourceStatus {
        val requiredSamples = (rawSampleRateHz * requiredDurationSeconds).toInt().coerceAtLeast(1)
        val nowNanos = System.nanoTime()
        return synchronized(lock) {
            val ageMillis = if (lastSampleNanos == 0L) {
                Long.MAX_VALUE
            } else {
                (nowNanos - lastSampleNanos) / 1_000_000L
            }
            val state = when {
                ageMillis > freshnessTimeoutMillis && lastSampleNanos != 0L -> "stale"
                else -> sourceState
            }
            EegSourceStatus(
                sourceName = sourceName,
                sourceStatus = statusText(state),
                dataAvailable = state == "connected" && samples.size >= requiredSamples,
                isLive = true,
                sequenceId = totalSamplesReceived,
                receivedAtNanos = lastSampleNanos,
            )
        }
    }

    override fun nextWindow(durationSeconds: Double): EegWindow {
        val requiredSamples = (rawSampleRateHz * durationSeconds).toInt()
        val ageMillis = if (lastSampleNanos == 0L) {
            Long.MAX_VALUE
        } else {
            (System.nanoTime() - lastSampleNanos) / 1_000_000
        }

        val snapshot = synchronized(lock) {
            if (
                sourceState != "connected" ||
                ageMillis > freshnessTimeoutMillis ||
                samples.size < requiredSamples
            ) {
                null
            } else {
                WindowSnapshot(samples.takeLast(requiredSamples), totalSamplesReceived)
            }
        }

        if (snapshot == null) {
            val status = when {
                ageMillis > freshnessTimeoutMillis && lastSampleNanos != 0L -> "stale"
                else -> sourceState
            }
            return EegWindow(
                channels = emptyArray(),
                expectedLabel = null,
                sourceName = sourceName,
                sourceStatus = statusText(status),
                dataAvailable = false,
                isLive = true,
            )
        }

        val channels = Array(32) { channelIndex ->
            DoubleArray(requiredSamples) { sampleIndex ->
                snapshot.samples[sampleIndex][channelIndex]
            }
        }
        return EegWindow(
            channels = channels,
            expectedLabel = null,
            sourceName = sourceName,
            sourceStatus = statusText("connected"),
            dataAvailable = true,
            isLive = true,
            sequenceId = snapshot.sequenceId,
            receivedAtNanos = lastSampleNanos,
        )
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        currentSocket?.runCatching { close() }
        receiveThread.interrupt()
        runCatching { receiveThread.join(1_000) }
        invalidateBuffer("closed")
    }

    private fun receiveLoop() {
        while (running.get()) {
            var connectedOnce = false
            for (host in hosts) {
                if (!running.get()) return
                if (tryConnect(host)) {
                    connectedOnce = true
                    break
                }
            }
            if (!running.get()) return
            sourceState = "waiting"
            try {
                Thread.sleep(if (connectedOnce) 300 else 1_200)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun tryConnect(host: String): Boolean {
        var established = false
        return try {
            Socket().use { socket ->
                currentSocket = socket
                sourceState = "connecting"
                socket.connect(InetSocketAddress(host, port), 1_500)
                established = true
                socket.soTimeout = 1_000
                sourceName = "Neuracle TCP $host:$port"
                invalidateBuffer("buffering")
                receivePackets(socket.getInputStream())
            }
            established
        } catch (exception: Exception) {
            if (running.get()) {
                Log.w(TAG, "TCP stream ended for $host:$port", exception)
            }
            established
        } finally {
            currentSocket = null
            if (established) rawPacketObserver?.onStreamInterrupted(if (running.get()) "disconnected" else "closed")
            invalidateBuffer(if (running.get()) "disconnected" else "closed")
        }
    }

    private fun receivePackets(input: InputStream) {
        val packetBytes = packetDecoder.packetBytes
        val chunk = ByteArray(4096)
        val pending = ByteArray(packetBytes + chunk.size)
        var pendingLength = 0

        while (running.get()) {
            val read = input.read(chunk)
            if (read <= 0) return
            chunk.copyInto(pending, pendingLength, 0, read)
            pendingLength += read

            var offset = 0
            while (pendingLength - offset >= packetBytes) {
                parsePacket(pending, offset, packetBytes)
                offset += packetBytes
            }
            if (offset > 0) {
                pending.copyInto(pending, 0, offset, pendingLength)
                pendingLength -= offset
            }
        }
    }

    private fun parsePacket(packet: ByteArray, offset: Int, packetBytes: Int) {
        val rawBytes = packet.copyOfRange(offset, offset + packetBytes)
        val selected = packetDecoder.decode(packet, offset)
        val nowNanos = System.nanoTime()
        val wallTimeMillis = System.currentTimeMillis()
        var sequenceStart = 0L
        var sequenceEnd = 0L

        synchronized(lock) {
            sequenceStart = totalSamplesReceived
            for (sample in 0 until packetSamples) {
                samples.addLast(DoubleArray(32) { channel -> selected[channel][sample] })
                totalSamplesReceived += 1
                while (samples.size > maxBufferedSamples) {
                    samples.removeFirst()
                }
            }
            sequenceEnd = totalSamplesReceived
            lastSampleNanos = nowNanos
            updateStreamRate(packetSamples.toLong(), nowNanos)
            sourceState = when {
                !streamRateHealthy -> "rate-mismatch"
                samples.size >= rawSampleRateHz * 4 -> "connected"
                else -> "buffering"
            }
        }
        sampleClockEstimator.addPacket(sequenceEnd, nowNanos)
        rawPacketObserver?.onRawPacket(
            RawPacketSnapshot(
                bytes = rawBytes,
                sequenceStart = sequenceStart,
                sequenceEnd = sequenceEnd,
                arrivalNanos = nowNanos,
                wallTimeMillis = wallTimeMillis,
            ),
        )
    }

    private fun updateStreamRate(newSamples: Long, nowNanos: Long) {
        if (rateWindowStartNanos == 0L) rateWindowStartNanos = nowNanos
        rateWindowSamples += newSamples
        val elapsedNanos = nowNanos - rateWindowStartNanos
        if (elapsedNanos < 2_000_000_000L) return

        val rate = rateEvaluator.evaluate(rateWindowSamples, elapsedNanos)
        measuredSampleRateHz = rate.measuredSampleRateHz
        streamRateHealthy = rate.healthy
        rateWindowStartNanos = nowNanos
        rateWindowSamples = 0L
    }

    private fun invalidateBuffer(newState: String) {
        synchronized(lock) {
            samples.clear()
            lastSampleNanos = 0L
            measuredSampleRateHz = 0.0
            streamRateHealthy = true
            rateWindowStartNanos = 0L
            rateWindowSamples = 0L
            sourceState = newState
        }
    }

    private fun statusText(state: String): String {
        val rate = measuredSampleRateHz
        return when {
            state == "connected" && rate > 0.0 ->
                "connected ${rate.roundToInt()}Hz · EEG$eegChannelCount+event$eventChannelCount · " +
                    "map${deviceTo32Map.size} · ${streamFormat.statusLabel}"
            state == "rate-mismatch" ->
                "rate-mismatch ${rate.roundToInt()}Hz (expected ${rawSampleRateHz}Hz)"
            state == "buffering" ->
                "buffering · ${rawSampleRateHz}Hz · EEG$eegChannelCount+event$eventChannelCount · " +
                    "map${deviceTo32Map.size} · ${streamFormat.statusLabel}"
            else -> state
        }
    }

    private data class WindowSnapshot(
        val samples: List<DoubleArray>,
        val sequenceId: Long,
    )

    private companion object {
        const val TAG = "NeuracleTcpEegSource"
    }
}
