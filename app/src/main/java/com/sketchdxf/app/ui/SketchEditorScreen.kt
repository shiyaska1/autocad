package com.sketchdxf.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchArc
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import com.sketchdxf.app.data.SketchWork
import com.sketchdxf.app.dxf.DxfWriter
import com.sketchdxf.app.dxf.PendingSketchEditor
import com.sketchdxf.app.dxf.PreviewRenderer
import com.sketchdxf.app.dxf.SketchAttachmentStore
import com.sketchdxf.app.ui.common.HandwriteInputDialog
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** kotlin.math.hypot only has a (Double, Double) overload — this fills the (Float, Float) gap. */
private fun hypotF(x: Float, y: Float): Float = hypot(x.toDouble(), y.toDouble()).toFloat()

/**
 * A trimmed-down [androidx.compose.foundation.gestures.detectTransformGestures] that, when
 * [requireTwoFingers] is true, only reacts to 2+ simultaneous pointers and otherwise leaves
 * single-finger events completely unconsumed — so pinch-zoom/two-finger-pan can sit "underneath"
 * every drawing tool (Line, Freehand, Box select, …) the same way Ortho/Snap do, instead of being
 * its own exclusive tool. With [requireTwoFingers] false (the dedicated Pan/Zoom tool), a single
 * finger drags the view too, matching the old behaviour there.
 */
private suspend fun PointerInputScope.detectPanOrZoom(requireTwoFingers: Boolean, onGesture: (pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled && (!requireTwoFingers || event.changes.size >= 2)) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) pastTouchSlop = true
                }
                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != Offset.Zero) onGesture(panChange, zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

private enum class Tool { SELECT, LINE, RECTANGLE, CIRCLE, TEXT, DIMENSION, OFFSET, TRIM, PAN, FREEHAND, BOX_SELECT, BREAK, FILLET, STRETCH }

/** One endpoint captured by a Stretch crossing-selection: [part] 0 = a shape's primary point
 *  (x1,y1 for LINE/DIMENSION/TEXT, cx,cy for CIRCLE), 1 = a LINE/DIMENSION's other end (x2,y2). */
private data class StretchPoint(val shapeIndex: Int, val part: Int)
private enum class DimMode { ALIGNED, LINEAR_H, LINEAR_V }

/**
 * Shared editor for both flows: tracing lines by hand over a background photo, and drawing a
 * sketch from scratch (baseImagePath == null) — nothing is auto-detected. Coordinates are this
 * composable's own canvas-pixel space — consistent within one work on one device, which is all
 * that's needed. The view can be pinch-zoomed/panned (Pan tool) without affecting that space:
 * zoom/pan is a pure display transform (graphicsLayer), shape coordinates never change with it.
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
    var unit by remember { mutableStateOf("cm") }
    val sourcesRef = remember { mutableStateListOf<com.sketchdxf.app.data.SketchSource>() }
    val shapes = remember { mutableStateListOf<SketchShape>() }

    // Box select: drag a rectangle to select every shape whose bounds intersect it; a drag too
    // short to count as a rectangle toggles just the shape under it. Selected shapes can then be
    // moved, copied, or deleted as a group via the action row that appears below the canvas.
    val selectedIndices = remember { mutableStateListOf<Int>() }

    // Undo/redo: whole-list snapshots, pushed right before each mutation. Simple and reliable —
    // every tool (line, circle, text, room plan, edit, delete, offset, trim) shares one history.
    val undoStack = remember { mutableStateListOf<List<SketchShape>>() }
    val redoStack = remember { mutableStateListOf<List<SketchShape>>() }
    fun pushUndo() {
        undoStack.add(shapes.toList())
        redoStack.clear()
        if (undoStack.size > 50) undoStack.removeAt(0)
    }
    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(shapes.toList())
        val prev = undoStack.removeAt(undoStack.lastIndex)
        shapes.clear(); shapes.addAll(prev)
        selectedIndices.clear() // indices from before the swap no longer point at the same shapes
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(shapes.toList())
        val next = redoStack.removeAt(redoStack.lastIndex)
        shapes.clear(); shapes.addAll(next)
        selectedIndices.clear()
    }

    LaunchedEffect(Unit) {
        workId = PendingSketchEditor.workId
        createdAt = PendingSketchEditor.createdAt
        name = PendingSketchEditor.name
        baseImagePath = PendingSketchEditor.baseImagePath
        oldDxfPath = PendingSketchEditor.oldDxfPath
        oldPreviewPath = PendingSketchEditor.oldPreviewPath
        unit = PendingSketchEditor.unit
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

    // Pinch-zoom/pan — a pure view transform; shape coordinates are never affected by it.
    var viewScale by remember { mutableStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }

    /** Keeps the drawing area always at least partly on screen — panning/zooming can't drag the
     *  whole thing out of view (when zoomed in, the viewport can't go past the content's edges;
     *  when zoomed out, the smaller content can't be pushed fully off the visible area). */
    fun clampViewOffset(offset: Offset, scale: Float): Offset {
        val cw = canvasSize.width.toFloat(); val ch = canvasSize.height.toFloat()
        if (cw <= 0f || ch <= 0f) return offset
        val slackX = cw - cw * scale; val slackY = ch - ch * scale
        return Offset(
            offset.x.coerceIn(minOf(0f, slackX), maxOf(0f, slackX)),
            offset.y.coerceIn(minOf(0f, slackY), maxOf(0f, slackY))
        )
    }

    // CAD-style line input: tap a start point, tap an end point — the line is drawn between them
    // immediately (Ortho locks the end point to horizontal/vertical from the start, like AutoCAD).
    // Typing an exact length afterward is optional; skipping it just keeps the line as tapped.
    var orthoOn by remember { mutableStateOf(true) }
    var snapOn by remember { mutableStateOf(true) }
    var chainOn by remember { mutableStateOf(true) }
    var lineStartPoint by remember { mutableStateOf<Offset?>(null) }
    var pendingLengthIndex by remember { mutableStateOf(-1) }

    // Offset: tap a line, tap the side to copy it toward. The copy is created immediately using
    // the tapped distance; an optional popup can then override it with an exact typed distance.
    var offsetLineIndex by remember { mutableStateOf(-1) }
    var offsetNewIndex by remember { mutableStateOf(-1) }
    var offsetOriginal by remember { mutableStateOf<SketchShape?>(null) }
    var offsetNormal by remember { mutableStateOf(Offset.Zero) }
    var offsetSign by remember { mutableStateOf(1f) }

    // Trim: tap the cutting line, tap the line to trim, then tap the side of it to remove.
    var trimBoundaryIndex by remember { mutableStateOf(-1) }
    var trimTargetIndex by remember { mutableStateOf(-1) }

    // Dimension: tap point 1, tap point 2 (both snap-aware), then type the dimension text —
    // pre-filled with the measured value but freely editable, e.g. to write "3000 CRS" instead.
    // Aligned measures the true distance between the points; Linear measures only the
    // horizontal or vertical span.
    var dimMode by remember { mutableStateOf(DimMode.ALIGNED) }
    var dimStartPoint by remember { mutableStateOf<Offset?>(null) }
    var pendingDimension by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    // Freehand (pencil): a continuous drag records points, released as one FREEHAND shape.
    val freehandPoints = remember { mutableStateListOf<Offset>() }

    // Box select drag state, plus "move mode" — armed by the Move action, the next drag on the
    // canvas translates every selected shape by the drag delta instead of drawing a new box.
    var selectDragStart by remember { mutableStateOf<Offset?>(null) }
    var selectDragCurrent by remember { mutableStateOf<Offset?>(null) }
    var moveModeActive by remember { mutableStateOf(false) }
    var moveDragStart by remember { mutableStateOf<Offset?>(null) }
    var moveDragCurrent by remember { mutableStateOf<Offset?>(null) }

    // Break: tap the line, tap the first break point, tap the second — the segment between the
    // two (projected onto the line) is removed. Tapping both points at nearly the same spot
    // leaves no visible gap, matching AutoCAD's "break at point".
    var breakLineIndex by remember { mutableStateOf(-1) }
    var breakPoint1 by remember { mutableStateOf<Offset?>(null) }

    // Fillet: tap the first line, tap the second, then type a radius — 0 makes them meet at a
    // sharp corner; a positive radius rounds the corner with a tangent arc of that radius.
    var filletIndex1 by remember { mutableStateOf(-1) }
    var filletIndex2 by remember { mutableStateOf(-1) }
    var filletTap1 by remember { mutableStateOf<Offset?>(null) }
    var filletTap2 by remember { mutableStateOf<Offset?>(null) }
    var filletError by remember { mutableStateOf<String?>(null) }

    // Stretch: drag a crossing box — only the endpoints inside it are captured (a fully-enclosed
    // shape moves as a whole; a shape with just one end inside gets genuinely stretched). Then tap
    // a base point and a second point for the direction; an exact distance can override afterward.
    val stretchPoints = remember { mutableStateListOf<StretchPoint>() }
    var stretchDragStart by remember { mutableStateOf<Offset?>(null) }
    var stretchDragCurrent by remember { mutableStateOf<Offset?>(null) }
    var stretchBasePoint by remember { mutableStateOf<Offset?>(null) }
    var stretchDirection by remember { mutableStateOf(Offset.Zero) }
    var stretchAppliedPx by remember { mutableStateOf(0f) }
    var showStretchExactDialog by remember { mutableStateOf(false) }

    fun resetToolState() {
        lineStartPoint = null
        offsetLineIndex = -1
        trimBoundaryIndex = -1; trimTargetIndex = -1
        dimStartPoint = null; pendingDimension = null
        freehandPoints.clear()
        selectedIndices.clear()
        selectDragStart = null; selectDragCurrent = null
        moveModeActive = false; moveDragStart = null; moveDragCurrent = null
        breakLineIndex = -1; breakPoint1 = null
        filletIndex1 = -1; filletIndex2 = -1; filletTap1 = null; filletTap2 = null; filletError = null
        stretchPoints.clear()
        stretchDragStart = null; stretchDragCurrent = null; stretchBasePoint = null
        stretchDirection = Offset.Zero; stretchAppliedPx = 0f; showStretchExactDialog = false
    }

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

    fun hitTestLine(p: Offset): Int {
        var best = -1; var bestDist = 26f
        shapes.forEachIndexed { i, s ->
            if (s.kind == ShapeKind.LINE) {
                val d = distToSegment(p, Offset(s.x1, s.y1), Offset(s.x2, s.y2))
                if (d < bestDist) { bestDist = d; best = i }
            }
        }
        return best
    }

    /** Points sampled along an ARC shape's actual (minor) sweep — used for hit-testing, bounds,
     *  and the box-select move-preview ghost, the same way FREEHAND uses its stored path. */
    fun arcPoints(s: SketchShape, steps: Int = 16): List<Offset> {
        val (startDeg, sweepDeg) = SketchArc.minorSweep(s.cx, s.cy, s.x1, s.y1, s.x2, s.y2)
        return (0..steps).map { i ->
            val ang = Math.toRadians((startDeg + sweepDeg * i / steps).toDouble())
            Offset(s.cx + s.r * cos(ang).toFloat(), s.cy + s.r * sin(ang).toFloat())
        }
    }

    fun hitTest(p: Offset): Int {
        var best = -1; var bestDist = 26f
        shapes.forEachIndexed { i, s ->
            val d = when (s.kind) {
                ShapeKind.LINE -> distToSegment(p, Offset(s.x1, s.y1), Offset(s.x2, s.y2))
                ShapeKind.CIRCLE -> abs(hypotF(p.x - s.cx, p.y - s.cy) - s.r)
                ShapeKind.TEXT -> hypotF(p.x - s.x1, p.y - s.y1)
                ShapeKind.DIMENSION -> distToSegment(p, Offset(s.x1, s.y1), Offset(s.x2, s.y2))
                ShapeKind.FREEHAND -> {
                    val pts = SketchPath.parse(s.path)
                    if (pts.size < 2) Float.MAX_VALUE
                    else pts.zipWithNext { a, b -> distToSegment(p, Offset(a.first, a.second), Offset(b.first, b.second)) }
                        .minOrNull() ?: Float.MAX_VALUE
                }
                ShapeKind.ARC -> arcPoints(s).zipWithNext { a, b -> distToSegment(p, a, b) }.minOrNull() ?: Float.MAX_VALUE
                else -> Float.MAX_VALUE
            }
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** Bounding box used by box-select to decide whether a shape falls inside the drag rect. */
    fun shapeBounds(s: SketchShape): androidx.compose.ui.geometry.Rect = when (s.kind) {
        ShapeKind.CIRCLE -> androidx.compose.ui.geometry.Rect(s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r)
        ShapeKind.TEXT -> androidx.compose.ui.geometry.Rect(s.x1 - 12f, s.y1 - 12f, s.x1 + 12f, s.y1 + 12f)
        ShapeKind.FREEHAND -> {
            val pts = SketchPath.parse(s.path)
            if (pts.isEmpty()) androidx.compose.ui.geometry.Rect(s.x1, s.y1, s.x1, s.y1)
            else androidx.compose.ui.geometry.Rect(
                pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second }
            )
        }
        ShapeKind.ARC -> {
            val pts = arcPoints(s)
            androidx.compose.ui.geometry.Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
        else -> androidx.compose.ui.geometry.Rect(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
    }

    /** Shifts a shape by (dx, dy) in canvas-pixel space — used by group Move and by Copy's paste offset. */
    fun translateShape(s: SketchShape, dx: Float, dy: Float): SketchShape = when (s.kind) {
        ShapeKind.CIRCLE -> s.copy(cx = s.cx + dx, cy = s.cy + dy)
        ShapeKind.TEXT -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy)
        ShapeKind.FREEHAND -> s.copy(path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> (x + dx) to (y + dy) }))
        ShapeKind.ARC -> s.copy(cx = s.cx + dx, cy = s.cy + dy, x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
        else -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
    }

    /** Removes every selected shape, highest index first so earlier removals don't shift the rest. */
    fun deleteSelection() {
        if (selectedIndices.isEmpty()) return
        pushUndo()
        selectedIndices.sortedDescending().forEach { shapes.removeAt(it) }
        selectedIndices.clear()
    }

    /** Duplicates every selected shape offset by a fixed paste distance, then selects the copies
     *  so Move can immediately drag them into their real position. */
    fun copySelection() {
        if (selectedIndices.isEmpty()) return
        pushUndo()
        val pasteOffsetPx = 40f
        val copies = selectedIndices.sorted().map { translateShape(shapes[it], pasteOffsetPx, pasteOffsetPx) }
        selectedIndices.clear()
        copies.forEach { shapes.add(it); selectedIndices.add(shapes.lastIndex) }
    }

    /** Creates the offset copy immediately, using the perpendicular distance from [sidePoint]
     *  to the (infinite) line through [lineIdx] — the side tapped decides the direction. */
    fun beginOffset(lineIdx: Int, sidePoint: Offset) {
        val s = shapes.getOrNull(lineIdx) ?: return
        val a = Offset(s.x1, s.y1); val b = Offset(s.x2, s.y2)
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypotF(dx, dy)
        if (len < 1e-3f) { offsetLineIndex = -1; return }
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        val dist = (sidePoint.x - a.x) * nx + (sidePoint.y - a.y) * ny
        offsetNormal = Offset(nx, ny)
        offsetSign = if (dist >= 0f) 1f else -1f
        offsetOriginal = s
        pushUndo()
        shapes.add(s.copy(x1 = a.x + nx * dist, y1 = a.y + ny * dist, x2 = b.x + nx * dist, y2 = b.y + ny * dist))
        offsetNewIndex = shapes.lastIndex
        offsetLineIndex = -1
    }

    /** Intersection of the two (infinite) lines through [l1] and [l2], or null if parallel. */
    fun lineIntersection(l1: SketchShape, l2: SketchShape): Offset? {
        val denom = (l1.x1 - l1.x2) * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * (l2.x1 - l2.x2)
        if (abs(denom) < 1e-6f) return null
        val t = ((l1.x1 - l2.x1) * (l2.y1 - l2.y2) - (l1.y1 - l2.y1) * (l2.x1 - l2.x2)) / denom
        return Offset(l1.x1 + t * (l1.x2 - l1.x1), l1.y1 + t * (l1.y2 - l1.y1))
    }

    /** Where [p] falls along the line a→b, as a fraction (0 = a, 1 = b; not clamped). */
    fun projectParam(a: Offset, b: Offset, p: Offset): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return 0f
        return ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
    }

    /** Trims the selected target line at its intersection with the boundary, removing whichever
     *  side [p] was tapped on. */
    fun trimAt(p: Offset) {
        val boundary = shapes.getOrNull(trimBoundaryIndex)
        val target = shapes.getOrNull(trimTargetIndex)
        if (boundary != null && target != null) {
            val inter = lineIntersection(boundary, target)
            if (inter != null) {
                val a = Offset(target.x1, target.y1); val b = Offset(target.x2, target.y2)
                val tTap = projectParam(a, b, p)
                val tInt = projectParam(a, b, inter)
                pushUndo()
                shapes[trimTargetIndex] = if (tTap > tInt) target.copy(x2 = inter.x, y2 = inter.y)
                else target.copy(x1 = inter.x, y1 = inter.y)
            }
        }
        trimBoundaryIndex = -1; trimTargetIndex = -1
    }

    /** Splits the line at the two (projected, clamped-to-segment) break points, removing the
     *  segment between them. Tapping both points at nearly the same spot leaves no visible gap. */
    fun performBreak(lineIdx: Int, p1: Offset, p2: Offset) {
        val line = shapes.getOrNull(lineIdx) ?: return
        val a = Offset(line.x1, line.y1); val b = Offset(line.x2, line.y2)
        var t1 = projectParam(a, b, p1).coerceIn(0f, 1f)
        var t2 = projectParam(a, b, p2).coerceIn(0f, 1f)
        if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
        val pLo = Offset(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1)
        val pHi = Offset(a.x + (b.x - a.x) * t2, a.y + (b.y - a.y) * t2)
        val keepFirst = t1 > 0.02f
        val keepSecond = t2 < 0.98f
        pushUndo()
        val pieces = mutableListOf<SketchShape>()
        // A break shortens the line, so any prior confirmed length no longer applies — the
        // pieces start unconfirmed until re-dimensioned, same as a freshly drawn line.
        if (keepFirst) pieces.add(line.copy(x2 = pLo.x, y2 = pLo.y, confirmed = false, realLength = 0.0))
        if (keepSecond) pieces.add(line.copy(x1 = pHi.x, y1 = pHi.y, confirmed = false, realLength = 0.0))
        shapes.removeAt(lineIdx)
        shapes.addAll(pieces)
    }

    /** Joins two lines at their intersection: radius 0 trims/extends both to meet at a sharp
     *  corner; a positive radius trims each back to its tangent point and adds a connecting ARC.
     *  [near1]/[near2] (the tap points that picked each line) identify which end of each line is
     *  at this corner — the other end is left untouched. Returns an error message, or null on success. */
    fun performFillet(idx1: Int, idx2: Int, radiusPx: Float, near1: Offset, near2: Offset): String? {
        val l1 = shapes.getOrNull(idx1) ?: return "That line no longer exists"
        val l2 = shapes.getOrNull(idx2) ?: return "That line no longer exists"
        val inter = lineIntersection(l1, l2) ?: return "Those lines are parallel — no corner to fillet"

        fun nearEndIsFirst(s: SketchShape, tap: Offset) =
            hypotF(tap.x - s.x1, tap.y - s.y1) <= hypotF(tap.x - s.x2, tap.y - s.y2)
        val l1NearFirst = nearEndIsFirst(l1, near1)
        val l2NearFirst = nearEndIsFirst(l2, near2)
        val l1Far = if (l1NearFirst) Offset(l1.x2, l1.y2) else Offset(l1.x1, l1.y1)
        val l2Far = if (l2NearFirst) Offset(l2.x2, l2.y2) else Offset(l2.x1, l2.y1)
        val d1 = Offset(l1Far.x - inter.x, l1Far.y - inter.y); val len1 = hypotF(d1.x, d1.y)
        val d2 = Offset(l2Far.x - inter.x, l2Far.y - inter.y); val len2 = hypotF(d2.x, d2.y)
        if (len1 < 1e-3f || len2 < 1e-3f) return "That corner is right at a line's other endpoint"
        val u1 = Offset(d1.x / len1, d1.y / len1)
        val u2 = Offset(d2.x / len2, d2.y / len2)

        if (radiusPx <= 0.5f) {
            pushUndo()
            shapes[idx1] = if (l1NearFirst) l1.copy(x1 = inter.x, y1 = inter.y) else l1.copy(x2 = inter.x, y2 = inter.y)
            shapes[idx2] = if (l2NearFirst) l2.copy(x1 = inter.x, y1 = inter.y) else l2.copy(x2 = inter.x, y2 = inter.y)
            return null
        }

        val cosTheta = (u1.x * u2.x + u1.y * u2.y).coerceIn(-1f, 1f)
        val theta = acos(cosTheta.toDouble())
        if (theta < 0.02 || theta > Math.PI - 0.02) return "Lines are almost parallel here — try radius 0 for a sharp corner"
        val tanLen = (radiusPx / tan(theta / 2.0)).toFloat()
        val clampedTanLen = tanLen.coerceAtMost(minOf(len1, len2) * 0.95f)
        if (clampedTanLen < 1f) return "Radius is too large for these lines"
        val effectiveRadius = clampedTanLen * tan(theta / 2.0).toFloat()
        val t1 = Offset(inter.x + u1.x * clampedTanLen, inter.y + u1.y * clampedTanLen)
        val t2 = Offset(inter.x + u2.x * clampedTanLen, inter.y + u2.y * clampedTanLen)
        val bisector = Offset(u1.x + u2.x, u1.y + u2.y)
        val bisLen = hypotF(bisector.x, bisector.y)
        if (bisLen < 1e-3f) return "Lines point almost opposite ways here — try radius 0 for a sharp corner"
        val bisUnit = Offset(bisector.x / bisLen, bisector.y / bisLen)
        val centerDist = (effectiveRadius / sin(theta / 2.0)).toFloat()
        val arcCenter = Offset(inter.x + bisUnit.x * centerDist, inter.y + bisUnit.y * centerDist)

        pushUndo()
        shapes[idx1] = if (l1NearFirst) l1.copy(x1 = t1.x, y1 = t1.y) else l1.copy(x2 = t1.x, y2 = t1.y)
        shapes[idx2] = if (l2NearFirst) l2.copy(x1 = t2.x, y1 = t2.y) else l2.copy(x2 = t2.x, y2 = t2.y)
        shapes.add(
            SketchShape(
                workId = 0, kind = ShapeKind.ARC, cx = arcCenter.x, cy = arcCenter.y, r = effectiveRadius,
                x1 = t1.x, y1 = t1.y, x2 = t2.x, y2 = t2.y
            )
        )
        return null
    }

    /** Crossing-selects individual endpoints (not whole shapes) inside [rect] — the basis of
     *  Stretch. FREEHAND/ARC aren't captured; they always stay put. */
    fun computeStretchSelection(rect: androidx.compose.ui.geometry.Rect) {
        stretchPoints.clear()
        shapes.forEachIndexed { i, s ->
            when (s.kind) {
                ShapeKind.LINE, ShapeKind.DIMENSION -> {
                    if (rect.contains(Offset(s.x1, s.y1))) stretchPoints.add(StretchPoint(i, 0))
                    if (rect.contains(Offset(s.x2, s.y2))) stretchPoints.add(StretchPoint(i, 1))
                }
                ShapeKind.CIRCLE -> if (rect.contains(Offset(s.cx, s.cy))) stretchPoints.add(StretchPoint(i, 0))
                ShapeKind.TEXT -> if (rect.contains(Offset(s.x1, s.y1))) stretchPoints.add(StretchPoint(i, 0))
            }
        }
    }

    /** Moves every captured endpoint by (dx, dy). A shape captured at only one end is genuinely
     *  stretched (its length changes, so any confirmed real length no longer applies); a shape
     *  captured at both ends just translates, keeping its confirmed length as-is. */
    fun applyStretchDelta(dx: Float, dy: Float) {
        stretchPoints.groupBy { it.shapeIndex }.forEach { (idx, pts) ->
            val s = shapes.getOrNull(idx) ?: return@forEach
            shapes[idx] = when (s.kind) {
                ShapeKind.CIRCLE -> s.copy(cx = s.cx + dx, cy = s.cy + dy)
                ShapeKind.TEXT -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy)
                else -> {
                    val movesFirst = pts.any { it.part == 0 }
                    val movesSecond = pts.any { it.part == 1 }
                    var updated = s
                    if (movesFirst) updated = updated.copy(x1 = updated.x1 + dx, y1 = updated.y1 + dy)
                    if (movesSecond) updated = updated.copy(x2 = updated.x2 + dx, y2 = updated.y2 + dy)
                    if (movesFirst != movesSecond) updated = updated.copy(confirmed = false, realLength = 0.0)
                    updated
                }
            }
        }
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
        pushUndo()
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
            Seg(dir, displayToMm(value, unit))
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
        pushUndo()
        shapes.addAll(newShapes)
        return null
    }

    fun save() {
        busy = true
        scope.launch {
            val dao = AppDatabase.get(context).sketchDao()
            val now = System.currentTimeMillis()
            val finalName = name.trim().ifBlank { "Untitled" }
            val id = if (workId > 0) workId else dao.insertWork(SketchWork(name = finalName, createdAt = now, updatedAt = now, unit = unit))

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
                    status = "FINALIZED", unit = unit
                )
            )
            busy = false
            onSaved(id)
        }
    }

    if (editingIndex >= 0 && editingIndex < shapes.size) {
        ShapeEditDialog(
            shape = shapes[editingIndex],
            unitLabel = unit,
            onConfirm = { updated -> pushUndo(); shapes[editingIndex] = updated; editingIndex = -1 },
            onDelete = { pushUndo(); shapes.removeAt(editingIndex); editingIndex = -1 },
            onDismiss = { editingIndex = -1 }
        )
    }
    pendingTextPos?.let { pos ->
        LabelInputDialog(
            title = "Text label",
            initial = "",
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    pushUndo()
                    shapes.add(SketchShape(workId = 0, kind = ShapeKind.TEXT, x1 = pos.x, y1 = pos.y, label = text))
                }
                pendingTextPos = null
            },
            onDismiss = { pendingTextPos = null }
        )
    }
    if (showRoomPlan) {
        RoomPlanDialog(
            unitLabel = unit,
            onConfirmRectangle = { l, w, t ->
                addRoomPlan(displayToMm(l, unit), displayToMm(w, unit), displayToMm(t, unit))
                showRoomPlan = false
            },
            onConfirmContinuous = { cmd, t ->
                val err = addContinuousPlan(cmd, displayToMm(t, unit))
                if (err == null) showRoomPlan = false
                err
            },
            onDismiss = { showRoomPlan = false }
        )
    }
    if (pendingLengthIndex in shapes.indices) {
        val drawn = shapes[pendingLengthIndex]
        val asDrawnMm = hypotF(drawn.x2 - drawn.x1, drawn.y2 - drawn.y1) / currentPxPerMm()
        val asDrawnAngleDeg = normalizeDeg(Math.toDegrees(atan2((drawn.y2 - drawn.y1).toDouble(), (drawn.x2 - drawn.x1).toDouble())).toFloat())
        // Ortho already guarantees a clean 0/90/180/270° line, so only ask for an exact angle
        // when Ortho is off and the tapped angle isn't one of those already.
        val showAngleField = !orthoOn && !isOrthoAngle(asDrawnAngleDeg)
        LineFinishDialog(
            asIsDisplay = mmToDisplay(asDrawnMm.toDouble(), unit).toFloat(),
            unitLabel = unit,
            asIsAngleDeg = asDrawnAngleDeg,
            showAngleField = showAngleField,
            onApply = { value, angleDeg ->
                val mm = value?.let { displayToMm(it, unit) }
                val cur = shapes[pendingLengthIndex]
                val start = Offset(cur.x1, cur.y1)
                val lenPx = if (mm != null) mm.toFloat() * currentPxPerMm() else hypotF(cur.x2 - cur.x1, cur.y2 - cur.y1)
                val angRad = Math.toRadians((angleDeg ?: asDrawnAngleDeg).toDouble())
                val newEnd = Offset(start.x + (lenPx * cos(angRad)).toFloat(), start.y + (lenPx * sin(angRad)).toFloat())
                shapes[pendingLengthIndex] = if (mm != null) {
                    cur.copy(x2 = newEnd.x, y2 = newEnd.y, realLength = mm, confirmed = true)
                } else {
                    cur.copy(x2 = newEnd.x, y2 = newEnd.y)
                }
                pendingLengthIndex = -1
            },
            onUseAsIs = { pendingLengthIndex = -1 },
            onCancel = {
                if (pendingLengthIndex in shapes.indices) shapes.removeAt(pendingLengthIndex)
                if (undoStack.isNotEmpty()) undoStack.removeAt(undoStack.lastIndex)
                pendingLengthIndex = -1
            }
        )
    }
    if (offsetNewIndex in shapes.indices && offsetOriginal != null) {
        val copy = shapes[offsetNewIndex]
        val orig = offsetOriginal!!
        val asIsMm = hypotF(copy.x1 - orig.x1, copy.y1 - orig.y1) / currentPxPerMm()
        OptionalDistanceDialog(
            title = "Offset distance",
            asIsDisplay = mmToDisplay(asIsMm.toDouble(), unit).toFloat(),
            unitLabel = unit,
            fieldLabel = "Exact offset ($unit) — optional",
            onSetExact = { value ->
                val mm = displayToMm(value, unit)
                val distPx = mm.toFloat() * currentPxPerMm() * offsetSign
                val nx = offsetNormal.x; val ny = offsetNormal.y
                shapes[offsetNewIndex] = orig.copy(
                    x1 = orig.x1 + nx * distPx, y1 = orig.y1 + ny * distPx,
                    x2 = orig.x2 + nx * distPx, y2 = orig.y2 + ny * distPx
                )
                offsetNewIndex = -1; offsetOriginal = null
            },
            onUseAsIs = { offsetNewIndex = -1; offsetOriginal = null },
            onCancel = {
                if (offsetNewIndex in shapes.indices) shapes.removeAt(offsetNewIndex)
                if (undoStack.isNotEmpty()) undoStack.removeAt(undoStack.lastIndex)
                offsetNewIndex = -1; offsetOriginal = null
            }
        )
    }
    pendingDimension?.let { (p1, p2) ->
        val measuredMm = hypotF(p2.x - p1.x, p2.y - p1.y) / currentPxPerMm()
        DimensionTextDialog(
            initialText = "${trimNum(mmToDisplay(measuredMm.toDouble(), unit))}$unit",
            onConfirm = { text ->
                pushUndo()
                shapes.add(SketchShape(workId = 0, kind = ShapeKind.DIMENSION, x1 = p1.x, y1 = p1.y, x2 = p2.x, y2 = p2.y, label = text))
                pendingDimension = null
            },
            onCancel = { pendingDimension = null }
        )
    }
    if (filletIndex1 >= 0 && filletIndex2 >= 0) {
        FilletRadiusDialog(
            error = filletError,
            unitLabel = unit,
            onConfirm = { radius ->
                val p1 = filletTap1; val p2 = filletTap2
                if (p1 != null && p2 != null) {
                    val radiusMm = displayToMm(radius, unit)
                    val err = performFillet(filletIndex1, filletIndex2, (radiusMm * currentPxPerMm()).toFloat(), p1, p2)
                    if (err == null) resetToolState() else filletError = err
                }
            },
            onDismiss = { resetToolState() }
        )
    }
    if (showStretchExactDialog) {
        OptionalDistanceDialog(
            title = "Stretch distance",
            asIsDisplay = mmToDisplay((stretchAppliedPx / currentPxPerMm()).toDouble(), unit).toFloat(),
            unitLabel = unit,
            fieldLabel = "Exact distance ($unit) — optional",
            onSetExact = { value ->
                val mm = displayToMm(value, unit)
                val exactPx = mm.toFloat() * currentPxPerMm()
                val correctionPx = exactPx - stretchAppliedPx
                applyStretchDelta(stretchDirection.x * correctionPx, stretchDirection.y * correctionPx)
                resetToolState()
            },
            onUseAsIs = { resetToolState() },
            onCancel = { undo(); resetToolState() }
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
                actions = {
                    IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Icon(Icons.Filled.Undo, "Undo") }
                    IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Icon(Icons.Filled.Redo, "Redo") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = tool == Tool.SELECT, onClick = {
                    tool = Tool.SELECT; resetToolState()
                }, label = { Icon(Icons.Filled.NearMe, "Select") })
                FilterChip(selected = tool == Tool.LINE, onClick = {
                    if (tool == Tool.LINE) resetToolState() // tap again = cancel/reset
                    tool = Tool.LINE
                }, label = { Icon(Icons.Filled.ShowChart, "Line") })
                FilterChip(selected = tool == Tool.RECTANGLE, onClick = {
                    tool = Tool.RECTANGLE; resetToolState()
                }, label = { Text("Rect") })
                FilterChip(selected = tool == Tool.CIRCLE, onClick = {
                    tool = Tool.CIRCLE; resetToolState()
                }, label = { Icon(Icons.Filled.Circle, "Circle") })
                FilterChip(selected = tool == Tool.TEXT, onClick = {
                    tool = Tool.TEXT; resetToolState()
                }, label = { Icon(Icons.Filled.TextFields, "Text") })
                FilterChip(selected = tool == Tool.DIMENSION, onClick = {
                    if (tool == Tool.DIMENSION) resetToolState()
                    tool = Tool.DIMENSION
                }, label = { Text("Dim") })
                FilterChip(selected = tool == Tool.OFFSET, onClick = {
                    if (tool == Tool.OFFSET) resetToolState()
                    tool = Tool.OFFSET
                }, label = { Text("Offset") })
                FilterChip(selected = tool == Tool.TRIM, onClick = {
                    if (tool == Tool.TRIM) resetToolState()
                    tool = Tool.TRIM
                }, label = { Text("Trim") })
                FilterChip(selected = tool == Tool.PAN, onClick = {
                    tool = Tool.PAN; resetToolState()
                }, label = { Text("Pan/Zoom") })
                FilterChip(selected = tool == Tool.FREEHAND, onClick = {
                    tool = Tool.FREEHAND; resetToolState()
                }, label = { Text("Pencil") })
                FilterChip(selected = tool == Tool.BOX_SELECT, onClick = {
                    tool = Tool.BOX_SELECT; resetToolState()
                }, label = { Text("Box") })
                FilterChip(selected = tool == Tool.BREAK, onClick = {
                    tool = Tool.BREAK; resetToolState()
                }, label = { Text("Break") })
                FilterChip(selected = tool == Tool.FILLET, onClick = {
                    tool = Tool.FILLET; resetToolState()
                }, label = { Text("Fillet") })
                FilterChip(selected = tool == Tool.STRETCH, onClick = {
                    tool = Tool.STRETCH; resetToolState()
                }, label = { Text("Stretch") })
                FilterChip(selected = false, onClick = { showRoomPlan = true },
                    label = { Icon(Icons.Filled.Straighten, "Room plan (type dimensions)") })
            }
            if (tool == Tool.BOX_SELECT && selectedIndices.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedIndices.size} selected", style = MaterialTheme.typography.bodySmall)
                    FilterChip(selected = moveModeActive, onClick = { moveModeActive = !moveModeActive }, label = { Text("Move") })
                    FilterChip(selected = false, onClick = { copySelection() }, label = { Text("Copy") })
                    FilterChip(selected = false, onClick = { deleteSelection() }, label = { Text("Delete") })
                    FilterChip(selected = false, onClick = { selectedIndices.clear(); moveModeActive = false }, label = { Text("Clear") })
                }
            }
            if (tool == Tool.LINE || tool == Tool.RECTANGLE || tool == Tool.DIMENSION) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tool != Tool.DIMENSION) {
                        FilterChip(selected = orthoOn, onClick = { orthoOn = !orthoOn }, label = { Text("Ortho") })
                    }
                    FilterChip(selected = snapOn, onClick = { snapOn = !snapOn }, label = { Text("Snap") })
                    if (tool == Tool.LINE) FilterChip(selected = chainOn, onClick = { chainOn = !chainOn }, label = { Text("Chain") })
                    if (tool == Tool.DIMENSION) {
                        FilterChip(selected = dimMode == DimMode.ALIGNED, onClick = { dimMode = DimMode.ALIGNED }, label = { Text("Aligned") })
                        FilterChip(selected = dimMode == DimMode.LINEAR_H, onClick = { dimMode = DimMode.LINEAR_H }, label = { Text("Linear H") })
                        FilterChip(selected = dimMode == DimMode.LINEAR_V, onClick = { dimMode = DimMode.LINEAR_V }, label = { Text("Linear V") })
                    }
                }
            }
            Text(
                when {
                    tool == Tool.SELECT -> "Tap a line/circle/text/dimension to edit or delete it"
                    tool == Tool.LINE && lineStartPoint == null -> "Tap the line's start point"
                    tool == Tool.LINE -> "Tap the end point — length is automatic, or type an exact one after"
                    tool == Tool.RECTANGLE -> "Drag from one corner to the opposite corner"
                    tool == Tool.CIRCLE -> "Drag from the centre outward to draw a circle"
                    tool == Tool.TEXT -> "Tap where you want a text label"
                    tool == Tool.DIMENSION && dimStartPoint == null -> "Tap the first point (snaps to nearby geometry)"
                    tool == Tool.DIMENSION -> "Tap the second point, then type the dimension text"
                    tool == Tool.OFFSET && offsetLineIndex < 0 -> "Tap the line to offset"
                    tool == Tool.OFFSET -> "Tap the side to copy it toward"
                    tool == Tool.TRIM && trimBoundaryIndex < 0 -> "Tap the cutting line"
                    tool == Tool.TRIM && trimTargetIndex < 0 -> "Tap the line to trim"
                    tool == Tool.TRIM -> "Tap the side of the line to remove"
                    tool == Tool.FREEHAND -> "Drag to draw a freehand stroke"
                    tool == Tool.BOX_SELECT && moveModeActive -> "Drag anywhere to move the selection, then release"
                    tool == Tool.BOX_SELECT && selectedIndices.isEmpty() -> "Drag left→right to select only fully-enclosed shapes, right→left to select anything touched"
                    tool == Tool.BOX_SELECT -> "${selectedIndices.size} selected — Move, Copy or Delete below, or drag a new box"
                    tool == Tool.BREAK && breakLineIndex < 0 -> "Tap the line to break"
                    tool == Tool.BREAK && breakPoint1 == null -> "Tap the first break point"
                    tool == Tool.BREAK -> "Tap the second break point"
                    tool == Tool.FILLET && filletIndex1 < 0 -> "Tap the first line"
                    tool == Tool.FILLET && filletIndex2 < 0 -> "Tap the second line"
                    tool == Tool.FILLET -> "Enter the fillet radius"
                    tool == Tool.STRETCH && stretchPoints.isEmpty() -> "Drag a crossing box over just the part to stretch"
                    tool == Tool.STRETCH && stretchBasePoint == null -> "${stretchPoints.size} point(s) captured — tap a base point"
                    tool == Tool.STRETCH -> "Tap where it should end up, then set an exact distance or use it as tapped"
                    else -> "Pinch to zoom, drag to pan"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    // Zoomed-in content is scaled via graphicsLayer below, which doesn't clip by
                    // default — without this, zooming in pushes the (invisible) touch region of
                    // the canvas up over the toolbar, and its buttons stop receiving taps.
                    .clipToBounds()
                    .background(Color.White)
                    .border(1.dp, Color(0xFF9E9E9E))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(tool) {
                        // Two-finger pinch/pan works underneath every tool, like Ortho/Snap; the
                        // dedicated Pan/Zoom tool additionally allows a single finger to drag it.
                        detectPanOrZoom(requireTwoFingers = tool != Tool.PAN) { pan, zoom ->
                            viewScale = (viewScale * zoom).coerceIn(0.5f, 6f)
                            viewOffset = clampViewOffset(viewOffset + pan, viewScale)
                        }
                    }
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer(
                        scaleX = viewScale, scaleY = viewScale,
                        translationX = viewOffset.x, translationY = viewOffset.y,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
                ) {
                    if (baseBitmap != null) {
                        Image(baseBitmap, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    val stretchArmed = stretchPoints.isNotEmpty()
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(tool, orthoOn, snapOn, moveModeActive, stretchArmed) {
                            when (tool) {
                                Tool.CIRCLE -> detectDragGestures(
                                    onDragStart = { p -> dragStart = p; dragCurrent = p },
                                    onDrag = { change, _ -> dragCurrent = change.position },
                                    onDragEnd = {
                                        val s = dragStart; val c = dragCurrent
                                        if (s != null && c != null) {
                                            val len = hypotF(c.x - s.x, c.y - s.y)
                                            if (len > 12f) {
                                                pushUndo()
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.CIRCLE, cx = s.x, cy = s.y, r = len))
                                            }
                                        }
                                        dragStart = null; dragCurrent = null
                                    }
                                )
                                Tool.RECTANGLE -> detectDragGestures(
                                    onDragStart = { p -> dragStart = trySnapPoint(p); dragCurrent = dragStart },
                                    onDrag = { change, _ -> dragCurrent = change.position },
                                    onDragEnd = {
                                        val s = dragStart; val c0 = dragCurrent
                                        if (s != null && c0 != null) {
                                            val c = trySnapPoint(c0)
                                            if (hypotF(c.x - s.x, c.y - s.y) > 8f) {
                                                pushUndo()
                                                val p2 = Offset(c.x, s.y); val p4 = Offset(s.x, c.y)
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = s.x, y1 = s.y, x2 = p2.x, y2 = p2.y))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = p2.x, y1 = p2.y, x2 = c.x, y2 = c.y))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = c.x, y1 = c.y, x2 = p4.x, y2 = p4.y))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = p4.x, y1 = p4.y, x2 = s.x, y2 = s.y))
                                            }
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
                                            pushUndo()
                                            shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = start.x, y1 = start.y, x2 = end.x, y2 = end.y, confirmed = false))
                                            pendingLengthIndex = shapes.lastIndex
                                            lineStartPoint = if (chainOn) end else null
                                        }
                                    }
                                })
                                Tool.TEXT -> detectTapGestures(onTap = { p -> pendingTextPos = p })
                                Tool.DIMENSION -> detectTapGestures(onTap = { p ->
                                    val start = dimStartPoint
                                    if (start == null) {
                                        dimStartPoint = trySnapPoint(p)
                                    } else {
                                        val p2 = trySnapPoint(p)
                                        if (hypotF(p2.x - start.x, p2.y - start.y) > 4f) {
                                            val end = when (dimMode) {
                                                DimMode.ALIGNED -> p2
                                                DimMode.LINEAR_H -> Offset(p2.x, start.y)
                                                DimMode.LINEAR_V -> Offset(start.x, p2.y)
                                            }
                                            pendingDimension = start to end
                                        }
                                        dimStartPoint = null
                                    }
                                })
                                Tool.SELECT -> detectTapGestures(onTap = { p ->
                                    val idx = hitTest(p)
                                    if (idx >= 0) editingIndex = idx
                                })
                                Tool.OFFSET -> detectTapGestures(onTap = { p ->
                                    if (offsetLineIndex < 0) {
                                        val idx = hitTestLine(p)
                                        if (idx >= 0) offsetLineIndex = idx
                                    } else {
                                        beginOffset(offsetLineIndex, p)
                                    }
                                })
                                Tool.TRIM -> detectTapGestures(onTap = { p ->
                                    when {
                                        trimBoundaryIndex < 0 -> {
                                            val idx = hitTestLine(p)
                                            if (idx >= 0) trimBoundaryIndex = idx
                                        }
                                        trimTargetIndex < 0 -> {
                                            val idx = hitTestLine(p)
                                            if (idx >= 0 && idx != trimBoundaryIndex) trimTargetIndex = idx
                                        }
                                        else -> trimAt(p)
                                    }
                                })
                                Tool.PAN -> {}
                                Tool.FREEHAND -> detectDragGestures(
                                    onDragStart = { p -> freehandPoints.clear(); freehandPoints.add(p) },
                                    onDrag = { change, _ -> freehandPoints.add(change.position) },
                                    onDragEnd = {
                                        if (freehandPoints.size >= 2) {
                                            pushUndo()
                                            shapes.add(
                                                SketchShape(
                                                    workId = 0, kind = ShapeKind.FREEHAND,
                                                    path = SketchPath.serialize(freehandPoints.map { it.x to it.y })
                                                )
                                            )
                                        }
                                        freehandPoints.clear()
                                    }
                                )
                                Tool.BOX_SELECT -> if (moveModeActive) {
                                    // Armed by the Move action: this drag translates the whole selection.
                                    detectDragGestures(
                                        onDragStart = { p -> moveDragStart = p; moveDragCurrent = p },
                                        onDrag = { change, _ -> moveDragCurrent = change.position },
                                        onDragEnd = {
                                            val s = moveDragStart; val c = moveDragCurrent
                                            if (s != null && c != null) {
                                                val dx = c.x - s.x; val dy = c.y - s.y
                                                if (hypotF(dx, dy) > 2f) {
                                                    pushUndo()
                                                    selectedIndices.forEach { idx -> shapes[idx] = translateShape(shapes[idx], dx, dy) }
                                                }
                                            }
                                            moveDragStart = null; moveDragCurrent = null
                                            moveModeActive = false
                                        }
                                    )
                                } else {
                                    detectDragGestures(
                                        onDragStart = { p -> selectDragStart = p; selectDragCurrent = p },
                                        onDrag = { change, _ -> selectDragCurrent = change.position },
                                        onDragEnd = {
                                            val s = selectDragStart; val c = selectDragCurrent
                                            if (s != null && c != null) {
                                                if (hypotF(c.x - s.x, c.y - s.y) > 8f) {
                                                    val rect = androidx.compose.ui.geometry.Rect(
                                                        minOf(s.x, c.x), minOf(s.y, c.y), maxOf(s.x, c.x), maxOf(s.y, c.y)
                                                    )
                                                    // AutoCAD convention: left-to-right drag = Window (only shapes
                                                    // fully enclosed); right-to-left = Crossing (anything touched too).
                                                    val isWindow = c.x >= s.x
                                                    selectedIndices.clear()
                                                    shapes.forEachIndexed { i, sh ->
                                                        val b = shapeBounds(sh)
                                                        val hit = if (isWindow) {
                                                            rect.left <= b.left && rect.top <= b.top && rect.right >= b.right && rect.bottom >= b.bottom
                                                        } else {
                                                            rect.overlaps(b)
                                                        }
                                                        if (hit) selectedIndices.add(i)
                                                    }
                                                } else {
                                                    val idx = hitTest(s)
                                                    if (idx >= 0) {
                                                        if (selectedIndices.contains(idx)) selectedIndices.remove(idx) else selectedIndices.add(idx)
                                                    } else {
                                                        selectedIndices.clear()
                                                    }
                                                }
                                            }
                                            selectDragStart = null; selectDragCurrent = null
                                        }
                                    )
                                }
                                Tool.BREAK -> detectTapGestures(onTap = { p ->
                                    when {
                                        breakLineIndex < 0 -> {
                                            val idx = hitTestLine(p)
                                            if (idx >= 0) breakLineIndex = idx
                                        }
                                        breakPoint1 == null -> breakPoint1 = p
                                        else -> {
                                            performBreak(breakLineIndex, breakPoint1!!, p)
                                            breakLineIndex = -1; breakPoint1 = null
                                        }
                                    }
                                })
                                Tool.FILLET -> detectTapGestures(onTap = { p ->
                                    when {
                                        filletIndex1 < 0 -> {
                                            val idx = hitTestLine(p)
                                            if (idx >= 0) { filletIndex1 = idx; filletTap1 = p }
                                        }
                                        filletIndex2 < 0 -> {
                                            val idx = hitTestLine(p)
                                            if (idx >= 0 && idx != filletIndex1) { filletIndex2 = idx; filletTap2 = p }
                                        }
                                        else -> {}
                                    }
                                })
                                Tool.STRETCH -> if (!stretchArmed) {
                                    detectDragGestures(
                                        onDragStart = { p -> stretchDragStart = p; stretchDragCurrent = p },
                                        onDrag = { change, _ -> stretchDragCurrent = change.position },
                                        onDragEnd = {
                                            val s = stretchDragStart; val c = stretchDragCurrent
                                            if (s != null && c != null && hypotF(c.x - s.x, c.y - s.y) > 8f) {
                                                val rect = androidx.compose.ui.geometry.Rect(
                                                    minOf(s.x, c.x), minOf(s.y, c.y), maxOf(s.x, c.x), maxOf(s.y, c.y)
                                                )
                                                computeStretchSelection(rect)
                                            }
                                            stretchDragStart = null; stretchDragCurrent = null
                                        }
                                    )
                                } else {
                                    detectTapGestures(onTap = { p ->
                                        val base = stretchBasePoint
                                        if (base == null) {
                                            stretchBasePoint = trySnapPoint(p)
                                        } else {
                                            val snapped = trySnapPoint(p)
                                            val delta = Offset(snapped.x - base.x, snapped.y - base.y)
                                            val mag = hypotF(delta.x, delta.y)
                                            if (mag > 2f) {
                                                stretchDirection = Offset(delta.x / mag, delta.y / mag)
                                                stretchAppliedPx = mag
                                                pushUndo()
                                                applyStretchDelta(delta.x, delta.y)
                                                showStretchExactDialog = true
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    ) {
                        val linePaint = Color(0xFF1565C0)
                        val highlightPaint = Color(0xFFE65100)
                        // Dimension marks scale with the sketch's own extent instead of a fixed pixel
                        // size, so they read right whether the drawing is a tiny detail or a full plan.
                        val drawingExtent = if (shapes.isEmpty()) {
                            hypotF(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                        } else {
                            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                            shapes.forEach { s ->
                                minX = minOf(minX, s.x1, s.x2, s.cx - s.r); maxX = maxOf(maxX, s.x1, s.x2, s.cx + s.r)
                                minY = minOf(minY, s.y1, s.y2, s.cy - s.r); maxY = maxOf(maxY, s.y1, s.y2, s.cy + s.r)
                            }
                            hypotF(maxX - minX, maxY - minY)
                        }
                        val dimTick = (drawingExtent * 0.012f).coerceIn(6f, 18f)
                        val dimTextSize = (drawingExtent * 0.028f).coerceIn(20f, 42f)
                        shapes.forEachIndexed { i, s ->
                            val isHighlighted = i == offsetLineIndex || i == trimBoundaryIndex || i == trimTargetIndex ||
                                i == breakLineIndex || i == filletIndex1 || i == filletIndex2 || i in selectedIndices
                            when (s.kind) {
                                ShapeKind.LINE -> {
                                    // Confirmed lines just turn green — no automatic length label; a
                                    // real-world size only ever appears where you place it by hand
                                    // with the Dimension tool.
                                    val lineColor = if (isHighlighted) highlightPaint else if (s.confirmed) Color(0xFF2E7D32) else linePaint
                                    drawLine(lineColor, Offset(s.x1, s.y1), Offset(s.x2, s.y2), strokeWidth = if (isHighlighted) 7f else 5f)
                                }
                                ShapeKind.CIRCLE -> drawCircle(
                                    if (isHighlighted) highlightPaint else linePaint, radius = s.r, center = Offset(s.cx, s.cy),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isHighlighted) 7f else 5f)
                                )
                                ShapeKind.TEXT -> drawContext.canvas.nativeCanvas.drawText(
                                    s.label, s.x1, s.y1,
                                    android.graphics.Paint().apply { color = (if (isHighlighted) 0xFFE65100 else 0xFF6A1B9A).toInt(); textSize = 34f }
                                )
                                ShapeKind.FREEHAND -> {
                                    val strokeColor = if (isHighlighted) highlightPaint else linePaint
                                    val strokeWidth = if (isHighlighted) 6f else 4f
                                    SketchPath.parse(s.path).zipWithNext { a, b ->
                                        drawLine(strokeColor, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = strokeWidth)
                                    }
                                }
                                ShapeKind.ARC -> {
                                    val (startDeg, sweepDeg) = SketchArc.minorSweep(s.cx, s.cy, s.x1, s.y1, s.x2, s.y2)
                                    drawArc(
                                        color = if (isHighlighted) highlightPaint else linePaint,
                                        startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
                                        topLeft = Offset(s.cx - s.r, s.cy - s.r),
                                        size = androidx.compose.ui.geometry.Size(s.r * 2f, s.r * 2f),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isHighlighted) 7f else 5f)
                                    )
                                }
                                ShapeKind.DIMENSION -> {
                                    val dimColor = if (isHighlighted) highlightPaint else Color(0xFF6A1B9A)
                                    val p1 = Offset(s.x1, s.y1); val p2 = Offset(s.x2, s.y2)
                                    drawLine(dimColor, p1, p2, strokeWidth = 3f)
                                    // small perpendicular ticks at each end, like a dimension line
                                    val dx = p2.x - p1.x; val dy = p2.y - p1.y
                                    val len = hypotF(dx, dy)
                                    if (len > 1e-3f) {
                                        val nx = -dy / len * dimTick; val ny = dx / len * dimTick
                                        drawLine(dimColor, Offset(p1.x - nx, p1.y - ny), Offset(p1.x + nx, p1.y + ny), strokeWidth = 3f)
                                        drawLine(dimColor, Offset(p2.x - nx, p2.y - ny), Offset(p2.x + nx, p2.y + ny), strokeWidth = 3f)
                                    }
                                    if (s.label.isNotBlank()) {
                                        val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
                                        drawContext.canvas.nativeCanvas.drawText(
                                            s.label, mx + 4f, my - 6f,
                                            android.graphics.Paint().apply { color = 0xFF6A1B9A.toInt(); textSize = dimTextSize }
                                        )
                                    }
                                }
                            }
                        }
                        val s = dragStart; val c = dragCurrent
                        if (s != null && c != null) {
                            if (tool == Tool.CIRCLE) {
                                drawCircle(Color.Gray, radius = hypotF(c.x - s.x, c.y - s.y), center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                            } else if (tool == Tool.RECTANGLE) {
                                val p2 = Offset(c.x, s.y); val p4 = Offset(s.x, c.y)
                                val gray = Color.Gray
                                drawLine(gray, s, p2, strokeWidth = 4f); drawLine(gray, p2, c, strokeWidth = 4f)
                                drawLine(gray, c, p4, strokeWidth = 4f); drawLine(gray, p4, s, strokeWidth = 4f)
                            }
                        }
                        lineStartPoint?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                        }
                        dimStartPoint?.let { p ->
                            drawCircle(Color(0xFF6A1B9A), radius = 8f, center = p)
                        }
                        breakPoint1?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                        }
                        filletTap1?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                        }
                        filletTap2?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                        }
                        stretchBasePoint?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = 8f, center = p)
                        }
                        if (tool == Tool.FREEHAND && freehandPoints.size >= 2) {
                            freehandPoints.zipWithNext { a, b -> drawLine(Color.Gray, a, b, strokeWidth = 4f) }
                        }
                        if (tool == Tool.BOX_SELECT) {
                            if (moveModeActive) {
                                val s2 = moveDragStart; val c2 = moveDragCurrent
                                if (s2 != null && c2 != null) {
                                    val dx = c2.x - s2.x; val dy = c2.y - s2.y
                                    selectedIndices.forEach { idx ->
                                        val sh = translateShape(shapes[idx], dx, dy)
                                        when (sh.kind) {
                                            ShapeKind.CIRCLE -> drawCircle(
                                                Color.Gray, radius = sh.r, center = Offset(sh.cx, sh.cy),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                                            )
                                            ShapeKind.FREEHAND -> SketchPath.parse(sh.path).zipWithNext { a, b ->
                                                drawLine(Color.Gray, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = 4f)
                                            }
                                            ShapeKind.ARC -> arcPoints(sh).zipWithNext { a, b -> drawLine(Color.Gray, a, b, strokeWidth = 4f) }
                                            ShapeKind.TEXT -> drawCircle(Color.Gray, radius = 6f, center = Offset(sh.x1, sh.y1))
                                            else -> drawLine(Color.Gray, Offset(sh.x1, sh.y1), Offset(sh.x2, sh.y2), strokeWidth = 4f)
                                        }
                                    }
                                }
                            } else {
                                val s2 = selectDragStart; val c2 = selectDragCurrent
                                if (s2 != null && c2 != null) {
                                    val topLeft = Offset(minOf(s2.x, c2.x), minOf(s2.y, c2.y))
                                    val boxSize = androidx.compose.ui.geometry.Size(abs(c2.x - s2.x), abs(c2.y - s2.y))
                                    // Same colour/line-style cue AutoCAD uses: solid blue = Window, dashed green = Crossing.
                                    val isWindow = c2.x >= s2.x
                                    val boxColor = if (isWindow) Color(0xFF1565C0) else Color(0xFF2E7D32)
                                    drawRect(boxColor.copy(alpha = 0.12f), topLeft = topLeft, size = boxSize)
                                    drawRect(
                                        boxColor, topLeft = topLeft, size = boxSize,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = 2f,
                                            pathEffect = if (isWindow) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                                        )
                                    )
                                }
                            }
                        }
                        if (tool == Tool.STRETCH) {
                            val s2 = stretchDragStart; val c2 = stretchDragCurrent
                            if (s2 != null && c2 != null) {
                                val topLeft = Offset(minOf(s2.x, c2.x), minOf(s2.y, c2.y))
                                val boxSize = androidx.compose.ui.geometry.Size(abs(c2.x - s2.x), abs(c2.y - s2.y))
                                // Always a crossing box — that's the whole point of Stretch.
                                val stretchColor = Color(0xFF2E7D32)
                                drawRect(stretchColor.copy(alpha = 0.12f), topLeft = topLeft, size = boxSize)
                                drawRect(
                                    stretchColor, topLeft = topLeft, size = boxSize,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                                    )
                                )
                            }
                            stretchPoints.forEach { sp ->
                                val s = shapes.getOrNull(sp.shapeIndex) ?: return@forEach
                                val p = when {
                                    s.kind == ShapeKind.CIRCLE -> Offset(s.cx, s.cy)
                                    sp.part == 0 -> Offset(s.x1, s.y1)
                                    else -> Offset(s.x2, s.y2)
                                }
                                drawCircle(Color(0xFFE65100), radius = 7f, center = p)
                            }
                        }
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
 * Both points are already picked; this asks for the dimension's text, pre-filled with the
 * measured value but freely editable (e.g. to write "3000 CRS" instead of a bare number).
 * Nothing is created until Confirm — Cancel just discards the two picked points.
 */
@Composable
private fun DimensionTextDialog(initialText: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var showHandwrite by remember { mutableStateOf(false) }
    if (showHandwrite) {
        HandwriteInputDialog(onResult = { text = it; showHandwrite = false }, onDismiss = { showHandwrite = false })
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Dimension text") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/**
 * Both lines are already picked; asks for the fillet radius. Stays open (showing [error]) if the
 * geometry can't support that radius, so a different value can be tried without re-picking lines.
 */
@Composable
private fun FilletRadiusDialog(error: String?, unitLabel: String, onConfirm: (radius: Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fillet radius") },
        text = {
            Column {
                Text(
                    "0 = a sharp corner, the lines just meet. A positive radius rounds the corner with an arc of that radius.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Radius ($unitLabel)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { text.toDoubleOrNull()?.let { if (it >= 0) onConfirm(it) } }) { Text("Fillet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A value (line length or offset distance) is already applied as-tapped; this optionally
 * overrides it with an exact typed one. The X closes and undoes the whole action (removes
 * whatever was just created); "Use as tapped" keeps it; "Set exact" applies the typed value.
 */
@Composable
private fun OptionalDistanceDialog(
    title: String,
    asIsDisplay: Float,
    unitLabel: String,
    fieldLabel: String,
    onSetExact: (value: Double) -> Unit,
    onUseAsIs: () -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showHandwrite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showHandwrite) {
        HandwriteInputDialog(onResult = { text = it.filter { c -> c.isDigit() || c == '.' }; showHandwrite = false }, onDismiss = { showHandwrite = false })
    }
    AlertDialog(
        onDismissRequest = onUseAsIs,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel — remove") }
            }
        },
        text = {
            Column {
                Text(
                    "As tapped: ~${trimNum(asIsDisplay.toDouble())}$unitLabel. Type an exact value to override it, or use it as tapped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text(fieldLabel) },
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
                if (v != null && v > 0) onSetExact(v) else error = "Enter a valid value"
            }) { Text("Set exact") }
        },
        dismissButton = { TextButton(onClick = onUseAsIs) { Text("Use as tapped") } }
    )
}

/**
 * Same "as tapped, optionally override" pattern as [OptionalDistanceDialog], but for the Line
 * tool specifically: length is always editable, and — only when Ortho is off and the tapped line
 * isn't already horizontal/vertical — an exact angle field appears too. Either field can be left
 * blank to keep that value as tapped; both can be set together.
 */
@Composable
private fun LineFinishDialog(
    asIsDisplay: Float,
    unitLabel: String,
    asIsAngleDeg: Float,
    showAngleField: Boolean,
    onApply: (value: Double?, angleDeg: Float?) -> Unit,
    onUseAsIs: () -> Unit,
    onCancel: () -> Unit
) {
    var lengthText by remember { mutableStateOf("") }
    var angleText by remember { mutableStateOf("") }
    var showHandwrite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (showHandwrite) {
        HandwriteInputDialog(onResult = { lengthText = it.filter { c -> c.isDigit() || c == '.' }; showHandwrite = false }, onDismiss = { showHandwrite = false })
    }
    AlertDialog(
        onDismissRequest = onUseAsIs,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAngleField) "Line length & angle" else "Line length", modifier = Modifier.weight(1f))
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel — remove") }
            }
        },
        text = {
            Column {
                val asTapped = "As tapped: ~${trimNum(asIsDisplay.toDouble())}$unitLabel" +
                    (if (showAngleField) " at ${trimNum(asIsAngleDeg.toDouble())}°" else "") +
                    ". Type exact values to override, or use it as tapped."
                Text(asTapped, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = lengthText, onValueChange = { lengthText = it; error = null }, singleLine = true,
                    label = { Text("Exact length ($unitLabel) — optional") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
                if (showAngleField) {
                    OutlinedTextField(
                        value = angleText, onValueChange = { angleText = it; error = null }, singleLine = true,
                        label = { Text("Exact angle (° from horizontal) — optional") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = if (lengthText.isBlank()) null else lengthText.toDoubleOrNull()
                val angle = if (angleText.isBlank()) null else angleText.toFloatOrNull()
                when {
                    lengthText.isNotBlank() && value == null -> error = "Enter a valid length"
                    angleText.isNotBlank() && angle == null -> error = "Enter a valid angle"
                    value == null && angle == null -> onUseAsIs()
                    else -> onApply(value, angle)
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onUseAsIs) { Text("Use as tapped") } }
    )
}

@Composable
private fun ShapeEditDialog(shape: SketchShape, unitLabel: String, onConfirm: (SketchShape) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    when (shape.kind) {
        ShapeKind.LINE -> {
            var text by remember {
                mutableStateOf(if (shape.realLength > 0) trimNum(mmToDisplay(shape.realLength, unitLabel)) else "")
            }
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
                            label = { Text("Real length ($unitLabel)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = { showHandwrite = true }) { Text("Write it by hand") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = text.toDoubleOrNull()
                        if (v != null && v > 0) onConfirm(shape.copy(realLength = displayToMm(v, unitLabel), confirmed = true))
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
                title = when (shape.kind) {
                    ShapeKind.CIRCLE -> "Circle label (optional)"
                    ShapeKind.DIMENSION -> "Dimension text"
                    else -> "Text label"
                },
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
    unitLabel: String,
    onConfirmRectangle: (length: Double, width: Double, wall: Double) -> Unit,
    onConfirmContinuous: (command: String, wall: Double) -> String?,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(PlanMode.RECTANGLE) }
    var length by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    // A typical brick-wall thickness, expressed in whichever unit this work uses.
    var wall by remember { mutableStateOf(if (unitLabel == "cm") "11.5" else "115") }
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
                        label = { Text("Length ($unitLabel)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    OutlinedTextField(value = width, onValueChange = { width = it; error = null }, singleLine = true,
                        label = { Text("Width ($unitLabel)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    Text(
                        "R = right wall, B = bottom, L = left, T = top. Number after each letter is that " +
                            "wall's length in $unitLabel. Example: R500 B200 L300 T200",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    OutlinedTextField(value = command, onValueChange = { command = it; error = null }, singleLine = true,
                        label = { Text("Walk, e.g. R500 B200 L300 T200") }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                OutlinedTextField(value = wall, onValueChange = { wall = it; error = null }, singleLine = true,
                    label = { Text("Wall thickness ($unitLabel)") }, keyboardOptions = decimalOpts, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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

/** Wraps a degree value into the 0..360 range (exclusive of 360). */
private fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f

/** True if [deg] (already normalized) is within ~1° of a multiple of 90 — i.e. Ortho would have
 *  produced this angle anyway, so there's nothing extra to ask the user for. */
private fun isOrthoAngle(deg: Float): Boolean {
    val fromNearest90 = ((deg % 90f) + 90f) % 90f
    return fromNearest90 < 1f || fromNearest90 > 89f
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

// Every SketchShape.realLength (and every pixel-derived distance) is always stored/computed in
// millimetres internally, regardless of the work's display unit — these two just convert at the
// UI boundary, so DXF export and the mm-based scale math never need to know a work is in cm.
private fun mmToDisplay(mm: Double, unit: String): Double = if (unit == "cm") mm / 10.0 else mm
private fun displayToMm(display: Double, unit: String): Double = if (unit == "cm") display * 10.0 else display

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
