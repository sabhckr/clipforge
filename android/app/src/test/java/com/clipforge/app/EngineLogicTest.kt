package com.clipforge.app

import com.clipforge.app.core.ProcessingSettings
import com.clipforge.app.media.Geometry
import com.clipforge.app.media.SceneDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogicTest {

    @Test
    fun sceneScoreFollowsFfmpegFormula() {
        val black = FloatArray(16) { 0f }
        val white = FloatArray(16) { 255f }
        val (s1, mafd1) = SceneDetector.score(black, white, 0.0)
        assertEquals(255.0, mafd1, 1e-9)
        assertEquals(1.0, s1, 1e-9) // min(255, 255)/100 clipped to 1
        val (s2, _) = SceneDetector.score(white, white, 255.0)
        assertEquals(0.0, s2, 1e-9)
        // Steady motion (same mafd every frame) is not a cut.
        val a = FloatArray(16) { 100f }
        val b = FloatArray(16) { 120f }
        val (s3, _) = SceneDetector.score(a, b, 20.0)
        assertEquals(0.0, s3, 1e-9)
    }

    @Test
    fun detectFindsHardCutsOnly() {
        val frames = sequence {
            for (i in 0 until 90) {
                val t = i / 15.0
                val base = if (t < 2.0) 40f else if (t < 4.0) 200f else 90f
                yield(t to FloatArray(SceneDetector.GRID_W * SceneDetector.GRID_H) { base + (i % 3) })
            }
        }
        assertEquals(listOf(2.0, 4.0), SceneDetector.detect(frames, 0.3))
    }

    @Test
    fun cropGeometryMatchesDesktopRule() {
        // 16:9 landscape -> 9:16: crop a 9:16 column.
        val c = Geometry.cropFor(1920, 1080, 1080, 1920, 0.5)
        val width = (c.right - c.left) / 2 * 1920
        assertEquals(607.5f, width, 0.01f)
        assertEquals(0f, (c.left + c.right) / 2, 1e-6f)
        val left = Geometry.cropFor(1920, 1080, 1080, 1920, 0.0)
        assertEquals(-1f, left.left, 1e-6f)
        val right = Geometry.cropFor(1920, 1080, 1080, 1920, 1.0)
        assertEquals(1f, right.right, 1e-6f)
        // Portrait -> 16:9 crops vertically; same aspect does nothing.
        val v = Geometry.cropFor(1080, 1920, 1920, 1080, 0.0)
        assertEquals(1f, v.top, 1e-6f)
        assertTrue(Geometry.cropFor(1080, 1920, 720, 1280, 0.5).isFull)
    }

    @Test
    fun settingsValidationAndSizes() {
        val d = ProcessingSettings()
        assertTrue(d.validate().isEmpty())
        assertEquals(1080 to 1920, d.outputSize)
        assertEquals(720 to 720, d.copy(resolution = "720p", aspectRatio = "1:1").outputSize)
        assertEquals(1920 to 1080, d.copy(aspectRatio = "16:9").outputSize)
        assertEquals(35.0, d.effectiveMaxDuration, 0.0)
        assertTrue(d.copy(minDuration = 40.0).validate().isNotEmpty())
        assertTrue(d.copy(overlap = 15.0).validate().isNotEmpty())
        assertTrue(d.copy(aspectRatio = "4:3").validate().isNotEmpty())
        assertEquals(d, ProcessingSettings.fromJson(d.toJson()))
    }
}
