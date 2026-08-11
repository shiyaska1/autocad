package com.sketchdxf.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchShape
import com.sketchdxf.app.data.SketchSource
import com.sketchdxf.app.dxf.BitmapUtil
import com.sketchdxf.app.dxf.DimensionGuesser
import com.sketchdxf.app.dxf.PdfPageRenderer
import com.sketchdxf.app.dxf.PendingSketchEditor
import com.sketchdxf.app.dxf.SketchAttachmentStore
import com.sketchdxf.app.dxf.SketchLineDetector
import com.sketchdxf.app.ocr.rememberImageCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

private data class PickedFile(val uri: Uri, val displayName: String, val isPdf: Boolean)

/**
 * Front page: name the work, pick photo(s)/PDF(s) of the sketch, then "Make DXF" runs
 * auto line-detection (best-effort) and opens the editor to review/confirm/correct it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DxfHomeScreen(onBack: () -> Unit, onGoToEditor: () -> Unit, onBlankCanvas: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<PickedFile>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun addUri(uri: Uri?) {
        if (uri == null) return
        val mime = context.contentResolver.getType(uri) ?: ""
        val name0 = queryDisplayName(context, uri) ?: "file"
        picked.add(PickedFile(uri, name0, mime == "application/pdf" || name0.endsWith(".pdf", true)))
    }

    val camera = rememberImageCamera { uri -> addUri(uri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { addUri(it) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { addUri(it) }
    }

    fun makeDxf() {
        if (name.isBlank()) { error = "Give this drawing a name first"; return }
        if (picked.isEmpty()) { error = "Add at least one photo or PDF of your sketch"; return }
        error = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // Copy every picked file into app storage as a source attachment.
                val sources = picked.mapNotNull { SketchAttachmentStore.copyIn(context, it.uri) }

                // The first picked file is the primary sketch used for line detection; a PDF's
                // first page is rasterized for that purpose (the original PDF is still kept as
                // a source attachment above).
                val first = picked.first()
                val primaryBitmap: Bitmap? = if (first.isPdf) {
                    PdfPageRenderer.renderPages(context, first.uri, targetWidth = 1600).firstOrNull()
                } else {
                    sources.firstOrNull { !it.mime.contains("pdf") }?.let { BitmapUtil.decodeOriented(it.path) }
                }

                if (primaryBitmap == null) {
                    return@withContext null
                }

                val (workingBitmap, lines) = SketchLineDetector.detect(primaryBitmap)
                val baseFile = SketchAttachmentStore.newFile(context, "base", "png")
                FileOutputStream(baseFile).use { workingBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val shapes = lines.map { line ->
                    val guess = DimensionGuesser.guess(context, workingBitmap, line).toDoubleOrNull() ?: 0.0
                    SketchShape(
                        workId = 0, kind = ShapeKind.LINE,
                        x1 = line.x1, y1 = line.y1, x2 = line.x2, y2 = line.y2,
                        realLength = guess, confirmed = false
                    )
                }
                Triple(baseFile.absolutePath, shapes, sources)
            }
            busy = false
            if (result == null) {
                error = "Couldn't read that file — try another photo"
            } else {
                val (basePath, shapes, sources) = result
                PendingSketchEditor.set(workId = 0, createdAt = 0, name = name.trim(), baseImagePath = basePath, shapes = shapes, sources = sources)
                onGoToEditor()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New sketch → DXF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name this drawing") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Photo(s) or PDF of your pen sketch, with dimensions written on it",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { camera() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PhotoCamera, "Camera", Modifier.padding(end = 4.dp))
                        Text("Camera")
                    }
                    OutlinedButton(
                        onClick = { gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, "Gallery", Modifier.padding(end = 4.dp))
                        Text("Gallery")
                    }
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.UploadFile, "File", Modifier.padding(end = 4.dp))
                        Text("File")
                    }
                }

                if (picked.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp).weight(1f, fill = false)) {
                        items(picked.size) { i ->
                            val f = picked[i]
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(f.displayName, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { picked.removeAt(i) }) {
                                    Icon(Icons.Filled.Close, "Remove")
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("No files added yet", color = MaterialTheme.colorScheme.outline)
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }

                Button(onClick = { makeDxf() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Converting…" else "Make DXF")
                }
                OutlinedButton(onClick = onBlankCanvas, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Draw, null, Modifier.padding(end = 6.dp))
                    Text("Or draw straight on the phone")
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Detecting lines…", modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
