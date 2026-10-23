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

data class MiMarker(
    val blockId: Int,
    val trialId: Int,
    val eventCode: Int,
    val eventType: String,
    val condition: MiCondition?,
    val frameTimeNanos: Long,
    val wallTimeMillis: Long,
    val sequence: SampleSequenceEstimate,
)

/**
 * 运动想象（MI）个人标定会话记录器。
 * 负责写入原始脑电流、事件触发 Marker、试次标签与完整元数据。
 */
class MiSessionRecorder(
    context: Context,
    private val subjectId: String,
    private val sessionId: String,
    private val seed: Long,
    private val profile: DeviceProfile,
) : NeuracleRawPacketObserver {
    private sealed interface QueueItem {
        data class Packet(val value: RawPacketSnapshot) : QueueItem
        data object Stop : QueueItem
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val directory = File(context.filesDir, "calibration_mi_${stamp}").apply { mkdirs() }
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
        require(subjectId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "受试者ID格式无效" }
        require(sessionId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "会话编号格式无效" }
        markerFile.writeText(
            "block_id,trial_id,event_code,event_type,condition_label,condition_name,frame_time_ns," +
                "wall_time_ms,sequence_floor,estimated_sequence,uncertainty_samples\n",
        )
        trialFile.writeText(
            "block_id,trial_id,practice,condition_label,condition_name,prepare_ms," +
                "active_onset_sequence,active_offset_sequence,active_duration_ms,valid,invalid_reason\n",
        )
        qualityFile.writeText(
            "block_id,trial_id,wall_time_ms,accepted,valid_channels,evaluated_channels,std_uv,max_abs_uv,reason\n",
        )
        frameTimingFile.writeText(
            "block_id,trial_id,event_type,frame_time_ns,refresh_rate_hz,dropped_frame_rate,healthy,reason\n",
        )
        writeSession("recording", null, null)
        writerThread = Thread({ writePackets() }, "MiRawWriter").apply {
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
    fun appendMarker(marker: MiMarker) {
        markerFile.appendText(
            listOf(
                marker.blockId,
                marker.trialId,
                marker.eventCode,
                csv(marker.eventType),
                marker.condition?.label?.toString().orEmpty(),
                marker.condition?.shortName.orEmpty(),
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
        trial: MiTrial,
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
                trial.condition.label,
                trial.condition.shortName,
                MiCalibrationSpec.PREPARE_MILLIS,
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
                "%.3f".format(Locale.US, result.diagnostics.std),
                "%.3f".format(Locale.US, result.diagnostics.maxAbsolute),
                csv(result.reason ?: "ok"),
            ).joinToString(",") + "\n",
        )
    }

    @Synchronized
    fun appendFrameTiming(
        blockId: Int,
        trialId: Int,
        eventType: String,
        frameTimeNanos: Long,
        timing: StimulusTimingStatus,
    ) {
        frameTimingFile.appendText(
            listOf(
                blockId,
                trialId,
                csv(eventType),
                frameTimeNanos,
                "%.2f".format(Locale.US, timing.refreshRateHz),
                "%.4f".format(Locale.US, timing.droppedFrameRate),
                timing.healthy,
                csv(timing.reason.orEmpty()),
            ).joinToString(",") + "\n",
        )
    }

    fun finish(completedFormalTrials: Int) {
        if (!acceptingPackets.compareAndSet(true, false)) return
        queue.offer(QueueItem.Stop)
        writerThread.join(TimeUnit.SECONDS.toMillis(5))
        writeSession(if (failure.get() == null) "completed" else "interrupted", failure.get(), completedFormalTrials)
        writeChecksums()
    }

    fun abort(reason: String) {
        fail(reason)
        if (!acceptingPackets.compareAndSet(true, false)) return
        queue.offer(QueueItem.Stop)
        writerThread.join(TimeUnit.SECONDS.toMillis(5))
        writeSession("interrupted", reason, null)
        writeChecksums()
    }

    fun fail(reason: String) {
        failure.compareAndSet(null, reason)
        acceptingPackets.set(false)
    }

    private fun writePackets() {
        packetFile.writeText("packet_index,sequence_start,sequence_end,arrival_nanos,wall_time_ms,bytes_written\n")
        var bytesWrittenTotal = 0L
        var packetIndex = 0L
        try {
            BufferedOutputStream(FileOutputStream(rawFile)).use { rawOut ->
                packetFile.bufferedWriter().use { packetOut ->
                    while (true) {
                        when (val item = queue.poll(100, TimeUnit.MILLISECONDS)) {
                            null -> continue
                            QueueItem.Stop -> break
                            is QueueItem.Packet -> {
                                val p = item.value
                                rawOut.write(p.bytes)
                                bytesWrittenTotal += p.bytes.size
                                packetOut.write(
                                    "$packetIndex,${p.sequenceStart},${p.sequenceEnd},${p.arrivalNanos}," +
                                        "${p.wallTimeMillis},${p.bytes.size}\n",
                                )
                                packetIndex += 1
                            }
                        }
                    }
                    rawOut.flush()
                    packetOut.flush()
                }
            }
        } catch (t: Throwable) {
            fail("原始数据写盘失败: ${t.message}")
        } finally {
            writerStopped.set(true)
        }
    }

    private fun writeSession(status: String, reason: String?, completedFormalTrials: Int?) {
        val channelNames = CalibrationRecordingProfile.rawChannelNames(profile)
        val deviceToSlot = profile.amplifier.channelMap.entries.associate { (device, slot) ->
            device.toString() to slot
        }
        val motorSlots = MiCalibrationSpec.MOTOR_MODEL_SLOTS
        val motorRawIndices = motorSlots.map { slot ->
            profile.amplifier.channelMap.entries.singleOrNull { it.value == slot }?.key
        }

        val json = JSONObject()
            .put("schema_version", 2)
            .put("paradigm", "MOTOR_IMAGERY")
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
            .put("classes", JSONArray(listOf("left_hand", "right_hand")))
            .put("motor_channel_names", JSONArray(MiCalibrationSpec.MOTOR_CHANNEL_NAMES))
            .put("motor_model_slots", JSONArray(motorSlots))
            .put("motor_raw_indices", JSONArray(motorRawIndices))
            .put("prepare_millis", MiCalibrationSpec.PREPARE_MILLIS)
            .put("cue_millis", MiCalibrationSpec.CUE_MILLIS)
            .put("active_millis", MiCalibrationSpec.ACTIVE_MILLIS)
            .put("rest_millis", MiCalibrationSpec.REST_MILLIS)
            .put("trial_count_completed", completedFormalTrials ?: JSONObject.NULL)
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
