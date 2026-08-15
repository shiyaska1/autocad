package com.sketchdxf.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.sketchdxf.app.data.DownloadSaver
import com.sketchdxf.app.dxf.PdfBuilder
import com.sketchdxf.app.dxf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Picks one PDF, renders every page as a thumbnail, then saves each page as either its own
 *  single-page PDF or a JPG — straight to Downloads, named "<original>_page<N>.<ext>". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSplitScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseName by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun loadPdf(uri: Uri?) {
        if (uri == null) return
        baseName = queryDisplayName(context, uri)?.substringBeforeLast('.') ?: "document"
        pages = emptyList(); status = null; busy = true
        scope.launch {
            pages = withContext(Dispatchers.Default) { PdfPageRenderer.renderPages(context, uri) }
            busy = false
            if (pages.isEmpty()) status = "Couldn't read that PDF"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> loadPdf(uri) }

    fun saveAll(asPdf: Boolean) {
        if (pages.isEmpty()) return
        busy = true; status = null
        scope.launch {
            val saved = withContext(Dispatchers.Default) {
                pages.mapIndexedNotNull { i, bmp ->
                    runCatching {
                        val name = "${baseName}_page${i + 1}"
                        if (asPdf) {
                            val tmp = File(context.cacheDir, "$name.pdf")
                            PdfBuilder.build(listOf(bmp), tmp)
                            DownloadSaver.save(context, tmp, "$name.pdf", "application/pdf").also { tmp.delete() }
                        } else {
                            val tmp = File(context.cacheDir, "$name.jpg")
                            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            DownloadSaver.save(context, tmp, "$name.jpg", "image/jpeg").also { tmp.delete() }
                        }
                    }.getOrDefault(false)
                }.count { it }
            }
            busy = false
            status = "Saved $saved of ${pages.size} page(s) to Downloads"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split PDF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.UploadFile, null, Modifier.padding(end = 6.dp))
                Text(if (pages.isEmpty()) "Pick a PDF" else "Pick a different PDF")
            }

            if (pages.isNotEmpty()) {
                Text(
                    "${pages.size} page(s)", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pages.size) { i ->
                        Column {
                            Image(
                                pages[i].asImageBitmap(), null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Text(
                                "Page ${i + 1}", style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveAll(asPdf = true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Save as PDFs")
                    }
                    Button(onClick = { saveAll(asPdf = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Save as JPGs")
                    }
                }
            } else if (!busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pick a PDF to split it into pages", color = MaterialTheme.colorScheme.outline)
                }
            }

            if (busy) {
                Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            status?.let {
                Text(it, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
