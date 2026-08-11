package com.sketchdxf.app.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline OCR — best-effort reading of a printed/typed number near a detected line.
 * Never authoritative: every screen that calls this shows the result as an editable suggestion.
 */
object TextOcr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** All recognized text lines (roughly top-to-bottom), or empty on failure. */
    suspend fun lines(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val result = Tasks.await(recognizer.process(image))
            result.textBlocks.flatMap { block -> block.lines.map { it.text } }
        }.getOrDefault(emptyList())
    }

    /** The whole recognized text collapsed to a single trimmed line. */
    suspend fun singleLine(context: Context, uri: Uri): String =
        lines(context, uri).joinToString(" ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Camera capture that writes a full-resolution photo into app storage and hands back its Uri.
 * Requests CAMERA permission first. Returns a lambda that launches the flow.
 */
@Composable
fun rememberImageCamera(onImage: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pending; pending = null
        if (ok && u != null) onImage(u)
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "cap_${System.nanoTime()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        pending = uri
        runCatching { capture.launch(uri) }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera()
        else perm.launch(Manifest.permission.CAMERA)
    }
}
