package com.bci.glasses

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSessionRecorderTest {
    @Test
    fun stoppedSessionKeepsRawPacketsAbortedMetadataAndChecksums() {
        val root = File.createTempFile("capture-test-", "").apply { delete(); mkdir() }
        try {
            val recorder = CalibrationSessionRecorder(
                root, "subject_01", "session_01", 20260923L,
                DeviceProfilePresets.neuracleNsw2ba0032SsvepMontage(),
            )
            val bytes = ByteArray(9 * 40 * Float.SIZE_BYTES) { it.toByte() }
            recorder.onRawPacket(RawPacketSnapshot(bytes, 0L, 40L, 1_000L, 1_000L))
            val trial = CalibrationPlanFactory.create(1L).formalBlocks.single().trials.first()
            recorder.appendTrial(trial, 0L, 40L, 1_000L, false, "未完成：用户主动停止")
            assertEquals("aborted", recorder.finish("aborted", 0, "用户主动停止"))

            val directory = recorder.directory
            assertArrayEquals(bytes, File(directory, "raw_stream.f32").readBytes())
            val session = JSONObject(File(directory, "session.json").readText())
            assertEquals(2, session.getInt("schema_version"))
            assertEquals("aborted", session.getString("status"))
            assertEquals(0, session.getInt("trial_count_completed"))
            assertEquals(11.0, session.getDouble("target_frequency_hz"), 0.0)
            assertTrue(File(directory, "trials.csv").readText().contains("未完成"))
            val hashes = File(directory, "checksums.sha256").readLines()
            assertEquals(7, hashes.size)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertTrue(hashes.any { it == "$expected  raw_stream.f32" })
        } finally {
            root.listFiles()?.forEach { directory ->
                directory.listFiles()?.forEach(File::delete)
                directory.delete()
            }
            root.delete()
        }
    }
}
