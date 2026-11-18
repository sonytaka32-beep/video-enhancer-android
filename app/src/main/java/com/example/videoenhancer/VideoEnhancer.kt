package com.example.videoenhancer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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

data class VideoProbeMetadata(
    val durationMs: Long,
    val isAv1: Boolean
)

class VideoEnhancer {

    private var currentSession: FFmpegSession? = null
    @Volatile
    private var userCancelled = false
    private var cachedEncoders: Set<String>? = null
    private var cachedDecoders: Set<String>? = null

    private fun getAvailableEncoders(): Set<String> {
        cachedEncoders?.let { return it }
        val session = FFmpegKit.execute("-hide_banner -encoders")
        val output = session.allLogsAsString.orEmpty()
        val candidates = listOf(
            "libx264", "libx265", "h264_mediacodec", "hevc_mediacodec", "libopenh264"
        )
        val found = candidates.filter { output.contains(it) }.toSet()
        cachedEncoders = found
        return found
    }

    private fun getAvailableDecoders(): Set<String> {
        cachedDecoders?.let { return it }
        val session = FFmpegKit.execute("-hide_banner -decoders")
        val output = session.allLogsAsString.orEmpty()
        val candidates = listOf("libdav1d", "av1", "av1_mediacodec")
        val found = candidates.filter { output.contains(it) }.toSet()
        cachedDecoders = found
        return found
    }

    fun probeVideoMetadata(
        context: Context,
        uri: Uri,
        freshInputPathProvider: () -> String
    ): VideoProbeMetadata {
        var durationMs = 0L
        var isAv1 = false

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            if (mime.contains("av01", ignoreCase = true) || mime.contains("av1", ignoreCase = true)) {
                isAv1 = true
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        if (durationMs <= 0L || !isAv1) {
            val mediaInfoSession = FFprobeKit.getMediaInformation(freshInputPathProvider())
            val info = mediaInfoSession.mediaInformation
            if (durationMs <= 0L) {
                durationMs = ((info?.duration?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
            }
            val streams = info?.streams.orEmpty()
            if (streams.any { it.codec?.contains("av1", ignoreCase = true) == true }) {
                isAv1 = true
            }
        }

        return VideoProbeMetadata(durationMs = durationMs, isAv1 = isAv1)
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

    private fun buildDecoderArgs(isAv1: Boolean): List<List<String>> {
        if (!isAv1) {
            return listOf(
                listOf("-thread_queue_size", "1024", "-threads", "8", "-flags2", "+fast")
            )
        }

        val decoders = getAvailableDecoders()
        val variants = mutableListOf<List<String>>()

        // Prioritaskan libdav1d (decoder software AV1 multi-thread) sebelum -i
        if ("libdav1d" in decoders) {
            variants.add(
                listOf(
                    "-probesize", "50M",
                    "-analyzeduration", "100M",
                    "-thread_queue_size", "1024",
                    "-c:v", "libdav1d",
                    "-threads", "8"
                )
            )
        }

        // Cadangan decoder AV1 otomatis tanpa memaksa codec hardware yang tidak didukung chipset
        variants.add(
            listOf(
                "-probesize", "50M",
                "-analyzeduration", "100M",
                "-thread_queue_size", "1024",
                "-threads", "8"
            )
        )

        return variants
    }

    private fun buildEncoderChain(preset: EnhancementPreset): List<String> {
        val available = getAvailableEncoders()
        val ordered = mutableListOf<String>()

        if (preset.preferHardware) {
            if ("h264_mediacodec" in available) {
                ordered.add("h264_mediacodec_ndk")
                ordered.add("h264_mediacodec_auto")
            }
            if ("libx264" in available) ordered.add("libx264")
            if ("libopenh264" in available) ordered.add("libopenh264")
        } else if (preset.preferHevc) {
            if ("libx265" in available) ordered.add("libx265")
            if ("hevc_mediacodec" in available) ordered.add("hevc_mediacodec")
            if ("libx264" in available) ordered.add("libx264")
            if ("h264_mediacodec" in available) ordered.add("h264_mediacodec_ndk")
        } else {
            if ("libx264" in available) ordered.add("libx264")
            if ("h264_mediacodec" in available) {
                ordered.add("h264_mediacodec_ndk")
                ordered.add("h264_mediacodec_auto")
            }
            if ("libopenh264" in available) ordered.add("libopenh264")
        }
        return ordered.distinct()
    }

    private fun buildEncoderArgs(encoderProfile: String, targetKbps: Int): List<String> {
        val bufsizeKbps = targetKbps * 2
        return when (encoderProfile) {
            "h264_mediacodec_ndk" -> listOf(
                "-c:v", "h264_mediacodec",
                "-ndk_codec", "1",
                "-b:v", "${targetKbps}k",
                "-g", "60",
                "-pix_fmt", "yuv420p"
            )
            "h264_mediacodec_auto" -> listOf(
                "-c:v", "h264_mediacodec",
                "-b:v", "${targetKbps}k",
                "-g", "60"
            )
            "hevc_mediacodec" -> listOf(
                "-c:v", "hevc_mediacodec",
                "-ndk_codec", "1",
                "-b:v", "${targetKbps}k",
                "-g", "60",
                "-pix_fmt", "yuv420p"
            )
            "libx264" -> listOf(
                "-c:v", "libx264",
                "-preset", "superfast",
                "-x264-params", "sliced-threads=0:cabac=0:ref=1:subme=1:me=dia:bframes=0:rc-lookahead=0:sync-lookahead=4",
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
            else -> listOf(
                "-c:v", "libopenh264",
                "-b:v", "${targetKbps}k",
                "-maxrate", "${targetKbps}k",
                "-pix_fmt", "yuv420p"
            )
        }
    }

    suspend fun enhanceVideo(
        context: Context,
        uri: Uri,
        freshInputPathProvider: () -> String,
        localFileFallbackProvider: () -> String,
        originalSizeBytes: Long,
        outputPath: String,
        preset: EnhancementPreset,
        strictSizeLock: Boolean,
        onProgress: (Float, Long) -> Unit
    ): EnhanceResult {
        userCancelled = false
        val metadata = probeVideoMetadata(context, uri, freshInputPathProvider)
        val targetKbps = calculateTargetBitrateKbps(
            fileSizeBytes = originalSizeBytes,
            durationMs = metadata.durationMs,
            preset = preset,
            strictSizeLock = strictSizeLock
        )

        val decoderVariants = buildDecoderArgs(metadata.isAv1)
        val encoderChain = buildEncoderChain(preset)
        var lastResult = EnhanceResult(success = false, errorDetail = "No compatible decoder/encoder")

        // Coba lewat SAF terlebih dahulu; jika AV1 gagal seek pada SAF, lanjutkan lewat file lokal cache
        val inputSources = listOf(freshInputPathProvider, localFileFallbackProvider)

        for (sourceProvider in inputSources) {
            if (userCancelled) break
            for (decoderArgs in decoderVariants) {
                if (userCancelled) break
                for (encoderProfile in encoderChain) {
                    if (userCancelled) break
                    File(outputPath).delete()

                    val inputPath = sourceProvider()
                    val args = buildList {
                        add("-y")
                        addAll(decoderArgs)
                        add("-i")
                        add(inputPath)
                        add("-vf")
                        add(preset.videoFilter)
                        addAll(buildEncoderArgs(encoderProfile, targetKbps))
                        add("-c:a")
                        add("copy")
                        add("-movflags")
                        add("+faststart")
                        add(outputPath)
                    }

                    lastResult = executeFfmpeg(
                        args = args,
                        totalDurationMs = metadata.durationMs,
                        onProgress = onProgress
                    )

                    if (lastResult.success || lastResult.isCancelled) {
                        return lastResult
                    }
                }
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
            { },
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