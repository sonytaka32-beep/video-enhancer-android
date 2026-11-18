package com.example.videoenhancer

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

data class EnhanceResult(
    val success: Boolean,
    val isCancelled: Boolean = false,
    val errorDetail: String? = null
)

class VideoEnhancer {

    private var currentSession: FFmpegSession? = null
    @Volatile
    private var userCancelled = false

    fun getVideoDurationMs(inputPath: String): Long {
        val mediaInfoSession = FFprobeKit.getMediaInformation(inputPath)
        val durationStr = mediaInfoSession.mediaInformation?.duration ?: return 0L
        return ((durationStr.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

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
        val originalVideoKbps = (totalOriginalKbps - 128.0).coerceAtLeast(250.0)

        return if (strictSizeLock) {
            (originalVideoKbps * 0.95).roundToInt().coerceIn(350, preset.maxBitrateKbps)
        } else {
            (originalVideoKbps * preset.bitrateMultiplier).roundToInt()
                .coerceIn(preset.minBitrateKbps, preset.maxBitrateKbps)
        }
    }

    suspend fun enhanceVideo(
        inputPath: String,
        outputPath: String,
        preset: EnhancementPreset,
        strictSizeLock: Boolean,
        onProgress: (Float, Long) -> Unit
    ): EnhanceResult {
        userCancelled = false
        val totalDurationMs = getVideoDurationMs(inputPath)
        val targetKbps = calculateTargetBitrateKbps(inputPath, totalDurationMs, preset, strictSizeLock)

        val primaryResult = executeFfmpeg(
            inputPath = inputPath,
            outputPath = outputPath,
            args = preset.ffmpegArgs,
            isHardwareCodec = preset.isHardwareCodec,
            targetKbps = targetKbps,
            totalDurationMs = totalDurationMs,
            onProgress = onProgress
        )

        // Jika hardware MediaCodec ditolak chipset, otomatis fallback ke libx264 ultrafast
        if (!primaryResult.success && !userCancelled && preset.isHardwareCodec) {
            File(outputPath).delete()
            val fallbackArgs = listOf(
                "-vf", "scale=-2:1080:flags=bicubic,unsharp=5:5:1.0:5:5:0.0",
                "-c:v", "libx264",
                "-crf", "27",
                "-preset", "ultrafast",
                "-x264-params", "sliced-threads=0:cabac=0:ref=1:subme=0:me=dia:bframes=0:rc-lookahead=0:sync-lookahead=4",
                "-threads", "8",
                "-pix_fmt", "yuv420p",
                "-c:a", "copy",
                "-movflags", "+faststart"
            )
            return executeFfmpeg(
                inputPath = inputPath,
                outputPath = outputPath,
                args = fallbackArgs,
                isHardwareCodec = false,
                targetKbps = targetKbps,
                totalDurationMs = totalDurationMs,
                onProgress = onProgress
            )
        }

        return primaryResult
    }

    private suspend fun executeFfmpeg(
        inputPath: String,
        outputPath: String,
        args: List<String>,
        isHardwareCodec: Boolean,
        targetKbps: Int,
        totalDurationMs: Long,
        onProgress: (Float, Long) -> Unit
    ): EnhanceResult = suspendCoroutine { continuation ->
        val bufsizeKbps = targetKbps * 2
        val startTime = System.currentTimeMillis()
        val errorLines = mutableListOf<String>()

        val commandList = mutableListOf<String>().apply {
            add("-y")
            add("-thread_queue_size")
            add("1024")
            add("-threads")
            add("8")
            add("-flags2")
            add("+fast")

            add("-i")
            add(inputPath)

            addAll(args)

            add("-b:v")
            add("${targetKbps}k")
            if (!isHardwareCodec) {
                add("-maxrate")
                add("${targetKbps}k")
                add("-bufsize")
                add("${bufsizeKbps}k")
            }

            add(outputPath)
        }

        val commandString = commandList.joinToString(" ") { arg ->
            if (arg.contains(" ")) "\"$arg\"" else arg
        }

        currentSession = FFmpegKit.executeAsync(
            commandString,
            { session ->
                val isSuccess = ReturnCode.isSuccess(session.returnCode)
                val isCancel = ReturnCode.isCancel(session.returnCode) || userCancelled
                val lastError = errorLines.takeLast(2).joinToString(" | ").ifBlank {
                    session.failStackTrace?.lines()?.firstOrNull()
                }
                if (continuation.context[kotlinx.coroutines.Job]?.isActive == true) {
                    continuation.resume(
                        EnhanceResult(
                            success = isSuccess,
                            isCancelled = isCancel,
                            errorDetail = if (!isSuccess && !isCancel) lastError else null
                        )
                    )
                }
            },
            { log ->
                val msg = log.message?.trim().orEmpty()
                if (msg.contains("Error", ignoreCase = true) ||
                    msg.contains("Invalid", ignoreCase = true) ||
                    msg.contains("Unknown", ignoreCase = true) ||
                    msg.contains("failed", ignoreCase = true)
                ) {
                    errorLines.add(msg)
                }
            },
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
        userCancelled = true
        currentSession?.cancel()
        FFmpegKit.cancel()
    }
}