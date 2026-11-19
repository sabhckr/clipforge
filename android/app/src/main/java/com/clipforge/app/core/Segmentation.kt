package com.clipforge.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic hybrid segmentation — a direct port of the desktop engine's rules
 * (backend/clipforge/segmentation.py). Pure functions, no Android dependencies.
 */
data class Segment(
    val index: Int,
    val start: Double,
    val end: Double,
    val startKind: String, // start | scene | fixed
    val endKind: String,   // scene | fixed | end
) {
    val duration: Double get() = r3(end - start)

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "index" to index, "start" to start, "end" to end,
        "start_kind" to startKind, "end_kind" to endKind, "duration" to duration,
    )
}

data class SegmentPlan(
    val segments: List<Segment>,
    val method: String,
    val notes: List<String>,
    val sceneBoundariesUsed: Int,
    val droppedTail: Double,
)

data class SegmentParams(
    val target: Double = 30.0,
    val window: Double = 5.0,
    val minDuration: Double = 15.0,
    val maxDuration: Double = 35.0,
    val remainderMin: Double = 10.0,
    val shortRemainder: String = "drop", // drop | merge
    val overlap: Double = 0.0,
)

/** Python `round(x, 3)`: correctly rounded from the exact binary value, ties to even. */
fun r3(x: Double): Double =
    if (!x.isFinite()) x else java.math.BigDecimal(x).setScale(3, java.math.RoundingMode.HALF_EVEN).toDouble()

/** Python `format(x, "g")` for the magnitudes used here (fixed notation, 6 significant digits). */
internal fun g(x: Double): String {
    if (x == 0.0) return "0"
    val bd = java.math.BigDecimal(x).round(java.math.MathContext(6, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros()
    return bd.toPlainString()
}

/** Python `format(x, ".2f")`: exact binary value, ties to even. */
internal fun f2(x: Double): String =
    java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()

object Segmenter {
    private const val EPS = 1e-6

    /** Sort, de-duplicate (1 ms) and keep strictly interior timestamps. */
    fun cleanSceneTimes(times: List<Double>, duration: Double): List<Double> {
        val out = ArrayList<Double>()
        for (raw in times.sorted()) {
            if (raw <= EPS || raw >= duration - EPS) continue
            val t = r3(raw)
            if (out.isNotEmpty() && abs(out.last() - t) < 0.001) continue
            out.add(t)
        }
        return out
    }

    fun plan(duration: Double, sceneTimes: List<Double>, p: SegmentParams, method: String = "hybrid"): SegmentPlan {
        require(duration > 0) { "duration must be positive" }
        require(p.minDuration <= p.target && p.maxDuration >= p.target) {
            "require min_duration <= target <= max_duration"
        }
        val scenes = if (method == "hybrid") cleanSceneTimes(sceneTimes, duration) else emptyList()
        val notes = ArrayList<String>()

        if (duration + EPS < p.remainderMin) {
            throw IllegalArgumentException(
                "Video is ${f2(duration)}s long, shorter than the ${g(p.remainderMin)}s minimum clip length")
        }

        val cuts = ArrayList<Pair<Double, String>>()
        var pos = 0.0
        var usedScenes = 0
        var guard = 0
        while (duration - pos > p.maxDuration + EPS) {
            guard++
            check(guard <= 100_000) { "segmentation did not converge" }
            val ideal = pos + p.target
            val lo = max(pos + p.minDuration, ideal - p.window)
            var hi = min(pos + p.maxDuration, ideal + p.window)
            // Prefer cuts that leave a usable tail when that is achievable.
            val tailHi = duration - p.remainderMin
            if (tailHi >= lo - EPS) hi = min(hi, tailHi)
            hi = max(hi, lo)

            val here = pos
            val candidates = scenes.filter { it >= lo - EPS && it <= hi + EPS && it > here + EPS }
            val cut: Double
            val kind: String
            if (candidates.isNotEmpty()) {
                cut = candidates.minWith(compareBy<Double>({ abs(it - ideal) }, { it }))
                kind = "scene"
                usedScenes++
            } else {
                cut = min(max(ideal, lo), hi)
                kind = "fixed"
            }
            val c = r3(cut)
            if (c <= pos + EPS || c >= duration - EPS) break
            cuts.add(c to kind)
            pos = c
        }

        var bounds: MutableList<Pair<Double, String>> = ArrayList()
        bounds.add(0.0 to "start")
        bounds.addAll(cuts)
        bounds.add(r3(duration) to "end")

        var dropped = 0.0
        if (bounds.size > 2) {
            val tail = bounds[bounds.size - 1].first - bounds[bounds.size - 2].first
            if (tail + EPS < p.remainderMin) {
                if (p.shortRemainder == "merge") {
                    notes.add("Final remainder of ${f2(tail)}s was below the ${g(p.remainderMin)}s minimum " +
                        "and was merged into the previous clip.")
                    bounds.removeAt(bounds.size - 2)
                } else {
                    notes.add("Final remainder of ${f2(tail)}s was below the ${g(p.remainderMin)}s minimum " +
                        "and was not rendered.")
                    dropped = r3(tail)
                    val lastCut = bounds[bounds.size - 2].first
                    val kept = ArrayList(bounds.subList(0, bounds.size - 2))
                    kept.add(lastCut to "end")
                    bounds = kept
                }
            }
        }

        val segments = ArrayList<Segment>()
        for (i in 0 until bounds.size - 1) {
            var start = bounds[i].first
            val end = r3(min(bounds[i + 1].first, duration))
            if (i > 0 && p.overlap > 0) start = max(0.0, start - p.overlap)
            start = r3(start)
            if (end - start <= EPS) continue // never emit zero-length clips
            segments.add(Segment(segments.size + 1, start, end, bounds[i].second, bounds[i + 1].second))
        }

        if (p.overlap > 0) notes.add("Clips after the first start ${g(p.overlap)}s early (overlap enabled).")
        if (method == "hybrid" && scenes.isEmpty()) {
            notes.add("No scene boundaries were available; fixed-interval cuts were used.")
        }
        return SegmentPlan(segments, method, notes, usedScenes, dropped)
    }
}
