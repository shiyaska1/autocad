package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchShape
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * A minimal DXF reader, symmetric to [DxfWriter]: understands the same entity subset that writer
 * produces (LINE, CIRCLE, ARC, TEXT), so an externally-authored DXF built from those primitives
 * — or one this app exported earlier — imports cleanly. Anything else (LWPOLYLINE, SPLINE,
 * DIMENSION, BLOCK/INSERT, ...) is silently skipped rather than erroring, so a more complex
 * external file just imports the parts this app understands, reported via [Result.skippedTypes].
 *
 * Coordinates come back in the file's own space (millimetres, after applying \$INSUNITS if
 * present) — placing them into the editor's canvas-pixel space, including the Y-flip DXF's
 * Y-up convention needs, is the caller's job (see SketchEditorScreen's DXF import).
 */
object DxfReader {

    data class Result(val shapes: List<SketchShape>, val skippedTypes: Set<String>)

    fun read(file: File): Result = parse(file.readLines())

    private fun parse(lines: List<String>): Result {
        // DXF is a flat stream of (group code, value) line pairs.
        val pairs = ArrayList<Pair<Int, String>>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull()
            if (code != null) pairs.add(code to lines[i + 1].trim())
            i += 2
        }

        var mmPerUnit = 1.0
        for (idx in pairs.indices) {
            if (pairs[idx].first == 9 && pairs[idx].second == "\$INSUNITS") {
                mmPerUnit = when (pairs.getOrNull(idx + 1)?.second?.toIntOrNull()) {
                    1 -> 25.4    // inches
                    2 -> 304.8   // feet
                    5 -> 10.0    // centimetres
                    6 -> 1000.0  // metres
                    else -> 1.0  // 4 = millimetres, or unspecified — assume mm
                }
                break
            }
        }

        val shapes = mutableListOf<SketchShape>()
        val skipped = mutableSetOf<String>()
        var inEntities = false
        var idx = 0
        while (idx < pairs.size) {
            val (code, value) = pairs[idx]
            when {
                code == 2 && value == "ENTITIES" -> { inEntities = true; idx++ }
                code == 0 && value == "ENDSEC" -> { inEntities = false; idx++ }
                inEntities && code == 0 -> {
                    var j = idx + 1
                    val fields = mutableMapOf<Int, MutableList<String>>()
                    while (j < pairs.size && pairs[j].first != 0) {
                        fields.getOrPut(pairs[j].first) { mutableListOf() }.add(pairs[j].second)
                        j++
                    }
                    fun d(groupCode: Int): Double = fields[groupCode]?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    when (value) {
                        "LINE" -> shapes.add(
                            SketchShape(
                                workId = 0, kind = ShapeKind.LINE,
                                x1 = (d(10) * mmPerUnit).toFloat(), y1 = (d(20) * mmPerUnit).toFloat(),
                                x2 = (d(11) * mmPerUnit).toFloat(), y2 = (d(21) * mmPerUnit).toFloat()
                            )
                        )
                        "CIRCLE" -> shapes.add(
                            SketchShape(
                                workId = 0, kind = ShapeKind.CIRCLE,
                                cx = (d(10) * mmPerUnit).toFloat(), cy = (d(20) * mmPerUnit).toFloat(),
                                r = (d(40) * mmPerUnit).toFloat()
                            )
                        )
                        "ARC" -> {
                            val cx = d(10) * mmPerUnit; val cy = d(20) * mmPerUnit; val r = d(40) * mmPerUnit
                            val startRad = Math.toRadians(d(50)); val endRad = Math.toRadians(d(51))
                            shapes.add(
                                SketchShape(
                                    workId = 0, kind = ShapeKind.ARC,
                                    cx = cx.toFloat(), cy = cy.toFloat(), r = r.toFloat(),
                                    x1 = (cx + r * cos(startRad)).toFloat(), y1 = (cy + r * sin(startRad)).toFloat(),
                                    x2 = (cx + r * cos(endRad)).toFloat(), y2 = (cy + r * sin(endRad)).toFloat()
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
                                        label = label
                                    )
                                )
                            }
                        }
                        else -> skipped.add(value)
                    }
                    idx = j
                }
                else -> idx++
            }
        }
        return Result(shapes, skipped)
    }
}
