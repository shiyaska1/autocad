package com.sketchdxf.app.dxf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Draws the confirmed vector shapes onto a clean white bitmap — used for list/detail thumbnails. */
object PreviewRenderer {

    fun render(shapes: List<SketchShape>, size: Int = 900): Bitmap {
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
                    minX = min(minX, min(s.x1, s.x2)); maxX = max(maxX, max(s.x1, s.x2))
                    minY = min(minY, min(s.y1, s.y2)); maxY = max(maxY, max(s.y1, s.y2))
                }
                ShapeKind.FREEHAND -> SketchPath.parse(s.path).forEach { (x, y) ->
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
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

        shapes.forEach { s ->
            when (s.kind) {
                // Confirmed lines just draw normally — no automatic length label; a real-world size
                // only ever appears where it was placed by hand with the Dimension tool.
                ShapeKind.LINE -> canvas.drawLine(px(s.x1), py(s.y1), px(s.x2), py(s.y2), linePaint)
                ShapeKind.CIRCLE -> canvas.drawCircle(px(s.cx), py(s.cy), s.r * fit, linePaint)
                ShapeKind.TEXT -> if (s.label.isNotBlank()) canvas.drawText(s.label, px(s.x1), py(s.y1), labelPaint)
                ShapeKind.DIMENSION -> {
                    val dimPaint = Paint().apply { color = 0xFF6A1B9A.toInt(); strokeWidth = 3f; isAntiAlias = true }
                    canvas.drawLine(px(s.x1), py(s.y1), px(s.x2), py(s.y2), dimPaint)
                    if (s.label.isNotBlank()) {
                        val mx = (px(s.x1) + px(s.x2)) / 2f
                        val my = (py(s.y1) + py(s.y2)) / 2f
                        canvas.drawText(s.label, mx + 4f, my - 4f, Paint().apply { color = 0xFF6A1B9A.toInt(); textSize = dimTextSize; isAntiAlias = true })
                    }
                }
                ShapeKind.FREEHAND -> {
                    val pts = SketchPath.parse(s.path)
                    pts.zipWithNext { (ax, ay), (bx, by) -> canvas.drawLine(px(ax), py(ay), px(bx), py(by), linePaint) }
                }
            }
        }
        return bmp
    }

    fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
