package com.sketchdxf.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sketchdxf.app.ink.InkRecognizer
import kotlinx.coroutines.launch

/** Small handwriting pad — write a number or short label, OK recognizes it with on-device ink ML. */
@Composable
fun HandwriteInputDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recognizer = remember { InkRecognizer() }
    DisposableEffect(Unit) { onDispose { recognizer.close() } }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var strokeVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { ready = recognizer.ensureReady() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { strokes.clear(); strokeVersion++ }, enabled = !busy) { Text("Clear") }
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        if (strokes.isEmpty()) { onDismiss(); return@Button }
                        busy = true
                        val snapshot = strokes.toList()
                        scope.launch {
                            val text = recognizer.recognize(snapshot)
                            busy = false
                            onResult(text.trim())
                        }
                    },
                    enabled = ready && !busy, modifier = Modifier.weight(1f)
                ) { Text(if (busy) "Reading…" else "OK") }
            }
            Text(
                if (!ready) "Preparing handwriting recognition… (first time needs internet)" else "Write, then tap OK",
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Box(
                Modifier.fillMaxWidth().height(220.dp)
                    .background(Color(0xFFF7F7F7), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.Gray, RoundedCornerShape(10.dp))
            ) {
                var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(ready) {
                        if (!ready) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var pts = listOf(down.position)
                            live = pts; down.consume()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (c.pressed) { pts = pts + c.position; live = pts; c.consume() }
                                else { strokes.add(pts); live = emptyList(); strokeVersion++; break }
                            }
                        }
                    }
                ) {
                    val all = if (live.isNotEmpty()) strokes + listOf(live) else strokes.toList()
                    all.forEach { pts ->
                        if (pts.size >= 2) {
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                            }
                            drawPath(path, color = Color(0xFF111111), style = Stroke(width = 5f))
                        } else if (pts.size == 1) drawCircle(Color(0xFF111111), radius = 2.5f, center = pts[0])
                    }
                }
                if (busy) Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
        }
    }
}
