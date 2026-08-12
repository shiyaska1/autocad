package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import java.io.BufferedReader
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * A minimal DXF reader, symmetric to [DxfWriter]: understands the same entity subset that writer
 * produces (LINE, CIRCLE, ARC, TEXT, LWPOLYLINE), so an externally-authored DXF built from those
 * primitives — or one this app exported earlier — imports cleanly. Anything else (SPLINE,
 * DIMENSION, BLOCK/INSERT, old-style POLYLINE/VERTEX, ...) is silently skipped rather than
 * erroring, so a more complex external file just imports the parts this app understands,
 * reported via [Result.skippedTypes].
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

    data class Result(val shapes: List<SketchShape>, val skippedTypes: Set<String>)

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

    private fun parse(reader: BufferedReader): Result {
        var mmPerUnit = 1.0
        val shapes = mutableListOf<SketchShape>()
        val skipped = mutableSetOf<String>()

        // Reads one (group code, value) line pair, or null at end of file.
        fun readPair(): Pair<Int, String>? {
            while (true) {
                val codeLine = reader.readLine() ?: return null
                val valueLine = reader.readLine() ?: return null
                val code = codeLine.trim().toIntOrNull() ?: continue
                return code to valueLine.trim()
            }
        }

        var awaitingInsunits = false
        var inEntities = false
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
                code == 0 && value == "ENDSEC" -> { inEntities = false; pair = readPair() }
                inEntities && code == 0 -> {
                    // Gather this one entity's fields until the next code-0 pair (the next
                    // entity, or a section boundary) — bounded by one entity's size, not the file.
                    val entityType = value
                    val fields = mutableMapOf<Int, MutableList<String>>()
                    var next = readPair()
                    while (next != null && next.first != 0) {
                        fields.getOrPut(next.first) { mutableListOf() }.add(next.second)
                        next = readPair()
                    }
                    fun d(groupCode: Int): Double = fields[groupCode]?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    // Group 420 is a 24-bit true colour (0x00RRGGBB, no alpha channel) — OR in full
                    // opacity so it's a valid ARGB int for Compose/Android's Color.
                    val color = fields[420]?.getOrNull(0)?.toIntOrNull()?.let { (it and 0x00FFFFFF) or 0xFF000000.toInt() }
                    when (entityType) {
                        "LINE" -> shapes.add(
                            SketchShape(
                                workId = 0, kind = ShapeKind.LINE,
                                x1 = (d(10) * mmPerUnit).toFloat(), y1 = (d(20) * mmPerUnit).toFloat(),
                                x2 = (d(11) * mmPerUnit).toFloat(), y2 = (d(21) * mmPerUnit).toFloat(),
                                color = color
                            )
                        )
                        "CIRCLE" -> shapes.add(
                            SketchShape(
                                workId = 0, kind = ShapeKind.CIRCLE,
                                cx = (d(10) * mmPerUnit).toFloat(), cy = (d(20) * mmPerUnit).toFloat(),
                                r = (d(40) * mmPerUnit).toFloat(), color = color
                            )
                        )
                        "ARC" -> {
                            val cx = d(10) * mmPerUnit; val cy = d(20) * mmPerUnit; val r = d(40) * mmPerUnit
                            val startRad = Math.toRadians(d(50)); val endRad = Math.toRadians(d(51))
                            // DXF ARC always sweeps CCW from angle 50 to 51 — over 180° means it's
                            // the major arc between the two boundary points, not the minor one.
                            var sweepDeg = (d(51) - d(50)) % 360.0
                            if (sweepDeg < 0.0) sweepDeg += 360.0
                            shapes.add(
                                SketchShape(
                                    workId = 0, kind = ShapeKind.ARC,
                                    cx = cx.toFloat(), cy = cy.toFloat(), r = r.toFloat(),
                                    x1 = (cx + r * cos(startRad)).toFloat(), y1 = (cy + r * sin(startRad)).toFloat(),
                                    x2 = (cx + r * cos(endRad)).toFloat(), y2 = (cy + r * sin(endRad)).toFloat(),
                                    color = color, major = sweepDeg > 180.0
                                )
                            )
                        }
                        "TEXT", "MTEXT" -> {
                            val label = fields[1]?.getOrNull(0).orEmpty()
                            if (label.isNotBlank()) {
                                shapes.add(
                                    SketchShape(
                                        workId = 0, kind = ShapeKind.TEXT,
                                        x1 = (d(10) * mmPerUnit).toFloat(), y1 = (d(20) * mmPerUnit).toFloat(),
                                        label = label, color = color
                                    )
                                )
                            }
                        }
                        "LWPOLYLINE" -> {
                            // Vertices are inline 10/20 pairs, one of each per vertex in order —
                            // unlike old-style POLYLINE, which uses separate VERTEX sub-entities
                            // and isn't supported here.
                            val xs = fields[10].orEmpty()
                            val ys = fields[20].orEmpty()
                            val pts = xs.zip(ys) { xStr, yStr ->
                                val x = xStr.toDoubleOrNull() ?: 0.0
                                val y = yStr.toDoubleOrNull() ?: 0.0
                                (x * mmPerUnit).toFloat() to (y * mmPerUnit).toFloat()
                            }
                            if (pts.size >= 2) {
                                shapes.add(
                                    SketchShape(workId = 0, kind = ShapeKind.POLYLINE, path = SketchPath.serialize(pts), color = color)
                                )
                            }
                        }
                        else -> skipped.add(entityType)
                    }
                    pair = next
                }
                else -> pair = readPair()
            }
        }
        return Result(shapes, skipped)
    }
}
