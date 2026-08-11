package com.sketchdxf.app.dxf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.content.FileProvider
import com.sketchdxf.app.ocr.TextOcr
import java.io.File
import java.io.FileOutputStream

/**
 * Best-effort reading of a written dimension near a detected line — never authoritative.
 * The caller always shows this as an editable, overridable suggestion.
 */
object DimensionGuesser {

    suspend fun guess(context: Context, bitmap: Bitmap, line: PixelLine): String {
        val midX = ((line.x1 + line.x2) / 2).toInt()
        val midY = ((line.y1 + line.y2) / 2).toInt()
        val pad = (bitmap.width * 0.08f).toInt().coerceAtLeast(40)
        val rect = Rect(
            (midX - pad).coerceIn(0, bitmap.width - 1),
            (midY - pad).coerceIn(0, bitmap.height - 1),
            (midX + pad).coerceIn(1, bitmap.width),
            (midY + pad).coerceIn(1, bitmap.height)
        )
        if (rect.width() <= 0 || rect.height() <= 0) return ""
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val tmp = File(context.cacheDir, "dimguess_${System.nanoTime()}.jpg")
        return try {
            FileOutputStream(tmp).use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", tmp)
            val text = TextOcr.singleLine(context, uri)
            Regex("""\d+(\.\d+)?""").find(text)?.value ?: ""
        } finally {
            tmp.delete()
        }
    }
}
