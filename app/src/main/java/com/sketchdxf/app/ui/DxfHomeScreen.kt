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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.sketchdxf.app.dxf.BitmapUtil
import com.sketchdxf.app.dxf.PdfPageRenderer
import com.sketchdxf.app.dxf.PendingSketchEditor
import com.sketchdxf.app.dxf.SketchAttachmentStore
import com.sketchdxf.app.ocr.rememberImageCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

private data class PickedFile(val uri: Uri, val displayName: String, val isPdf: Boolean)

/**
 * Front page: name the work and pick photo(s)/PDF(s) of the sketch. There is no automatic
 * line-detection — that turned out unreliable enough on real pencil sketches to crash and
 * mislead more than it helped. Instead the first picked file opens as a background layer in
 * the editor, and every line/circle/text on top of it is drawn (and dimensioned) by hand with
 * the CAD-style tools there (ortho, snap, direction + typed length).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
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

    fun openInEditor() {
        if (name.isBlank()) { error = "Give this drawing a name first"; return }
        if (picked.isEmpty()) { error = "Add at least one photo or PDF of your sketch"; return }
        error = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // Copy every picked file into app storage as a source attachment.
                val sources = picked.mapNotNull { SketchAttachmentStore.copyIn(context, it.uri) }

                // The first picked file becomes the background layer to trace over; a PDF's
                // first page is rasterized for that (the original PDF stays a source attachment).
                val first = picked.first()
                val bitmap: Bitmap? = if (first.isPdf) {
                    PdfPageRenderer.renderPages(context, first.uri, targetWidth = 1600).firstOrNull()
                } else {
                    sources.firstOrNull { !it.mime.contains("pdf") }?.let { BitmapUtil.decodeOriented(it.path) }
                }
                if (bitmap == null) return@withContext null

                val baseFile = SketchAttachmentStore.newFile(context, "base", "png")
                FileOutputStream(baseFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                baseFile.absolutePath to sources
            }
            busy = false
            if (result == null) {
                error = "Couldn't read that file — try another photo"
            } else {
                val (basePath, sources) = result
                PendingSketchEditor.set(workId = 0, createdAt = 0, name = name.trim(), baseImagePath = basePath, shapes = emptyList(), sources = sources)
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
                    "Photo(s) or PDF of your pen sketch — the first one becomes the background you trace over",
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

                Button(onClick = { openInEditor() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Opening…" else "Open in editor")
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
                        Text("Opening…", modifier = Modifier.padding(top = 12.dp))
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
