package com.sketchdxf.app.dxf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.sketchdxf.app.data.SketchSource
import java.io.File

/** Copies picked/captured sketch source files (photos, PDFs) into app storage. */
object SketchAttachmentStore {

    fun dir(context: Context): File = File(context.filesDir, "sketches").apply { mkdirs() }

    /** Copies [uri] into app storage and returns a (not-yet-persisted) source row for [workId]. */
    fun copyIn(context: Context, uri: Uri, workId: Long = 0): SketchSource? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryName(context, uri) ?: "file_${System.nanoTime()}"
        val ext = displayName.substringAfterLast('.', "").ifBlank { extFromMime(mime) }
        val target = File(dir(context), "src_${System.nanoTime()}" + if (ext.isNotBlank()) ".$ext" else "")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            SketchSource(workId = workId, path = target.absolutePath, name = displayName, mime = mime)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /** Registers an already-written file (e.g. a rendered PDF page, or a generated preview/DXF). */
    fun newFile(context: Context, prefix: String, ext: String): File =
        File(dir(context), "${prefix}_${System.nanoTime()}.$ext")

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))

    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
        "application/pdf" -> "pdf"; else -> ""
    }

    private fun queryName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
}
