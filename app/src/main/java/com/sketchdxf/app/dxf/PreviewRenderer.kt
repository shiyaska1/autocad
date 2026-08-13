package com.sketchdxf.app.dxf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchArc
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Draws the confirmed vector shapes onto a clean white bitmap — used for list/detail thumbnails. */
object PreviewRenderer {

    /** The actual dimension-line endpoints for a DIMENSION shape — [SketchShape.x1,y1]/[x2,y2]
     *  offset perpendicular to the measured segment by [SketchShape.dimOffset] — mirroring the
     *  editor's same-named helper so a saved thumbnail matches what the editor and DXF show. */
    private fun dimLineEndpoints(s: SketchShape): FloatArray {
        if (s.dimOffset == 0f) return floatArrayOf(s.x1, s.y1, s.x2, s.y2)
        val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-3f) return floatArrayOf(s.x1, s.y1, s.x2, s.y2)
        val nx = -dy / len * s.dimOffset; val ny = dx / len * s.dimOffset
        return floatArrayOf(s.x1 + nx, s.y1 + ny, s.x2 + nx, s.y2 + ny)
    }

    /** [includeImages] draws every inserted reference IMAGE too — off by default, matching the
     *  editor's own background image not being part of this preview either; shared/exported
     *  pictures should only pick these up when explicitly asked for (see DxfDetailScreen's
     *  "Share picture" checkbox). */
    fun render(shapes: List<SketchShape>, size: Int = 900, includeImages: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        if (shapes.isEmpty()) return bmp

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        shapes.forEach { s ->
            when (s.kind) {
                ShapeKind.LINE -> {
                    minX = min(minX, min(s.x1, s.x2)); maxX = max(maxX, max(s.x1, s.x2))
                    minY = min(minY, min(s.y1, s.y2)); maxY = max(maxY, max(s.y1, s.y2))
                }
                ShapeKind.CIRCLE -> {
                    minX = min(minX, s.cx - s.r); maxX = max(maxX, s.cx + s.r)
                    minY = min(minY, s.cy - s.r); maxY = max(maxY, s.cy + s.r)
                }
                ShapeKind.TEXT -> {
                    minX = min(minX, s.x1); maxX = max(maxX, s.x1)
                    minY = min(minY, s.y1); maxY = max(maxY, s.y1)
                }
                ShapeKind.DIMENSION -> {
                    val d = dimLineEndpoints(s)
                    minX = min(minX, min(min(s.x1, s.x2), min(d[0], d[2]))); maxX = max(maxX, max(max(s.x1, s.x2), max(d[0], d[2])))
                    minY = min(minY, min(min(s.y1, s.y2), min(d[1], d[3]))); maxY = max(maxY, max(max(s.y1, s.y2), max(d[1], d[3])))
                }
                ShapeKind.FREEHAND, ShapeKind.POLYLINE -> SketchPath.parse(s.path).forEach { (x, y) ->
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
                ShapeKind.ARC -> {
                    minX = min(minX, s.cx - s.r); maxX = max(maxX, s.cx + s.r)
                    minY = min(minY, s.cy - s.r); maxY = max(maxY, s.cy + s.r)
                }
                ShapeKind.IMAGE -> if (includeImages) {
                    minX = min(minX, min(s.x1, s.x2)); maxX = max(maxX, max(s.x1, s.x2))
                    minY = min(minY, min(s.y1, s.y2)); maxY = max(maxY, max(s.y1, s.y2))
                }
            }
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val margin = size * 0.08f
        val fit = min((size - 2 * margin) / w, (size - 2 * margin) / h)
        val offX = margin + (size - 2 * margin - w * fit) / 2f - minX * fit
        val offY = margin + (size - 2 * margin - h * fit) / 2f - minY * fit
        fun px(x: Float) = offX + x * fit
        fun py(y: Float) = offY + y * fit

        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 4f; isAntiAlias = true }
        val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 20f; isAntiAlias = true }

        // Dimension text/ticks scale with the fit factor, same idea as the editor: proportional to
        // the drawing rather than a fixed pixel size, so a big plan and a tiny detail both read right.
        val dimTextSize = (900f * fit * 0.028f).coerceIn(14f, 34f)

        // A shape's own explicit colour (if the user picked one) overrides this kind's usual default.
        fun paintFor(s: SketchShape, default: Paint): Paint =
            if (s.color != null) Paint(default).apply { color = s.color } else default

        shapes.forEach { s ->
            when (s.kind) {
                // Confirmed lines just draw normally — no automatic length label; a real-world size
                // only ever appears where it was placed by hand with the Dimension tool.
                ShapeKind.LINE -> canvas.drawLine(px(s.x1), py(s.y1), px(s.x2), py(s.y2), paintFor(s, linePaint))
                ShapeKind.CIRCLE -> canvas.drawCircle(px(s.cx), py(s.cy), s.r * fit, paintFor(s, linePaint))
                ShapeKind.TEXT -> if (s.label.isNotBlank()) canvas.drawText(s.label, px(s.x1), py(s.y1), paintFor(s, labelPaint))
                ShapeKind.DIMENSION -> {
                    val dimPaint = paintFor(s, Paint().apply { color = 0xFF6A1B9A.toInt(); strokeWidth = 3f; isAntiAlias = true })
                    val d = dimLineEndpoints(s)
                    if (s.dimOffset != 0f) {
                        canvas.drawLine(px(s.x1), py(s.y1), px(d[0]), py(d[1]), dimPaint)
                        canvas.drawLine(px(s.x2), py(s.y2), px(d[2]), py(d[3]), dimPaint)
                    }
                    canvas.drawLine(px(d[0]), py(d[1]), px(d[2]), py(d[3]), dimPaint)
                    if (s.label.isNotBlank()) {
                        val mx = (px(d[0]) + px(d[2])) / 2f
                        val my = (py(d[1]) + py(d[3])) / 2f
                        canvas.drawText(s.label, mx + 4f, my - 4f, Paint(dimPaint).apply { textSize = dimTextSize })
                    }
                }
                ShapeKind.FREEHAND, ShapeKind.POLYLINE -> {
                    val pts = SketchPath.parse(s.path)
                    val paint = paintFor(s, linePaint)
                    pts.zipWithNext { (ax, ay), (bx, by) -> canvas.drawLine(px(ax), py(ay), px(bx), py(by), paint) }
                }
                ShapeKind.ARC -> {
                    val (startDeg, sweepDeg) = SketchArc.sweepFor(s.cx, s.cy, s.x1, s.y1, s.x2, s.y2, s.major)
                    val oval = RectF(px(s.cx - s.r), py(s.cy - s.r), px(s.cx + s.r), py(s.cy + s.r))
                    canvas.drawArc(oval, startDeg, sweepDeg, false, paintFor(s, linePaint))
                }
                ShapeKind.IMAGE -> if (includeImages) {
                    val imgBmp = runCatching { BitmapFactory.decodeFile(s.path) }.getOrNull()
                    if (imgBmp != null) {
                        val dst = RectF(px(min(s.x1, s.x2)), py(min(s.y1, s.y2)), px(max(s.x1, s.x2)), py(max(s.y1, s.y2)))
                        canvas.drawBitmap(imgBmp, null, dst, null)
                    }
                }
            }
        }
        return bmp
    }

    fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
