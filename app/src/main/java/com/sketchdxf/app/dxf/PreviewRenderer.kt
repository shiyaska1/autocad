package com.sketchdxf.app.dxf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sketchdxf.app.data.ShapeKind
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

        shapes.forEach { s ->
            when (s.kind) {
                ShapeKind.LINE -> {
                    canvas.drawLine(px(s.x1), py(s.y1), px(s.x2), py(s.y2), linePaint)
                    if (s.confirmed && s.realLength > 0) {
                        val mx = (px(s.x1) + px(s.x2)) / 2f
                        val my = (py(s.y1) + py(s.y2)) / 2f
                        canvas.drawText("${trimNum(s.realLength)}mm", mx + 4f, my - 4f, labelPaint)
                    }
                }
                ShapeKind.CIRCLE -> canvas.drawCircle(px(s.cx), py(s.cy), s.r * fit, linePaint)
                ShapeKind.TEXT -> if (s.label.isNotBlank()) canvas.drawText(s.label, px(s.x1), py(s.y1), labelPaint)
                ShapeKind.DIMENSION -> {
                    val dimPaint = Paint().apply { color = 0xFF6A1B9A.toInt(); strokeWidth = 3f; isAntiAlias = true }
                    canvas.drawLine(px(s.x1), py(s.y1), px(s.x2), py(s.y2), dimPaint)
                    if (s.label.isNotBlank()) {
                        val mx = (px(s.x1) + px(s.x2)) / 2f
                        val my = (py(s.y1) + py(s.y2)) / 2f
                        canvas.drawText(s.label, mx + 4f, my - 4f, Paint().apply { color = 0xFF6A1B9A.toInt(); textSize = 20f; isAntiAlias = true })
                    }
                }
            }
        }
        return bmp
    }

    fun save(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
}
