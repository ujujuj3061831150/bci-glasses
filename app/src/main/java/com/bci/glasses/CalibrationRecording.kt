package com.bci.glasses

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class RawPacketSnapshot(
    val bytes: ByteArray,
    val sequenceStart: Long,
    val sequenceEnd: Long,
    val arrivalNanos: Long,
    val wallTimeMillis: Long,
)

interface NeuracleRawPacketObserver {
    fun onRawPacket(packet: RawPacketSnapshot)
    fun onStreamInterrupted(reason: String)
}

data class CalibrationMarker(
    val blockId: Int,
    val trialId: Int,
    val eventCode: Int,
    val eventType: String,
    val condition: CalibrationCondition?,
    val frameTimeNanos: Long,
    val wallTimeMillis: Long,
    val sequence: SampleSequenceEstimate,
)

class CalibrationSessionRecorder(
    private val rootDirectory: File,
    private val subjectId: String,
    private val sessionId: String,
    private val seed: Long,
    private val profile: DeviceProfile,
) : NeuracleRawPacketObserver {
    constructor(
        context: Context,
        subjectId: String,
        sessionId: String,
        seed: Long,
        profile: DeviceProfile,
    ) : this(context.filesDir, subjectId, sessionId, seed, profile)

    private sealed interface QueueItem {
        data class Packet(val value: RawPacketSnapshot) : QueueItem
        data object Stop : QueueItem
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val directory = File(rootDirectory, "capture_${stamp}_${sessionId}").apply {
        require(subjectId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "受试者ID格式无效" }
        require(sessionId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "会话编号格式无效" }
        require(CalibrationRecordingProfile.incompatibilityReason(profile) == null) {
            CalibrationRecordingProfile.incompatibilityReason(profile) ?: "采集档案不兼容"
        }
        check(mkdirs()) { "无法创建采集会话目录" }
    }
    private val rawFile = File(directory, "raw_stream.f32")
    private val packetFile = File(directory, "packets.csv")
    private val markerFile = File(directory, "markers.csv")
    private val trialFile = File(directory, "trials.csv")
    private val qualityFile = File(directory, "quality.csv")
    private val frameTimingFile = File(directory, "frame_timing.csv")
    private val sessionFile = File(directory, "session.json")
    private val queue = ArrayBlockingQueue<QueueItem>(256)
    private val acceptingPackets = AtomicBoolean(true)
    private val writerStopped = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private val startedWallTimeMillis = System.currentTimeMillis()
    private val writerThread: Thread

    init {
        markerFile.writeText(
            "block_id,trial_id,event_code,event_type,target_id,target_hz,frame_time_ns," +
                "wall_time_ms,sequence_floor,estimated_sequence,uncertainty_samples\n",
        )
        trialFile.writeText(
            "block_id,trial_id,practice,target_id,target_hz,training_label,prepare_ms," +
                "active_onset_sequence,active_offset_sequence,active_duration_ms,valid,invalid_reason\n",
        )
        qualityFile.writeText(
            "block_id,trial_id,wall_time_ms,accepted,valid_channels,evaluated_channels,std_uv,max_abs_uv,reason\n",
        )
        frameTimingFile.writeText(
            "block_id,trial_id,event_type,frame_time_ns,refresh_rate_hz,dropped_frame_rate,healthy,reason\n",
        )
        writeSession("recording", null, null)
        writerThread = Thread({ writePackets() }, "CalibrationRawWriter").apply {
            isDaemon = true
            start()
        }
    }

    override fun onRawPacket(packet: RawPacketSnapshot) {
        if (!acceptingPackets.get()) return
        if (!queue.offer(QueueItem.Packet(packet))) {
            fail("原始数据写盘队列溢出")
        }
    }

    override fun onStreamInterrupted(reason: String) {
        if (acceptingPackets.get()) fail("TCP数据流中断：$reason")
    }

    fun failureReason(): String? = failure.get()

    @Synchronized
    fun appendMarker(marker: CalibrationMarker) {
        markerFile.appendText(
            listOf(
                marker.blockId,
                marker.trialId,
                marker.eventCode,
                csv(marker.eventType),
                marker.condition?.targetId ?: 0,
                marker.condition?.targetHz?.toString().orEmpty(),
                marker.frameTimeNanos,
                marker.wallTimeMillis,
                marker.sequence.sequenceFloor,
                marker.sequence.estimatedSequence,
                marker.sequence.uncertaintySamples,
            ).joinToString(",") + "\n",
        )
    }

    @Synchronized
    fun appendTrial(
        trial: CalibrationTrial,
        onsetSequence: Long,
        offsetSequence: Long,
        activeDurationMillis: Long,
        valid: Boolean,
        invalidReason: String,
    ) {
        trialFile.appendText(
            listOf(
                trial.blockId,
                trial.trialId,
                trial.practice,
                trial.condition.targetId,
                trial.condition.targetHz?.toString().orEmpty(),
                trial.condition.trainingLabel,
                trial.baselineMillis,
                onsetSequence,
                offsetSequence,
                activeDurationMillis,
                valid,
                csv(invalidReason),
            ).joinToString(",") + "\n",
        )
    }

    @Synchronized
    fun appendQuality(blockId: Int, trialId: Int, result: SignalQualityResult) {
        qualityFile.appendText(
            listOf(
                blockId,
                trialId,
                System.currentTimeMillis(),
                result.accepted,
                result.diagnostics.validChannelCount,
                result.diagnostics.evaluatedChannelCount,
                result.diagnostics.std,
                result.diagnostics.maxAbsolute,
                csv(result.reason.orEmpty()),
            ).joinToString(",") + "\n",
        )
    }

    @Synchronized
    fun appendFrameTiming(
        blockId: Int,
        trialId: Int,
        eventType: String,
        frameTimeNanos: Long,
        status: StimulusTimingStatus,
    ) {
        frameTimingFile.appendText(
            listOf(
                blockId,
                trialId,
                csv(eventType),
                frameTimeNanos,
                status.refreshRateHz,
                status.droppedFrameRate,
                status.healthy,
                csv(status.reason.orEmpty()),
            ).joinToString(",") + "\n",
        )
    }

    fun fail(reason: String) {
        failure.compareAndSet(null, reason)
        acceptingPackets.set(false)
    }

    fun finish(status: String, completedFormalTrials: Int, reason: String? = null): String {
        if (!writerStopped.compareAndSet(false, true)) return "aborted"
        acceptingPackets.set(false)
        while (!queue.offer(QueueItem.Stop, 100, TimeUnit.MILLISECONDS)) {
            if (!writerThread.isAlive) break
        }
        writerThread.join(10_000)
        if (writerThread.isAlive) {
            failure.compareAndSet(null, "写盘线程未在10秒内结束")
            writerThread.interrupt()
            writerThread.join(1_000)
        }
        val finalReason = reason ?: failure.get()
        val finalStatus = CaptureSessionOutcome.finalStatus(status, finalReason)
        writeSession(finalStatus, completedFormalTrials, finalReason)
        check(!writerThread.isAlive) { "原始数据仍在写盘；未生成校验文件" }
        writeChecksums()
        return finalStatus
    }

    private fun writePackets() {
        try {
            BufferedOutputStream(FileOutputStream(rawFile)).use { raw ->
                packetFile.bufferedWriter().use { packets ->
                    packets.appendLine("sequence_start,sequence_end,arrival_nanos,wall_time_ms,bytes")
                    while (true) {
                        when (val item = queue.take()) {
                            is QueueItem.Packet -> {
                                val packet = item.value
                                raw.write(packet.bytes)
                                packets.appendLine(
                                    "${packet.sequenceStart},${packet.sequenceEnd},${packet.arrivalNanos}," +
                                        "${packet.wallTimeMillis},${packet.bytes.size}",
                                )
                            }
                            QueueItem.Stop -> break
                        }
                    }
                    packets.flush()
                }
                raw.flush()
            }
        } catch (exception: Exception) {
            failure.compareAndSet(null, "写盘失败：${exception.javaClass.simpleName}: ${exception.message}")
            acceptingPackets.set(false)
        }
    }

    private fun writeSession(status: String, completedFormalTrials: Int?, reason: String?) {
        val channelNames = CalibrationRecordingProfile.rawChannelNames(profile)
        val occipitalRawIndices = CalibrationRecordingProfile.occipitalRawIndices(profile)
        val deviceToSlot = profile.amplifier.channelMap.entries.associate { (device, slot) ->
            device.toString() to slot
        }
        val json = JSONObject()
            .put("schema_version", 2)
            .put("status", status)
            .put("failure_reason", reason ?: JSONObject.NULL)
            .put("subject_id", subjectId)
            .put("session_id", sessionId)
            .put("seed", seed)
            .put("started_wall_time_ms", startedWallTimeMillis)
            .put("finished_wall_time_ms", if (status == "recording") JSONObject.NULL else System.currentTimeMillis())
            .put("profile_id", profile.profileId)
            .put("raw_sample_rate_hz", profile.amplifier.rawSampleRateHz)
            .put("eeg_channel_count", profile.amplifier.eegChannelCount)
            .put("event_channel_count", profile.amplifier.eventChannelCount)
            .put("stream_channel_count", profile.amplifier.channelCount)
            .put("packet_samples", profile.amplifier.packetSamples)
            .put("stream_format", profile.amplifier.streamFormat.preferenceId)
            .put("sample_layout", "float32_le_sample_major")
            .put("channel_names", JSONArray(channelNames))
            .put("device_to_slot_map", JSONObject(deviceToSlot))
            .put("five_channel_raw_indices", JSONArray(occipitalRawIndices))
            .put("five_channel_names", JSONArray(CalibrationRecordingProfile.occipitalRawChannelNames(profile)))
            .put("target_frequency_hz", PersonalCaptureSpec.TARGET_FREQUENCY_HZ)
            .put("duty_cycle", PersonalCaptureSpec.DUTY_CYCLE)
            .put("trial_count_expected", PersonalCaptureSpec.TRIAL_COUNT)
            .put("trial_count_completed", completedFormalTrials ?: JSONObject.NULL)
            .put("prepare_millis", PersonalCaptureSpec.PREPARE_MILLIS)
            .put("active_millis", PersonalCaptureSpec.ACTIVE_MILLIS)
            .put("rest_millis", PersonalCaptureSpec.REST_MILLIS)
            .put("display_device", profile.display.deviceFamily)
            .put("preferred_refresh_rate_hz", profile.display.preferredRefreshRateHz ?: JSONObject.NULL)
        sessionFile.writeText(json.toString(2))
    }

    private fun writeChecksums() {
        val files = listOf(
            sessionFile, rawFile, packetFile, markerFile, trialFile, qualityFile, frameTimingFile,
        )
        File(directory, "checksums.sha256").bufferedWriter().use { writer ->
            files.filter(File::exists).forEach { file ->
                writer.appendLine("${sha256(file)}  ${file.name}")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
