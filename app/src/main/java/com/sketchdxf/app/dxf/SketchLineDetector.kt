package com.sketchdxf.app.dxf

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** kotlin.math.hypot only has a (Double, Double) overload — this fills the (Float, Float) gap. */
private fun hypotF(x: Float, y: Float): Float = hypot(x.toDouble(), y.toDouble()).toFloat()

/** A straight edge candidate in the (possibly downscaled) working bitmap's pixel space. */
data class PixelLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun length(): Float = hypotF(x2 - x1, y2 - y1)
}

/**
 * Finds candidate straight lines in a photographed sketch — a plain-Kotlin Sobel-edge +
 * Hough-transform pipeline, no native CV library (keeps the APK arm-only and small).
 *
 * This is a FIRST GUESS only: pencil sketches are never perfectly straight or clean, so results
 * are always meant to be reviewed and corrected in the editor, never trusted outright.
 */
object SketchLineDetector {

    /** Downscaled to this on the long edge before processing — keeps Hough voting fast on-phone. */
    private const val WORK_MAX_DIM = 900

    /** Returns detected lines in the coordinate space of [bitmap] AS PASSED IN (already scaled). */
    fun detect(bitmap: Bitmap): Pair<Bitmap, List<PixelLine>> {
        val working = downscale(bitmap, WORK_MAX_DIM)
        val w = working.width
        val h = working.height
        val gray = toGray(working)
        val mag = sobelMagnitude(gray, w, h)
        val edgeThreshold = percentileThreshold(mag, 0.90) // top ~10% of gradient magnitude = edge
        val edges = BooleanArray(w * h) { mag[it] >= edgeThreshold }

        val peaks = houghPeaks(edges, w, h, maxPeaks = 60)
        val segments = peaks.flatMap { peak -> extractSegments(edges, w, h, peak) }
            .filter { it.length() >= minLength(w, h) }

        val snapped = segments.map { snapToAxis(it) }
        val merged = mergeCollinear(snapped)
        return working to merged
    }

    private fun minLength(w: Int, h: Int): Float = hypotF(w.toFloat(), h.toFloat()) * 0.035f

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun toGray(bmp: Bitmap): IntArray {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }

    private fun sobelMagnitude(gray: IntArray, w: Int, h: Int): IntArray {
        val mag = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -gray[i - w - 1] + gray[i - w + 1] -
                    2 * gray[i - 1] + 2 * gray[i + 1] -
                    gray[i + w - 1] + gray[i + w + 1]
                val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                mag[i] = (abs(gx) + abs(gy))
            }
        }
        return mag
    }

    private fun percentileThreshold(mag: IntArray, percentile: Double): Int {
        val nonZero = mag.filter { it > 0 }
        if (nonZero.isEmpty()) return Int.MAX_VALUE
        val sorted = nonZero.sorted()
        val idx = (sorted.size * percentile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx].coerceAtLeast(24) // floor so a near-blank photo doesn't call noise "edges"
    }

    private data class HoughPeak(val thetaDeg: Int, val rho: Int, val votes: Int)

    /** theta in degrees [0,180), rho in pixels from the image center. 1-degree / 1-pixel bins. */
    private fun houghPeaks(edges: BooleanArray, w: Int, h: Int, maxPeaks: Int): List<HoughPeak> {
        val diag = hypotF(w.toFloat(), h.toFloat()).toInt() + 1
        val rhoOffset = diag
        val rhoBins = 2 * diag + 1
        val thetaBins = 180
        val cosTable = DoubleArray(thetaBins) { cos(it * PI / 180) }
        val sinTable = DoubleArray(thetaBins) { sin(it * PI / 180) }
        val acc = IntArray(thetaBins * rhoBins)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!edges[y * w + x]) continue
                for (t in 0 until thetaBins) {
                    val rho = (x * cosTable[t] + y * sinTable[t]).toInt() + rhoOffset
                    if (rho in 0 until rhoBins) acc[t * rhoBins + rho]++
                }
            }
        }

        // Greedy peak-pick with local non-maximum suppression, so one thick line doesn't
        // dominate every slot.
        val minVotes = (minLength(w, h)).toInt().coerceAtLeast(15)
        val peaks = mutableListOf<HoughPeak>()
        val suppressed = BooleanArray(thetaBins * rhoBins)
        repeat(maxPeaks) {
            var best = -1; var bestVotes = minVotes
            for (idx in acc.indices) {
                if (suppressed[idx]) continue
                if (acc[idx] > bestVotes) { bestVotes = acc[idx]; best = idx }
            }
            if (best < 0) return@repeat
            val t = best / rhoBins
            val r = best % rhoBins
            peaks.add(HoughPeak(t, r - rhoOffset, bestVotes))
            for (dt in -4..4) {
                val tt = ((t + dt) % thetaBins + thetaBins) % thetaBins
                for (dr in -8..8) {
                    val rr = r + dr
                    if (rr in 0 until rhoBins) suppressed[tt * rhoBins + rr] = true
                }
            }
        }
        return peaks
    }

    /** Walks along a Hough peak's infinite line, turning runs of nearby edge pixels into segments. */
    private fun extractSegments(edges: BooleanArray, w: Int, h: Int, peak: HoughPeak): List<PixelLine> {
        val thetaRad = peak.thetaDeg * PI / 180
        val cosT = cos(thetaRad); val sinT = sin(thetaRad)
        // Direction along the line is perpendicular to (cosT, sinT).
        val dx = -sinT; val dy = cosT
        val diag = hypotF(w.toFloat(), h.toFloat())
        val steps = diag.toInt()
        val onLine = BooleanArray(steps * 2 + 1)
        val pts = arrayOfNulls<Pair<Int, Int>>(steps * 2 + 1)

        for (s in -steps..steps) {
            val px = (w / 2 + peak.rho * cosT + s * dx).toInt()
            val py = (h / 2 + peak.rho * sinT + s * dy).toInt()
            val idx = s + steps
            if (px in 0 until w && py in 0 until h) {
                // Accept if this pixel or one just off the line perpendicular to it is an edge —
                // a hand-drawn stroke is a few pixels wide, not a single-pixel path.
                var hit = edges[py * w + px]
                if (!hit) {
                    val nx = (px + cosT).toInt(); val ny = (py + sinT).toInt()
                    if (nx in 0 until w && ny in 0 until h) hit = edges[ny * w + nx]
                }
                if (!hit) {
                    val nx = (px - cosT).toInt(); val ny = (py - sinT).toInt()
                    if (nx in 0 until w && ny in 0 until h) hit = edges[ny * w + nx]
                }
                onLine[idx] = hit
                pts[idx] = px to py
            }
        }

        val segments = mutableListOf<PixelLine>()
        var runStart = -1
        var gap = 0
        val maxGap = 10 // bridges small breaks in a pencil stroke
        for (i in onLine.indices) {
            if (onLine[i]) {
                if (runStart < 0) runStart = i
                gap = 0
            } else if (runStart >= 0) {
                gap++
                if (gap > maxGap) {
                    addSegmentIfValid(pts, runStart, i - gap, segments)
                    runStart = -1; gap = 0
                }
            }
        }
        if (runStart >= 0) addSegmentIfValid(pts, runStart, onLine.lastIndex, segments)
        return segments
    }

    private fun addSegmentIfValid(pts: Array<Pair<Int, Int>?>, from: Int, to: Int, out: MutableList<PixelLine>) {
        val a = pts.getOrNull(from) ?: return
        val b = pts.getOrNull(to) ?: return
        out.add(PixelLine(a.first.toFloat(), a.second.toFloat(), b.first.toFloat(), b.second.toFloat()))
    }

    /** Snaps a segment's angle to 0/45/90/135 degrees when it's already close, keeping its length. */
    private fun snapToAxis(line: PixelLine): PixelLine {
        val angleDeg = Math.toDegrees(atan2((line.y2 - line.y1).toDouble(), (line.x2 - line.x1).toDouble()))
        val normalized = ((angleDeg % 180) + 180) % 180
        val targets = doubleArrayOf(0.0, 45.0, 90.0, 135.0)
        val nearest = targets.minByOrNull { abs(it - normalized) } ?: return line
        if (abs(nearest - normalized) > 4.0) return line
        val len = line.length()
        val rad = Math.toRadians(nearest + (angleDeg - normalized))
        val midX = (line.x1 + line.x2) / 2f
        val midY = (line.y1 + line.y2) / 2f
        val hx = (cos(rad) * len / 2).toFloat()
        val hy = (sin(rad) * len / 2).toFloat()
        return PixelLine(midX - hx, midY - hy, midX + hx, midY + hy)
    }

    /** Merges near-duplicate/near-collinear segments (common when a peak splits into two runs). */
    private fun mergeCollinear(lines: List<PixelLine>): List<PixelLine> {
        val remaining = lines.toMutableList()
        val out = mutableListOf<PixelLine>()
        while (remaining.isNotEmpty()) {
            var cur = remaining.removeAt(0)
            var mergedAny: Boolean
            do {
                mergedAny = false
                val it2 = remaining.iterator()
                while (it2.hasNext()) {
                    val other = it2.next()
                    val combined = tryMerge(cur, other)
                    if (combined != null) { cur = combined; it2.remove(); mergedAny = true }
                }
            } while (mergedAny)
            out.add(cur)
        }
        return out
    }

    private fun tryMerge(a: PixelLine, b: PixelLine): PixelLine? {
        val angA = Math.toDegrees(atan2((a.y2 - a.y1).toDouble(), (a.x2 - a.x1).toDouble()))
        val angB = Math.toDegrees(atan2((b.y2 - b.y1).toDouble(), (b.x2 - b.x1).toDouble()))
        val diff = abs(((angA - angB) % 180 + 270) % 180 - 90) // angle diff, direction-agnostic
        if (abs(diff - 90) > 6.0) return null
        // Close enough endpoints (within a small gap) on a shared line -> merge into the envelope.
        val candidates = listOf(a.x1 to a.y1, a.x2 to a.y2, b.x1 to b.y1, b.x2 to b.y2)
        var maxDist = 0f; var p1 = candidates[0]; var p2 = candidates[0]
        for (p in candidates) for (q in candidates) {
            val d = hypotF(p.first - q.first, p.second - q.second)
            if (d > maxDist) { maxDist = d; p1 = p; p2 = q }
        }
        val gapOk = minOf(
            hypotF(a.x2 - b.x1, a.y2 - b.y1), hypotF(a.x1 - b.x2, a.y1 - b.y2),
            hypotF(a.x1 - b.x1, a.y1 - b.y1), hypotF(a.x2 - b.x2, a.y2 - b.y2)
        ) < 14f
        if (!gapOk) return null
        return PixelLine(p1.first, p1.second, p2.first, p2.second)
    }
}
