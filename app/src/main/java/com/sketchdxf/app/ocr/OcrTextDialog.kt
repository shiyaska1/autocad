package com.sketchdxf.app.ocr

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sketchdxf.app.dxf.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen: shows a picked/captured photo, lets the user drag a rectangle over just the text
 * they want (so recognition isn't thrown off by everything else in the shot), runs ML Kit's Latin
 * text recognizer (English) on that cropped region, then shows the result in an editable field —
 * OCR is rarely perfect, so this is a starting point to fix up, not assumed correct. [onResult]
 * hands back the (possibly hand-corrected) text; the caller places it same as a normal Text label.
 */
@Composable
fun OcrTextDialog(imagePath: String, onResult: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bitmap = remember(imagePath) { BitmapUtil.decodeOriented(imagePath, maxDim = 2200) }

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var displayedSize by remember { mutableStateOf(Size.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recognized by remember { mutableStateOf<String?>(null) }

    fun runRecognition() {
        val bmp = bitmap ?: return
        val s = dragStart; val c = dragCurrent
        if (s == null || c == null || displayedSize.width <= 0f || displayedSize.height <= 0f) {
            error = "Drag a rectangle over the text first"; return
        }
        val scaleX = bmp.width / displayedSize.width
        val scaleY = bmp.height / displayedSize.height
        val left = (minOf(s.x, c.x) * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val top = (minOf(s.y, c.y) * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val right = (maxOf(s.x, c.x) * scaleX).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (maxOf(s.y, c.y) * scaleY).toInt().coerceIn(top + 1, bmp.height)
        error = null
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(cropped, 0)))
                    result.text
                }.getOrNull()
            }
            busy = false
            if (text.isNullOrBlank()) error = "No text recognized in that area — try a tighter or clearer selection"
            else recognized = text
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // usePlatformDefaultWidth = false alone only frees up the WIDTH — a plain Compose Dialog's
        // underlying window still defaults to WRAP_CONTENT height, so a fillMaxSize() Column inside
        // it doesn't reliably get the full screen: the recognize/cancel row at the bottom could end
        // up outside the window's actual (wrap-measured) bounds instead of visible below the image.
        // Forcing the real Android window to MATCH_PARENT is the standard fix for that.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        // A scrollable, height-bounded layout rather than a weight(1f) image box filling
        // whatever's left: weight(1f) trusted the Dialog window to be exactly screen-sized to
        // leave room below it for the Recognize/Cancel row, which the MATCH_PARENT fix above
        // didn't reliably guarantee across devices — this way the row is always reachable
        // (scroll to it) regardless of how tall the window or the image ends up being.
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(12.dp)
        ) {
            Text("Select the text area", style = MaterialTheme.typography.titleMedium)
            Text(
                "Drag a rectangle over the text, then tap Recognize.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (bitmap == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("Couldn't open that picture", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(420.dp).onSizeChanged { displayedSize = Size(it.width.toFloat(), it.height.toFloat()) }
                ) {
                    Image(
                        bitmap.asImageBitmap(), null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p -> dragStart = p; dragCurrent = p },
                                onDrag = { change, _ -> change.consume(); dragCurrent = change.position },
                                onDragEnd = {}
                            )
                        }
                    ) {
                        val s = dragStart; val c = dragCurrent
                        if (s != null && c != null) {
                            val topLeft = Offset(minOf(s.x, c.x), minOf(s.y, c.y))
                            val size = Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y))
                            drawRect(Color(0xFF1565C0).copy(alpha = 0.15f), topLeft = topLeft, size = size)
                            drawRect(Color(0xFF1565C0), topLeft = topLeft, size = size, style = Stroke(width = 3f))
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            recognized?.let { text ->
                var edited by remember(text) { mutableStateOf(text) }
                OutlinedTextField(
                    value = edited, onValueChange = { edited = it },
                    label = { Text("Recognized text (edit if needed)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { recognized = null }) { Text("Retry selection") }
                    TextButton(onClick = { onResult(edited) }) { Text("Use this text") }
                }
            } ?: run {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp).height(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { runRecognition() }) { Text("Recognize") }
                    }
                }
            }
        }
    }
}
