package com.sketchdxf.app.dxf

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Composes bitmaps into a PDF file using the platform's PdfDocument (no dependency, same
 * philosophy as DxfWriter's hand-built DXF text) — each bitmap becomes one page, sized to the
 * bitmap's own pixel dimensions (points == pixels here, matching how PdfPageRenderer already
 * treats a rendered page). This rasterizes rather than preserving original vector PDF content —
 * Android has no public API for true vector page extraction/composition — but for a photographed
 * or scanned document (this app's actual use case) that's visually indistinguishable. Used by
 * both PDF Split (one bitmap in, one single-page PDF out) and PDF Merge (many bitmaps in, one
 * multi-page PDF out).
 */
object PdfBuilder {
    fun build(bitmaps: List<Bitmap>, outFile: File) {
        val doc = PdfDocument()
        try {
            bitmaps.forEachIndexed { i, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            FileOutputStream(outFile).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}
