package com.example.videoenhancer

enum class EnhancementPreset(
    val displayName: String,
    val bitrateMultiplier: Double,
    val minBitrateKbps: Int,
    val maxBitrateKbps: Int,
    val preferHardware: Boolean = false,
    val preferHevc: Boolean = false,
    val videoFilter: String,
    val targetFps: Int? = null,
    val ffmpegArgs: List<String> // Untuk preview di layar
) {
    TURBO_HARDWARE_1080P(
        displayName = "1080p Turbo Hardware GPU/NPU (Sangat Cepat)",
        bitrateMultiplier = 1.15,
        minBitrateKbps = 900,
        maxBitrateKbps = 2400,
        preferHardware = true,
        videoFilter = "scale=trunc(oh*a/16)*16:1080:flags=bicubic,unsharp=5:5:1.0:5:5:0.0",
        ffmpegArgs = listOf(
            "-vf", "scale=trunc(oh*a/16)*16:1080:flags=bicubic,unsharp=5:5:1.0:5:5:0.0",
            "-c:v", "h264_mediacodec",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    CRISP_1080P_ANTI_BLOAT(
        displayName = "1080p Crisp Upscale (Anti-Bloat Size)",
        bitrateMultiplier = 1.05,
        minBitrateKbps = 750,
        maxBitrateKbps = 2000,
        videoFilter = "hqdn3d=1.5:1.5:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,eq=contrast=1.04:saturation=1.05",
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=1.5:1.5:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,eq=contrast=1.04:saturation=1.05",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    ULTRA_SHARP_HEVC_1080P(
        displayName = "1080p Ultra Sharp HEVC (Size Lebih Kecil)",
        bitrateMultiplier = 0.85,
        minBitrateKbps = 550,
        maxBitrateKbps = 1500,
        preferHevc = true,
        videoFilter = "hqdn3d=2.0:2.0:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.2:5:5:0.0,eq=contrast=1.05:saturation=1.06",
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=2.0:2.0:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.2:5:5:0.0,eq=contrast=1.05:saturation=1.06",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    ANIME_DETAIL_1080P(
        displayName = "1080p Anime & Kartun (Garis Tajam & Bersih)",
        bitrateMultiplier = 0.95,
        minBitrateKbps = 650,
        maxBitrateKbps = 1700,
        videoFilter = "hqdn3d=3.0:3.0:4:4,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.4:5:5:0.0,eq=contrast=1.06:saturation=1.10",
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=3.0:3.0:4:4,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.4:5:5:0.0,eq=contrast=1.06:saturation=1.10",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    ),

    SMOOTH_60FPS_1080P(
        displayName = "1080p 60FPS Crisp + Smooth Motion",
        bitrateMultiplier = 1.20,
        minBitrateKbps = 1000,
        maxBitrateKbps = 2500,
        targetFps = 60,
        videoFilter = "hqdn3d=1.5:1.5:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,fps=60",
        ffmpegArgs = listOf(
            "-vf", "hqdn3d=1.5:1.5:3:3,scale=trunc(oh*a/16)*16:1080:flags=lanczos,unsharp=5:5:1.1:5:5:0.0,fps=60",
            "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            "-movflags", "+faststart"
        )
    );

    override fun toString(): String = displayName
}