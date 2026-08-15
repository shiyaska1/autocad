package com.sketchdxf.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.SketchWork
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DxfListScreen(onBack: () -> Unit, onNew: () -> Unit, onOpen: (Long) -> Unit, onBlocks: () -> Unit, onBackup: () -> Unit, onPdfTools: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).sketchDao() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var allWorks by remember { mutableStateOf<List<SketchWork>>(emptyList()) }
    LaunchedEffect(Unit) { dao.works().collectLatest { allWorks = it } }

    var query by remember { mutableStateOf("") }
    var useDateRange by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(monthAgo()) }
    var to by remember { mutableStateOf(endOfToday()) }
    var pendingDelete by remember { mutableStateOf<SketchWork?>(null) }

    val filtered = remember(allWorks, query, useDateRange, from, to) {
        allWorks.filter { w ->
            (query.isBlank() || w.name.contains(query, ignoreCase = true)) &&
                (!useDateRange || w.updatedAt in from..to)
        }
    }

    pendingDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${w.name}\"?") },
            text = { Text("This removes its DXF, preview and source files. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    com.sketchdxf.app.dxf.SketchAttachmentStore.delete(w.dxfPath)
                    com.sketchdxf.app.dxf.SketchAttachmentStore.delete(w.previewPath)
                    scope.launch {
                        val sources = dao.sourcesFor(w.id)
                        sources.forEach { com.sketchdxf.app.dxf.SketchAttachmentStore.delete(it.path) }
                        dao.deleteSourcesFor(w.id)
                        dao.deleteShapesFor(w.id)
                        dao.deleteWork(w)
                    }
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sketch DXF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onBlocks) { Icon(Icons.Filled.Category, "Block library") }
                    IconButton(onClick = onPdfTools) { Icon(Icons.Filled.PictureAsPdf, "PDF tools (split/merge)") }
                    IconButton(onClick = onBackup) { Icon(Icons.Filled.SettingsBackupRestore, "Backup & Restore") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "New conversion") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useDateRange, onCheckedChange = { useDateRange = it })
                Text("Filter by date (default: last 1 month)", style = MaterialTheme.typography.labelLarge)
            }
            if (useDateRange) {
                val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(context, from) { from = it } }, modifier = Modifier.weight(1f)) {
                        Text("From ${df.format(from)}")
                    }
                    OutlinedButton(onClick = { pickDate(context, to) { to = it } }, modifier = Modifier.weight(1f)) {
                        Text("To ${df.format(to)}")
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversions yet — tap + to start one", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { w ->
                        val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()) }
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(w.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val thumb = remember(w.previewPath) {
                                runCatching {
                                    val f = File(w.previewPath)
                                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
                                }.getOrNull()
                            }
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) Image(thumb, null, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(w.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(dateFmt.format(w.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { pendingDelete = w }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pickDate(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = current }
    android.app.DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) },
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun monthAgo(): Long = Calendar.getInstance().apply {
    add(Calendar.MONTH, -1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis
