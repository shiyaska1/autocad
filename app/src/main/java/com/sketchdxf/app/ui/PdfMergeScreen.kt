package com.sketchdxf.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sketchdxf.app.data.DownloadSaver
import com.sketchdxf.app.dxf.PdfBuilder
import com.sketchdxf.app.dxf.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class MergePage(val bitmap: Bitmap, val sourceName: String, val pageNumber: Int)

/** Picks several PDFs, flattens every page from all of them into one drag-reorderable list
 *  (thumbnail + source file name + page number), then merges them — in whatever order the list
 *  ends up in — into a single output PDF saved to Downloads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMergeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<MergePage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }

    fun addPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true; status = null
        scope.launch {
            val added = withContext(Dispatchers.Default) {
                uris.flatMap { uri ->
                    val name = queryDisplayNameMerge(context, uri)?.substringBeforeLast('.') ?: "document"
                    PdfPageRenderer.renderPages(context, uri).mapIndexed { i, bmp -> MergePage(bmp, name, i + 1) }
                }
            }
            pages = pages + added
            busy = false
            if (added.isEmpty()) status = "Couldn't read that PDF"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addPdfs(uris) }

    if (showNameDialog) {
        var outName by remember { mutableStateOf("merged") }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Merged file name") },
            text = {
                OutlinedTextField(
                    value = outName, onValueChange = { outName = it }, singleLine = true,
                    label = { Text("File name") }, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    val name = outName.ifBlank { "merged" }
                    busy = true; status = null
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            runCatching {
                                val tmp = File(context.cacheDir, "$name.pdf")
                                PdfBuilder.build(pages.map { it.bitmap }, tmp)
                                DownloadSaver.save(context, tmp, "$name.pdf", "application/pdf").also { tmp.delete() }
                            }.getOrDefault(false)
                        }
                        busy = false
                        status = if (ok) "Saved $name.pdf (${pages.size} pages) to Downloads" else "Could not save the merged file"
                    }
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge PDFs") },
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
                Text("Add PDFs")
            }

            if (pages.isNotEmpty()) {
                Text(
                    "${pages.size} page(s) — long-press and drag to reorder",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ReorderablePageList(
                        pages = pages,
                        onReorder = { pages = it },
                        onRemove = { idx -> pages = pages.toMutableList().apply { removeAt(idx) } }
                    )
                }
                Button(
                    onClick = { showNameDialog = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) { Text("Merge into one PDF") }
            } else if (!busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Add two or more PDFs to merge them", color = MaterialTheme.colorScheme.outline)
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

/** Plain scrollable Column (not LazyColumn — merge lists are realistically small) whose rows can
 *  be long-pressed and dragged to swap places, reordering [pages] live as the drag crosses row
 *  boundaries — no drag-reorder component ships with Compose Foundation, so this hand-rolls the
 *  same "accumulate drag offset, swap once it passes half a row's height" approach every such
 *  implementation uses.
 *
 *  Each row's long-lived drag gesture is keyed by the dragged item's own (stable, never-recreated)
 *  Bitmap identity rather than its list position, and looks its current position back up via
 *  [rememberUpdatedState] on every drag event rather than closing over a captured index — a
 *  position captured once at gesture-start would go stale as soon as this or another drag
 *  reorders the list, since the gesture's coroutine itself is never restarted mid-drag. */
@Composable
private fun ReorderablePageList(pages: List<MergePage>, onReorder: (List<MergePage>) -> Unit, onRemove: (Int) -> Unit) {
    val rowHeight = 72.dp
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val currentPages = rememberUpdatedState(pages)
    val currentOnReorder = rememberUpdatedState(onReorder)
    var draggedItem by remember { mutableStateOf<MergePage?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        pages.forEachIndexed { index, page ->
            key(page.bitmap) {
                val isDragged = draggedItem === page
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        page.bitmap.asImageBitmap(), null,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(page.sourceName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text("Page ${page.pageNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { onRemove(index) }) { Icon(Icons.Filled.Close, "Remove") }
                    Icon(
                        Icons.Filled.DragHandle, "Drag to reorder",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.pointerInput(page) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedItem = page; dragOffset = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    val list = currentPages.value
                                    val curIdx = list.indexOf(page)
                                    if (curIdx >= 0) {
                                        val moveBy = (dragOffset / rowHeightPx).toInt()
                                        if (moveBy != 0) {
                                            val newIndex = (curIdx + moveBy).coerceIn(0, list.size - 1)
                                            if (newIndex != curIdx) {
                                                val mutable = list.toMutableList()
                                                mutable.removeAt(curIdx)
                                                mutable.add(newIndex, page)
                                                currentOnReorder.value(mutable)
                                                dragOffset -= moveBy * rowHeightPx
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { draggedItem = null; dragOffset = 0f },
                                onDragCancel = { draggedItem = null; dragOffset = 0f }
                            )
                        }
                    )
                }
            }
        }
    }
}

private fun queryDisplayNameMerge(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
