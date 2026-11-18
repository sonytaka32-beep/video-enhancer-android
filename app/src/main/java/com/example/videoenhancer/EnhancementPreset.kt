package com.example.videoenhancer

enum class EnhancementPreset(
    val displayName: String,
    val bitrateMultiplier: Double,
    val minBitrateKbps: Int,
    val maxBitrateKbps: Int,
    val isHardwareCodec: Boolean = false,
    val ffmpegArgs: List<String>
) {
    CRISP_1080P_ANTI_BLOAT(
        displayName = "1080p Crisp Upscale (Anti-Bloat Size)",
        bitrateMultiplier = 1.05,
        minBitrateKbps = 650,
        maxBitrateKbps = 1800,
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=1.5:1.5:3:3,scale=-2:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,eq=contrast=1.04:saturation=1.05",
            "-c:v", "libx264",
            "-crf", "26",
            "-preset", "superfast",
            "-x264-params", "sliced-threads=0:ref=2:subme=2:me=hex:bframes=2:rc-lookahead=10:sync-lookahead=4",
            "-threads", "8",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    ULTRA_SHARP_HEVC_1080P(
        displayName = "1080p Ultra Sharp HEVC (Size Lebih Kecil)",
        bitrateMultiplier = 0.85,
        minBitrateKbps = 500,
        maxBitrateKbps = 1400,
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=2.0:2.0:3:3,scale=-2:1080:flags=lanczos,unsharp=5:5:1.2:5:5:0.0,eq=contrast=1.05:saturation=1.06",
            "-c:v", "libx265",
            "-crf", "26",
            "-preset", "ultrafast",
            "-x265-params", "frame-threads=4:wpp=1:no-sao=1",
            "-threads", "8",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    ANIME_DETAIL_1080P(
        displayName = "1080p Anime & Kartun (Garis Tajam & Bersih)",
        bitrateMultiplier = 0.95,
        minBitrateKbps = 550,
        maxBitrateKbps = 1500,
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=3.0:3.0:4:4,scale=-2:1080:flags=lanczos,unsharp=5:5:1.4:5:5:0.0,eq=contrast=1.06:saturation=1.10",
            "-c:v", "libx264",
            "-crf", "26",
            "-preset", "superfast",
            "-tune", "animation",
            "-threads", "8",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    TURBO_HARDWARE_1080P(
        displayName = "1080p Turbo Hardware GPU/NPU (Sangat Cepat)",
        bitrateMultiplier = 1.15,
        minBitrateKbps = 800,
        maxBitrateKbps = 2200,
        isHardwareCodec = true,
        ffmpegArgs = listOf(
            "-vf", "scale=-2:1080:flags=bicubic,unsharp=5:5:1.0:5:5:0.0",
            "-c:v", "h264_mediacodec",
            "-pix_fmt", "nv12",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    SMOOTH_60FPS_1080P(
        displayName = "1080p 60FPS Crisp + Smooth Motion",
        bitrateMultiplier = 1.20,
        minBitrateKbps = 900,
        maxBitrateKbps = 2400,
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=1.5:1.5:3:3,scale=-2:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,fps=60",
            "-c:v", "libx264",
            "-crf", "27",
            "-preset", "ultrafast",
            "-x264-params", "sliced-threads=0:ref=1:subme=1:me=dia:bframes=0:rc-lookahead=0:sync-lookahead=4",
            "-threads", "8",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    );

    override fun toString(): String = displayName
}