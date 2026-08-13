package com.sketchdxf.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sketchdxf.app.data.BackupManager
import com.sketchdxf.app.data.DownloadSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A manual, user-controlled alternative to Android's own Auto Backup (which needs a Google
 * account with backup enabled, only runs on its own schedule, and caps out at 25MB) — export
 * everything the app has stored into a single .zip you keep wherever you like, and bring it back
 * with Import. See [BackupManager].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoredOk by remember { mutableStateOf(false) }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    fun exportBackup() {
        busy = true
        message = null
        scope.launch {
            val zip = withContext(Dispatchers.IO) { BackupManager.export(context) }
            busy = false
            if (zip == null) {
                message = "Couldn't create the backup"
            } else {
                val saved = DownloadSaver.save(context, zip, zip.name, "application/zip")
                message = if (saved) "Saved to Downloads: ${zip.name}" else "Couldn't save to Downloads"
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zip)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Restore this backup?") },
            text = { Text("This replaces every drawing, block, and attached file currently on this device with what's in the backup. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    busy = true
                    message = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { BackupManager.import(context, uri) }
                        busy = false
                        if (ok) {
                            restoredOk = true
                        } else {
                            message = "That doesn't look like a Sketch DXF backup"
                        }
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") } }
        )
    }

    if (restoredOk) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restore complete") },
            text = { Text("Restart the app to see your restored drawings.") },
            confirmButton = {
                TextButton(onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }) { Text("Restart now") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
            Text(
                "Everything Sketch DXF stores — every drawing, block, and attached photo/PDF/DXF — lives only on this device. " +
                    "Export it to a .zip you keep somewhere safe (or share to cloud storage), and Import it back on this or another device.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { exportBackup() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Export backup")
            }
            OutlinedButton(
                onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text("Import backup") }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp))
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}
