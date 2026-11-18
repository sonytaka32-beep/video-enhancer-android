package com.example.videoenhancer

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

class VideoEnhancer {

    private var currentSession: FFmpegSession? = null

    fun getVideoDurationMs(inputPath: String): Long {
        val mediaInfoSession = FFprobeKit.getMediaInformation(inputPath)
        val durationStr = mediaInfoSession.mediaInformation?.duration ?: return 0L
        return ((durationStr.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    /**
     * Menghitung batas bitrate 1080p secara dinamis dari ukuran file asli
     * agar hasil upscale 1080p jernih tapi ukurannya tidak membengkak.
     */
    fun calculateTargetBitrateKbps(
        inputPath: String,
        durationMs: Long,
        preset: EnhancementPreset,
        strictSizeLock: Boolean
    ): Int {
        val fileSizeBytes = File(inputPath).length()
        if (durationMs <= 0L || fileSizeBytes <= 0L) return preset.minBitrateKbps

        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val totalOriginalKbps = ((fileSizeBytes * 8.0) / 1024.0) / durationSec
        // Kurangi estimasi bitrate audio (~128 kbps)
        val originalVideoKbps = (totalOriginalKbps - 128.0).coerceAtLeast(250.0)

        return if (strictSizeLock) {
            // Jika mode Lock Size aktif, paksa bitrate video <= bitrate asli
            (originalVideoKbps * 0.95).roundToInt().coerceIn(350, preset.maxBitrateKbps)
        } else {
            (originalVideoKbps * preset.bitrateMultiplier).roundToInt()
                .coerceIn(preset.minBitrateKbps, preset.maxBitrateKbps)
        }
    }

    fun buildCommandPreview(
        preset: EnhancementPreset,
        targetKbps: Int
    ): String {
        val bufsizeKbps = targetKbps * 2
        return (preset.ffmpegArgs + listOf(
            "-b:v", "${targetKbps}k",
            "-maxrate", "${targetKbps}k",
            "-bufsize", "${bufsizeKbps}k"
        )).joinToString(" ")
    }

    suspend fun enhanceVideo(
        inputPath: String,
        outputPath: String,
        preset: EnhancementPreset,
        strictSizeLock: Boolean,
        onProgress: (Float, Long) -> Unit
    ): Boolean = suspendCoroutine { continuation ->
        val totalDurationMs = getVideoDurationMs(inputPath)
        val targetKbps = calculateTargetBitrateKbps(inputPath, totalDurationMs, preset, strictSizeLock)
        val bufsizeKbps = targetKbps * 2
        val startTime = System.currentTimeMillis()

        val commandList = mutableListOf<String>().apply {
            add("-y")
            // Akselerasi Multi-Thread Decoder (termasuk untuk video input AV1/HEVC/H264)
            add("-thread_queue_size")
            add("1024")
            add("-threads")
            add("8")
            add("-flags2")
            add("+fast")

            add("-i")
            add(inputPath)

            addAll(preset.ffmpegArgs)

            // Kunci Bitrate Anti-Bengkak
            add("-b:v")
            add("${targetKbps}k")
            add("-maxrate")
            add("${targetKbps}k")
            add("-bufsize")
            add("${bufsizeKbps}k")

            add(outputPath)
        }

        val commandString = commandList.joinToString(" ") { arg ->
            if (arg.contains(" ")) "\"$arg\"" else arg
        }

        currentSession = FFmpegKit.executeAsync(
            commandString,
            { session ->
                val isSuccess = ReturnCode.isSuccess(session.returnCode)
                if (continuation.context[kotlinx.coroutines.Job]?.isActive == true) {
                    continuation.resume(isSuccess)
                }
            },
            { /* Log callback */ },
            { statistics ->
                if (totalDurationMs > 0 && statistics.time > 0) {
                    val progress = (statistics.time.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    onProgress(progress, elapsedMs)
                }
            }
        )
    }

    fun cancel() {
        currentSession?.cancel()
        FFmpegKit.cancel()
    }
}