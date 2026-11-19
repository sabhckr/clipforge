package com.clipforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clipforge.app.TestSupport.asset
import com.clipforge.app.TestSupport.context
import com.clipforge.app.TestSupport.frameAt
import com.clipforge.app.TestSupport.hex
import com.clipforge.app.TestSupport.isBlack
import com.clipforge.app.TestSupport.isBlue
import com.clipforge.app.TestSupport.isRed
import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.media.ClipRenderer
import com.clipforge.app.media.InvalidMediaException
import com.clipforge.app.media.MediaProbe
import com.clipforge.app.media.SceneDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaTest {

    @Test
    fun probeReadsH264WithAudio() {
        val m = MediaProbe.probe(asset("scenes.mp4"))
        assertEquals(640, m.width)
        assertEquals(360, m.height)
        assertEquals(0, m.rotation)
        assertEquals("h264", m.videoCodec)
        assertTrue(m.hasAudio)
        assertEquals("aac", m.audioCodec)
        assertEquals(26.0, m.duration, 0.15)
    }

    @Test
    fun probeAppliesPhoneRotation() {
        val m = MediaProbe.probe(asset("phone_hevc.mp4"))
        assertEquals("hevc", m.videoCodec)
        assertTrue("rotation was ${m.rotation}", m.rotation == 90 || m.rotation == 270)
        assertEquals(640, m.codedWidth)
        assertEquals(360, m.width)
        assertEquals(640, m.height)
    }

    @Test(expected = InvalidMediaException::class)
    fun probeRejectsNonVideo() {
        val f = File(context.cacheDir, "not_a_video.mp4")
        f.writeText("this is not a video file, whatever the extension says")
        MediaProbe.probe(f)
    }

    @Test
    fun sceneDetectorFindsTheTwoHardCuts() {
        val f = asset("scenes.mp4")
        val cuts = SceneDetector.detect(f, 0.3, 26.0, { false }) {}
        // Exactly two cuts, each within one frame (1/15 s) of the true cut.
        assertEquals("cuts were $cuts", 2, cuts.size)
        assertEquals(8.0, cuts[0], 0.07)
        assertEquals(17.0, cuts[1], 0.07)
    }

    private fun render(name: String, settings: ProcessingSettings, start: Double = 0.0, dur: Double = 3.0): File {
        val src = asset(name)
        val dst = File(context.cacheDir, "render_${name}_${System.nanoTime()}.mp4")
        ClipRenderer(context).render(src, dst, start, dur, settings, MediaProbe.probe(src), { false }) {}
        return dst
    }

    private val vertical = ProcessingSettings(resolution = "720p", aspectRatio = "9:16", quality = "compact")

    @Test
    fun cropLeftAndRightPickTheRightSide() {
        for (name in listOf("redblue.mp4", "redblue_rot.mp4")) {
            val left = render(name, vertical.copy(cropPosition = "left"))
            val out = MediaProbe.probe(left)
            assertEquals(720, out.width)
            assertEquals(1280, out.height)
            val lf = frameAt(left, 1.0)
            val c1 = lf.getPixel(lf.width / 2, lf.height / 2)
            assertTrue("$name left crop centre should be red, was ${hex(c1)}", isRed(c1))

            val right = render(name, vertical.copy(cropPosition = "right"))
            val rf = frameAt(right, 1.0)
            val c2 = rf.getPixel(rf.width / 2, rf.height / 2)
            assertTrue("$name right crop centre should be blue, was ${hex(c2)}", isBlue(c2))
        }
    }

    @Test
    fun padKeepsWholePictureWithBars() {
        val dst = render("redblue_rot.mp4", vertical.copy(fitMode = "pad"))
        val f = frameAt(dst, 1.0)
        assertTrue("top bar", isBlack(f.getPixel(f.width / 2, 10)))
        assertTrue("bottom bar", isBlack(f.getPixel(f.width / 2, f.height - 10)))
        assertTrue("left of picture is red", isRed(f.getPixel(f.width / 8, f.height / 2)))
        assertTrue("right of picture is blue", isBlue(f.getPixel(f.width * 7 / 8, f.height / 2)))
    }

    @Test
    fun hevcPortraitRecordingRendersUpright() {
        val dst = render("phone_hevc.mp4", vertical, start = 2.0, dur = 4.0)
        val out = MediaProbe.probe(dst)
        assertEquals("h264", out.videoCodec)
        assertEquals(720, out.width)
        assertEquals(1280, out.height)
        assertTrue(out.hasAudio)
        assertEquals("aac", out.audioCodec)
        assertEquals(4.0, out.duration, 0.35)
    }

    @Test
    fun squareAndWideOutputs() {
        val sq = MediaProbe.probe(render("redblue.mp4", vertical.copy(aspectRatio = "1:1")))
        assertEquals(720, sq.width); assertEquals(720, sq.height)
        val wide = MediaProbe.probe(render("redblue_rot.mp4", vertical.copy(aspectRatio = "16:9")))
        assertEquals(1280, wide.width); assertEquals(720, wide.height)
    }
}
