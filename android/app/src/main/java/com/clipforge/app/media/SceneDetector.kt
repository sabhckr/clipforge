package com.clipforge.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.clipforge.app.core.r3
import java.io.File
import kotlin.math.abs
import kotlin.math.min

class CancelledException : Exception("Cancelled")

/**
 * Deterministic scene-change detection — no learned model.
 *
 * Every frame is decoded with the device's MediaCodec decoder and reduced to a fixed
 * [GRID_W]x[GRID_H] grid of block-averaged luma values. The per-frame score follows FFmpeg's
 * `scene` metric: mafd = mean absolute difference to the previous frame (0–255),
 * score = min(mafd, |mafd − previous mafd|) / 100, clipped to [0, 1]. A frame whose score
 * exceeds the threshold marks a scene boundary at its presentation time.
 */
object SceneDetector {
    const val GRID_W = 64
    const val GRID_H = 36
    private const val SAMPLES = 3 // samples per block edge (3x3 = 9 luma reads per block)
    private const val TIMEOUT_US = 10_000L

    /** Pure scoring function (unit-tested): returns (score, mafd). */
    fun score(prev: FloatArray, cur: FloatArray, prevMafd: Double): Pair<Double, Double> {
        var sad = 0.0
        for (i in cur.indices) sad += abs(cur[i] - prev[i])
        val mafd = sad / cur.size
        val diff = abs(mafd - prevMafd)
        val s = min(mafd, diff) / 100.0
        return s.coerceIn(0.0, 1.0) to mafd
    }

    /** Pure detection over a sequence of (time, grid) frames (unit-tested). */
    fun detect(frames: Sequence<Pair<Double, FloatArray>>, threshold: Double): List<Double> {
        val out = ArrayList<Double>()
        var prev: FloatArray? = null
        var prevMafd = 0.0
        for ((t, grid) in frames) {
            val p = prev
            if (p != null) {
                val (s, mafd) = score(p, grid, prevMafd)
                if (s > threshold) out.add(r3(t))
                prevMafd = mafd
            }
            prev = grid
        }
        return out.distinct().sorted()
    }

    /**
     * Decodes [file] and returns scene-change timestamps (seconds) where score > [threshold].
     * Throws on decoder failure so the caller can fall back to fixed intervals.
     */
    fun detect(
        file: File,
        threshold: Double,
        durationSec: Double,
        isCancelled: () -> Boolean,
        onProgress: (Double) -> Unit,
    ): List<Double> {
        require(threshold > 0 && threshold < 1) { "threshold must be in (0, 1)" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val result = ArrayList<Double>()
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) throw IllegalStateException("No video track")
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var prev: FloatArray? = null
            var prevMafd = 0.0
            var lastProgress = -1.0
            val grid = FloatArray(GRID_W * GRID_H)

            while (!outputDone) {
                if (isCancelled()) throw CancelledException()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (eos) outputDone = true
                    if (info.size > 0 || !eos) {
                        val image = try { c.getOutputImage(outIdx) } catch (e: Exception) { null }
                        if (image != null) {
                            image.use { img ->
                                val plane = img.planes[0]
                                val crop = img.cropRect
                                lumaGrid(plane.buffer, plane.rowStride, plane.pixelStride,
                                    crop.left, crop.top, crop.width(), crop.height(), grid)
                            }
                            val t = info.presentationTimeUs / 1_000_000.0
                            val p = prev
                            if (p != null) {
                                val (s, mafd) = score(p, grid, prevMafd)
                                if (s > threshold) result.add(r3(t))
                                prevMafd = mafd
                                System.arraycopy(grid, 0, p, 0, grid.size)
                            } else {
                                prev = grid.copyOf()
                            }
                            if (durationSec > 0) {
                                val frac = (t / durationSec).coerceIn(0.0, 1.0)
                                if (frac - lastProgress >= 0.01) { lastProgress = frac; onProgress(frac) }
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
        return result.distinct().sorted()
    }

    /**
     * Block-averages the Y plane into [out]. Handles 8-bit (pixelStride 1) and 16-bit
     * little-endian (P010, pixelStride 2: the high byte carries the 8 most significant bits).
     */
    fun lumaGrid(
        buf: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int, out: FloatArray,
    ) {
        val hiByte = if (pixelStride >= 2) 1 else 0
        val limit = buf.limit()
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                var sum = 0
                var n = 0
                for (sy in 0 until SAMPLES) {
                    val y = ((gy * SAMPLES + sy) * 2 + 1).toLong() * height / (GRID_H * SAMPLES * 2)
                    for (sx in 0 until SAMPLES) {
                        val x = ((gx * SAMPLES + sx) * 2 + 1).toLong() * width / (GRID_W * SAMPLES * 2)
                        val idx = ((top + y) * rowStride + (left + x) * pixelStride + hiByte).toInt()
                        if (idx in 0 until limit) {
                            sum += buf.get(idx).toInt() and 0xff
                            n++
                        }
                    }
                }
                out[gy * GRID_W + gx] = if (n > 0) sum.toFloat() / n else 0f
            }
        }
    }
}
