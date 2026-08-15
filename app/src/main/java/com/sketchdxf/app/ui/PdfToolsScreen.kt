package com.sketchdxf.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Landing page for the PDF utilities: split a PDF into per-page PDFs/JPGs, or merge several
 *  PDFs into one — reachable from the drawings list's top bar, independent of any sketch/DXF
 *  work (these operate on any PDF, not just ones this app produced). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(onBack: () -> Unit, onSplit: () -> Unit, onMerge: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF tools") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Button(onClick = onSplit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CallSplit, null, Modifier.padding(end = 8.dp))
                Text("Split a PDF into pages (PDF or JPG)")
            }
            Button(onClick = onMerge, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Icon(Icons.Filled.MergeType, null, Modifier.padding(end = 8.dp))
                Text("Merge several PDFs into one")
            }
        }
    }
}
