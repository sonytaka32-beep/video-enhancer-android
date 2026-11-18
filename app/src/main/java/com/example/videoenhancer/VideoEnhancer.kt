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
    private var cachedEncoders: Set<String>? = null

    private fun getAvailableEncoders(): Set<String> {
        cachedEncoders?.let { return it }
        val session = FFmpegKit.execute("-hide_banner -encoders")
        val output = session.allLogsAsString.orEmpty()
        val candidates = listOf(
            "libx264", "libx265", "h264_mediacodec", "hevc_mediacodec",
            "libopenh264", "libvpx-vp9", "mpeg4"
        )
        val found = candidates.filter { output.contains(it) }.toSet()
        cachedEncoders = found
        return found
    }

    fun getVideoDurationMs(inputPath: String): Long {
        val mediaInfoSession = FFprobeKit.getMediaInformation(inputPath)
        val durationStr = mediaInfoSession.mediaInformation?.duration ?: return 0L
        return ((durationStr.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }

    fun calculateTargetBitrateKbps(
        fileSizeBytes: Long,
        durationMs: Long,
        preset: EnhancementPreset,
        strictSizeLock: Boolean
    ): Int {
        if (durationMs <= 0L || fileSizeBytes <= 0L) return preset.minBitrateKbps

        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val totalOriginalKbps = ((fileSizeBytes * 8.0) / 1024.0) / durationSec
        val originalVideoKbps = (totalOriginalKbps - 128.0).coerceAtLeast(300.0)

        return if (strictSizeLock) {
            (originalVideoKbps * 0.95).roundToInt().coerceIn(400, preset.maxBitrateKbps)
        } else {
            (originalVideoKbps * preset.bitrateMultiplier).roundToInt()
                .coerceIn(preset.minBitrateKbps, preset.maxBitrateKbps)
        }
    }

    private fun buildEncoderChain(preset: EnhancementPreset): List<String> {
        val available = getAvailableEncoders()
        val ordered = mutableListOf<String>()

        if (preset.preferHardware) {
            if ("h264_mediacodec" in available) ordered.add("h264_mediacodec")
            if ("libx264" in available) ordered.add("libx264")
            if ("libopenh264" in available) ordered.add("libopenh264")
            ordered.add("mpeg4")
        } else if (preset.preferHevc) {
            if ("libx265" in available) ordered.add("libx265")
            if ("hevc_mediacodec" in available) ordered.add("hevc_mediacodec")
            if ("libx264" in available) ordered.add("libx264")
            if ("h264_mediacodec" in available) ordered.add("h264_mediacodec")
            if ("libopenh264" in available) ordered.add("libopenh264")
            ordered.add("mpeg4")
        } else {
            if ("libx264" in available) ordered.add("libx264")
            if ("h264_mediacodec" in available) ordered.add("h264_mediacodec")
            if ("libopenh264" in available) ordered.add("libopenh264")
            ordered.add("mpeg4")
        }
        return ordered.distinct()
    }

    private fun buildEncoderArgs(encoder: String, targetKbps: Int): List<String> {
        val bufsizeKbps = targetKbps * 2
        return when (encoder) {
            "h264_mediacodec", "hevc_mediacodec" -> listOf(
                "-c:v", encoder,
                "-b:v", "${targetKbps}k",
                "-pix_fmt", "yuv420p"
            )
            "libx264" -> listOf(
                "-c:v", "libx264",
                "-preset", "superfast",
                "-x264-params", "sliced-threads=0:ref=1:subme=1:me=dia:bframes=0:rc-lookahead=0:sync-lookahead=4",
                "-b:v", "${targetKbps}k",
                "-maxrate", "${targetKbps}k",
                "-bufsize", "${bufsizeKbps}k",
                "-threads", "8",
                "-pix_fmt", "yuv420p"
            )
            "libx265" -> listOf(
                "-c:v", "libx265",
                "-preset", "ultrafast",
                "-x265-params", "frame-threads=4:wpp=1:no-sao=1",
                "-b:v", "${targetKbps}k",
                "-maxrate", "${targetKbps}k",
                "-bufsize", "${bufsizeKbps}k",
                "-threads", "8",
                "-pix_fmt", "yuv420p"
            )
            "libopenh264" -> listOf(
                "-c:v", "libopenh264",
                "-b:v", "${targetKbps}k",
                "-maxrate", "${targetKbps}k",
                "-pix_fmt", "yuv420p"
            )
            else -> listOf(
                // Universal MPEG-4 Part 2 (kompatibel 100% di semua player Android & FFmpeg LGPL)
                "-c:v", "mpeg4",
                "-vtag", "mp4v",
                "-b:v", "${targetKbps}k",
                "-maxrate", "${targetKbps}k",
                "-bufsize", "${bufsizeKbps}k",
                "-mbd", "rd",
                "-trellis", "1",
                "-threads", "8",
                "-pix_fmt", "yuv420p"
            )
        }
    }

    suspend fun enhanceVideo(
        inputPath: String,
        originalSizeBytes: Long,
        outputPath: String,
        preset: EnhancementPreset,
        strictSizeLock: Boolean,
        onProgress: (Float, Long) -> Unit
    ): EnhanceResult {
        userCancelled = false
        val totalDurationMs = getVideoDurationMs(inputPath)
        val targetKbps = calculateTargetBitrateKbps(
            fileSizeBytes = originalSizeBytes,
            durationMs = totalDurationMs,
            preset = preset,
            strictSizeLock = strictSizeLock
        )

        val encoderChain = buildEncoderChain(preset)
        var lastResult = EnhanceResult(success = false, errorDetail = "No compatible encoder found")

        for (encoder in encoderChain) {
            if (userCancelled) break
            File(outputPath).delete()

            val args = buildList {
                add("-y")
                add("-thread_queue_size")
                add("1024")
                add("-threads")
                add("8")
                add("-i")
                add(inputPath)
                add("-vf")
                add(preset.videoFilter)
                addAll(buildEncoderArgs(encoder, targetKbps))
                add("-c:a")
                add("copy")
                add("-movflags")
                add("+faststart")
                add(outputPath)
            }

            lastResult = executeFfmpeg(
                args = args,
                totalDurationMs = totalDurationMs,
                onProgress = onProgress
            )

            if (lastResult.success || lastResult.isCancelled) {
                return lastResult
            }
        }

        return lastResult
    }

    private suspend fun executeFfmpeg(
        args: List<String>,
        totalDurationMs: Long,
        onProgress: (Float, Long) -> Unit
    ): EnhanceResult = suspendCoroutine { continuation ->
        val startTime = System.currentTimeMillis()

        currentSession = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { session ->
                val isSuccess = ReturnCode.isSuccess(session.returnCode)
                val isCancel = ReturnCode.isCancel(session.returnCode) || userCancelled
                val fullLogs = session.allLogsAsString.orEmpty()
                val tailError = fullLogs.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .takeLast(4)
                    .joinToString(" | ")
                    .ifBlank { session.failStackTrace?.lines()?.firstOrNull() ?: "Unknown FFmpeg error" }

                if (continuation.context[kotlinx.coroutines.Job]?.isActive == true) {
                    continuation.resume(
                        EnhanceResult(
                            success = isSuccess,
                            isCancelled = isCancel,
                            errorDetail = if (!isSuccess && !isCancel) tailError else null
                        )
                    )
                }
            },
            { /* Log otomatis terkumpul di session.allLogsAsString */ },
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