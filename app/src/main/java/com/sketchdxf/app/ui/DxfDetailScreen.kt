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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var showSharePictureDialog by remember { mutableStateOf(false) }

    LaunchedEffect(workId) {
        work = dao.work(workId)
        sources = dao.sourcesFor(workId)
    }

    if (showPreview) {
        work?.previewPath?.takeIf { it.isNotBlank() }?.let { p ->
            ImageViewerDialog(paths = listOf(p), onDismiss = { showPreview = false })
        }
    }

    fun share(path: String, mime: String) {
        val f = File(path); if (!f.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The saved preview picture never includes inserted reference images (same as the editor's
     *  own background image) unless [includeImages] is asked for here, in which case a fresh
     *  render is made on the spot and shared instead of the saved file. */
    fun sharePicture(includeImages: Boolean) {
        val w2 = work ?: return
        if (!includeImages) {
            share(w2.previewPath, "image/png")
            return
        }
        scope.launch {
            val shapes = dao.shapesFor(w2.id)
            val bmp = withContext(Dispatchers.Default) { PreviewRenderer.render(shapes, includeImages = true) }
            val tmp = SketchAttachmentStore.newFile(context, "share_preview", "png")
            withContext(Dispatchers.Default) { PreviewRenderer.save(bmp, tmp) }
            share(tmp.absolutePath, "image/png")
        }
    }

    fun download(path: String, name: String, mime: String): String {
        val f = File(path); if (!f.exists()) return "File is missing"
        return if (DownloadSaver.save(context, f, name, mime)) "Saved to Downloads: $name" else "Could not save the file"
    }

    fun goEdit() {
        val w = work ?: return
        scope.launch {
            val shapes = dao.shapesFor(w.id)
            // Re-attach the original reference photo/PDF page (if any) so continuing a sketch
            // still shows the background it was traced from, instead of a blank canvas. A PDF
            // source has no ready-made background bitmap (only the original PDF was kept) — its
            // first page has to be re-rendered the same way DxfHomeScreen did when the sketch was
            // first created.
            val bg = withContext(Dispatchers.Default) { rebuildBackgroundPath(context, sources) }
            PendingSketchEditor.set(
                workId = w.id, createdAt = w.createdAt, name = w.name,
                baseImagePath = bg, shapes = shapes, sources = sources,
                oldDxfPath = w.dxfPath, oldPreviewPath = w.previewPath, unit = w.unit
            )
            onEdit()
        }
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
            if (preview != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showSharePictureDialog = true }) { Icon(Icons.Filled.Share, "Share picture") }
                }
            }

            Text("DXF file", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(File(w.dxfPath).name.ifBlank { "Not generated" }, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { download(w.dxfPath, "${w.name}.dxf", "application/dxf") }) { Icon(Icons.Filled.Download, "Download") }
                IconButton(onClick = { share(w.dxfPath, "application/dxf") }) { Icon(Icons.Filled.Share, "Share") }
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
    if (showSharePictureDialog) {
        var includeImages by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSharePictureDialog = false },
            title = { Text("Share picture") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeImages, onCheckedChange = { includeImages = it })
                    Text("Include inserted images")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSharePictureDialog = false; sharePicture(includeImages) }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { showSharePictureDialog = false }) { Text("Cancel") } }
        )
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
