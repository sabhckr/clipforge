package com.clipforge.app.core

/**
 * Every parameter that affects output; stored verbatim in each signed manifest.
 *
 * Differences from the desktop engine, by design: Android encodes with the device's
 * MediaCodec H.264 encoder (hardware when available), so quality is a bitrate tier
 * rather than an x264 CRF/preset, and AAC audio uses the encoder's default bitrate.
 */
data class ProcessingSettings(
    val segmentationMethod: String = "hybrid",
    val targetDuration: Double = 30.0,
    val sceneThreshold: Double = 0.30,
    val sceneWindow: Double = 5.0,
    val minDuration: Double = 15.0,
    val maxDuration: Double? = null,
    val remainderMin: Double = 10.0,
    val shortRemainder: String = "drop",
    val overlap: Double = 0.0,
    val aspectRatio: String = "9:16",
    val resolution: String = "1080p",
    val fitMode: String = "crop",
    val cropPosition: String = "center",
    val cropOffset: Double = 0.5,
    val quality: String = "standard",
    val thumbnails: Boolean = true,
) {
    val effectiveMaxDuration: Double get() = maxDuration ?: (targetDuration + sceneWindow)

    val outputSize: Pair<Int, Int>
        get() {
            val short = if (resolution == "720p") 720 else 1080
            val long = if (resolution == "720p") 1280 else 1920
            return when (aspectRatio) {
                "1:1" -> short to short
                "16:9" -> long to short
                else -> short to long
            }
        }

    val cropFraction: Double
        get() = when (cropPosition) {
            "left" -> 0.0
            "right" -> 1.0
            "center" -> 0.5
            else -> cropOffset
        }

    /** Target video bitrate in bits/s, scaled by output pixel count (30 fps reference). */
    val videoBitrate: Int
        get() {
            val (w, h) = outputSize
            val bitsPerPixel = when (quality) {
                "high" -> 0.26
                "compact" -> 0.10
                else -> 0.16
            }
            return (w.toDouble() * h * 30.0 * bitsPerPixel).toInt()
        }

    fun segmentParams(): SegmentParams = SegmentParams(
        target = targetDuration, window = sceneWindow, minDuration = minDuration,
        maxDuration = effectiveMaxDuration, remainderMin = remainderMin,
        shortRemainder = shortRemainder, overlap = overlap,
    )

    /** Mirrors the desktop server's validation rules. Empty list = valid. */
    fun validate(): List<String> {
        val e = ArrayList<String>()
        if (segmentationMethod !in METHODS) e.add("Unknown segmentation method.")
        if (targetDuration !in 3.0..600.0) e.add("Clip duration must be 3–600 s.")
        if (sceneThreshold !in 0.05..0.95) e.add("Scene threshold must be 0.05–0.95.")
        if (sceneWindow !in 0.0..120.0) e.add("Scene search window must be 0–120 s.")
        if (minDuration !in 1.0..600.0) e.add("Minimum duration must be 1–600 s.")
        if (minDuration > targetDuration) e.add("Minimum duration cannot exceed the target duration.")
        val max = maxDuration
        if (max != null && (max < targetDuration || max > 1200.0)) {
            e.add("Maximum duration must be at least the target duration (and at most 1200 s).")
        }
        if (remainderMin !in 0.5..600.0) e.add("Final remainder minimum must be 0.5–600 s.")
        if (shortRemainder !in REMAINDER) e.add("Unknown short remainder policy.")
        if (overlap !in 0.0..30.0) e.add("Overlap must be 0–30 s.")
        if (overlap >= minDuration) e.add("Overlap must be smaller than the minimum duration.")
        if (aspectRatio !in ASPECTS) e.add("Unknown aspect ratio.")
        if (resolution !in RESOLUTIONS) e.add("Unknown resolution.")
        if (fitMode !in FITS) e.add("Unknown fit mode.")
        if (cropPosition !in CROPS) e.add("Unknown crop position.")
        if (cropOffset !in 0.0..1.0) e.add("Custom crop offset must be 0–1.")
        if (quality !in QUALITIES) e.add("Unknown quality.")
        return e
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "segmentation_method" to segmentationMethod,
        "target_duration" to targetDuration,
        "scene_threshold" to sceneThreshold,
        "scene_window" to sceneWindow,
        "min_duration" to minDuration,
        "max_duration" to maxDuration,
        "remainder_min" to remainderMin,
        "short_remainder" to shortRemainder,
        "overlap" to overlap,
        "aspect_ratio" to aspectRatio,
        "resolution" to resolution,
        "fit_mode" to fitMode,
        "crop_position" to cropPosition,
        "crop_offset" to cropOffset,
        "quality" to quality,
        "video_bitrate" to videoBitrate,
        "video_codec" to "h264",
        "audio_codec" to "aac",
        "thumbnails" to thumbnails,
    )

    companion object {
        val METHODS = listOf("hybrid", "fixed")
        val REMAINDER = listOf("drop", "merge")
        val ASPECTS = listOf("9:16", "1:1", "16:9")
        val RESOLUTIONS = listOf("1080p", "720p")
        val FITS = listOf("crop", "pad")
        val CROPS = listOf("center", "left", "right", "custom")
        val QUALITIES = listOf("high", "standard", "compact")

        fun fromJson(m: Map<String, Any?>?): ProcessingSettings {
            val d = ProcessingSettings()
            if (m == null) return d
            fun dbl(k: String, def: Double) = Json.num(m[k]) ?: def
            fun str(k: String, def: String) = m[k] as? String ?: def
            return ProcessingSettings(
                segmentationMethod = str("segmentation_method", d.segmentationMethod),
                targetDuration = dbl("target_duration", d.targetDuration),
                sceneThreshold = dbl("scene_threshold", d.sceneThreshold),
                sceneWindow = dbl("scene_window", d.sceneWindow),
                minDuration = dbl("min_duration", d.minDuration),
                maxDuration = Json.num(m["max_duration"]),
                remainderMin = dbl("remainder_min", d.remainderMin),
                shortRemainder = str("short_remainder", d.shortRemainder),
                overlap = dbl("overlap", d.overlap),
                aspectRatio = str("aspect_ratio", d.aspectRatio),
                resolution = str("resolution", d.resolution),
                fitMode = str("fit_mode", d.fitMode),
                cropPosition = str("crop_position", d.cropPosition),
                cropOffset = dbl("crop_offset", d.cropOffset),
                quality = str("quality", d.quality),
                thumbnails = m["thumbnails"] as? Boolean ?: d.thumbnails,
            )
        }
    }
}
