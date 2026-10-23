package com.bci.glasses

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureArchiveTest {
    @Test
    fun exportsAllFilesInsideSessionDirectory() {
        val dir = File.createTempFile("capture_", "").apply {
            delete()
            mkdir()
        }
        try {
            CaptureArchive.requiredFiles.forEach { name -> File(dir, name).writeText(name) }
            val output = ByteArrayOutputStream()
            CaptureArchive.write(dir, output)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().decodeToString()
                    zip.closeEntry()
                }
            }
            assertEquals(
                CaptureArchive.requiredFiles.map { "${dir.name}/$it" }.toSet(),
                entries.keys,
            )
            assertEquals("raw_stream.f32", entries["${dir.name}/raw_stream.f32"])
        } finally {
            CaptureArchive.requiredFiles.forEach { File(dir, it).delete() }
            dir.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteSession() {
        val dir = File.createTempFile("capture_", "").apply { delete(); mkdir() }
        try {
            CaptureArchive.write(dir, ByteArrayOutputStream())
        } finally {
            dir.delete()
        }
    }
}
