package com.sketchdxf.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchShape
import com.sketchdxf.app.data.SketchWork
import com.sketchdxf.app.dxf.DxfWriter
import com.sketchdxf.app.dxf.PendingSketchEditor
import com.sketchdxf.app.dxf.PreviewRenderer
import com.sketchdxf.app.dxf.SketchAttachmentStore
import com.sketchdxf.app.ui.common.HandwriteInputDialog
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** kotlin.math.hypot only has a (Double, Double) overload — this fills the (Float, Float) gap. */
private fun hypotF(x: Float, y: Float): Float = hypot(x.toDouble(), y.toDouble()).toFloat()

private enum class Tool { SELECT, LINE, CIRCLE, TEXT }

/**
 * Shared editor for both flows: tracing lines by hand over a background photo, and drawing a
 * sketch from scratch (baseImagePath == null) — nothing is auto-detected. Coordinates are this
 * composable's own canvas-pixel space — consistent within one work on one device, which is all
 * that's needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SketchEditorScreen(onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var workId by remember { mutableStateOf(0L) }
    var createdAt by remember { mutableStateOf(0L) }
    var name by remember { mutableStateOf("") }
    var baseImagePath by remember { mutableStateOf<String?>(null) }
    var oldDxfPath by remember { mutableStateOf("") }
    var oldPreviewPath by remember { mutableStateOf("") }
    val sourcesRef = remember { mutableStateListOf<com.sketchdxf.app.data.SketchSource>() }
    val shapes = remember { mutableStateListOf<SketchShape>() }

    LaunchedEffect(Unit) {
        workId = PendingSketchEditor.workId
        createdAt = PendingSketchEditor.createdAt
        name = PendingSketchEditor.name
        baseImagePath = PendingSketchEditor.baseImagePath
        oldDxfPath = PendingSketchEditor.oldDxfPath
        oldPreviewPath = PendingSketchEditor.oldPreviewPath
        sourcesRef.addAll(PendingSketchEditor.sources)
        shapes.addAll(PendingSketchEditor.shapes)
        loaded = true
    }

    var tool by remember { mutableStateOf(Tool.SELECT) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var editingIndex by remember { mutableStateOf(-1) }
    var pendingTextPos by remember { mutableStateOf<Offset?>(null) }
    var showRoomPlan by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    // CAD-style line input: tap a start point, tap an end point — the line is drawn between them
    // immediately (Ortho locks the end point to horizontal/vertical from the start, like AutoCAD).
    // Typing an exact length afterward is optional; skipping it just keeps the line as tapped.
    var orthoOn by remember { mutableStateOf(true) }
    var snapOn by remember { mutableStateOf(true) }
    var chainOn by remember { mutableStateOf(true) }
    var lineStartPoint by remember { mutableStateOf<Offset?>(null) }
    var pendingLengthIndex by remember { mutableStateOf(-1) }

    /** Snaps to the nearest existing line's endpoint/midpoint within range, else returns [p]. */
    fun trySnapPoint(p: Offset): Offset {
        if (!snapOn) return p
        var best = p; var bestDist = 28f
        shapes.forEach { s ->
            if (s.kind == ShapeKind.LINE) {
                listOf(Offset(s.x1, s.y1), Offset(s.x2, s.y2), Offset((s.x1 + s.x2) / 2f, (s.y1 + s.y2) / 2f)).forEach { c ->
                    val d = hypotF(p.x - c.x, p.y - c.y)
                    if (d < bestDist) { bestDist = d; best = c }
                }
            }
        }
        return best
    }

    /** Locks [raw] onto the horizontal or vertical line through [start], whichever is closer —
     *  the end point keeps its tapped distance along that axis, like AutoCAD's Ortho mode. */
    fun orthoProject(start: Offset, raw: Offset): Offset {
        val dx = raw.x - start.x; val dy = raw.y - start.y
        return if (abs(dx) >= abs(dy)) Offset(raw.x, start.y) else Offset(start.x, raw.y)
    }

    /** Pixels per real-world mm, derived from confirmed lines so far (or a sensible default). */
    fun currentPxPerMm(): Float {
        val ratios = shapes.filter { it.kind == ShapeKind.LINE && it.confirmed && it.realLength > 0.01 }
            .map { hypotF(it.x2 - it.x1, it.y2 - it.y1) / it.realLength.toFloat() }
        if (ratios.isNotEmpty()) return ratios.sorted()[ratios.size / 2]
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: 800f
        return (cw * 0.6f) / 3000f // first-ever line: assume it's roughly a 3 m wall
    }

    val baseBitmap = remember(baseImagePath) {
        baseImagePath?.let { p -> runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() }
    }

    /**
     * Quick-command: generates a rectangular room plan (outer + inner wall lines, already
     * dimensioned) from typed length/width/wall-thickness — the "type it instead of drawing it"
     * shortcut, mirroring what an AutoCAD macro of the same shape would do with GetPoint clicks.
     */
    fun addRoomPlan(lengthMm: Double, widthMm: Double, wallMm: Double) {
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: 800f
        val ch = canvasSize.height.toFloat().takeIf { it > 0f } ?: 1000f
        val scale = minOf((cw * 0.85f) / lengthMm.toFloat(), (ch * 0.85f) / widthMm.toFloat())
        val len = (lengthMm * scale).toFloat()
        val wid = (widthMm * scale).toFloat()
        val wall = (wallMm * scale).toFloat()
        val ox = (cw - len) / 2f
        val oy = (ch - wid) / 2f

        val one = Offset(ox, oy); val two = Offset(ox + len, oy)
        val three = Offset(ox + len, oy + wid); val four = Offset(ox, oy + wid)
        val one2 = Offset(ox + wall, oy + wall); val two2 = Offset(ox + len - wall, oy + wall)
        val three2 = Offset(ox + len - wall, oy + wid - wall); val four2 = Offset(ox + wall, oy + wid - wall)

        fun ln(a: Offset, b: Offset, realLen: Double) = SketchShape(
            workId = 0, kind = ShapeKind.LINE, x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y,
            realLength = realLen.coerceAtLeast(0.0), confirmed = true
        )
        val innerLen = (lengthMm - 2 * wallMm).coerceAtLeast(0.0)
        val innerWid = (widthMm - 2 * wallMm).coerceAtLeast(0.0)
        shapes.addAll(
            listOf(
                ln(one, two, lengthMm), ln(two, three, widthMm), ln(three, four, lengthMm), ln(four, one, widthMm),
                ln(one2, two2, innerLen), ln(two2, three2, innerWid), ln(three2, four2, innerLen), ln(four2, one2, innerWid)
            )
        )
    }

    /**
     * Quick-command: a space-separated walk like "R500 B200 L300 T200" — R/B/L/T = right/
     * bottom/left/top wall, the number is that wall's length in mm — draws each outer wall
     * plus its inner (wall-thickness) line, extended past each corner so consecutive walls'
     * inner lines overlap into a clean joint, same technique as an AutoCAD "type the walk"
     * macro. Returns an error message, or null on success.
     */
    fun addContinuousPlan(command: String, wallMm: Double): String? {
        data class Seg(val dir: Char, val value: Double)
        val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "Type at least one segment, e.g. R500 B200 L300 T200"
        val segs = tokens.map { tok ->
            val dir = tok[0].uppercaseChar()
            if (dir !in "RBLT") return "\"$tok\" doesn't start with R, B, L or T"
            val value = tok.substring(1).toDoubleOrNull()
            if (value == null || value <= 0) return "\"$tok\" needs a positive number after the direction"
            Seg(dir, value)
        }

        // Walk in mm-space first (screen-style: R=+x, L=-x, T=up=-y, B=down=+y).
        val mmPoints = mutableListOf(Offset(0f, 0f))
        segs.forEach { s ->
            val prev = mmPoints.last()
            val (dx, dy) = when (s.dir) {
                'R' -> s.value.toFloat() to 0f
                'L' -> -s.value.toFloat() to 0f
                'B' -> 0f to s.value.toFloat()
                else -> 0f to -s.value.toFloat() // 'T'
            }
            mmPoints.add(Offset(prev.x + dx, prev.y + dy))
        }

        val minX = mmPoints.minOf { it.x }; val maxX = mmPoints.maxOf { it.x }
        val minY = mmPoints.minOf { it.y }; val maxY = mmPoints.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1f); val spanY = (maxY - minY).coerceAtLeast(1f)
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: 800f
        val ch = canvasSize.height.toFloat().takeIf { it > 0f } ?: 1000f
        val scale = minOf((cw * 0.8f) / spanX, (ch * 0.8f) / spanY)
        val ox = (cw - spanX * scale) / 2f - minX * scale
        val oy = (ch - spanY * scale) / 2f - minY * scale
        val canvasPoints = mmPoints.map { Offset(ox + it.x * scale, oy + it.y * scale) }
        val wallPx = (wallMm * scale).toFloat()

        val newShapes = mutableListOf<SketchShape>()
        for (i in segs.indices) {
            val a = canvasPoints[i]; val b = canvasPoints[i + 1]
            newShapes.add(
                SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y,
                    realLength = segs[i].value, confirmed = true)
            )
            val seg = b - a
            val len = hypotF(seg.x, seg.y)
            if (len < 1e-3f) continue
            val d = Offset(seg.x / len, seg.y / len)
            val n = Offset(-d.y, d.x) // inward normal, for a clockwise R/B/L/T-ordered wall walk
            val innerA = Offset(a.x + wallPx * (n.x - d.x), a.y + wallPx * (n.y - d.y))
            val innerB = Offset(b.x + wallPx * (n.x + d.x), b.y + wallPx * (n.y + d.y))
            newShapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = innerA.x, y1 = innerA.y, x2 = innerB.x, y2 = innerB.y))
        }
        shapes.addAll(newShapes)
        return null
    }

    fun hitTest(p: Offset): Int {
        var best = -1; var bestDist = 26f
        shapes.forEachIndexed { i, s ->
            val d = when (s.kind) {
                ShapeKind.LINE -> distToSegment(p, Offset(s.x1, s.y1), Offset(s.x2, s.y2))
                ShapeKind.CIRCLE -> abs(hypotF(p.x - s.cx, p.y - s.cy) - s.r)
                ShapeKind.TEXT -> hypotF(p.x - s.x1, p.y - s.y1)
                else -> Float.MAX_VALUE
            }
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    fun save() {
        busy = true
        scope.launch {
            val dao = AppDatabase.get(context).sketchDao()
            val now = System.currentTimeMillis()
            val finalName = name.trim().ifBlank { "Untitled" }
            val id = if (workId > 0) workId else dao.insertWork(SketchWork(name = finalName, createdAt = now, updatedAt = now))

            dao.deleteShapesFor(id)
            if (shapes.isNotEmpty()) dao.insertShapes(shapes.map { it.copy(id = 0, workId = id) })

            if (workId <= 0) {
                dao.deleteSourcesFor(id)
                sourcesRef.forEach { dao.insertSource(it.copy(id = 0, workId = id)) }
            }

            if (oldDxfPath.isNotBlank()) SketchAttachmentStore.delete(oldDxfPath)
            if (oldPreviewPath.isNotBlank()) SketchAttachmentStore.delete(oldPreviewPath)

            val dxfFile = SketchAttachmentStore.newFile(context, "work", "dxf")
            DxfWriter.export(dxfFile, shapes)
            val previewBmp = PreviewRenderer.render(shapes)
            val previewFile = SketchAttachmentStore.newFile(context, "preview", "png")
            PreviewRenderer.save(previewBmp, previewFile)

            dao.updateWork(
                SketchWork(
                    id = id, name = finalName, createdAt = if (createdAt > 0) createdAt else now,
                    updatedAt = now, dxfPath = dxfFile.absolutePath, previewPath = previewFile.absolutePath,
                    status = "FINALIZED"
                )
            )
            busy = false
            onSaved(id)
        }
    }

    if (editingIndex >= 0 && editingIndex < shapes.size) {
        ShapeEditDialog(
            shape = shapes[editingIndex],
            onConfirm = { updated -> shapes[editingIndex] = updated; editingIndex = -1 },
            onDelete = { shapes.removeAt(editingIndex); editingIndex = -1 },
            onDismiss = { editingIndex = -1 }
        )
    }
    pendingTextPos?.let { pos ->
        LabelInputDialog(
            title = "Text label",
            initial = "",
            onConfirm = { text ->
                if (text.isNotBlank()) shapes.add(SketchShape(workId = 0, kind = ShapeKind.TEXT, x1 = pos.x, y1 = pos.y, label = text))
                pendingTextPos = null
            },
            onDismiss = { pendingTextPos = null }
        )
    }
    if (showRoomPlan) {
        RoomPlanDialog(
            onConfirmRectangle = { l, w, t -> addRoomPlan(l, w, t); showRoomPlan = false },
            onConfirmContinuous = { cmd, t ->
                val err = addContinuousPlan(cmd, t)
                if (err == null) showRoomPlan = false
                err
            },
            onDismiss = { showRoomPlan = false }
        )
    }
    if (pendingLengthIndex in shapes.indices) {
        val drawn = shapes[pendingLengthIndex]
        val asDrawnMm = hypotF(drawn.x2 - drawn.x1, drawn.y2 - drawn.y1) / currentPxPerMm()
        OptionalLineLengthDialog(
            asDrawnMm = asDrawnMm,
            onSetExact = { mm ->
                val cur = shapes[pendingLengthIndex]
                val curLenPx = hypotF(cur.x2 - cur.x1, cur.y2 - cur.y1)
                if (curLenPx > 1e-3f) {
                    val f = (mm.toFloat() * currentPxPerMm()) / curLenPx
                    shapes[pendingLengthIndex] = cur.copy(
                        x2 = cur.x1 + (cur.x2 - cur.x1) * f,
                        y2 = cur.y1 + (cur.y2 - cur.y1) * f,
                        realLength = mm, confirmed = true
                    )
                }
                pendingLengthIndex = -1
            },
            onUseAsDrawn = { pendingLengthIndex = -1 }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                    )
                },
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tool == Tool.SELECT, onClick = {
                    tool = Tool.SELECT; lineStartPoint = null
                }, label = { Icon(Icons.Filled.NearMe, "Select") })
                FilterChip(selected = tool == Tool.LINE, onClick = {
                    if (tool == Tool.LINE) lineStartPoint = null // tap again = cancel/reset
                    tool = Tool.LINE
                }, label = { Icon(Icons.Filled.ShowChart, "Line") })
                FilterChip(selected = tool == Tool.CIRCLE, onClick = {
                    tool = Tool.CIRCLE; lineStartPoint = null
                }, label = { Icon(Icons.Filled.Circle, "Circle") })
                FilterChip(selected = tool == Tool.TEXT, onClick = {
                    tool = Tool.TEXT; lineStartPoint = null
                }, label = { Icon(Icons.Filled.TextFields, "Text") })
                FilterChip(selected = false, onClick = { showRoomPlan = true },
                    label = { Icon(Icons.Filled.Straighten, "Room plan (type dimensions)") })
            }
            if (tool == Tool.LINE) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = orthoOn, onClick = { orthoOn = !orthoOn }, label = { Text("Ortho") })
                    FilterChip(selected = snapOn, onClick = { snapOn = !snapOn }, label = { Text("Snap") })
                    FilterChip(selected = chainOn, onClick = { chainOn = !chainOn }, label = { Text("Chain") })
                }
            }
            Text(
                when {
                    tool == Tool.SELECT -> "Tap a line/circle/text to edit its dimension or delete it"
                    tool == Tool.LINE && lineStartPoint == null -> "Tap the line's start point"
                    tool == Tool.LINE -> "Tap the end point — length is automatic, or type an exact one after"
                    tool == Tool.CIRCLE -> "Drag from the centre outward to draw a circle"
                    else -> "Tap where you want a text label"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(Color.White)
                    .onSizeChanged { canvasSize = it }
            ) {
                if (baseBitmap != null) {
                    Image(baseBitmap, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(tool, orthoOn, snapOn) {
                        when (tool) {
                            Tool.CIRCLE -> detectDragGestures(
                                onDragStart = { p -> dragStart = p; dragCurrent = p },
                                onDrag = { change, _ -> dragCurrent = change.position },
                                onDragEnd = {
                                    val s = dragStart; val c = dragCurrent
                                    if (s != null && c != null) {
                                        val len = hypotF(c.x - s.x, c.y - s.y)
                                        if (len > 12f) shapes.add(SketchShape(workId = 0, kind = ShapeKind.CIRCLE, cx = s.x, cy = s.y, r = len))
                                    }
                                    dragStart = null; dragCurrent = null
                                }
                            )
                            Tool.LINE -> detectTapGestures(onTap = { p ->
                                val start = lineStartPoint
                                if (start == null) {
                                    lineStartPoint = trySnapPoint(p)
                                } else {
                                    val snapped = trySnapPoint(p)
                                    val end = if (orthoOn) orthoProject(start, snapped) else snapped
                                    if (hypotF(end.x - start.x, end.y - start.y) > 4f) {
                                        shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = start.x, y1 = start.y, x2 = end.x, y2 = end.y, confirmed = false))
                                        pendingLengthIndex = shapes.lastIndex
                                        lineStartPoint = if (chainOn) end else null
                                    }
                                }
                            })
                            Tool.TEXT -> detectTapGestures(onTap = { p -> pendingTextPos = p })
                            Tool.SELECT -> detectTapGestures(onTap = { p ->
                                val idx = hitTest(p)
                                if (idx >= 0) editingIndex = idx
                            })
                        }
                    }
                ) {
                    val linePaint = Color(0xFF1565C0)
                    shapes.forEach { s ->
                        when (s.kind) {
                            ShapeKind.LINE -> {
                                val lineColor = if (s.confirmed) Color(0xFF2E7D32) else linePaint
                                drawLine(lineColor, Offset(s.x1, s.y1), Offset(s.x2, s.y2), strokeWidth = 5f)
                                if (s.confirmed && s.realLength > 0) {
                                    val mx = (s.x1 + s.x2) / 2f; val my = (s.y1 + s.y2) / 2f
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "${trimNum(s.realLength)}mm", mx + 4f, my - 4f,
                                        android.graphics.Paint().apply { color = 0xFF2E7D32.toInt(); textSize = 30f }
                                    )
                                }
                            }
                            ShapeKind.CIRCLE -> drawCircle(linePaint, radius = s.r, center = Offset(s.cx, s.cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
                            ShapeKind.TEXT -> drawContext.canvas.nativeCanvas.drawText(
                                s.label, s.x1, s.y1,
                                android.graphics.Paint().apply { color = 0xFF6A1B9A.toInt(); textSize = 34f }
                            )
                        }
                    }
                    val s = dragStart; val c = dragCurrent
                    if (s != null && c != null && tool == Tool.CIRCLE) {
                        drawCircle(Color.Gray, radius = hypotF(c.x - s.x, c.y - s.y), center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                    }
                    lineStartPoint?.let { p ->
                        drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { save() }, enabled = !busy && loaded, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Saving…" else "Save")
                }
            }
        }
    }
}

/**
 * The line is already drawn from tap to tap; this optionally overrides its length with an
 * exact typed value (same direction, endpoint adjusted). Skipping just keeps it as drawn.
 */
@Composable
private fun OptionalLineLengthDialog(asDrawnMm: Float, onSetExact: (mm: Double) -> Unit, onUseAsDrawn: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var showHandwrite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showHandwrite) {
        HandwriteInputDialog(onResult = { text = it.filter { c -> c.isDigit() || c == '.' }; showHandwrite = false }, onDismiss = { showHandwrite = false })
    }
    AlertDialog(
        onDismissRequest = onUseAsDrawn,
        title = { Text("Line length") },
        text = {
            Column {
                Text(
                    "As drawn: ~${trimNum(asDrawnMm.toDouble())}mm. Type an exact length to override it, or use it as drawn.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text("Exact length (mm) — optional") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull()
                if (v != null && v > 0) onSetExact(v) else error = "Enter a valid length"
            }) { Text("Set exact") }
        },
        dismissButton = { TextButton(onClick = onUseAsDrawn) { Text("Use as drawn") } }
    )
}

@Composable
private fun ShapeEditDialog(shape: SketchShape, onConfirm: (SketchShape) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    when (shape.kind) {
        ShapeKind.LINE -> {
            var text by remember { mutableStateOf(if (shape.realLength > 0) trimNum(shape.realLength) else "") }
            var showHandwrite by remember { mutableStateOf(false) }
            if (showHandwrite) {
                HandwriteInputDialog(onResult = { text = it.filter { c -> c.isDigit() || c == '.' }; showHandwrite = false }, onDismiss = { showHandwrite = false })
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Line dimension") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            label = { Text("Real length (mm)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = text.toDoubleOrNull()
                        if (v != null && v > 0) onConfirm(shape.copy(realLength = v, confirmed = true))
                        else onConfirm(shape.copy(confirmed = false))
                    }) { Text("Confirm") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
            )
        }
        else -> {
            LabelInputDialog(
                title = if (shape.kind == ShapeKind.CIRCLE) "Circle label (optional)" else "Text label",
                initial = shape.label,
                onConfirm = { onConfirm(shape.copy(label = it)) },
                onDelete = onDelete,
                onDismiss = onDismiss
            )
        }
    }
}

private enum class PlanMode { RECTANGLE, CONTINUOUS }

/**
 * Type dimensions and get a fully-dimensioned wall plan, instead of tracing it by hand.
 * Rectangle mode: length + width + wall thickness. Continuous mode: a walk like
 * "R500 B200 L300 T200" (R/B/L/T = right/bottom/left/top wall, number = its length in mm).
 */
@Composable
private fun RoomPlanDialog(
    onConfirmRectangle: (length: Double, width: Double, wall: Double) -> Unit,
    onConfirmContinuous: (command: String, wall: Double) -> String?,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(PlanMode.RECTANGLE) }
    var length by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var wall by remember { mutableStateOf("115") }
    var error by remember { mutableStateOf<String?>(null) }
    val decimalOpts = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room plan") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == PlanMode.RECTANGLE, onClick = { mode = PlanMode.RECTANGLE; error = null },
                        label = { Text("Rectangle") })
                    FilterChip(selected = mode == PlanMode.CONTINUOUS, onClick = { mode = PlanMode.CONTINUOUS; error = null },
                        label = { Text("Continuous (R/B/L/T)") })
                }
                if (mode == PlanMode.RECTANGLE) {
                    Text(
                        "Draws the outer + inner wall lines, already dimensioned.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(value = length, onValueChange = { length = it; error = null }, singleLine = true,
                        label = { Text("Length (mm)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    OutlinedTextField(value = width, onValueChange = { width = it; error = null }, singleLine = true,
                        label = { Text("Width (mm)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    Text(
                        "R = right wall, B = bottom, L = left, T = top. Number after each letter is that " +
                            "wall's length in mm. Example: R500 B200 L300 T200",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(value = command, onValueChange = { command = it; error = null }, singleLine = true,
                        label = { Text("Walk, e.g. R500 B200 L300 T200") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                OutlinedTextField(value = wall, onValueChange = { wall = it; error = null }, singleLine = true,
                    label = { Text("Wall thickness (mm)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = wall.toDoubleOrNull()
                if (t == null || t < 0) { error = "Enter a valid wall thickness"; return@TextButton }
                if (mode == PlanMode.RECTANGLE) {
                    val l = length.toDoubleOrNull(); val w = width.toDoubleOrNull()
                    when {
                        l == null || l <= 0 -> error = "Enter a valid length"
                        w == null || w <= 0 -> error = "Enter a valid width"
                        t * 2 >= minOf(l, w) -> error = "Wall thickness is too large for that size"
                        else -> onConfirmRectangle(l, w, t)
                    }
                } else {
                    val err = onConfirmContinuous(command, t)
                    if (err != null) error = err
                }
            }) { Text("Draw") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LabelInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var showHandwrite by remember { mutableStateOf(false) }
    if (showHandwrite) {
        HandwriteInputDialog(onResult = { text = it; showHandwrite = false }, onDismiss = { showHandwrite = false })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq < 1e-6f) return hypotF(p.x - a.x, p.y - a.y)
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
    t = t.coerceIn(0f, 1f)
    val projX = a.x + t * dx; val projY = a.y + t * dy
    return hypotF(p.x - projX, p.y - projY)
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
