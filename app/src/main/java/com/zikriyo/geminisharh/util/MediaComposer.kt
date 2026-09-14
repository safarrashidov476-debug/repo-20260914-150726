package com.zikriyo.geminisharh.util

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Original video + tavsif audio ni birlashtirish.
 * Original ovoz pasaytiriladi (default 30%), tavsif to'liq eshitiladi.
 */
object MediaComposer {

    /**
     * Segment MP3/WAV fayllarni timestamp bo'yicha joylab, bitta narration.mp3 yaratadi.
     */
    fun buildTimedNarration(
        segments: List<TimedSegment>,
        segmentFiles: List<File>,
        outputMp3: File
    ): Boolean {
        if (segments.isEmpty() || segmentFiles.isEmpty()) return false

        val inputs = mutableListOf<String>()
        val filterParts = mutableListOf<String>()
        var inputIdx = 0

        segmentFiles.forEachIndexed { i, file ->
            if (!file.exists() || file.length() == 0L) return@forEachIndexed
            val delayMs = (segments.getOrNull(i)?.seconds?.times(1000.0) ?: 0.0).toLong().coerceAtLeast(0)
            inputs.add("-i")
            inputs.add(file.absolutePath)
            // adelay=ms|ms (left|right)
            filterParts.add("[$inputIdx:a]adelay=${delayMs}|${delayMs},volume=1.0[a$inputIdx]")
            inputIdx++
        }

        if (inputIdx == 0) return false

        val mixInputs = (0 until inputIdx).joinToString("") { "[a$it]" }
        val filter = filterParts.joinToString(";") +
            ";${mixInputs}amix=inputs=$inputIdx:duration=longest:normalize=0[aout]"

        val cmd = buildString {
            inputs.forEach { append(it).append(' ') }
            append("-filter_complex ").append('"').append(filter).append('"')
            append(" -map [aout] -c:a libmp3lame -q:a 4 ")
            append('"').append(outputMp3.absolutePath).append('"')
        }

        val session = FFmpegKit.execute(cmd)
        return ReturnCode.isSuccess(session.returnCode)
    }

    /**
     * Video + narration ni birlashtiradi.
     * @param originalVolume 0.0–1.0 (masalan 0.28 = original ancha past)
     * @param narrationVolume 0.0–1.0
     */
    fun mergeVideoWithNarration(
        videoFile: File,
        narrationFile: File,
        outputFile: File,
        originalVolume: Double = 0.28,
        narrationVolume: Double = 1.0
    ): Boolean {
        if (!videoFile.exists() || !narrationFile.exists()) return false

        // Agar videoda audio bo'lmasa — faqat narration
        val hasAudioProbe = FFmpegKit.execute(
            "-i \"${videoFile.absolutePath}\" -hide_banner"
        )
        val log = hasAudioProbe.allLogsAsString ?: ""
        val videoHasAudio = log.contains("Audio:")

        val cmd = if (videoHasAudio) {
            """
            -y -i "${videoFile.absolutePath}" -i "${narrationFile.absolutePath}"
            -filter_complex "[0:a]volume=$originalVolume[va];[1:a]volume=$narrationVolume[na];[va][na]amix=inputs=2:duration=first:dropout_transition=2:normalize=0[aout]"
            -map 0:v -map "[aout]" -c:v copy -c:a aac -b:a 192k -shortest
            "${outputFile.absolutePath}"
            """.trimIndent().replace("\n", " ")
        } else {
            """
            -y -i "${videoFile.absolutePath}" -i "${narrationFile.absolutePath}"
            -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest
            "${outputFile.absolutePath}"
            """.trimIndent().replace("\n", " ")
        }

        val session = FFmpegKit.execute(cmd)
        return ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0
    }

    /**
     * Oddiy concat (timestamp yo'q) — zaxira variant.
     */
    fun concatAudioSimple(files: List<File>, output: File): Boolean {
        val existing = files.filter { it.exists() && it.length() > 0 }
        if (existing.isEmpty()) return false
        if (existing.size == 1) {
            existing[0].copyTo(output, overwrite = true)
            return true
        }
        val listFile = File(output.parentFile, "concat_list.txt")
        listFile.writeText(existing.joinToString("\n") { "file '${it.absolutePath}'" })
        val cmd = "-y -f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy \"${output.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        listFile.delete()
        return ReturnCode.isSuccess(session.returnCode)
    }
}
