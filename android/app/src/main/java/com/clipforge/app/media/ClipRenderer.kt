package com.clipforge.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.clipforge.app.core.ProcessingSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class RenderException(message: String, val detail: String? = null) : Exception(message)

/** Crop window in normalized device coordinates ([-1, 1] on both axes, +y up). */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    val isFull: Boolean get() = left <= -1f && right >= 1f && bottom <= -1f && top >= 1f
}

object Geometry {
    /**
     * Same rule as the desktop renderer: scale to cover the output, then crop at
     * [fraction] of the overflow (0 = left/top, 0.5 = center, 1 = right/bottom). Never stretches.
     */
    fun cropFor(inW: Int, inH: Int, outW: Int, outH: Int, fraction: Double): NdcCrop {
        val f = fraction.coerceIn(0.0, 1.0)
        val inAspect = inW.toDouble() / inH
        val outAspect = outW.toDouble() / outH
        return if (inAspect > outAspect) {
            val cw = inH * outAspect
            val x0 = (inW - cw) * f
            val left = -1.0 + 2.0 * x0 / inW
            NdcCrop(left.toFloat(), (left + 2.0 * cw / inW).toFloat(), -1f, 1f)
        } else if (inAspect < outAspect) {
            val ch = inW / outAspect
            val y0 = (inH - ch) * f
            val top = 1.0 - 2.0 * y0 / inH
            NdcCrop(-1f, 1f, (top - 2.0 * ch / inH).toFloat(), top.toFloat())
        } else {
            NdcCrop(-1f, 1f, -1f, 1f)
        }
    }
}

/**
 * Renders one segment with Media3 Transformer: trims [start, start + duration), crops or pads to
 * the output size without stretching, and encodes H.264 video + AAC audio into an MP4 using the
 * device's MediaCodec encoders. HDR sources are tone-mapped to SDR so the output plays everywhere.
 */
@OptIn(UnstableApi::class)
class ClipRenderer(private val context: Context) {

    data class Result(val videoEncoder: String?, val audioEncoder: String?)

    fun render(
        src: File,
        dst: File,
        startSec: Double,
        durationSec: Double,
        settings: ProcessingSettings,
        media: MediaInfo,
        isCancelled: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): Result {
        val (outW, outH) = settings.outputSize
        val startMs = (startSec * 1000).roundToLong()
        val endMs = ((startSec + durationSec) * 1000).roundToLong()

        val effects = ArrayList<Effect>()
        if (settings.fitMode == "pad") {
            effects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
        } else {
            val crop = Geometry.cropFor(media.width, media.height, outW, outH, settings.cropFraction)
            if (!crop.isFull) effects.add(Crop(crop.left, crop.right, crop.bottom, crop.top))
            effects.add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(src))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(listOf(edited)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.delete()

        val thread = HandlerThread("clipforge-render").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var transformer: Transformer? = null
        var exportResult: ExportResult? = null
        var failure: Exception? = null

        handler.post {
            try {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(settings.videoBitrate).build(),
                    )
                    .setEnableFallback(true)
                    .build()
                val t = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            exportResult = result
                            done.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            failure = exception
                            done.countDown()
                        }
                    })
                    .build()
                transformer = t
                t.start(composition, dst.absolutePath)
            } catch (e: Exception) {
                failure = e
                done.countDown()
            }
        }

        val holder = ProgressHolder()
        try {
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    val stopped = CountDownLatch(1)
                    handler.post {
                        try { transformer?.cancel() } catch (_: Exception) {}
                        stopped.countDown()
                    }
                    stopped.await(10, TimeUnit.SECONDS)
                    dst.delete()
                    throw CancelledException()
                }
                handler.post {
                    val t = transformer ?: return@post
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100.0)
                    }
                }
            }
        } finally {
            thread.quitSafely()
        }

        failure?.let { e ->
            dst.delete()
            val code = (e as? ExportException)?.errorCodeName
            throw RenderException(
                "Rendering failed" + (code?.let { " ($it)" } ?: "") + ": " + (e.message ?: e.javaClass.simpleName),
                e.stackTraceToString().take(4000),
            )
        }
        val r = exportResult ?: throw RenderException("Rendering finished without a result")
        if (!dst.isFile || dst.length() == 0L) throw RenderException("Rendering produced no output file")
        onProgress(1.0)
        return Result(r.videoEncoderName, r.audioEncoderName)
    }
}

object Thumbnails {
    /** Writes a 360 px wide JPEG of the frame near [atSec] (display orientation). */
    fun write(video: File, dst: File, atSec: Double, width: Int = 360) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val us = (atSec * 1_000_000).roundToLong()
            val frame = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw RenderException("Could not read a frame for the thumbnail")
            val h = ((frame.height.toLong() * width / frame.width) / 2 * 2).toInt().coerceAtLeast(2)
            val scaled = Bitmap.createScaledBitmap(frame, width, h, true)
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    throw RenderException("Could not encode the thumbnail")
                }
            }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
        } finally {
            mmr.release()
        }
    }
}
