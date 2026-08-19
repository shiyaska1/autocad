package com.sketchdxf.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.DownloadSaver
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchSource
import com.sketchdxf.app.data.SketchWork
import com.sketchdxf.app.dxf.PdfPageRenderer
import com.sketchdxf.app.dxf.PendingSketchEditor
import com.sketchdxf.app.dxf.PreviewRenderer
import com.sketchdxf.app.dxf.SketchAttachmentStore
import com.sketchdxf.app.ui.common.ImageViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DxfDetailScreen(workId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).sketchDao() }
    var work by remember { mutableStateOf<SketchWork?>(null) }
    var sources by remember { mutableStateOf<List<SketchSource>>(emptyList()) }
    var showPreview by remember { mutableStateOf(false) }
    var showJpgChoice by remember { mutableStateOf(false) }
    var jpgExporting by remember { mutableStateOf(false) }
    // Pictures inserted as movable objects (Insert Picture), not the trace-over background — DXF
    // is a vector format and never embeds these, so on their own they'd silently vanish from a
    // Download/Share of just the .dxf. Bundled alongside it as separate files instead.
    var insertedImagePaths by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(workId) {
        work = dao.work(workId)
        sources = dao.sourcesFor(workId)
        insertedImagePaths = dao.shapesFor(workId)
            .filter { it.kind == ShapeKind.IMAGE && it.label.isNotBlank() && File(it.label).exists() }
            .map { it.label }
            .distinct()
    }

    if (showPreview) {
        work?.previewPath?.takeIf { it.isNotBlank() }?.let { p ->
            ImageViewerDialog(paths = listOf(p), onDismiss = { showPreview = false })
        }
    }

    fun mimeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        "dxf" -> "application/dxf"; else -> "*/*"
    }

    fun share(path: String, mime: String) {
        val f = File(path); if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Shares several files (e.g. the .dxf plus any inserted picture objects) as one multi-
     *  attachment share action, falling back to the single-file path when there's just one. */
    fun shareMultiple(paths: List<String>) {
        val existing = paths.filter { File(it).exists() }
        if (existing.isEmpty()) return
        if (existing.size == 1) { share(existing[0], mimeForPath(existing[0])); return }
        val uris = ArrayList(existing.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", File(it)) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun download(path: String, name: String, mime: String): String {
        val f = File(path); if (!f.exists()) return "File is missing"
        return if (DownloadSaver.save(context, f, name, mime)) "Saved to Downloads: $name" else "Could not save the file"
    }

    // baseImagePath/backgroundCleared, if set, are the editor's own explicit choice (Set as
    // background / Delete background / Insert Picture "use as background?") and always win.
    // Otherwise, fall back to re-deriving the original reference photo/PDF page (if any) so an
    // untouched sketch still resolves the background it was traced from — a PDF source has no
    // ready-made background bitmap (only the original PDF was kept), so its first page has to be
    // re-rendered the same way DxfHomeScreen did when the sketch was first created.
    suspend fun resolveBackgroundPath(w: SketchWork): String? = when {
        w.baseImagePath.isNotBlank() -> w.baseImagePath
        w.backgroundCleared -> null
        else -> withContext(Dispatchers.Default) { rebuildBackgroundPath(context, sources) }
    }

    fun goEdit() {
        val w = work ?: return
        scope.launch {
            val shapes = dao.shapesFor(w.id)
            val bg = resolveBackgroundPath(w)
            PendingSketchEditor.set(
                workId = w.id, createdAt = w.createdAt, name = w.name,
                baseImagePath = bg, shapes = shapes, sources = sources,
                oldDxfPath = w.dxfPath, oldPreviewPath = w.previewPath, unit = w.unit,
                backgroundCleared = w.backgroundCleared
            )
            onEdit()
        }
    }

    fun exportJpg(includeBackground: Boolean) {
        val w = work ?: return
        jpgExporting = true
        scope.launch {
            val shapes = withContext(Dispatchers.IO) { dao.shapesFor(w.id) }
            val bgBitmap = if (includeBackground) {
                resolveBackgroundPath(w)?.let { path -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } }
            } else null
            val bitmap = withContext(Dispatchers.Default) { PreviewRenderer.render(shapes, size = 1600, background = bgBitmap) }
            val suffix = if (includeBackground) "_with_background" else "_no_background"
            val file = SketchAttachmentStore.newFile(context, "export", "jpg")
            withContext(Dispatchers.IO) { PreviewRenderer.saveJpg(bitmap, file) }
            val msg = download(file.absolutePath, "${w.name}$suffix.jpg", "image/jpeg")
            jpgExporting = false
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (showJpgChoice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showJpgChoice = false },
            title = { Text("Save as JPG") },
            text = { Text("Include the traced background photo, or just the drawing lines?") },
            confirmButton = {
                Button(onClick = { showJpgChoice = false; exportJpg(includeBackground = true) }) {
                    Text("With background")
                }
            },
            dismissButton = {
                Button(onClick = { showJpgChoice = false; exportJpg(includeBackground = false) }) {
                    Text("Without background")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(work?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { goEdit() }) { Icon(Icons.Filled.Edit, "Edit") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        val w = work
        if (w == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            val preview = remember(w.previewPath) {
                runCatching { File(w.previewPath).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }.getOrNull()
            }
            Box(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = preview != null) { showPreview = true }
            ) {
                if (preview != null) {
                    Image(preview, "Preview", modifier = Modifier.fillMaxWidth())
                } else {
                    Text("No preview yet", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(24.dp))
                }
            }

            Text("DXF file", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(File(w.dxfPath).name.ifBlank { "Not generated" }, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = {
                    val msg = download(w.dxfPath, "${w.name}.dxf", "application/dxf")
                    var savedImages = 0
                    insertedImagePaths.forEachIndexed { i, p ->
                        val name = "${w.name}_image_${i + 1}.${File(p).extension.ifBlank { "jpg" }}"
                        if (download(p, name, mimeForPath(p)).startsWith("Saved")) savedImages++
                    }
                    val full = if (insertedImagePaths.isEmpty()) msg else "$msg (+$savedImages image(s))"
                    android.widget.Toast.makeText(context, full, android.widget.Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.Download, "Download") }
                IconButton(onClick = { shareMultiple(listOf(w.dxfPath) + insertedImagePaths) }) { Icon(Icons.Filled.Share, "Share") }
            }

            Text("Image (JPG)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (jpgExporting) "Generating…" else "Save the drawing as a JPG image",
                    modifier = Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.outline
                )
                IconButton(onClick = { showJpgChoice = true }, enabled = !jpgExporting) {
                    Icon(Icons.Filled.Download, "Save as JPG")
                }
            }

            if (sources.isNotEmpty()) {
                Text("Source files", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                sources.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name.ifBlank { "file" }, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { download(s.path, s.name.ifBlank { "file" }, s.mime) }) { Icon(Icons.Filled.Download, "Download") }
                        IconButton(onClick = { share(s.path, s.mime.ifBlank { "*/*" }) }) { Icon(Icons.Filled.Share, "Share") }
                    }
                }
            }

            Button(onClick = { goEdit() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Edit / regenerate")
            }
        }
    }
}

/** Recovers a background bitmap path for re-opening a saved sketch: an image source's own path
 *  works as-is, but a PDF source only ever kept the original PDF — its first page is re-rendered
 *  into a fresh PNG the same way DxfHomeScreen does when a sketch is first created. */
private suspend fun rebuildBackgroundPath(context: android.content.Context, sources: List<SketchSource>): String? {
    sources.firstOrNull { it.mime.startsWith("image/") }?.let { return it.path }
    val pdfSource = sources.firstOrNull { it.mime.contains("pdf") || it.name.endsWith(".pdf", ignoreCase = true) } ?: return null
    val bitmap = PdfPageRenderer.renderPages(context, android.net.Uri.fromFile(File(pdfSource.path))).firstOrNull() ?: return null
    val baseFile = SketchAttachmentStore.newFile(context, "base", "png")
    FileOutputStream(baseFile).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return baseFile.absolutePath
}
