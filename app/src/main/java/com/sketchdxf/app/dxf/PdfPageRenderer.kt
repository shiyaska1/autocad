package com.sketchdxf.app.dxf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Rasterizes a picked PDF's pages to bitmaps, using the system PdfRenderer (no dependency). */
object PdfPageRenderer {

    fun renderPages(context: Context, uri: Uri, targetWidth: Int = 1600): List<Bitmap> {
        val tmp = File(context.cacheDir, "pdfin_${System.nanoTime()}.pdf")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return emptyList()
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    (0 until renderer.pageCount).map { i ->
                        renderer.openPage(i).use { page ->
                            val scale = targetWidth.toFloat() / page.width
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            tmp.delete()
        }
    }
}
