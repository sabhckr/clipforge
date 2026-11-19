package com.clipforge.app.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.clipforge.app.core.r3
import java.io.File

/** Media facts read from the container by content (MediaExtractor + MediaMetadataRetriever). */
data class MediaInfo(
    val container: String,
    val duration: Double,
    val sizeBytes: Long,
    val width: Int,        // display width (after rotation)
    val height: Int,       // display height
    val codedWidth: Int,
    val codedHeight: Int,
    val rotation: Int,
    val fps: Double,
    val videoCodec: String,
    val hasAudio: Boolean,
    val audioCodec: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "format_name" to container,
        "container" to container,
        "duration" to duration,
        "size_bytes" to sizeBytes,
        "width" to width,
        "height" to height,
        "coded_width" to codedWidth,
        "coded_height" to codedHeight,
        "rotation" to rotation,
        "fps" to fps,
        "video_codec" to videoCodec,
        "has_audio" to hasAudio,
        "audio_codec" to audioCodec,
        "audio_channels" to audioChannels,
        "audio_sample_rate" to audioSampleRate,
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): MediaInfo = MediaInfo(
            container = m["container"] as? String ?: "",
            duration = (m["duration"] as? Number)?.toDouble() ?: 0.0,
            sizeBytes = (m["size_bytes"] as? Number)?.toLong() ?: 0L,
            width = (m["width"] as? Number)?.toInt() ?: 0,
            height = (m["height"] as? Number)?.toInt() ?: 0,
            codedWidth = (m["coded_width"] as? Number)?.toInt() ?: 0,
            codedHeight = (m["coded_height"] as? Number)?.toInt() ?: 0,
            rotation = (m["rotation"] as? Number)?.toInt() ?: 0,
            fps = (m["fps"] as? Number)?.toDouble() ?: 0.0,
            videoCodec = m["video_codec"] as? String ?: "",
            hasAudio = m["has_audio"] as? Boolean ?: false,
            audioCodec = m["audio_codec"] as? String,
            audioChannels = (m["audio_channels"] as? Number)?.toInt(),
            audioSampleRate = (m["audio_sample_rate"] as? Number)?.toInt(),
        )
    }
}

class InvalidMediaException(message: String) : Exception(message)

object MediaProbe {

    private val CODEC_NAMES = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/x-vnd.on2.vp8" to "vp8",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/av01" to "av1",
        "video/mp4v-es" to "mpeg4",
        "video/3gpp" to "h263",
        "video/dolby-vision" to "dolby_vision",
        "audio/mp4a-latm" to "aac",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
        "audio/mpeg" to "mp3",
        "audio/3gpp" to "amr_nb",
        "audio/amr-wb" to "amr_wb",
        "audio/flac" to "flac",
        "audio/raw" to "pcm",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
    )

    private val CONTAINERS = mapOf(
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/3gpp" to "3gp",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/avi" to "avi",
        "video/x-msvideo" to "avi",
        "video/mp2ts" to "ts",
    )

    fun codecName(mime: String?): String = mime?.let { CODEC_NAMES[it] ?: it.substringAfter('/') } ?: "unknown"

    /** Reads and validates a local file. Throws [InvalidMediaException] for anything unusable. */
    fun probe(file: File): MediaInfo {
        if (!file.isFile || file.length() == 0L) throw InvalidMediaException("File is empty or missing")
        val extractor = MediaExtractor()
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw InvalidMediaException("Invalid or unsupported video: the container could not be read")
            }
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoFormat == null) videoFormat = f
                if (mime.startsWith("audio/") && audioFormat == null) audioFormat = f
            }
        } finally {
            extractor.release()
        }
        val vf = videoFormat ?: throw InvalidMediaException("Invalid or unsupported video: no video stream found")

        val mmr = MediaMetadataRetriever()
        try {
            try {
                mmr.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw InvalidMediaException("Invalid or unsupported video: metadata could not be read")
            }
            fun meta(key: Int): String? = try { mmr.extractMetadata(key) } catch (e: Exception) { null }

            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            if (durationMs <= 0) throw InvalidMediaException("Invalid or unsupported video: duration is unknown")

            val codedW = vf.getInteger(MediaFormat.KEY_WIDTH)
            val codedH = vf.getInteger(MediaFormat.KEY_HEIGHT)
            if (codedW <= 0 || codedH <= 0) throw InvalidMediaException("Invalid or unsupported video: no frame size")

            var rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?: if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
            rotation = ((rotation % 360) + 360) % 360
            val swap = rotation == 90 || rotation == 270

            val fps = when {
                vf.containsKey(MediaFormat.KEY_FRAME_RATE) -> try {
                    vf.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                } catch (e: ClassCastException) {
                    vf.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                }
                else -> {
                    val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
                    if (frames != null && frames > 0) frames * 1000.0 / durationMs else 0.0
                }
            }

            val containerMime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val container = containerMime?.let { CONTAINERS[it] ?: it.substringAfter('/') }
                ?: file.extension.lowercase().ifEmpty { "unknown" }

            val af = audioFormat
            return MediaInfo(
                container = container,
                duration = r3(durationMs / 1000.0),
                sizeBytes = file.length(),
                width = if (swap) codedH else codedW,
                height = if (swap) codedW else codedH,
                codedWidth = codedW,
                codedHeight = codedH,
                rotation = rotation,
                fps = r3(fps),
                videoCodec = codecName(vf.getString(MediaFormat.KEY_MIME)),
                hasAudio = af != null,
                audioCodec = af?.let { codecName(it.getString(MediaFormat.KEY_MIME)) },
                audioChannels = af?.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                    ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                audioSampleRate = af?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                    ?.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            )
        } finally {
            mmr.release()
        }
    }
}
