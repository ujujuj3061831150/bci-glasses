package com.bci.glasses

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CaptureArchive {
    val requiredFiles = listOf(
        "raw_stream.f32", "packets.csv", "markers.csv", "trials.csv",
        "quality.csv", "frame_timing.csv", "session.json", "checksums.sha256",
    )

    fun write(directory: File, output: OutputStream) {
        require(
            directory.isDirectory &&
                (directory.name.startsWith("capture_") || directory.name.startsWith("calibration_mi_")),
        ) { "无效采集目录" }
        val files = requiredFiles.map { name ->
            File(directory, name).also { require(it.isFile) { "采集文件缺失：$name" } }
        }
        ZipOutputStream(output).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("${directory.name}/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
