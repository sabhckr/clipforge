package com.clipforge.app

import com.clipforge.app.core.Json
import com.clipforge.app.core.SegmentParams
import com.clipforge.app.core.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Every case was planned by the desktop engine (backend/clipforge/segmentation.py). */
class SegmentationTest {

    @Test
    fun matchesDesktopEngineOnGoldenCases() {
        val cases = Json.arr(Json.parse(Golden.text("segmentation.json")))!!
        assertTrue(cases.size > 400)
        for ((i, raw) in cases.withIndex()) {
            val c = Json.obj(raw)!!
            val pm = Json.obj(c["params"])!!
            val params = SegmentParams(
                target = Json.num(pm["target"])!!,
                window = Json.num(pm["window"])!!,
                minDuration = Json.num(pm["min_duration"])!!,
                maxDuration = Json.num(pm["max_duration"])!!,
                remainderMin = Json.num(pm["remainder_min"])!!,
                shortRemainder = pm["short_remainder"] as String,
                overlap = Json.num(pm["overlap"])!!,
            )
            val duration = Json.num(c["duration"])!!
            val scenes = Json.arr(c["scenes"])!!.map { Json.num(it)!! }
            val method = c["method"] as String
            val label = "case $i (duration=$duration, method=$method)"
            val error = c["error"] as String?
            if (error != null) {
                try {
                    Segmenter.plan(duration, scenes, params, method)
                    fail("$label: expected error '$error'")
                } catch (e: IllegalArgumentException) {
                    assertEquals(label, error, e.message)
                }
                continue
            }
            val expected = Json.obj(c["expected"])!!
            val plan = Segmenter.plan(duration, scenes, params, method)
            val actual = linkedMapOf(
                "segments" to plan.segments.map { it.toJson() },
                "notes" to plan.notes,
                "scene_boundaries_used" to plan.sceneBoundariesUsed,
                "dropped_tail" to plan.droppedTail,
            )
            assertEquals(label, Json.canonical(expected), Json.canonical(actual))
        }
    }

    @Test
    fun invariantsHoldForDefaults() {
        val p = SegmentParams()
        val plan = Segmenter.plan(612.4, listOf(29.0, 61.0, 95.5, 300.0), p)
        assertEquals(0.0, plan.segments.first().start, 0.0)
        assertEquals(612.4, plan.segments.last().end, 1e-9)
        for ((a, b) in plan.segments.zipWithNext()) {
            assertEquals(a.end, b.start, 1e-9)
        }
        for (s in plan.segments) {
            assertTrue(s.duration >= p.minDuration - 1e-9 || s === plan.segments.last())
            assertTrue(s.duration <= p.maxDuration + 1e-9)
        }
    }
}
