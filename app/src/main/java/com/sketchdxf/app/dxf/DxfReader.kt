package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import java.io.BufferedReader
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A minimal DXF reader, symmetric to [DxfWriter]: understands the same entity subset that writer
 * produces (LINE, CIRCLE, ARC, TEXT, LWPOLYLINE), plus BLOCK/INSERT (a block's own definition is
 * flattened into ordinary shapes at every place it's inserted, and — since the app has its own
 * reusable Block library — every named, actually-used block is also handed back separately in
 * [Result.blocks] so the caller can save it there; see [Result.usedBlocks]). Anything else
 * (SPLINE, DIMENSION, VIEWPORT, old-style POLYLINE/VERTEX, ...) is silently skipped rather than
 * erroring, so a more complex external file just imports the parts this app understands, reported
 * via [Result.skippedTypes].
 *
 * Reads the file as a stream (one group-code/value pair at a time, one entity's fields held at
 * once) rather than loading it whole — a real-world DXF exported from desktop CAD can be tens of
 * megabytes of largely LWPOLYLINE vertex data, and materialising that as a `List<String>` plus a
 * parallel `List<Pair<Int,String>>` easily doubles or triples it in memory, enough to exhaust a
 * phone's heap on an otherwise perfectly valid file (which used to surface here as a misleading
 * "not a valid DXF" — see [read]'s OutOfMemoryError handling).
 *
 * Coordinates come back in the file's own space (millimetres, after applying \$INSUNITS if
 * present) — placing them into the editor's canvas-pixel space, including the Y-flip DXF's
 * Y-up convention needs, is the caller's job (see SketchEditorScreen's DXF import).
 */
object DxfReader {

    /** One block's own child geometry, in mm, already shifted so the block's base point is the
     *  origin — ready to serialize straight into a [com.sketchdxf.app.data.SketchBlock] (whose
     *  own convention is "coordinates relative to origin"), with pxPerMm = 1.0 since these
     *  coordinates already *are* millimetres. */
    data class BlockDef(val name: String, val entities: List<SketchShape>)

    data class Result(
        val shapes: List<SketchShape>,
        val skippedTypes: Set<String>,
        /** Every named (non "*..." internal), non-empty block that at least one INSERT actually
         *  used — the file may define far more blocks (title frames, hatch patterns, unused
         *  library symbols) than are worth cluttering the Block library with. */
        val usedBlocks: List<BlockDef>
    )

    /** Thrown (instead of a generic parse failure) when the file ran the device out of memory,
     *  so the caller can tell the user what actually happened. */
    class TooLargeException(cause: Throwable) : Exception(cause)

    fun read(file: File): Result {
        try {
            return file.bufferedReader().use { parse(it) }
        } catch (e: OutOfMemoryError) {
            throw TooLargeException(e)
        }
    }

    /** Builds one shape from an already-gathered entity's fields, or null for an unsupported
     *  entity type — shared between top-level ENTITIES and a BLOCK definition's children. */
    private fun buildShape(entityType: String, fields: Map<Int, MutableList<String>>, mmPerUnit: Double): SketchShape? {
        fun d(groupCode: Int): Double = fields[groupCode]?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        // Group 420 is a 24-bit true colour (0x00RRGGBB, no alpha channel) — OR in full opacity
        // so it's a valid ARGB int for Compose/Android's Color.
        val color = fields[420]?.getOrNull(0)?.toIntOrNull()?.let { (it and 0x00FFFFFF) or 0xFF000000.toInt() }
        return when (entityType) {
            "LINE" -> SketchShape(
                workId = 0, kind = ShapeKind.LINE,
                x1 = (d(10) * mmPerUnit).toFloat(), y1 = (d(20) * mmPerUnit).toFloat(),
                x2 = (d(11) * mmPerUnit).toFloat(), y2 = (d(21) * mmPerUnit).toFloat(),
                color = color
            )
            "CIRCLE" -> SketchShape(
                workId = 0, kind = ShapeKind.CIRCLE,
                cx = (d(10) * mmPerUnit).toFloat(), cy = (d(20) * mmPerUnit).toFloat(),
                r = (d(40) * mmPerUnit).toFloat(), color = color
            )
            "ARC" -> {
                val cx = d(10) * mmPerUnit; val cy = d(20) * mmPerUnit; val r = d(40) * mmPerUnit
                val startRad = Math.toRadians(d(50)); val endRad = Math.toRadians(d(51))
                // DXF ARC always sweeps CCW from angle 50 to 51 — over 180° means it's the major
                // arc between the two boundary points, not the minor one.
                var sweepDeg = (d(51) - d(50)) % 360.0
                if (sweepDeg < 0.0) sweepDeg += 360.0
                SketchShape(
                    workId = 0, kind = ShapeKind.ARC,
                    cx = cx.toFloat(), cy = cy.toFloat(), r = r.toFloat(),
                    x1 = (cx + r * cos(startRad)).toFloat(), y1 = (cy + r * sin(startRad)).toFloat(),
                    x2 = (cx + r * cos(endRad)).toFloat(), y2 = (cy + r * sin(endRad)).toFloat(),
                    color = color, major = sweepDeg > 180.0
                )
            }
            "TEXT", "MTEXT" -> {
                val label = fields[1]?.getOrNull(0).orEmpty()
                if (label.isBlank()) null else SketchShape(
                    workId = 0, kind = ShapeKind.TEXT,
                    x1 = (d(10) * mmPerUnit).toFloat(), y1 = (d(20) * mmPerUnit).toFloat(),
                    label = label, color = color
                )
            }
            "LWPOLYLINE" -> {
                // Vertices are inline 10/20 pairs, one of each per vertex in order — unlike
                // old-style POLYLINE, which uses separate VERTEX sub-entities and isn't supported.
                val xs = fields[10].orEmpty()
                val ys = fields[20].orEmpty()
                val pts = xs.zip(ys) { xStr, yStr ->
                    val x = xStr.toDoubleOrNull() ?: 0.0
                    val y = yStr.toDoubleOrNull() ?: 0.0
                    (x * mmPerUnit).toFloat() to (y * mmPerUnit).toFloat()
                }
                if (pts.size < 2) null else SketchShape(workId = 0, kind = ShapeKind.POLYLINE, path = SketchPath.serialize(pts), color = color)
            }
            else -> null
        }
    }

    /** Places one instance of a block's (already base-relative) entities at an INSERT: scale
     *  (non-uniform X/Y averaged — [SketchShape.r] can't represent an ellipse), rotate, then
     *  translate to the insertion point. */
    private fun transformShape(s: SketchShape, insX: Float, insY: Float, xScale: Float, yScale: Float, rotDeg: Float): SketchShape {
        val rad = Math.toRadians(rotDeg.toDouble())
        val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
        fun tp(x: Float, y: Float): Pair<Float, Float> {
            val lx = x * xScale; val ly = y * yScale
            val rx = lx * cosR - ly * sinR
            val ry = lx * sinR + ly * cosR
            return (rx + insX) to (ry + insY)
        }
        val avgScale = (abs(xScale) + abs(yScale)) / 2f
        return when (s.kind) {
            ShapeKind.CIRCLE -> tp(s.cx, s.cy).let { (cx, cy) -> s.copy(cx = cx, cy = cy, r = s.r * avgScale) }
            ShapeKind.TEXT -> tp(s.x1, s.y1).let { (x, y) -> s.copy(x1 = x, y1 = y) }
            ShapeKind.FREEHAND, ShapeKind.POLYLINE ->
                s.copy(path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> tp(x, y) }))
            ShapeKind.ARC -> {
                val (cx, cy) = tp(s.cx, s.cy); val (x1, y1) = tp(s.x1, s.y1); val (x2, y2) = tp(s.x2, s.y2)
                s.copy(cx = cx, cy = cy, r = s.r * avgScale, x1 = x1, y1 = y1, x2 = x2, y2 = y2)
            }
            else -> {
                val (x1, y1) = tp(s.x1, s.y1); val (x2, y2) = tp(s.x2, s.y2)
                s.copy(x1 = x1, y1 = y1, x2 = x2, y2 = y2)
            }
        }
    }

    private fun parse(reader: BufferedReader): Result {
        var mmPerUnit = 1.0
        val shapes = mutableListOf<SketchShape>()
        val skipped = mutableSetOf<String>()

        // name -> (base point in mm, child entities in mm, still in absolute file coordinates —
        // the base point is subtracted only once a block is actually exported as a BlockDef).
        val blockChildren = mutableMapOf<String, MutableList<SketchShape>>()
        val blockBase = mutableMapOf<String, Pair<Float, Float>>()
        val usedBlockNames = mutableSetOf<String>()

        // Reads one (group code, value) line pair, or null at end of file.
        fun readPair(): Pair<Int, String>? {
            while (true) {
                val codeLine = reader.readLine() ?: return null
                val valueLine = reader.readLine() ?: return null
                val code = codeLine.trim().toIntOrNull() ?: continue
                return code to valueLine.trim()
            }
        }

        // Gathers one entity/header's fields until the next code-0 pair, returning them plus
        // that next pair (the caller continues the main loop from there without re-reading).
        fun gather(): Pair<Map<Int, MutableList<String>>, Pair<Int, String>?> {
            val fields = mutableMapOf<Int, MutableList<String>>()
            var next = readPair()
            while (next != null && next.first != 0) {
                fields.getOrPut(next.first) { mutableListOf() }.add(next.second)
                next = readPair()
            }
            return fields to next
        }

        var awaitingInsunits = false
        var inEntities = false
        var inBlocks = false
        var currentBlockName: String? = null
        var pair = readPair()
        while (pair != null) {
            val (code, value) = pair
            when {
                code == 9 && value == "\$INSUNITS" -> { awaitingInsunits = true; pair = readPair() }
                awaitingInsunits && code == 70 -> {
                    mmPerUnit = when (value.toIntOrNull()) {
                        1 -> 25.4    // inches
                        2 -> 304.8   // feet
                        5 -> 10.0    // centimetres
                        6 -> 1000.0  // metres
                        else -> 1.0  // 4 = millimetres, or unspecified — assume mm
                    }
                    awaitingInsunits = false
                    pair = readPair()
                }
                code == 2 && value == "ENTITIES" -> { inEntities = true; pair = readPair() }
                code == 2 && value == "BLOCKS" -> { inBlocks = true; pair = readPair() }
                code == 0 && value == "ENDSEC" -> { inEntities = false; inBlocks = false; pair = readPair() }

                inBlocks && code == 0 && value == "BLOCK" -> {
                    val (fields, next) = gather()
                    val name = fields[2]?.getOrNull(0)
                    if (name != null) {
                        blockBase[name] = (fields[10]?.getOrNull(0)?.toDoubleOrNull()?.times(mmPerUnit) ?: 0.0).toFloat() to
                            (fields[20]?.getOrNull(0)?.toDoubleOrNull()?.times(mmPerUnit) ?: 0.0).toFloat()
                        blockChildren[name] = mutableListOf()
                        currentBlockName = name
                    }
                    pair = next
                }
                inBlocks && code == 0 && value == "ENDBLK" -> {
                    val (_, next) = gather()
                    currentBlockName = null
                    pair = next
                }
                inBlocks && code == 0 && currentBlockName != null -> {
                    val entityType = value
                    val (fields, next) = gather()
                    // Nested block references aren't expanded — rare in practice for simple
                    // furniture/door/window symbols, and avoids any risk of a reference cycle.
                    if (entityType != "INSERT") {
                        buildShape(entityType, fields, mmPerUnit)?.let { blockChildren[currentBlockName]?.add(it) }
                    }
                    pair = next
                }

                inEntities && code == 0 && value == "INSERT" -> {
                    val (fields, next) = gather()
                    fun d(g: Int): Double = fields[g]?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    val blockName = fields[2]?.getOrNull(0)
                    val children = blockName?.let { blockChildren[it] }
                    if (blockName != null && children != null) {
                        usedBlockNames.add(blockName)
                        val (baseX, baseY) = blockBase[blockName] ?: (0f to 0f)
                        val insX = (d(10) * mmPerUnit).toFloat(); val insY = (d(20) * mmPerUnit).toFloat()
                        val xScale = (fields[41]?.getOrNull(0)?.toFloatOrNull() ?: 1f)
                        val yScale = (fields[42]?.getOrNull(0)?.toFloatOrNull() ?: 1f)
                        val rotDeg = (fields[50]?.getOrNull(0)?.toFloatOrNull() ?: 0f)
                        children.forEach { child ->
                            val local = shiftShape(child, -baseX, -baseY)
                            shapes.add(transformShape(local, insX, insY, xScale, yScale, rotDeg))
                        }
                    } else {
                        skipped.add("INSERT")
                    }
                    pair = next
                }
                inEntities && code == 0 -> {
                    val entityType = value
                    val (fields, next) = gather()
                    val shape = buildShape(entityType, fields, mmPerUnit)
                    if (shape != null) shapes.add(shape) else skipped.add(entityType)
                    pair = next
                }
                else -> pair = readPair()
            }
        }

        val usedBlocks = usedBlockNames
            .filter { !it.startsWith("*") && !blockChildren[it].isNullOrEmpty() }
            .map { name ->
                val (baseX, baseY) = blockBase[name] ?: (0f to 0f)
                BlockDef(name, blockChildren[name].orEmpty().map { shiftShape(it, -baseX, -baseY) })
            }
        return Result(shapes, skipped, usedBlocks)
    }

    private fun shiftShape(s: SketchShape, dx: Float, dy: Float): SketchShape = when (s.kind) {
        ShapeKind.CIRCLE -> s.copy(cx = s.cx + dx, cy = s.cy + dy)
        ShapeKind.TEXT -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy)
        ShapeKind.FREEHAND, ShapeKind.POLYLINE ->
            s.copy(path = SketchPath.serialize(SketchPath.parse(s.path).map { (x, y) -> (x + dx) to (y + dy) }))
        ShapeKind.ARC -> s.copy(cx = s.cx + dx, cy = s.cy + dy, x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
        else -> s.copy(x1 = s.x1 + dx, y1 = s.y1 + dy, x2 = s.x2 + dx, y2 = s.y2 + dy)
    }
}
