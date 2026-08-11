package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchShape
import java.io.File
import kotlin.math.hypot

/** A minimal, dependency-free ASCII DXF writer — same "hand-build the file format" approach the
 *  billing app uses for its .xlsx export, just for DXF's much simpler group-code text format. */
object DxfWriter {

    private sealed class Entity {
        data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Entity()
        data class Circle(val cx: Double, val cy: Double, val r: Double) : Entity()
        data class Text(val x: Double, val y: Double, val height: Double, val value: String) : Entity()
    }

    /**
     * Writes [shapes] (in the editor's canvas-pixel space) as a DXF in millimetres.
     *
     * Scale: every LINE the user typed a real length for ([SketchShape.confirmed]) contributes
     * pixels-per-mm; the median of those becomes the global scale applied to every shape's
     * position. A confirmed line's OWN typed length then wins exactly (its far endpoint is
     * nudged along its direction so the scaled segment matches what was typed, rather than
     * trusting the (slightly noisier) globally-scaled pixel distance).
     */
    fun export(file: File, shapes: List<SketchShape>) {
        val entities = buildEntities(shapes)
        file.writeText(render(entities))
    }

    private fun buildEntities(shapes: List<SketchShape>): List<Entity> {
        if (shapes.isEmpty()) return emptyList()

        val confirmedLines = shapes.filter { it.kind == ShapeKind.LINE && it.confirmed && it.realLength > 0 }
        val ratios = confirmedLines.mapNotNull { s ->
            val px = hypot((s.x2 - s.x1).toDouble(), (s.y2 - s.y1).toDouble())
            if (px > 0.5) s.realLength / px else null
        }
        // mm per pixel; 1.0 (i.e. "treat pixels as mm") when nothing was confirmed yet, so an
        // unfinished work still exports something sane instead of collapsing to a point.
        val scale = if (ratios.isEmpty()) 1.0 else ratios.sorted()[ratios.size / 2]

        // Flip Y (screen space grows downward, DXF space grows upward) around the drawing's
        // own top edge, so the exported drawing isn't mirrored vertically.
        val maxY = shapes.maxOf { maxOf(it.y1, it.y2, it.cy + it.r) }.toDouble()
        fun sx(x: Float) = x * scale
        fun sy(y: Float) = (maxY - y) * scale

        return shapes.flatMap { s ->
            when (s.kind) {
                ShapeKind.LINE -> {
                    var x1 = sx(s.x1); var y1 = sy(s.y1)
                    var x2 = sx(s.x2); var y2 = sy(s.y2)
                    if (s.confirmed && s.realLength > 0) {
                        val curLen = hypot(x2 - x1, y2 - y1)
                        if (curLen > 1e-6) {
                            val f = s.realLength / curLen
                            val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                            x1 = mx + (x1 - mx) * f; y1 = my + (y1 - my) * f
                            x2 = mx + (x2 - mx) * f; y2 = my + (y2 - my) * f
                        }
                    }
                    listOf(Entity.Line(x1, y1, x2, y2))
                }
                ShapeKind.CIRCLE -> listOf(Entity.Circle(sx(s.cx), sy(s.cy), s.r * scale))
                ShapeKind.TEXT -> if (s.label.isNotBlank()) listOf(Entity.Text(sx(s.x1), sy(s.y1), 3.0, s.label)) else emptyList()
                ShapeKind.DIMENSION -> {
                    // Not a live/associative DXF DIMENSION entity — a plain line + text label that
                    // reads correctly when opened, without needing a dimension-style block setup.
                    val x1 = sx(s.x1); val y1 = sy(s.y1); val x2 = sx(s.x2); val y2 = sy(s.y2)
                    val entities = mutableListOf<Entity>(Entity.Line(x1, y1, x2, y2))
                    if (s.label.isNotBlank()) entities.add(Entity.Text((x1 + x2) / 2, (y1 + y2) / 2 + 1.5, 2.5, s.label))
                    entities
                }
                else -> emptyList()
            }
        }
    }

    private fun render(entities: List<Entity>): String {
        val sb = StringBuilder()
        fun code(n: Int, v: String) { sb.append(n).append('\n').append(v).append('\n') }
        fun code(n: Int, v: Double) { code(n, "%.4f".format(v)) }

        code(0, "SECTION"); code(2, "HEADER")
        code(9, "\$INSUNITS"); code(70, "4") // 4 = millimeters
        code(0, "ENDSEC")

        code(0, "SECTION"); code(2, "ENTITIES")
        entities.forEach { e ->
            when (e) {
                is Entity.Line -> {
                    code(0, "LINE"); code(8, "0")
                    code(10, e.x1); code(20, e.y1); code(30, 0.0)
                    code(11, e.x2); code(21, e.y2); code(31, 0.0)
                }
                is Entity.Circle -> {
                    code(0, "CIRCLE"); code(8, "0")
                    code(10, e.cx); code(20, e.cy); code(30, 0.0)
                    code(40, e.r)
                }
                is Entity.Text -> {
                    code(0, "TEXT"); code(8, "0")
                    code(10, e.x); code(20, e.y); code(30, 0.0)
                    code(40, e.height)
                    code(1, e.value)
                }
            }
        }
        code(0, "ENDSEC")
        code(0, "EOF")
        return sb.toString()
    }
}
