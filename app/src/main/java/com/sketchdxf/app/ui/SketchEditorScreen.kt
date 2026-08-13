package com.sketchdxf.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sketchdxf.app.data.AppDatabase
import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchArc
import com.sketchdxf.app.data.SketchBlock
import com.sketchdxf.app.data.SketchBlockCodec
import com.sketchdxf.app.data.SketchCircleFit
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import com.sketchdxf.app.data.SketchWork
import com.sketchdxf.app.dxf.DxfReader
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

private enum class Tool { SELECT, LINE, RECTANGLE, CIRCLE, TEXT, DIMENSION, OFFSET, TRIM, PAN, FREEHAND, BOX_SELECT, BREAK, FILLET, STRETCH, EXTEND, ARC, DISTANCE, BLOCK }

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
    val androidView = LocalView.current
    // The canvas reaches close to the screen edges, so Android's own edge-swipe-back gesture can
    // otherwise steal a drag that starts/ends near the left or right border — see the canvas Box's
    // onGloballyPositioned below, which keeps this in sync with its actual on-screen bounds.
    DisposableEffect(Unit) {
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 29) androidView.systemGestureExclusionRects = emptyList()
        }
    }
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).sketchDao() }
    var savedBlocks by remember { mutableStateOf<List<SketchBlock>>(emptyList()) }
    LaunchedEffect(Unit) { savedBlocks = dao.allBlocks() }

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
    // Freehand "Room" mode: a hand-drawn stroke is auto-straightened into wall segments, then the
    // most level (or longest) segment is offered as a scale reference before the view refits.
    var freehandRoomMode by remember { mutableStateOf(false) }
    var pendingRoomCalibrate by remember { mutableStateOf<Int?>(null) }
    var showRoomPlan by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var dxfImportMessage by remember { mutableStateOf<String?>(null) }
    // Both Cancel (the X icon) and the app bar's back arrow leave without saving — confirm first
    // so an accidental tap can't silently discard work.
    var showCloseConfirm by remember { mutableStateOf(false) }

    // Current draw colour — applies to every newly-drawn shape (Line, Circle, Rectangle,
    // Freehand, Text, Dimension, Room plan); null means "use this shape kind's usual default"
    // (green when a LINE is confirmed, blue otherwise, purple for Dimension/Text, ...). Shapes
    // derived from an existing one (Offset, Copy, Break, Stretch, Trim) keep the source's own
    // colour automatically, since .copy() only overrides fields explicitly passed to it.
    var currentColor by remember { mutableStateOf<Color?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    // Fullscreen canvas: hides the toolbars/command line so the drawing area fills the screen; a
    // corner button (drawn over the canvas itself, since the top bar is hidden too) exits back.
    var fullscreenCanvas by remember { mutableStateOf(false) }

    // AutoCAD-style command line: type a tool's name/alias and press Enter/Run to switch to it —
    // a keyboard-first shortcut alongside the toolbar chips. A leading ' marks a "transparent"
    // command (e.g. 'ORTHO, 'SNAP): it runs without cancelling whatever tool/points are already
    // in progress, the same way AutoCAD lets you pan or toggle a mode mid-command.
    var commandInput by remember { mutableStateOf("") }
    var commandFeedback by remember { mutableStateOf<String?>(null) }
    var commandLineVisible by remember { mutableStateOf(true) }

    // Pinch-zoom/pan — a pure view transform; shape coordinates are never affected by it.
    var viewScale by remember { mutableStateOf(1f) }
    var viewOffset by remember { mutableStateOf(Offset.Zero) }

    // CAD-style line input: tap a start point, tap an end point — the line is drawn between them
    // immediately (Ortho locks the end point to horizontal/vertical from the start, like AutoCAD).
    // Typing an exact length afterward is optional; skipping it just keeps the line as tapped.
    var orthoOn by remember { mutableStateOf(true) }
    var snapOn by remember { mutableStateOf(true) }
    var chainOn by remember { mutableStateOf(true) }
    // Wall mode: each LINE drawn is treated as a wall's inside face — a second, parallel line is
    // auto-added on the outside at the given thickness. Chained (connected) wall segments have
    // their outside lines mitered to meet exactly at the corner instead of gapping/overlapping.
    var wallModeOn by remember { mutableStateOf(false) }
    var wallThickness by remember { mutableStateOf<Double?>(null) } // mm
    var showWallThicknessDialog by remember { mutableStateOf(false) }
    var lastWallInnerEnd by remember { mutableStateOf<Offset?>(null) }
    var lastWallOuterIndex by remember { mutableStateOf(-1) }
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

    // Extend: tap the boundary line once, then tap any number of lines near the end that should
    // reach it — the boundary stays selected so several lines can be extended to it in a row.
    var extendBoundaryIndex by remember { mutableStateOf(-1) }
    var extendMessage by remember { mutableStateOf<String?>(null) }

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
    // Copy works the same way as Move (armed, then a drag on the canvas supplies the placement)
    // instead of pasting an instant fixed-offset copy — so the user aims each copy by hand.
    var copyModeActive by remember { mutableStateOf(false) }
    var moveDragStart by remember { mutableStateOf<Offset?>(null) }
    var moveDragCurrent by remember { mutableStateOf<Offset?>(null) }
    // While a Move/Copy drag is live, the nearest existing point (if Snap is on) that the drag
    // would land on — shown as a highlight, and used as the actual drop point on release.
    var moveSnapTarget by remember { mutableStateOf<Offset?>(null) }

    // Grip editing (Tool.SELECT): dragging a LINE/DIMENSION endpoint reshapes just that end,
    // instead of moving the whole shape — same idea as AutoCAD's blue grip squares.
    var gripDragIndex by remember { mutableStateOf(-1) }
    var gripDragPart by remember { mutableStateOf(0) } // 1 = x1/y1 endpoint, 2 = x2/y2 endpoint
    var selectTapStart by remember { mutableStateOf<Offset?>(null) }

    // Break: tap the line, tap the first break point, tap the second — the segment between the
    // two (projected onto the line) is removed. Tapping both points at nearly the same spot
    // leaves no visible gap, matching AutoCAD's "break at point".
    var breakLineIndex by remember { mutableStateOf(-1) }
    var breakPoint1 by remember { mutableStateOf<Offset?>(null) }

    // Fillet: tap the first line, tap the second, then type a radius — 0 makes them meet at a
    // sharp corner; a positive radius rounds the corner with a tangent arc of that radius.
    var filletIndex1 by remember { mutableStateOf(-1) }
    var filletIndex2 by remember { mutableStateOf(-1) }
    // 3-point ARC: tap start, then a point the arc passes through, then the end point.
    var arcP1 by remember { mutableStateOf<Offset?>(null) }
    var arcP2 by remember { mutableStateOf<Offset?>(null) }
    // Distance/calibration tool: tap 2 points on the background reference image whose real-world
    // distance is already known (e.g. written on the original blueprint) — the on-screen pixel
    // gap between them versus that typed-in real value becomes the scale every future exact
    // length (a drawn line, or a Dimension's prefilled reading) is converted through, instead of
    // the usual "median of confirmed lines" guess. See currentPxPerMm().
    var distanceStart by remember { mutableStateOf<Offset?>(null) }
    var pendingDistancePx by remember { mutableStateOf<Float?>(null) }
    var calibrationRatio by remember { mutableStateOf<Float?>(null) } // px per mm
    var useCalibrationRatio by remember { mutableStateOf(true) }
    // Block library: save a selection as a reusable, categorised group of shapes, then drop it
    // back in elsewhere — see SketchBlock/SketchBlockCodec.
    var showSaveBlockDialog by remember { mutableStateOf(false) }
    var showGroupWidthDialog by remember { mutableStateOf(false) }
    var showBlockPicker by remember { mutableStateOf(false) }
    var pendingBlockInsert by remember { mutableStateOf<SketchBlock?>(null) }
    var insertUseRatio by remember { mutableStateOf(true) }
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
        pendingRoomCalibrate = null
        offsetLineIndex = -1
        trimBoundaryIndex = -1; trimTargetIndex = -1
        extendBoundaryIndex = -1; extendMessage = null
        dimStartPoint = null; pendingDimension = null
        freehandPoints.clear()
        selectedIndices.clear()
        selectDragStart = null; selectDragCurrent = null
        moveModeActive = false; copyModeActive = false
        moveDragStart = null; moveDragCurrent = null; moveSnapTarget = null
        gripDragIndex = -1; gripDragPart = 0; selectTapStart = null
        breakLineIndex = -1; breakPoint1 = null
        filletIndex1 = -1; filletIndex2 = -1; filletTap1 = null; filletTap2 = null; filletError = null
        stretchPoints.clear()
        stretchDragStart = null; stretchDragCurrent = null; stretchBasePoint = null
        stretchDirection = Offset.Zero; stretchAppliedPx = 0f; showStretchExactDialog = false
        arcP1 = null; arcP2 = null
        distanceStart = null; pendingDistancePx = null
        pendingBlockInsert = null
        lastWallInnerEnd = null; lastWallOuterIndex = -1
    }

    /** Nearest existing line endpoint/midpoint within range, excluding shapes at [excludeIndices]
     *  (e.g. the ones currently being moved/copied, so a selection doesn't snap to itself) — null
     *  when nothing is close enough. Used both to snap a single tapped point and to highlight the
     *  point a live Move/Copy drag would land on. */
    fun findSnapPoint(p: Offset, excludeIndices: Collection<Int> = emptyList()): Offset? {
        if (!snapOn) return null
        var best: Offset? = null; var bestDist = 28f
        shapes.forEachIndexed { i, s ->
            if (i in excludeIndices) return@forEachIndexed
            if (s.kind == ShapeKind.LINE) {
                listOf(Offset(s.x1, s.y1), Offset(s.x2, s.y2), Offset((s.x1 + s.x2) / 2f, (s.y1 + s.y2) / 2f)).forEach { c ->
                    val d = hypotF(p.x - c.x, p.y - c.y)
                    if (d < bestDist) { bestDist = d; best = c }
                }
            }
        }
        return best
    }

    /** Snaps to the nearest existing line's endpoint/midpoint within range, else returns [p]. */
    fun trySnapPoint(p: Offset): Offset = findSnapPoint(p) ?: p

    /** Locks [raw] onto the horizontal or vertical line through [start], whichever is closer —
     *  the end point keeps its tapped distance along that axis, like AutoCAD's Ortho mode. */
    fun orthoProject(start: Offset, raw: Offset): Offset {
        val dx = raw.x - start.x; val dy = raw.y - start.y
        return if (abs(dx) >= abs(dy)) Offset(raw.x, start.y) else Offset(start.x, raw.y)
    }

    /** Pixels per real-world mm, derived from confirmed lines so far (or a sensible default). */
    fun currentPxPerMm(): Float {
        if (useCalibrationRatio) calibrationRatio?.let { return it }
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

    /** Points sampled along an ARC shape's actual sweep (minor or major, per [SketchShape.major])
     *  — used for hit-testing, bounds, and the box-select move-preview ghost, the same way
     *  FREEHAND uses its stored path. */
    fun arcPoints(s: SketchShape, steps: Int = 16): List<Offset> {
        val (startDeg, sweepDeg) = SketchArc.sweepFor(s.cx, s.cy, s.x1, s.y1, s.x2, s.y2, s.major)
        return (0..steps).map { i ->
            val ang = Math.toRadians((startDeg + sweepDeg * i / steps).toDouble())
            Offset(s.cx + s.r * cos(ang).toFloat(), s.cy + s.r * sin(ang).toFloat())
        }
    }

    /** Rough (width, height) in px of a TEXT shape as actually drawn (see the drawing loop and
     *  [SketchShape.fontSize]) — used so tapping anywhere over a label selects it, not just right
     *  on its anchor point, which used to make longer or larger text hard to tap reliably. */
    fun textExtent(s: SketchShape): Pair<Float, Float> {
        val sizePx = if (s.fontSize > 0f) (s.fontSize * currentPxPerMm()).coerceAtLeast(8f) else 34f
        return (s.label.length * sizePx * 0.56f) to sizePx
    }

    fun distToRect(p: Offset, r: androidx.compose.ui.geometry.Rect): Float {
        val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
        val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom)
        return hypotF(dx, dy)
    }

    /** The actual dimension-line endpoints for a DIMENSION shape: [SketchShape.x1,y1]/[x2,y2]
     *  offset perpendicular to the measured segment by [SketchShape.dimOffset] px, so the
     *  dimension line (and its text) sit clear of the object being measured instead of drawn
     *  right on top of it — with short extension lines back to the actual measured points, like a
     *  real AutoCAD linear dimension. dimOffset == 0 (e.g. dimensions saved before this existed)
     *  draws exactly on the measured points, unchanged from before. */
    fun dimLineEndpoints(s: SketchShape): Pair<Offset, Offset> {
        val base1 = Offset(s.x1, s.y1); val base2 = Offset(s.x2, s.y2)
        if (s.dimOffset == 0f) return base1 to base2
        val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
        val len = hypotF(dx, dy)
        if (len < 1e-3f) return base1 to base2
        val nx = -dy / len * s.dimOffset; val ny = dx / len * s.dimOffset
        return Offset(base1.x + nx, base1.y + ny) to Offset(base2.x + nx, base2.y + ny)
    }

    fun hitTest(p: Offset): Int {
        var best = -1; var bestDist = 26f
        shapes.forEachIndexed { i, s ->
            val d = when (s.kind) {
                ShapeKind.LINE -> distToSegment(p, Offset(s.x1, s.y1), Offset(s.x2, s.y2))
                ShapeKind.CIRCLE -> abs(hypotF(p.x - s.cx, p.y - s.cy) - s.r)
                ShapeKind.TEXT -> {
                    val (w, h) = textExtent(s)
                    distToRect(p, androidx.compose.ui.geometry.Rect(s.x1, s.y1 - h, s.x1 + w, s.y1 + h * 0.3f))
                }
                ShapeKind.DIMENSION -> {
                    // Hit-test the line as it's actually drawn (offset from the object, if any),
                    // not the invisible measured segment — otherwise tapping the visible dimension
                    // line/text wouldn't select it once it's drawn clear of the object.
                    val (dp1, dp2) = dimLineEndpoints(s)
                    distToSegment(p, dp1, dp2)
                }
                ShapeKind.FREEHAND, ShapeKind.POLYLINE -> {
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

    /** Finds a draggable grip (a LINE/DIMENSION endpoint) near [p] — a tighter radius than the
     *  usual hit-test so grabbing an endpoint to reshape it doesn't fight with tapping the shape's
     *  body to open its edit dialog. Returns the shape index and which end (1 = x1/y1, 2 = x2/y2),
     *  or null if nothing is close enough. */
    fun hitTestGrip(p: Offset): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null; var bestDist = 24f
        shapes.forEachIndexed { i, s ->
            if (s.kind == ShapeKind.LINE || s.kind == ShapeKind.DIMENSION) {
                val d1 = hypotF(p.x - s.x1, p.y - s.y1)
                if (d1 < bestDist) { bestDist = d1; best = i to 1 }
                val d2 = hypotF(p.x - s.x2, p.y - s.y2)
                if (d2 < bestDist) { bestDist = d2; best = i to 2 }
            }
        }
        return best
    }

    /** Bounding box used by box-select to decide whether a shape falls inside the drag rect. */
    fun shapeBounds(s: SketchShape): androidx.compose.ui.geometry.Rect = when (s.kind) {
        ShapeKind.CIRCLE -> androidx.compose.ui.geometry.Rect(s.cx - s.r, s.cy - s.r, s.cx + s.r, s.cy + s.r)
        ShapeKind.TEXT -> textExtent(s).let { (w, h) -> androidx.compose.ui.geometry.Rect(s.x1, s.y1 - h, s.x1 + w, s.y1 + h * 0.3f) }
        ShapeKind.FREEHAND, ShapeKind.POLYLINE -> {
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
        ShapeKind.DIMENSION -> {
            // Covers both the measured points and the (possibly offset) drawn dimension line, so
            // panning/box-select account for the extension lines too.
            val (dp1, dp2) = dimLineEndpoints(s)
            androidx.compose.ui.geometry.Rect(
                minOf(s.x1, s.x2, dp1.x, dp2.x), minOf(s.y1, s.y2, dp1.y, dp2.y),
                maxOf(s.x1, s.x2, dp1.x, dp2.x), maxOf(s.y1, s.y2, dp1.y, dp2.y)
            )
        }
        else -> androidx.compose.ui.geometry.Rect(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
    }

    /** Keeps the drawing area always reachable by panning — not just the nominal canvas box, but
     *  the union of that box and every shape's own bounds. A shape confirmed at a real-world
     *  length (e.g. a 1500mm wall) can easily extend far outside the canvas's own pixel box at
     *  the current scale; clamping to just the canvas box (as this used to) made the far end of
     *  a shape like that permanently unreachable by panning, and zoom alone couldn't shrink it
     *  into view either — see the pinch-zoom handler's widened scale range below. */
    fun clampViewOffset(offset: Offset, scale: Float): Offset {
        val cw = canvasSize.width.toFloat(); val ch = canvasSize.height.toFloat()
        if (cw <= 0f || ch <= 0f) return offset
        var left = 0f; var top = 0f; var right = cw; var bottom = ch
        shapes.forEach { s ->
            val b = shapeBounds(s)
            left = minOf(left, b.left); top = minOf(top, b.top)
            right = maxOf(right, b.right); bottom = maxOf(bottom, b.bottom)
        }
        val minOffsetX = cw - right * scale; val maxOffsetX = -left * scale
        val minOffsetY = ch - bottom * scale; val maxOffsetY = -top * scale
        return Offset(
            offset.x.coerceIn(minOf(minOffsetX, maxOffsetX), maxOf(minOffsetX, maxOffsetX)),
            offset.y.coerceIn(minOf(minOffsetY, maxOffsetY), maxOf(minOffsetY, maxOffsetY))
        )
    }

    /** Re-centres the view on [p] (keeping the current zoom) if it isn't already comfortably on
     *  screen — used right after typing an exact length moves a line's endpoint somewhere far from
     *  where it was roughly tapped, so the point you'd continue drawing from is immediately visible
     *  instead of needing to be hunted down by hand afterward. */
    fun ensurePointVisible(p: Offset) {
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: return
        val ch = canvasSize.height.toFloat().takeIf { it > 0f } ?: return
        val screenX = p.x * viewScale + viewOffset.x
        val screenY = p.y * viewScale + viewOffset.y
        val margin = 48f
        if (screenX in margin..(cw - margin) && screenY in margin..(ch - margin)) return
        viewOffset = clampViewOffset(Offset(cw / 2f - p.x * viewScale, ch / 2f - p.y * viewScale), viewScale)
    }

    /** Resets pan/zoom so every shape — including anything currently panned/zoomed out of view —
     *  fits back on screen at once. */
    fun fitToScreen() {
        if (shapes.isEmpty()) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        shapes.forEach { s ->
            val b = shapeBounds(s)
            minX = minOf(minX, b.left); maxX = maxOf(maxX, b.right)
            minY = minOf(minY, b.top); maxY = maxOf(maxY, b.bottom)
        }
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: return
        val ch = canvasSize.height.toFloat().takeIf { it > 0f } ?: return
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf(cw * 0.9f / spanX, ch * 0.9f / spanY).coerceIn(0.02f, 6f)
        val midX = (minX + maxX) / 2f; val midY = (minY + maxY) / 2f
        viewScale = scale
        viewOffset = clampViewOffset(Offset(cw / 2f - midX * scale, ch / 2f - midY * scale), scale)
    }

    /** Shifts a shape by (dx, dy) in canvas-pixel space — used by group Move and by Copy's paste offset. */
    fun translateShape(s: SketchShape, dx: Float, dy: Float): SketchShape = when (s.kind) {
        ShapeKind.CIRCLE -> s.copy(cx = s.cx + dx, cy = s.cy + dy)
        ShapeKind.TEXT -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy)
        ShapeKind.FREEHAND, ShapeKind.POLYLINE -> s.copy(path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> (x + dx) to (y + dy) }))
        ShapeKind.ARC -> s.copy(cx = s.cx + dx, cy = s.cy + dy, x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
        else -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
    }

    /** Scales a shape's geometry (only) by [f] around the origin (0,0) — used to resize a block's
     *  saved-relative-to-origin shapes on insert. Never touches [SketchShape.realLength] or
     *  [SketchShape.label]: those are real-world/text data, not pixel geometry. */
    fun scaleShape(s: SketchShape, f: Float): SketchShape = if (f == 1f) s else when (s.kind) {
        ShapeKind.CIRCLE -> s.copy(cx = s.cx * f, cy = s.cy * f, r = s.r * f)
        ShapeKind.TEXT -> s.copy(x1 = s.x1 * f, y1 = s.y1 * f)
        ShapeKind.FREEHAND, ShapeKind.POLYLINE -> s.copy(path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> (x * f) to (y * f) }))
        ShapeKind.ARC -> s.copy(cx = s.cx * f, cy = s.cy * f, r = s.r * f, x1 = s.x1 * f, y1 = s.y1 * f, x2 = s.x2 * f, y2 = s.y2 * f)
        else -> s.copy(x1 = s.x1 * f, y1 = s.y1 * f, x2 = s.x2 * f, y2 = s.y2 * f)
    }

    /** Removes every selected shape, highest index first so earlier removals don't shift the rest. */
    fun deleteSelection() {
        if (selectedIndices.isEmpty()) return
        pushUndo()
        selectedIndices.sortedDescending().forEach { shapes.removeAt(it) }
        selectedIndices.clear()
    }

    /** Cleans up every selected FREEHAND stroke: one that's clearly meant as a circle (a closed
     *  loop, roughly constant distance from its own centre) becomes a real CIRCLE; anything else
     *  becomes a POLYLINE — same points, but exported to DXF as one true polyline entity instead
     *  of a chain of separate LINE segments. Non-FREEHAND selected shapes are left untouched. */
    fun convertFreehandSelection() {
        val targets = selectedIndices.filter { shapes.getOrNull(it)?.kind == ShapeKind.FREEHAND }
        if (targets.isEmpty()) return
        pushUndo()
        targets.forEach { idx ->
            val s = shapes[idx]
            val pts = SketchPath.parse(s.path)
            val fit = SketchCircleFit.tryFit(pts)
            shapes[idx] = if (fit != null) {
                val (cx, cy, r) = fit
                s.copy(kind = ShapeKind.CIRCLE, cx = cx, cy = cy, r = r, path = "")
            } else {
                s.copy(kind = ShapeKind.POLYLINE)
            }
        }
    }

    /** AutoCAD-style Explode: turns every selected POLYLINE back into its individual LINE
     *  segments — the reverse of "Convert to Polyline". The compound shape is removed and
     *  replaced with plain LINEs, which are then left selected. */
    fun explodeSelection() {
        val targets = selectedIndices.filter { shapes.getOrNull(it)?.kind == ShapeKind.POLYLINE }
        if (targets.isEmpty()) return
        pushUndo()
        val newLines = mutableListOf<SketchShape>()
        targets.sorted().forEach { idx ->
            val s = shapes[idx]
            SketchPath.parse(s.path).zipWithNext { a, b ->
                newLines.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = a.first, y1 = a.second, x2 = b.first, y2 = b.second, color = s.color))
            }
        }
        targets.sortedDescending().forEach { shapes.removeAt(it) }
        val firstNewIndex = shapes.size
        shapes.addAll(newLines)
        selectedIndices.clear()
        selectedIndices.addAll(firstNewIndex until shapes.size)
    }

    /** AutoCAD-style Smooth: straightens the shaky jitter in one or more hand-drawn FREEHAND/
     *  POLYLINE strokes while keeping any real corners — small wobbles collapse into a single
     *  straight segment, larger genuine bends (an L-shaped wall, say) stay as separate segments.
     *  Uses the Douglas-Peucker line-simplification algorithm; non-freehand shapes are untouched. */
    fun smoothSelection() {
        val targets = selectedIndices.filter {
            val k = shapes.getOrNull(it)?.kind
            k == ShapeKind.FREEHAND || k == ShapeKind.POLYLINE
        }
        if (targets.isEmpty()) return
        pushUndo()
        val tolerancePx = 14f
        targets.forEach { idx ->
            val s = shapes[idx]
            val pts = SketchPath.parse(s.path).map { Offset(it.first, it.second) }
            if (pts.size >= 3) {
                val simplified = douglasPeucker(pts, tolerancePx)
                shapes[idx] = s.copy(path = SketchPath.serialize(simplified.map { it.x to it.y }))
            }
        }
    }

    /** Freehand "Room" mode: straightens a hand-drawn stroke (Douglas-Peucker, same as Smooth)
     *  straight into individual LINE wall segments — no separate FREEHAND/Explode step needed —
     *  then arms [pendingRoomCalibrate] on whichever segment reads most like "the top wall": the
     *  most level (within ~20° of horizontal) and topmost of those, or just the longest segment if
     *  nothing drawn is roughly level. */
    fun finishFreehandRoom(points: List<Offset>) {
        if (points.size < 2) return
        val simplified = douglasPeucker(points, 14f)
        if (simplified.size < 2) return
        pushUndo()
        val firstIndex = shapes.size
        simplified.zipWithNext { a, b ->
            shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = a.x, y1 = a.y, x2 = b.x, y2 = b.y, color = currentColor?.toArgb()))
        }
        val newIndices = firstIndex until shapes.size
        val levelThreshold = kotlin.math.sin(Math.toRadians(20.0)).toFloat()
        val levelCandidates = newIndices.filter { idx ->
            val s = shapes[idx]
            val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
            val len = hypotF(dx, dy)
            len > 1e-3f && abs(dy) / len < levelThreshold
        }
        pendingRoomCalibrate = if (levelCandidates.isNotEmpty()) {
            levelCandidates.minByOrNull { (shapes[it].y1 + shapes[it].y2) / 2f }
        } else {
            newIndices.maxByOrNull { hypotF(shapes[it].x2 - shapes[it].x1, shapes[it].y2 - shapes[it].y1) }
        }
    }

    /** Applies one line width (px) to every selected shape that draws a stroke — everything
     *  except TEXT, which has its own separate font-size control. 0 clears back to each shape
     *  kind's usual default width instead of setting an explicit one. */
    fun applyGroupWidth(widthPx: Float) {
        val targets = selectedIndices.filter { shapes.getOrNull(it)?.kind != ShapeKind.TEXT }
        if (targets.isEmpty()) return
        pushUndo()
        targets.forEach { idx -> shapes[idx] = shapes[idx].copy(strokeWidth = widthPx) }
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

    /** Wall mode: adds the outside-face line parallel to the just-finished inner line at
     *  [innerIdx], offset by [wallThickness] to the right of its drawn direction (a fixed
     *  convention — trace a room's inside perimeter the same rotational way each time and every
     *  wall's outside line lands on the actual outside). If this segment continues the previous
     *  wall segment (its start matches the previous inner line's end — Chain does this), the two
     *  outside lines are mitered to meet exactly at the corner instead of leaving a gap or overlap. */
    fun addWallOuterLine(innerIdx: Int) {
        val inner = shapes.getOrNull(innerIdx) ?: return
        val thickMm = wallThickness ?: return
        val thickPx = (thickMm * currentPxPerMm()).toFloat()
        val dx = inner.x2 - inner.x1; val dy = inner.y2 - inner.y1
        val len = hypotF(dx, dy)
        if (len < 1e-3f) { lastWallInnerEnd = null; lastWallOuterIndex = -1; return }
        val nx = dy / len; val ny = -dx / len
        var outer = SketchShape(
            workId = 0, kind = ShapeKind.LINE,
            x1 = inner.x1 + nx * thickPx, y1 = inner.y1 + ny * thickPx,
            x2 = inner.x2 + nx * thickPx, y2 = inner.y2 + ny * thickPx,
            color = currentColor?.toArgb()
        )
        val prevEnd = lastWallInnerEnd; val prevIdx = lastWallOuterIndex
        if (prevEnd != null && prevIdx in shapes.indices && hypotF(inner.x1 - prevEnd.x, inner.y1 - prevEnd.y) < 2f) {
            val prevOuter = shapes[prevIdx]
            lineIntersection(prevOuter, outer)?.let { meet ->
                shapes[prevIdx] = prevOuter.copy(x2 = meet.x, y2 = meet.y)
                outer = outer.copy(x1 = meet.x, y1 = meet.y)
            }
        }
        shapes.add(outer)
        lastWallOuterIndex = shapes.lastIndex
        lastWallInnerEnd = Offset(inner.x2, inner.y2)
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

    /** The counterpart to Trim: extends whichever end of [targetIdx] is nearer [tap] out to
     *  where it meets [boundaryIdx] (extended if needed). Only valid when that meeting point
     *  actually lies beyond the tapped end — otherwise the boundary crosses the line already
     *  (that's a Trim, not an Extend) or sits behind the far end entirely. Returns an error
     *  message, or null on success. Line length loses its confirmed status either way, since
     *  extending changes it. */
    fun performExtend(boundaryIdx: Int, targetIdx: Int, tap: Offset): String? {
        val boundary = shapes.getOrNull(boundaryIdx) ?: return "That line no longer exists"
        val target = shapes.getOrNull(targetIdx) ?: return "That line no longer exists"
        val inter = lineIntersection(boundary, target) ?: return "Those lines are parallel — nothing to extend to"
        val a = Offset(target.x1, target.y1); val b = Offset(target.x2, target.y2)
        val tTap = projectParam(a, b, tap)
        val tInt = projectParam(a, b, inter)
        val extendEnd2 = tTap > 0.5f
        val validExtension = if (extendEnd2) tInt > 1f else tInt < 0f
        if (!validExtension) return "That boundary doesn't lie beyond the tapped end"
        pushUndo()
        shapes[targetIdx] = if (extendEnd2) target.copy(x2 = inter.x, y2 = inter.y, confirmed = false, realLength = 0.0)
        else target.copy(x1 = inter.x, y1 = inter.y, confirmed = false, realLength = 0.0)
        return null
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
                x1 = t1.x, y1 = t1.y, x2 = t2.x, y2 = t2.y, color = l1.color
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
            realLength = realLen.coerceAtLeast(0.0), confirmed = true, color = currentColor?.toArgb()
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
                    realLength = segs[i].value, confirmed = true, color = currentColor?.toArgb())
            )
            val seg = b - a
            val len = hypotF(seg.x, seg.y)
            if (len < 1e-3f) continue
            val d = Offset(seg.x / len, seg.y / len)
            val n = Offset(-d.y, d.x) // inward normal, for a clockwise R/B/L/T-ordered wall walk
            val innerA = Offset(a.x + wallPx * (n.x - d.x), a.y + wallPx * (n.y - d.y))
            val innerB = Offset(b.x + wallPx * (n.x + d.x), b.y + wallPx * (n.y + d.y))
            newShapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = innerA.x, y1 = innerA.y, x2 = innerB.x, y2 = innerB.y, color = currentColor?.toArgb()))
        }
        pushUndo()
        shapes.addAll(newShapes)
        return null
    }

    /**
     * Reads LINE/CIRCLE/ARC/TEXT entities from a picked .dxf file (the same subset DxfWriter
     * produces) and places them onto the canvas, scaled to fit and Y-flipped from DXF's Y-up
     * convention into this editor's Y-down one. Confirmed LINE lengths come straight from the
     * file's own coordinates, so re-exporting keeps the original real-world dimensions exactly.
     * Anything DxfReader doesn't understand (LWPOLYLINE, BLOCK/INSERT, ...) is just skipped.
     */
    fun importDxf(uri: Uri) {
        val tempFile = SketchAttachmentStore.newFile(context, "import", "dxf")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) { dxfImportMessage = "Couldn't read that file"; return }

        val outcome = runCatching { DxfReader.read(tempFile) }
        SketchAttachmentStore.delete(tempFile.absolutePath)
        if (outcome.exceptionOrNull() is com.sketchdxf.app.dxf.DxfReader.TooLargeException) {
            dxfImportMessage = "That file is too large to import on this device — try a smaller/simplified DXF"
            return
        }
        val result = outcome.getOrNull()
        if (result == null) { dxfImportMessage = "That file doesn't look like a valid DXF"; return }
        if (result.usedBlocks.isNotEmpty()) {
            // Every INSERT is already flattened into result.shapes so the drawing looks right
            // immediately — this additionally saves each referenced block into the app's own
            // Block library (pxPerMm = 1f: these coordinates already are millimetres) so it can
            // be reused elsewhere, same as a block saved by hand with "Save Block".
            scope.launch {
                result.usedBlocks.forEach { b ->
                    dao.insertBlock(
                        SketchBlock(
                            name = b.name, category = "Imported",
                            createdAt = System.currentTimeMillis(),
                            shapesData = SketchBlockCodec.serialize(b.entities), pxPerMm = 1f
                        )
                    )
                }
                savedBlocks = dao.allBlocks()
            }
        }
        if (result.shapes.isEmpty()) {
            dxfImportMessage = if (result.skippedTypes.isNotEmpty())
                "No LINE/CIRCLE/ARC/TEXT/LWPOLYLINE entities found — only ${result.skippedTypes.joinToString()}, which isn't supported yet"
            else "No entities found in that file"
            return
        }

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        result.shapes.forEach { s ->
            minX = minOf(minX, s.x1, s.x2, s.cx - s.r); maxX = maxOf(maxX, s.x1, s.x2, s.cx + s.r)
            minY = minOf(minY, s.y1, s.y2, s.cy - s.r); maxY = maxOf(maxY, s.y1, s.y2, s.cy + s.r)
            if (s.kind == ShapeKind.POLYLINE) {
                SketchPath.parse(s.path).forEach { (x, y) ->
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
        }
        val spanX = (maxX - minX).coerceAtLeast(1f); val spanY = (maxY - minY).coerceAtLeast(1f)
        val cw = canvasSize.width.toFloat().takeIf { it > 0f } ?: 800f
        val ch = canvasSize.height.toFloat().takeIf { it > 0f } ?: 1000f
        val fitScale = minOf((cw * 0.85f) / spanX, (ch * 0.85f) / spanY)
        val offX = (cw - spanX * fitScale) / 2f
        val offY = (ch - spanY * fitScale) / 2f
        // DXF is Y-up; the canvas is Y-down — flip around the imported content's own top edge.
        fun mapX(x: Float) = offX + (x - minX) * fitScale
        fun mapY(y: Float) = offY + (maxY - y) * fitScale

        val placed = result.shapes.map { s ->
            when (s.kind) {
                ShapeKind.CIRCLE -> s.copy(cx = mapX(s.cx), cy = mapY(s.cy), r = s.r * fitScale)
                ShapeKind.TEXT -> s.copy(x1 = mapX(s.x1), y1 = mapY(s.y1))
                ShapeKind.ARC -> s.copy(
                    cx = mapX(s.cx), cy = mapY(s.cy), r = s.r * fitScale,
                    x1 = mapX(s.x1), y1 = mapY(s.y1), x2 = mapX(s.x2), y2 = mapY(s.y2)
                )
                ShapeKind.POLYLINE -> s.copy(
                    path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> mapX(x) to mapY(y) })
                )
                else -> { // LINE
                    val realLenMm = hypot((s.x2 - s.x1).toDouble(), (s.y2 - s.y1).toDouble())
                    s.copy(
                        x1 = mapX(s.x1), y1 = mapY(s.y1), x2 = mapX(s.x2), y2 = mapY(s.y2),
                        realLength = realLenMm, confirmed = realLenMm > 0.01
                    )
                }
            }
        }
        pushUndo()
        shapes.addAll(placed)
        dxfImportMessage = if (result.skippedTypes.isNotEmpty())
            "Imported ${placed.size} shape(s) — skipped unsupported entity types: ${result.skippedTypes.joinToString()}"
        else "Imported ${placed.size} shape(s)"
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

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close without saving?") },
            text = { Text("Any changes since your last Save will be lost.") },
            confirmButton = {
                TextButton(onClick = { showCloseConfirm = false; onBack() }) {
                    Text("Close", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") } }
        )
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
            showFontSize = true,
            unitLabel = unit,
            onConfirm = { text, _, fontSizeMm, _, _ ->
                if (text.isNotBlank()) {
                    pushUndo()
                    shapes.add(SketchShape(workId = 0, kind = ShapeKind.TEXT, x1 = pos.x, y1 = pos.y, label = text, color = currentColor?.toArgb(), fontSize = fontSizeMm))
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
                // Chain mode armed the next line's start at this line's rough tapped endpoint —
                // now that an exact length/angle has moved the real endpoint (often much further
                // away), the chained continuation needs to follow it, or the next line would
                // silently start from the wrong, stale point.
                if (chainOn) lineStartPoint = newEnd
                ensurePointVisible(newEnd)
                if (wallModeOn) addWallOuterLine(pendingLengthIndex)
                pendingLengthIndex = -1
            },
            onUseAsIs = {
                if (wallModeOn) addWallOuterLine(pendingLengthIndex)
                pendingLengthIndex = -1
            },
            onCancel = {
                if (pendingLengthIndex in shapes.indices) shapes.removeAt(pendingLengthIndex)
                if (undoStack.isNotEmpty()) undoStack.removeAt(undoStack.lastIndex)
                pendingLengthIndex = -1
            }
        )
    }
    if (showWallThicknessDialog) {
        WallThicknessDialog(
            initial = wallThickness?.let { trimNum(mmToDisplay(it, unit)) } ?: "",
            unitLabel = unit,
            onConfirm = { display ->
                wallThickness = displayToMm(display.toDouble(), unit)
                wallModeOn = true
                lastWallInnerEnd = null; lastWallOuterIndex = -1
                showWallThicknessDialog = false
            },
            onCancel = { showWallThicknessDialog = false }
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
            initialText = trimNum(mmToDisplay(measuredMm.toDouble(), unit)),
            unitLabel = unit,
            onConfirm = { text, fontSizeMm, offsetPx ->
                pushUndo()
                shapes.add(
                    SketchShape(
                        workId = 0, kind = ShapeKind.DIMENSION, x1 = p1.x, y1 = p1.y, x2 = p2.x, y2 = p2.y,
                        label = text, color = currentColor?.toArgb(), fontSize = fontSizeMm, dimOffset = offsetPx
                    )
                )
                pendingDimension = null
            },
            onCancel = { pendingDimension = null }
        )
    }
    pendingRoomCalibrate?.let { idx ->
        if (idx in shapes.indices) {
            val line = shapes[idx]
            val lenPx = hypotF(line.x2 - line.x1, line.y2 - line.y1)
            val asTappedMm = lenPx / currentPxPerMm()
            RoomCalibrateDialog(
                asTappedDisplay = mmToDisplay(asTappedMm.toDouble(), unit).toFloat(),
                unitLabel = unit,
                onConfirm = { value ->
                    val mm = displayToMm(value, unit)
                    shapes[idx] = shapes[idx].copy(confirmed = true, realLength = mm)
                    pendingRoomCalibrate = null
                    fitToScreen()
                },
                onSkip = {
                    pendingRoomCalibrate = null
                    fitToScreen()
                }
            )
        } else {
            pendingRoomCalibrate = null
        }
    }
    pendingDistancePx?.let { px ->
        DistanceCalibrationDialog(
            measuredPx = px,
            unitLabel = unit,
            onConfirm = { realValueDisplay ->
                val realMm = displayToMm(realValueDisplay.toDouble(), unit)
                if (realMm > 0.01) calibrationRatio = px / realMm.toFloat()
                pendingDistancePx = null
            },
            onDismiss = { pendingDistancePx = null }
        )
    }
    if (showSaveBlockDialog) {
        SaveBlockDialog(
            existingCategories = savedBlocks.map { it.category }.distinct(),
            onConfirm = { blockName, category ->
                val targets = selectedIndices.mapNotNull { shapes.getOrNull(it) }
                if (targets.isNotEmpty()) {
                    val minX = targets.minOf { shapeBounds(it).left }
                    val minY = targets.minOf { shapeBounds(it).top }
                    val local = targets.map { translateShape(it, -minX, -minY) }
                    val data = SketchBlockCodec.serialize(local)
                    val pxPerMm = currentPxPerMm()
                    scope.launch {
                        dao.insertBlock(
                            SketchBlock(
                                name = blockName, category = category.ifBlank { "General" },
                                createdAt = System.currentTimeMillis(), shapesData = data, pxPerMm = pxPerMm
                            )
                        )
                        savedBlocks = dao.allBlocks()
                    }
                }
                showSaveBlockDialog = false
            },
            onCancel = { showSaveBlockDialog = false }
        )
    }
    if (showGroupWidthDialog) {
        GroupWidthDialog(
            onConfirm = { widthPx -> applyGroupWidth(widthPx); showGroupWidthDialog = false },
            onCancel = { showGroupWidthDialog = false }
        )
    }
    if (showBlockPicker) {
        BlockPickerDialog(
            blocks = savedBlocks,
            insertUseRatio = insertUseRatio,
            onToggleUseRatio = { insertUseRatio = !insertUseRatio },
            onPick = { block ->
                pendingBlockInsert = block
                tool = Tool.BLOCK
                showBlockPicker = false
            },
            onDelete = { block ->
                scope.launch { dao.deleteBlock(block); savedBlocks = dao.allBlocks() }
            },
            onDismiss = { showBlockPicker = false }
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
    dxfImportMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { dxfImportMessage = null },
            title = { Text("Import DXF") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { dxfImportMessage = null }) { Text("OK") } }
        )
    }
    if (showColorPicker) {
        ColorPickerDialog(
            current = currentColor,
            onPick = { currentColor = it },
            onDismiss = { showColorPicker = false }
        )
    }

    val dxfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDxf(uri)
    }

    fun runCommand(raw: String) {
        val transparent = raw.trim().startsWith("'")
        val cmd = raw.trim().removePrefix("'").uppercase()
        commandInput = ""
        if (cmd.isBlank()) return
        // EXPLODE (AutoCAD's own "X" alias) acts on whatever's already selected, same as
        // AutoCAD itself — it must run before resetToolState() below, which would otherwise
        // clear that exact selection out from under it before the command ever sees it.
        if (cmd == "X" || cmd == "EXPLODE") {
            val before = selectedIndices.size
            explodeSelection()
            commandFeedback = if (before == 0) "Nothing selected to explode" else null
            return
        }
        // A normal command replaces whatever's active, same as AutoCAD starting a new command;
        // a transparent one leaves the current tool/in-progress points alone.
        if (!transparent) resetToolState()
        var recognized = true
        when (cmd) {
            "L", "LINE" -> tool = Tool.LINE
            "REC", "RECT", "RECTANGLE" -> tool = Tool.RECTANGLE
            "C", "CIRCLE" -> tool = Tool.CIRCLE
            "T", "TEXT", "TXT" -> tool = Tool.TEXT
            "DIM", "DIMENSION" -> tool = Tool.DIMENSION
            "O", "OFFSET" -> tool = Tool.OFFSET
            "TR", "TRIM" -> tool = Tool.TRIM
            "EX", "EXTEND" -> tool = Tool.EXTEND
            "A", "ARC" -> tool = Tool.ARC
            "DI", "DIST", "DISTANCE" -> tool = Tool.DISTANCE
            "P", "PAN" -> tool = Tool.PAN
            "FH", "FREEHAND", "PENCIL" -> tool = Tool.FREEHAND
            "SEL", "SELECT", "S" -> tool = Tool.SELECT
            "BOX", "WIN", "WINDOW" -> tool = Tool.BOX_SELECT
            "BR", "BREAK" -> tool = Tool.BREAK
            "F", "FILLET" -> tool = Tool.FILLET
            "STR", "STRETCH" -> tool = Tool.STRETCH
            "RP", "ROOMPLAN" -> showRoomPlan = true
            "COL", "COLOR", "COLOUR" -> showColorPicker = true
            "U", "UNDO" -> undo()
            "RE", "REDO" -> redo()
            "SAVE" -> save()
            "DXFIN", "IMPORT" -> dxfPicker.launch(arrayOf("*/*"))
            "ORTHO" -> orthoOn = !orthoOn
            "SNAP" -> snapOn = !snapOn
            "CANCEL", "ESC" -> resetToolState()
            else -> recognized = false
        }
        commandFeedback = if (recognized) null else "Unknown command: $cmd"
    }

    Scaffold(
        topBar = {
            if (!fullscreenCanvas) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = { IconButton(onClick = { showCloseConfirm = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { dxfPicker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.UploadFile, "Import DXF") }
                        IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Icon(Icons.Filled.Undo, "Undo") }
                        IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Icon(Icons.Filled.Redo, "Redo") }
                        IconButton(onClick = { fitToScreen() }) { Icon(Icons.Filled.FitScreen, "Fit all shapes on screen") }
                        IconButton(onClick = { fullscreenCanvas = true }) { Icon(Icons.Filled.Fullscreen, "Fullscreen canvas") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(if (fullscreenCanvas) PaddingValues(0.dp) else pad).padding(if (fullscreenCanvas) 0.dp else 12.dp)) {
            if (!fullscreenCanvas) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close/Save lead the same scrolling row as the tools instead of sitting on their
                // own row underneath — one row of vertical space back for the canvas.
                IconButton(onClick = { showCloseConfirm = true }, enabled = !busy) { Icon(Icons.Filled.Close, "Cancel") }
                IconButton(onClick = { save() }, enabled = !busy && loaded) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Save, "Save")
                }
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
                FilterChip(selected = tool == Tool.EXTEND, onClick = {
                    if (tool == Tool.EXTEND) resetToolState()
                    tool = Tool.EXTEND
                }, label = { Text("Extend") })
                FilterChip(selected = tool == Tool.ARC, onClick = {
                    tool = Tool.ARC; resetToolState()
                }, label = { Text("Arc") })
                FilterChip(selected = tool == Tool.DISTANCE, onClick = {
                    tool = Tool.DISTANCE; resetToolState()
                }, label = { Text("Distance") })
                FilterChip(selected = tool == Tool.PAN, onClick = {
                    tool = Tool.PAN; resetToolState()
                }, label = { Text("Pan/Zoom") })
                FilterChip(selected = tool == Tool.FREEHAND, onClick = {
                    tool = Tool.FREEHAND; resetToolState()
                }, label = { Text("Pencil") })
                FilterChip(selected = tool == Tool.BOX_SELECT, onClick = {
                    tool = Tool.BOX_SELECT; resetToolState()
                }, label = { Text("Select") })
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
                FilterChip(selected = tool == Tool.BLOCK, onClick = { showBlockPicker = true },
                    label = { Text("Block") })
                FilterChip(
                    selected = false, onClick = { showColorPicker = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(16.dp).clip(CircleShape)
                                    .background(currentColor ?: Color.LightGray)
                                    .border(1.dp, Color.Gray, CircleShape)
                            )
                            Text("Colour", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                )
            }
            if (tool == Tool.BOX_SELECT && selectedIndices.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedIndices.size} selected", style = MaterialTheme.typography.bodySmall)
                    FilterChip(
                        selected = moveModeActive,
                        onClick = { moveModeActive = !moveModeActive; if (moveModeActive) copyModeActive = false },
                        label = { Text("Move") }
                    )
                    FilterChip(
                        selected = copyModeActive,
                        onClick = { copyModeActive = !copyModeActive; if (copyModeActive) moveModeActive = false },
                        label = { Text("Copy") }
                    )
                    FilterChip(selected = false, onClick = { deleteSelection() }, label = { Text("Delete") })
                    if (selectedIndices.any { shapes.getOrNull(it)?.kind == ShapeKind.FREEHAND }) {
                        FilterChip(
                            selected = false, onClick = { convertFreehandSelection() },
                            label = { Text("Polyline") }
                        )
                    }
                    if (selectedIndices.any { shapes.getOrNull(it)?.kind == ShapeKind.POLYLINE }) {
                        FilterChip(selected = false, onClick = { explodeSelection() }, label = { Text("Explode") })
                    }
                    if (selectedIndices.any { shapes.getOrNull(it)?.kind == ShapeKind.FREEHAND || shapes.getOrNull(it)?.kind == ShapeKind.POLYLINE }) {
                        FilterChip(selected = false, onClick = { smoothSelection() }, label = { Text("Smooth") })
                    }
                    FilterChip(selected = false, onClick = { showGroupWidthDialog = true }, label = { Text("Width") })
                    FilterChip(selected = false, onClick = { showSaveBlockDialog = true }, label = { Text("Save Block") })
                    FilterChip(
                        selected = false,
                        onClick = { selectedIndices.clear(); moveModeActive = false; copyModeActive = false },
                        label = { Text("Clear") }
                    )
                }
            }
            if (tool == Tool.LINE || tool == Tool.RECTANGLE || tool == Tool.DIMENSION) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (tool != Tool.DIMENSION) {
                        FilterChip(selected = orthoOn, onClick = { orthoOn = !orthoOn }, label = { Text("Ortho") })
                    }
                    FilterChip(selected = snapOn, onClick = { snapOn = !snapOn }, label = { Text("Snap") })
                    if (tool == Tool.LINE) FilterChip(selected = chainOn, onClick = { chainOn = !chainOn }, label = { Text("Chain") })
                    if (tool == Tool.LINE) {
                        FilterChip(
                            selected = wallModeOn,
                            onClick = {
                                if (wallModeOn) { wallModeOn = false; lastWallInnerEnd = null; lastWallOuterIndex = -1 }
                                else showWallThicknessDialog = true
                            },
                            label = { Text(if (wallModeOn) "Wall: ${trimNum(mmToDisplay(wallThickness ?: 0.0, unit))}$unit" else "Wall") }
                        )
                    }
                    if (tool == Tool.DIMENSION) {
                        FilterChip(selected = dimMode == DimMode.ALIGNED, onClick = { dimMode = DimMode.ALIGNED }, label = { Text("Aligned") })
                        FilterChip(selected = dimMode == DimMode.LINEAR_H, onClick = { dimMode = DimMode.LINEAR_H }, label = { Text("Linear H") })
                        FilterChip(selected = dimMode == DimMode.LINEAR_V, onClick = { dimMode = DimMode.LINEAR_V }, label = { Text("Linear V") })
                    }
                    if (calibrationRatio != null) {
                        FilterChip(
                            selected = useCalibrationRatio, onClick = { useCalibrationRatio = !useCalibrationRatio },
                            label = { Text("Use scale") }
                        )
                    }
                }
            }
            if (tool == Tool.FREEHAND) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = freehandRoomMode, onClick = { freehandRoomMode = !freehandRoomMode },
                        label = { Text("Room") }
                    )
                }
            }
            calibrationRatio?.let { r ->
                val pxPerDisplayUnit = r * displayToMm(1.0, unit)
                Text(
                    "Scale set from Distance: 1$unit = ${trimNum(pxPerDisplayUnit)}px" +
                        if (useCalibrationRatio) "" else " (currently off)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                when {
                    tool == Tool.SELECT -> "Tap a shape to edit it, or drag an end-point grip to reshape it"
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
                    tool == Tool.EXTEND && extendMessage != null -> extendMessage!!
                    tool == Tool.EXTEND && extendBoundaryIndex < 0 -> "Tap the boundary line"
                    tool == Tool.EXTEND -> "Tap a line near the end that should reach the boundary"
                    tool == Tool.ARC && arcP1 == null -> "Tap the arc's start point"
                    tool == Tool.ARC && arcP2 == null -> "Tap a point the arc should pass through"
                    tool == Tool.ARC -> "Tap the arc's end point"
                    tool == Tool.DISTANCE && distanceStart == null -> "Tap the first point of a known distance (e.g. on the background image)"
                    tool == Tool.DISTANCE -> "Tap the second point"
                    tool == Tool.BLOCK && pendingBlockInsert == null -> "Pick a block from the picker"
                    tool == Tool.BLOCK -> "Tap where to drop '${pendingBlockInsert?.name}'"
                    tool == Tool.FREEHAND && freehandRoomMode -> "Drag to sketch a room — it's auto-straightened into walls, then asks for the top wall's real length"
                    tool == Tool.FREEHAND -> "Drag to draw a freehand stroke"
                    tool == Tool.BOX_SELECT && moveModeActive -> "Drag anywhere to move the selection, then release" + if (snapOn) " (snaps to nearby points)" else ""
                    tool == Tool.BOX_SELECT && copyModeActive -> "Drag to where the copy should go, then release" + if (snapOn) " (snaps to nearby points)" else ""
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
            }

            Box(
                (if (fullscreenCanvas) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                    // Zoomed-in content is scaled via graphicsLayer below, which doesn't clip by
                    // default — without this, zooming in pushes the (invisible) touch region of
                    // the canvas up over the toolbar, and its buttons stop receiving taps.
                    .clipToBounds()
                    .background(Color.White)
                    .border(1.dp, Color(0xFF9E9E9E))
                    .onSizeChanged { canvasSize = it }
                    // Exclude the canvas's own on-screen area from Android's edge-swipe-back
                    // gesture, so dragging a line/rectangle to a corner near the left or right
                    // border isn't intercepted by the system before this app ever sees it.
                    .onGloballyPositioned { coords ->
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val b = coords.boundsInWindow()
                            androidView.systemGestureExclusionRects = listOf(
                                android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                            )
                        }
                    }
                    .pointerInput(tool) {
                        // Two-finger pinch/pan works underneath every tool, like Ortho/Snap; the
                        // dedicated Pan/Zoom tool additionally allows a single finger to drag it.
                        detectPanOrZoom(requireTwoFingers = tool != Tool.PAN) { pan, zoom ->
                            viewScale = (viewScale * zoom).coerceIn(0.02f, 6f)
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
                    // The canvas below draws in its own fixed local coordinate space (the same
                    // space shapes are stored in); the Box above it uniformly scales/pans that
                    // whole space for display via graphicsLayer. Touches delivered to a
                    // pointerInput nested inside that transform come through as raw, untransformed
                    // screen coordinates rather than being converted back into local space — so
                    // without correcting for it, every tool can only ever reach whatever was
                    // visible at viewScale=1 / viewOffset=(0,0), no matter how far you zoom or pan
                    // afterward.
                    fun toContentSpace(raw: Offset): Offset =
                        Offset((raw.x - viewOffset.x) / viewScale, (raw.y - viewOffset.y) / viewScale)
                    // Hand-rolled replacements for Foundation's detectTapGestures/detectDragGestures
                    // (same call signatures, so no tool-specific logic below needed to change) that
                    // fix a second, subtler bug on top of the content-space conversion above: the
                    // stock detectors only ever track the FIRST pointer that went down, so releasing
                    // a two-finger pinch-zoom one finger at a time — the normal way to end a pinch —
                    // has that first finger's own eventual lift look, to the stock detector, exactly
                    // like an ordinary single-finger tap/drag. That fired a tool action wherever that
                    // finger of the *pinch* happened to be, not at the user's actual next, deliberate
                    // tap — this is what "zoom, then tap to draw, and it draws somewhere else" was.
                    // Fix: refuse to fire at all once a second pointer joins partway through.
                    suspend fun PointerInputScope.detectTapGestures(onTap: (Offset) -> Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var multiTouch = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) multiTouch = true
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) return@awaitEachGesture
                                if (!change.pressed) {
                                    if (!multiTouch) onTap(toContentSpace(change.position))
                                    break
                                }
                            }
                        }
                    }
                    suspend fun PointerInputScope.detectDragGestures(
                        onDragStart: (Offset) -> Unit = {},
                        onDrag: (Offset) -> Unit,
                        onDragEnd: () -> Unit = {}
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var multiTouch = false
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) multiTouch = true
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (multiTouch || change.isConsumed) {
                                    if (dragging) onDragEnd()
                                    return@awaitEachGesture
                                }
                                if (change.positionChanged()) {
                                    if (!dragging) { dragging = true; onDragStart(toContentSpace(change.position)) }
                                    onDrag(toContentSpace(change.position))
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    if (dragging) onDragEnd()
                                    break
                                }
                            }
                        }
                    }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(tool, orthoOn, snapOn, moveModeActive, copyModeActive, stretchArmed) {
                            when (tool) {
                                Tool.CIRCLE -> detectDragGestures(
                                    onDragStart = { p -> dragStart = p; dragCurrent = p },
                                    onDrag = { p -> dragCurrent = p },
                                    onDragEnd = {
                                        val s = dragStart; val c = dragCurrent
                                        if (s != null && c != null) {
                                            val len = hypotF(c.x - s.x, c.y - s.y)
                                            if (len > 12f) {
                                                pushUndo()
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.CIRCLE, cx = s.x, cy = s.y, r = len, color = currentColor?.toArgb()))
                                            }
                                        }
                                        dragStart = null; dragCurrent = null
                                    }
                                )
                                Tool.RECTANGLE -> detectDragGestures(
                                    onDragStart = { p -> dragStart = trySnapPoint(p); dragCurrent = dragStart },
                                    onDrag = { p -> dragCurrent = p },
                                    onDragEnd = {
                                        val s = dragStart; val c0 = dragCurrent
                                        if (s != null && c0 != null) {
                                            val c = trySnapPoint(c0)
                                            if (hypotF(c.x - s.x, c.y - s.y) > 8f) {
                                                pushUndo()
                                                val p2 = Offset(c.x, s.y); val p4 = Offset(s.x, c.y)
                                                val rectColor = currentColor?.toArgb()
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = s.x, y1 = s.y, x2 = p2.x, y2 = p2.y, color = rectColor))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = p2.x, y1 = p2.y, x2 = c.x, y2 = c.y, color = rectColor))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = c.x, y1 = c.y, x2 = p4.x, y2 = p4.y, color = rectColor))
                                                shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = p4.x, y1 = p4.y, x2 = s.x, y2 = s.y, color = rectColor))
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
                                            shapes.add(SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = start.x, y1 = start.y, x2 = end.x, y2 = end.y, confirmed = false, color = currentColor?.toArgb()))
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
                                Tool.ARC -> detectTapGestures(onTap = { p ->
                                    val snapped = trySnapPoint(p)
                                    val p1 = arcP1; val p2 = arcP2
                                    when {
                                        p1 == null -> arcP1 = snapped
                                        p2 == null -> arcP2 = snapped
                                        else -> {
                                            val center = SketchArc.circumcenter(p1.x, p1.y, p2.x, p2.y, snapped.x, snapped.y)
                                            if (center == null) {
                                                commandFeedback = "Those 3 points are in a line — pick a different middle point"
                                            } else {
                                                val (cx, cy) = center
                                                val r = hypotF(p1.x - cx, p1.y - cy)
                                                val major = !SketchArc.minorArcContains(cx, cy, p1.x, p1.y, snapped.x, snapped.y, p2.x, p2.y)
                                                pushUndo()
                                                shapes.add(
                                                    SketchShape(
                                                        workId = 0, kind = ShapeKind.ARC, cx = cx, cy = cy, r = r,
                                                        x1 = p1.x, y1 = p1.y, x2 = snapped.x, y2 = snapped.y,
                                                        major = major, color = currentColor?.toArgb()
                                                    )
                                                )
                                            }
                                            arcP1 = null; arcP2 = null
                                        }
                                    }
                                })
                                Tool.DISTANCE -> detectTapGestures(onTap = { p ->
                                    val start = distanceStart
                                    if (start == null) {
                                        distanceStart = p
                                    } else {
                                        pendingDistancePx = hypotF(p.x - start.x, p.y - start.y)
                                        distanceStart = null
                                    }
                                })
                                Tool.BLOCK -> detectTapGestures(onTap = { p ->
                                    val block = pendingBlockInsert
                                    if (block != null) {
                                        val local = SketchBlockCodec.deserialize(block.shapesData)
                                        val factor = if (insertUseRatio && block.pxPerMm > 0f) currentPxPerMm() / block.pxPerMm else 1f
                                        pushUndo()
                                        shapes.addAll(local.map { translateShape(scaleShape(it, factor), p.x, p.y) })
                                        // One insert per pick, not one per tap — arm the picker again
                                        // (tap "Block") for another copy instead of it repeating on
                                        // every further tap.
                                        pendingBlockInsert = null
                                        tool = Tool.SELECT
                                    }
                                })
                                Tool.SELECT -> detectDragGestures(
                                    onDragStart = { p ->
                                        val grip = hitTestGrip(p)
                                        if (grip != null) {
                                            pushUndo()
                                            gripDragIndex = grip.first; gripDragPart = grip.second
                                        } else {
                                            selectTapStart = p
                                        }
                                    },
                                    onDrag = { p ->
                                        val idx = gripDragIndex
                                        if (idx >= 0) {
                                            val np = trySnapPoint(p)
                                            val s = shapes[idx]
                                            // Reshaping an endpoint by hand invalidates any previously
                                            // confirmed/typed real-world length, same as Trim/Extend/Break.
                                            shapes[idx] = if (gripDragPart == 1) {
                                                s.copy(x1 = np.x, y1 = np.y, confirmed = false, realLength = 0.0)
                                            } else {
                                                s.copy(x2 = np.x, y2 = np.y, confirmed = false, realLength = 0.0)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (gripDragIndex < 0) {
                                            // No grip was grabbed at drag-start: treat this as a tap on
                                            // the shape's body, same as before grips existed.
                                            selectTapStart?.let { p ->
                                                val idx = hitTest(p)
                                                if (idx >= 0) editingIndex = idx
                                            }
                                        }
                                        gripDragIndex = -1; gripDragPart = 0; selectTapStart = null
                                    }
                                )
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
                                Tool.EXTEND -> detectTapGestures(onTap = { p ->
                                    if (extendBoundaryIndex < 0) {
                                        val idx = hitTestLine(p)
                                        if (idx >= 0) { extendBoundaryIndex = idx; extendMessage = null }
                                    } else {
                                        val idx = hitTestLine(p)
                                        if (idx >= 0 && idx != extendBoundaryIndex) {
                                            extendMessage = performExtend(extendBoundaryIndex, idx, p)
                                        }
                                    }
                                })
                                Tool.PAN -> {}
                                Tool.FREEHAND -> detectDragGestures(
                                    onDragStart = { p -> freehandPoints.clear(); freehandPoints.add(p) },
                                    onDrag = { p -> freehandPoints.add(p) },
                                    onDragEnd = {
                                        if (freehandPoints.size >= 2) {
                                            if (freehandRoomMode) {
                                                finishFreehandRoom(freehandPoints.toList())
                                            } else {
                                                pushUndo()
                                                shapes.add(
                                                    SketchShape(
                                                        workId = 0, kind = ShapeKind.FREEHAND,
                                                        path = SketchPath.serialize(freehandPoints.map { it.x to it.y }),
                                                        color = currentColor?.toArgb()
                                                    )
                                                )
                                            }
                                        }
                                        freehandPoints.clear()
                                    }
                                )
                                Tool.BOX_SELECT -> if (moveModeActive || copyModeActive) {
                                    // Armed by the Move or Copy action: this drag supplies the placement —
                                    // Move translates the selection, Copy leaves the originals and adds
                                    // translated duplicates instead. While dragging, the nearest existing
                                    // point (if Snap is on) is highlighted and used as the actual drop point.
                                    detectDragGestures(
                                        onDragStart = { p -> moveDragStart = p; moveDragCurrent = p; moveSnapTarget = null },
                                        onDrag = { p ->
                                            moveDragCurrent = p
                                            moveSnapTarget = findSnapPoint(p, selectedIndices)
                                        },
                                        onDragEnd = {
                                            val s = moveDragStart; val c = moveSnapTarget ?: moveDragCurrent
                                            if (s != null && c != null) {
                                                val dx = c.x - s.x; val dy = c.y - s.y
                                                if (hypotF(dx, dy) > 2f) {
                                                    pushUndo()
                                                    if (copyModeActive) {
                                                        val copies = selectedIndices.sorted().map { translateShape(shapes[it], dx, dy) }
                                                        selectedIndices.clear()
                                                        copies.forEach { shapes.add(it); selectedIndices.add(shapes.lastIndex) }
                                                    } else {
                                                        selectedIndices.forEach { idx -> shapes[idx] = translateShape(shapes[idx], dx, dy) }
                                                    }
                                                }
                                            }
                                            moveDragStart = null; moveDragCurrent = null; moveSnapTarget = null
                                            moveModeActive = false; copyModeActive = false
                                        }
                                    )
                                } else {
                                    detectDragGestures(
                                        onDragStart = { p -> selectDragStart = p; selectDragCurrent = p },
                                        onDrag = { p -> selectDragCurrent = p },
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
                                        onDrag = { p -> stretchDragCurrent = p },
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
                        // Every width/radius/text-size literal below is in this canvas's own local
                        // (content) space, which then gets uniformly scaled by viewScale for display
                        // (see the graphicsLayer this Canvas sits inside). At a normal zoom that's fine,
                        // but after zooming way out — e.g. clicking Fit Screen right after a line ends
                        // up huge — a small fixed local width shrinks to sub-pixel on screen and
                        // effectively disappears, while a freshly drawn shape still gets its correct
                        // on-screen length/position (that part of the transform is just a translation +
                        // uniform scale of two endpoints, always faithful). That combination makes newly
                        // drawn geometry look like it never happened. minPx floors a size to a minimum
                        // that stays visible on screen at any zoom level.
                        val invViewScale = if (viewScale > 0.0001f) 1f / viewScale else 1f
                        fun minPx(screenPx: Float) = screenPx * invViewScale
                        // A shape's own explicit colour (if the user picked one) always wins over the
                        // kind's usual default; the transient highlight (active tool selection) wins over both.
                        fun shapeColor(s: SketchShape, default: Color, isHighlighted: Boolean): Color =
                            if (isHighlighted) highlightPaint else s.color?.let { Color(it) } ?: default
                        // A shape's own explicit width (if set) always wins over the kind's usual
                        // default; highlighting still adds its usual +2px on top either way, so a
                        // custom-width shape still reads as selected the same as any other. Always at
                        // least ~1.5 screen px so it can't vanish when zoomed far out.
                        fun strokeW(s: SketchShape, default: Float, isHighlighted: Boolean): Float {
                            val base = maxOf(if (s.strokeWidth > 0f) s.strokeWidth else default, minPx(1.5f))
                            return if (isHighlighted) base + 2f else base
                        }
                        shapes.forEachIndexed { i, s ->
                            val isHighlighted = i == offsetLineIndex || i == trimBoundaryIndex || i == trimTargetIndex ||
                                i == breakLineIndex || i == filletIndex1 || i == filletIndex2 || i == extendBoundaryIndex || i in selectedIndices
                            when (s.kind) {
                                ShapeKind.LINE -> {
                                    // Confirmed lines just turn green — no automatic length label; a
                                    // real-world size only ever appears where you place it by hand
                                    // with the Dimension tool.
                                    val lineColor = shapeColor(s, if (s.confirmed) Color(0xFF2E7D32) else linePaint, isHighlighted)
                                    val w = strokeW(s, 5f, isHighlighted)
                                    drawLine(lineColor, Offset(s.x1, s.y1), Offset(s.x2, s.y2), strokeWidth = w)
                                }
                                ShapeKind.CIRCLE -> drawCircle(
                                    shapeColor(s, linePaint, isHighlighted), radius = s.r, center = Offset(s.cx, s.cy),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW(s, 5f, isHighlighted))
                                )
                                ShapeKind.TEXT -> drawContext.canvas.nativeCanvas.drawText(
                                    s.label, s.x1, s.y1,
                                    android.graphics.Paint().apply {
                                        color = if (isHighlighted) 0xFFE65100.toInt() else (s.color ?: 0xFF6A1B9A.toInt())
                                        textSize = maxOf(
                                            if (s.fontSize > 0f) (s.fontSize * currentPxPerMm()).coerceAtLeast(8f) else 34f,
                                            minPx(10f)
                                        )
                                    }
                                )
                                ShapeKind.FREEHAND, ShapeKind.POLYLINE -> {
                                    val strokeColor = shapeColor(s, linePaint, isHighlighted)
                                    val strokeWidthPx = strokeW(s, 4f, isHighlighted)
                                    SketchPath.parse(s.path).zipWithNext { a, b ->
                                        drawLine(strokeColor, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = strokeWidthPx)
                                    }
                                }
                                ShapeKind.ARC -> {
                                    val (startDeg, sweepDeg) = SketchArc.sweepFor(s.cx, s.cy, s.x1, s.y1, s.x2, s.y2, s.major)
                                    drawArc(
                                        color = shapeColor(s, linePaint, isHighlighted),
                                        startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
                                        topLeft = Offset(s.cx - s.r, s.cy - s.r),
                                        size = androidx.compose.ui.geometry.Size(s.r * 2f, s.r * 2f),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW(s, 5f, isHighlighted))
                                    )
                                }
                                ShapeKind.DIMENSION -> {
                                    val dimColor = shapeColor(s, Color(0xFF6A1B9A), isHighlighted)
                                    val dimW = strokeW(s, 3f, isHighlighted)
                                    val base1 = Offset(s.x1, s.y1); val base2 = Offset(s.x2, s.y2)
                                    val (p1, p2) = dimLineEndpoints(s)
                                    // Extension (witness) lines from the actual measured points out to
                                    // the offset dimension line — only when there's an offset to show.
                                    if (s.dimOffset != 0f) {
                                        val extW = minPx(1.5f)
                                        drawLine(dimColor, base1, p1, strokeWidth = extW)
                                        drawLine(dimColor, base2, p2, strokeWidth = extW)
                                    }
                                    drawLine(dimColor, p1, p2, strokeWidth = dimW)
                                    // small perpendicular ticks at each end, like a dimension line
                                    val dx = p2.x - p1.x; val dy = p2.y - p1.y
                                    val len = hypotF(dx, dy)
                                    if (len > 1e-3f) {
                                        val nx = -dy / len * dimTick; val ny = dx / len * dimTick
                                        drawLine(dimColor, Offset(p1.x - nx, p1.y - ny), Offset(p1.x + nx, p1.y + ny), strokeWidth = dimW)
                                        drawLine(dimColor, Offset(p2.x - nx, p2.y - ny), Offset(p2.x + nx, p2.y + ny), strokeWidth = dimW)
                                    }
                                    if (s.label.isNotBlank()) {
                                        val mx = (p1.x + p2.x) / 2f; val my = (p1.y + p2.y) / 2f
                                        val effTextSize = maxOf(
                                            if (s.fontSize > 0f) (s.fontSize * currentPxPerMm()).coerceAtLeast(10f) else dimTextSize,
                                            minPx(10f)
                                        )
                                        drawContext.canvas.nativeCanvas.drawText(
                                            s.label, mx + 4f, my - 6f,
                                            android.graphics.Paint().apply { color = dimColor.toArgb(); textSize = effTextSize }
                                        )
                                    }
                                }
                            }
                        }
                        val s = dragStart; val c = dragCurrent
                        if (s != null && c != null) {
                            val previewW = minPx(4f)
                            if (tool == Tool.CIRCLE) {
                                drawCircle(Color.Gray, radius = hypotF(c.x - s.x, c.y - s.y), center = s, style = androidx.compose.ui.graphics.drawscope.Stroke(width = previewW))
                            } else if (tool == Tool.RECTANGLE) {
                                val p2 = Offset(c.x, s.y); val p4 = Offset(s.x, c.y)
                                val gray = Color.Gray
                                drawLine(gray, s, p2, strokeWidth = previewW); drawLine(gray, p2, c, strokeWidth = previewW)
                                drawLine(gray, c, p4, strokeWidth = previewW); drawLine(gray, p4, s, strokeWidth = previewW)
                            }
                        }
                        lineStartPoint?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        dimStartPoint?.let { p ->
                            drawCircle(Color(0xFF6A1B9A), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        breakPoint1?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        filletTap1?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        filletTap2?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        stretchBasePoint?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        arcP1?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        arcP2?.let { p ->
                            drawCircle(Color(0xFFE65100), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        distanceStart?.let { p ->
                            drawCircle(Color(0xFF1565C0), radius = maxOf(8f, minPx(4f)), center = p)
                        }
                        if (tool == Tool.FREEHAND && freehandPoints.size >= 2) {
                            val freehandPreviewW = minPx(4f)
                            freehandPoints.zipWithNext { a, b -> drawLine(Color.Gray, a, b, strokeWidth = freehandPreviewW) }
                        }
                        if (tool == Tool.SELECT) {
                            // AutoCAD-style grips: every LINE/DIMENSION endpoint is a small draggable
                            // handle — drag one to reshape just that end instead of moving the whole
                            // shape. The one currently being dragged is drawn larger and filled.
                            val gripHalf = maxOf(6f, minPx(4f))
                            shapes.forEachIndexed { i, s ->
                                if (s.kind == ShapeKind.LINE || s.kind == ShapeKind.DIMENSION) {
                                    listOf(1 to Offset(s.x1, s.y1), 2 to Offset(s.x2, s.y2)).forEach { (part, pt) ->
                                        val active = gripDragIndex == i && gripDragPart == part
                                        val half = if (active) gripHalf * 1.33f else gripHalf
                                        drawRect(
                                            Color(0xFF1565C0),
                                            topLeft = Offset(pt.x - half, pt.y - half),
                                            size = androidx.compose.ui.geometry.Size(half * 2f, half * 2f),
                                            style = if (active) androidx.compose.ui.graphics.drawscope.Fill
                                            else androidx.compose.ui.graphics.drawscope.Stroke(width = minPx(1.5f))
                                        )
                                    }
                                }
                            }
                        }
                        if (tool == Tool.BOX_SELECT) {
                            if (moveModeActive || copyModeActive) {
                                val s2 = moveDragStart; val c2 = moveSnapTarget ?: moveDragCurrent
                                if (s2 != null && c2 != null) {
                                    val dx = c2.x - s2.x; val dy = c2.y - s2.y
                                    val ghostColor = if (copyModeActive) Color(0xFF2E7D32) else Color.Gray
                                    val ghostW = minPx(4f)
                                    selectedIndices.forEach { idx ->
                                        val sh = translateShape(shapes[idx], dx, dy)
                                        when (sh.kind) {
                                            ShapeKind.CIRCLE -> drawCircle(
                                                ghostColor, radius = sh.r, center = Offset(sh.cx, sh.cy),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = ghostW)
                                            )
                                            ShapeKind.FREEHAND, ShapeKind.POLYLINE -> SketchPath.parse(sh.path).zipWithNext { a, b ->
                                                drawLine(ghostColor, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = ghostW)
                                            }
                                            ShapeKind.ARC -> arcPoints(sh).zipWithNext { a, b -> drawLine(ghostColor, a, b, strokeWidth = ghostW) }
                                            ShapeKind.TEXT -> drawCircle(ghostColor, radius = maxOf(6f, minPx(3f)), center = Offset(sh.x1, sh.y1))
                                            else -> drawLine(ghostColor, Offset(sh.x1, sh.y1), Offset(sh.x2, sh.y2), strokeWidth = ghostW)
                                        }
                                    }
                                }
                                // Highlights the existing point a Snap-assisted Move/Copy drag would land on.
                                moveSnapTarget?.let { p ->
                                    drawCircle(
                                        Color(0xFFE65100), radius = maxOf(11f, minPx(5f)), center = p,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = minPx(2f))
                                    )
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
                                            width = minPx(2f),
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
                                        width = minPx(2f), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
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
                                drawCircle(Color(0xFFE65100), radius = maxOf(7f, minPx(4f)), center = p)
                            }
                        }
                    }
                }
                if (fullscreenCanvas) {
                    IconButton(
                        onClick = { fullscreenCanvas = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color.White.copy(alpha = 0.85f), CircleShape)
                    ) { Icon(Icons.Filled.FullscreenExit, "Exit fullscreen") }
                }
            }

            if (!fullscreenCanvas) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Command line", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { commandLineVisible = !commandLineVisible }) {
                    Text(if (commandLineVisible) "Hide" else "Show")
                }
            }
            if (commandLineVisible) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it; commandFeedback = null },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = { Text("L, C, FILLET, 'ORTHO …") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { runCommand(commandInput) }),
                        modifier = Modifier.weight(1f).height(52.dp)
                    )
                    TextButton(onClick = { runCommand(commandInput) }, modifier = Modifier.padding(start = 6.dp)) { Text("Run") }
                }
                commandFeedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

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
private fun DimensionTextDialog(
    initialText: String,
    initialOffsetPx: Float = 30f,
    unitLabel: String = "mm",
    onConfirm: (text: String, fontSizeMm: Float, offsetPx: Float) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var showHandwrite by remember { mutableStateOf(false) }
    var fontSizeText by remember { mutableStateOf("") }
    var offsetText by remember { mutableStateOf(trimNum(initialOffsetPx.toDouble())) }
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
                OutlinedTextField(
                    value = fontSizeText, onValueChange = { fontSizeText = it }, singleLine = true,
                    label = { Text("Text size ($unitLabel) — optional") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = offsetText, onValueChange = { offsetText = it }, singleLine = true,
                    label = { Text("Offset from object (px) — 0 draws on the object") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) {
                    val fontSizeMm = fontSizeText.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { displayToMm(it, unitLabel).toFloat() } ?: 0f
                    val offsetPx = offsetText.toFloatOrNull() ?: initialOffsetPx
                    onConfirm(text, fontSizeMm, offsetPx)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private val COLOR_PALETTE: List<Color> = listOf(
    Color(0xFF000000), Color(0xFFD32F2F), Color(0xFFE65100), Color(0xFFF9A825), Color(0xFF2E7D32),
    Color(0xFF00838F), Color(0xFF1565C0), Color(0xFF6A1B9A), Color(0xFFAD1457), Color(0xFF616161)
)

/**
 * Picks the "current draw colour" applied to newly-drawn shapes from now on (see [currentColor]
 * in SketchEditorScreen); existing shapes are unaffected unless edited individually. "Default"
 * clears the override, going back to each shape kind's usual colour (green when a LINE is
 * confirmed, blue otherwise, purple for Dimension/Text, ...).
 */
@Composable
private fun ColorPickerDialog(current: Color?, onPick: (Color?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw colour") },
        text = {
            Column {
                Text(
                    "Applies to shapes drawn from now on. Existing shapes keep their own colour unless edited.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                COLOR_PALETTE.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        row.forEach { color ->
                            val selected = current == color
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(color)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) Color.Black else Color.Gray, CircleShape)
                                    .clickable { onPick(color) }
                            )
                        }
                    }
                }
                TextButton(onClick = { onPick(null) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Use default" + if (current == null) " (current)" else "")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
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

/** Asked from Box Select's "Width" action: applies one line width (px) to every selected shape
 *  at once, instead of having to open each one's own edit dialog individually. */
@Composable
private fun GroupWidthDialog(onConfirm: (widthPx: Float) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Line width for selection") },
        text = {
            Column {
                Text(
                    "Applies to every selected line, circle, arc, polyline and dimension (not text, which has its own font size).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text("Width (px)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toFloatOrNull()
                if (v == null || v <= 0f) error = "Enter a valid width" else onConfirm(v)
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Asked once each time Wall mode is switched on: every LINE drawn from then on gets a second,
 *  parallel outside-face line at this thickness — see SketchEditorScreen's addWallOuterLine. */
@Composable
private fun WallThicknessDialog(initial: String, unitLabel: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Wall thickness") },
        text = {
            Column {
                Text(
                    "Each line you draw becomes the wall's inside face — a second line is added automatically on the outside, this far away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text("Thickness ($unitLabel)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull()
                if (v == null || v <= 0.0) error = "Enter a valid thickness" else onConfirm(text)
            }) { Text("Start walls") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Asked when saving the current Box-Select selection as a reusable Block: a name plus a category
 *  (existing categories are offered as one-tap suggestions, or type a new one). */
@Composable
private fun SaveBlockDialog(existingCategories: List<String>, onConfirm: (name: String, category: String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(existingCategories.firstOrNull() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save as Block") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, singleLine = true,
                    label = { Text("Block name") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category, onValueChange = { category = it }, singleLine = true,
                    label = { Text("Category") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (existingCategories.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        existingCategories.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "Enter a name" else onConfirm(name.trim(), category.trim())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Search/browse saved Blocks by name or category, pick one to arm for insertion (tap the canvas
 *  next to drop it), and choose whether it's scaled through the current calibration ratio or
 *  inserted at the pixel size it was originally saved at. */
@Composable
private fun BlockPickerDialog(
    blocks: List<SketchBlock>,
    insertUseRatio: Boolean,
    onToggleUseRatio: () -> Unit,
    onPick: (SketchBlock) -> Unit,
    onDelete: (SketchBlock) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val categories = remember(blocks) { blocks.map { it.category }.distinct() }
    val filtered = blocks.filter {
        (category == null || it.category == category) &&
            (query.isBlank() || it.name.contains(query, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Block") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search by name") }, modifier = Modifier.fillMaxWidth()
                )
                if (categories.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                        categories.forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                }
                FilterChip(
                    selected = insertUseRatio, onClick = onToggleUseRatio,
                    label = { Text(if (insertUseRatio) "Insert at calculated scale" else "Insert at original size") },
                    modifier = Modifier.padding(top = 8.dp)
                )
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    if (filtered.isEmpty()) {
                        Text(
                            if (blocks.isEmpty()) "No blocks saved yet — select shapes with Box Select, then tap Save Block."
                            else "No blocks match",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                        )
                    }
                    filtered.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name)
                                Text(b.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { onDelete(b) }) { Icon(Icons.Filled.Close, "Delete block") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Shown once a hand-drawn Room-mode stroke has been straightened into wall segments: asks for the
 *  real length of whichever segment looked most like "the top wall" (see [SketchEditorScreen]'s
 *  finishFreehandRoom), then applies it as a Set Scale-style calibration reference — same geometry,
 *  just marked confirmed with that real length — before the view refits to the whole sketch. */
@Composable
private fun RoomCalibrateDialog(
    asTappedDisplay: Float,
    unitLabel: String,
    onConfirm: (realValue: Double) -> Unit,
    onSkip: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Top wall length") },
        text = {
            Column {
                Text(
                    "Your sketch is now straight walls. As drawn, the top (or most level) one reads " +
                        "~${trimNum(asTappedDisplay.toDouble())}$unitLabel — type its actual length to scale the " +
                        "whole sketch to match, or skip to keep it as drawn.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text("Actual length ($unitLabel)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull()
                if (v == null || v <= 0.0) error = "Enter a valid length" else onConfirm(v)
            }) { Text("Set scale") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } }
    )
}

/** Shown after the Distance tool's 2 taps: reports the on-screen pixel gap and asks for the real
 *  distance those two points actually represent (e.g. a dimension already written on a traced
 *  background photo) — confirming stores the ratio between the two as the calibration scale. */
@Composable
private fun DistanceCalibrationDialog(
    measuredPx: Float,
    unitLabel: String,
    onConfirm: (realValue: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set scale from distance") },
        text = {
            Column {
                Text(
                    "Measured on screen: ${trimNum(measuredPx.toDouble())}px between the two points you tapped.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "Enter the actual real-world distance those two points represent (e.g. a dimension already marked on the background image):",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, singleLine = true,
                    label = { Text("Actual distance ($unitLabel)") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toFloatOrNull()
                if (v == null || v <= 0f) error = "Enter a valid distance" else onConfirm(v)
            }) { Text("Set scale") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ShapeEditDialog(shape: SketchShape, unitLabel: String, onConfirm: (SketchShape) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    when (shape.kind) {
        ShapeKind.LINE -> {
            var text by remember {
                mutableStateOf(if (shape.realLength > 0) trimNum(mmToDisplay(shape.realLength, unitLabel)) else "")
            }
            var pickedColor by remember { mutableStateOf(shape.color?.let { Color(it) }) }
            var showHandwrite by remember { mutableStateOf(false) }
            var widthText by remember { mutableStateOf(if (shape.strokeWidth > 0f) trimNum(shape.strokeWidth.toDouble()) else "") }
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
                        ColorSwatchRow(current = pickedColor, onPick = { pickedColor = it })
                        OutlinedTextField(
                            value = widthText, onValueChange = { widthText = it }, singleLine = true,
                            label = { Text("Line width (px) — optional, for visibility when zoomed out") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val v = text.toDoubleOrNull()
                        val colorArgb = pickedColor?.toArgb()
                        val widthPx = widthText.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
                        if (v != null && v > 0) onConfirm(shape.copy(realLength = displayToMm(v, unitLabel), confirmed = true, color = colorArgb, strokeWidth = widthPx))
                        else onConfirm(shape.copy(confirmed = false, color = colorArgb, strokeWidth = widthPx))
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
                initialColor = shape.color?.let { Color(it) },
                showColorPicker = true,
                showFontSize = shape.kind == ShapeKind.TEXT || shape.kind == ShapeKind.DIMENSION,
                initialFontSizeMm = shape.fontSize,
                showStrokeWidth = shape.kind != ShapeKind.TEXT,
                initialStrokeWidthPx = shape.strokeWidth,
                showDimOffset = shape.kind == ShapeKind.DIMENSION,
                initialDimOffsetPx = shape.dimOffset,
                unitLabel = unitLabel,
                onConfirm = { text, color, fontSizeMm, strokeWidthPx, dimOffsetPx ->
                    val usesFontSize = shape.kind == ShapeKind.TEXT || shape.kind == ShapeKind.DIMENSION
                    onConfirm(
                        shape.copy(
                            label = text, color = color?.toArgb(),
                            fontSize = if (usesFontSize) fontSizeMm else shape.fontSize,
                            strokeWidth = if (shape.kind != ShapeKind.TEXT) strokeWidthPx else shape.strokeWidth,
                            dimOffset = if (shape.kind == ShapeKind.DIMENSION) dimOffsetPx else shape.dimOffset
                        )
                    )
                },
                onDelete = onDelete,
                onDismiss = onDismiss
            )
        }
    }
}

/** Palette + "default" swatch shared by the draw-colour picker and every shape edit dialog. */
@Composable
private fun ColorSwatchRow(current: Color?, onPick: (Color?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        COLOR_PALETTE.forEach { color ->
            val selected = current == color
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(color)
                    .border(if (selected) 3.dp else 1.dp, if (selected) Color.Black else Color.Gray, CircleShape)
                    .clickable { onPick(color) }
            )
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color.LightGray)
                .border(if (current == null) 3.dp else 1.dp, if (current == null) Color.Black else Color.Gray, CircleShape)
                .clickable { onPick(null) }
        )
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
    initialColor: Color? = null,
    showColorPicker: Boolean = false,
    showFontSize: Boolean = false,
    initialFontSizeMm: Float = 0f,
    showStrokeWidth: Boolean = false,
    initialStrokeWidthPx: Float = 0f,
    showDimOffset: Boolean = false,
    initialDimOffsetPx: Float = 0f,
    unitLabel: String = "mm",
    onConfirm: (text: String, color: Color?, fontSizeMm: Float, strokeWidthPx: Float, dimOffsetPx: Float) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var pickedColor by remember { mutableStateOf(initialColor) }
    var showHandwrite by remember { mutableStateOf(false) }
    var fontSizeText by remember {
        mutableStateOf(if (initialFontSizeMm > 0f) trimNum(mmToDisplay(initialFontSizeMm.toDouble(), unitLabel)) else "")
    }
    var widthText by remember {
        mutableStateOf(if (initialStrokeWidthPx > 0f) trimNum(initialStrokeWidthPx.toDouble()) else "")
    }
    var dimOffsetText by remember { mutableStateOf(trimNum(initialDimOffsetPx.toDouble())) }
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
                if (showColorPicker) {
                    ColorSwatchRow(current = pickedColor, onPick = { pickedColor = it })
                }
                if (showFontSize) {
                    OutlinedTextField(
                        value = fontSizeText, onValueChange = { fontSizeText = it }, singleLine = true,
                        label = { Text("Font size ($unitLabel) — optional") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                if (showStrokeWidth) {
                    OutlinedTextField(
                        value = widthText, onValueChange = { widthText = it }, singleLine = true,
                        label = { Text("Line width (px) — optional, for visibility when zoomed out") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                if (showDimOffset) {
                    OutlinedTextField(
                        value = dimOffsetText, onValueChange = { dimOffsetText = it }, singleLine = true,
                        label = { Text("Offset from object (px) — 0 draws on the object; negative flips the side") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fontSizeMm = fontSizeText.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { displayToMm(it, unitLabel).toFloat() } ?: 0f
                val strokeWidthPx = widthText.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
                val dimOffsetPx = dimOffsetText.toFloatOrNull() ?: initialDimOffsetPx
                onConfirm(text, pickedColor, fontSizeMm, strokeWidthPx, dimOffsetPx)
            }) { Text("Save") }
        },
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

/** Ramer/Douglas-Peucker line simplification: keeps only the points that matter to the stroke's
 *  actual shape — any point within [epsilon] of the straight line between its neighbours on either
 *  side is dropped, so shaky hand-drawn segments collapse to one straight line while real corners
 *  (which deviate by more than [epsilon]) survive. Used by Box Select's "Smooth" action. */
private fun douglasPeucker(points: List<Offset>, epsilon: Float): List<Offset> {
    if (points.size < 3) return points
    val first = points.first(); val last = points.last()
    var maxDist = 0f; var index = 0
    for (i in 1 until points.size - 1) {
        val d = distToSegment(points[i], first, last)
        if (d > maxDist) { maxDist = d; index = i }
    }
    return if (maxDist > epsilon) {
        val left = douglasPeucker(points.subList(0, index + 1), epsilon)
        val right = douglasPeucker(points.subList(index, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(first, last)
    }
}

// Every SketchShape.realLength (and every pixel-derived distance) is always stored/computed in
// millimetres internally, regardless of the work's display unit — these two just convert at the
// UI boundary, so DXF export and the mm-based scale math never need to know a work is in cm.
private fun mmToDisplay(mm: Double, unit: String): Double = if (unit == "cm") mm / 10.0 else mm
private fun displayToMm(display: Double, unit: String): Double = if (unit == "cm") display * 10.0 else display

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
