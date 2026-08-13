package com.sketchdxf.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.SketchBlock
import com.sketchdxf.app.data.SketchBlockCodec
import com.sketchdxf.app.dxf.PreviewRenderer
import kotlinx.coroutines.launch

/**
 * Standalone Block library, reachable straight from the home screen — browse, search and delete
 * every Block you've saved across every drawing (Blocks aren't tied to a single sketch; see
 * SketchBlock). Actually dropping a block onto a canvas still happens from inside a sketch's
 * editor (the "Block" toolbar button there), since a drop needs a canvas to drop onto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).sketchDao() }
    val scope = rememberCoroutineScope()

    var blocks by remember { mutableStateOf<List<SketchBlock>>(emptyList()) }
    LaunchedEffect(Unit) { blocks = dao.allBlocks() }

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SketchBlock?>(null) }

    val categories = remember(blocks) { blocks.map { it.category }.distinct() }
    val filtered = remember(blocks, query, category) {
        blocks.filter {
            (category == null || it.category == category) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    pendingDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${b.name}\"?") },
            text = { Text("This removes it from every drawing's Block picker. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deleteBlock(b); blocks = dao.allBlocks() }
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Block library") },
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            if (categories.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                    categories.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (blocks.isEmpty())
                            "No blocks saved yet — inside any drawing, select shapes with Box Select and tap \"Save Block\""
                        else "No blocks match",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { b ->
                        val thumb = remember(b.id) {
                            runCatching {
                                PreviewRenderer.render(SketchBlockCodec.deserialize(b.shapesData), size = 200).asImageBitmap()
                            }.getOrNull()
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) Image(thumb, null, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(b.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { pendingDelete = b }) {
                                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
