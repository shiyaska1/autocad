package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.ShapeKind
import com.sketchdxf.app.data.SketchPath
import com.sketchdxf.app.data.SketchShape
import java.io.File
import kotlin.math.hypot

/** A minimal, dependency-free ASCII DXF writer — same "hand-build the file format" approach the
 *  billing app uses for its .xlsx export, just for DXF's much simpler group-code text format. */
object DxfWriter {

    private sealed class Entity {
        abstract val color: Int?
        data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, override val color: Int? = null) : Entity()
        data class Circle(val cx: Double, val cy: Double, val r: Double, override val color: Int? = null) : Entity()
        data class Text(val x: Double, val y: Double, val height: Double, val value: String, override val color: Int? = null) : Entity()
        data class Arc(val cx: Double, val cy: Double, val r: Double, val startAngle: Double, val endAngle: Double, override val color: Int? = null) : Entity()
        data class Polyline(val points: List<Pair<Double, Double>>, override val color: Int? = null) : Entity()
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
        val maxY = shapes.maxOf { s ->
            val pathMaxY = if (s.kind == ShapeKind.FREEHAND || s.kind == ShapeKind.POLYLINE) {
                SketchPath.parse(s.path).maxOfOrNull { it.second } ?: Float.NEGATIVE_INFINITY
            } else Float.NEGATIVE_INFINITY
            maxOf(s.y1, s.y2, s.cy + s.r, pathMaxY)
        }.toDouble()
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
                    listOf(Entity.Line(x1, y1, x2, y2, s.color))
                }
                ShapeKind.CIRCLE -> listOf(Entity.Circle(sx(s.cx), sy(s.cy), s.r * scale, s.color))
                ShapeKind.TEXT -> if (s.label.isNotBlank()) {
                    val height = if (s.fontSize > 0f) s.fontSize.toDouble() else 3.0
                    listOf(Entity.Text(sx(s.x1), sy(s.y1), height, s.label, s.color))
                } else emptyList()
                ShapeKind.DIMENSION -> {
                    // Not a live/associative DXF DIMENSION entity — a plain line + text label that
                    // reads correctly when opened, without needing a dimension-style block setup.
                    // If the dimension line is offset from the measured points (SketchShape.dimOffset),
                    // mirror that here too: the dimension line itself sits off to the side, with
                    // extension lines connecting it back to the actual measured points, matching what
                    // the editor and the exported preview both show.
                    var bx1 = s.x1; var by1 = s.y1; var bx2 = s.x2; var by2 = s.y2
                    if (s.dimOffset != 0f) {
                        val dx = s.x2 - s.x1; val dy = s.y2 - s.y1
                        val lenPx = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (lenPx > 1e-3f) {
                            val nx = -dy / lenPx * s.dimOffset; val ny = dx / lenPx * s.dimOffset
                            bx1 += nx; by1 += ny; bx2 += nx; by2 += ny
                        }
                    }
                    val x1 = sx(bx1); val y1 = sy(by1); val x2 = sx(bx2); val y2 = sy(by2)
                    val entities = mutableListOf<Entity>(Entity.Line(x1, y1, x2, y2, s.color))
                    if (s.dimOffset != 0f) {
                        entities.add(Entity.Line(sx(s.x1), sy(s.y1), x1, y1, s.color))
                        entities.add(Entity.Line(sx(s.x2), sy(s.y2), x2, y2, s.color))
                    }
                    if (s.label.isNotBlank()) {
                        val height = if (s.fontSize > 0f) s.fontSize.toDouble() else 2.5
                        entities.add(Entity.Text((x1 + x2) / 2, (y1 + y2) / 2 + 1.5, height, s.label, s.color))
                    }
                    entities
                }
                ShapeKind.FREEHAND -> SketchPath.parse(s.path).zipWithNext { (ax, ay), (bx, by) ->
                    Entity.Line(sx(ax), sy(ay), sx(bx), sy(by), s.color)
                }
                ShapeKind.POLYLINE -> {
                    val pts = SketchPath.parse(s.path).map { (x, y) -> sx(x) to sy(y) }
                    if (pts.size >= 2) listOf(Entity.Polyline(pts, s.color)) else emptyList()
                }
                ShapeKind.ARC -> {
                    // Recompute the sweep in DXF's own (Y-flipped) space rather than reusing the
                    // screen-space one — a Y-flip reverses angular orientation, and DXF's ARC always
                    // sweeps counter-clockwise from angle 50 to 51, so start/end may need swapping.
                    val dxfCx = sx(s.cx); val dxfCy = sy(s.cy); val dxfR = s.r * scale
                    val dxfX1 = sx(s.x1); val dxfY1 = sy(s.y1); val dxfX2 = sx(s.x2); val dxfY2 = sy(s.y2)
                    val a1 = Math.toDegrees(kotlin.math.atan2(dxfY1 - dxfCy, dxfX1 - dxfCx))
                    val a2 = Math.toDegrees(kotlin.math.atan2(dxfY2 - dxfCy, dxfX2 - dxfCx))
                    var sweep = (a2 - a1) % 360.0
                    if (sweep > 180.0) sweep -= 360.0
                    if (sweep < -180.0) sweep += 360.0
                    if (s.major) sweep = if (sweep >= 0.0) sweep - 360.0 else sweep + 360.0
                    val (start50, end51) = if (sweep >= 0.0) a1 to (a1 + sweep) else (a1 + sweep) to a1
                    fun norm360(d: Double) = ((d % 360.0) + 360.0) % 360.0
                    listOf(Entity.Arc(dxfCx, dxfCy, dxfR, norm360(start50), norm360(end51), s.color))
                }
                // XLINE has no real length by design (its stored x2,y2 is just a tiny direction
                // marker, see ShapeKind.XLINE) — exported as a long-but-finite LINE instead of a
                // real DXF XLINE entity, extended generously past any normal drawing's extent so it
                // still reads as a construction/reference line when opened.
                ShapeKind.XLINE -> {
                    val dirX = s.x2 - s.x1; val dirY = s.y2 - s.y1
                    val len = hypot(dirX.toDouble(), dirY.toDouble())
                    if (len < 1e-6) emptyList() else {
                        val ux = dirX / len; val uy = dirY / len
                        val reach = 100000.0 // mm, well past any realistic sketch's extent
                        val ax = s.x1 - (ux * reach / scale).toFloat(); val ay = s.y1 - (uy * reach / scale).toFloat()
                        val bx = s.x1 + (ux * reach / scale).toFloat(); val by = s.y1 + (uy * reach / scale).toFloat()
                        listOf(Entity.Line(sx(ax), sy(ay), sx(bx), sy(by), s.color))
                    }
                }
                else -> emptyList()
            }
        }
    }

    private fun render(entities: List<Entity>): String {
        val sb = StringBuilder()
        fun code(n: Int, v: String) { sb.append(n).append('\n').append(v).append('\n') }
        fun code(n: Int, v: Double) { code(n, "%.4f".format(v)) }
        fun code(n: Int, v: Int) { code(n, v.toString()) }
        // Group 420 is a 24-bit true colour (0x00RRGGBB, no alpha) — write it right after the
        // layer code so a shape's own explicit colour overrides the by-layer default (ACI 256).
        fun colorCode(color: Int?) { color?.let { code(420, it and 0x00FFFFFF) } }

        code(0, "SECTION"); code(2, "HEADER")
        code(9, "\$INSUNITS"); code(70, "4") // 4 = millimeters
        code(0, "ENDSEC")

        code(0, "SECTION"); code(2, "ENTITIES")
        entities.forEach { e ->
            when (e) {
                is Entity.Line -> {
                    code(0, "LINE"); code(8, "0"); colorCode(e.color)
                    code(10, e.x1); code(20, e.y1); code(30, 0.0)
                    code(11, e.x2); code(21, e.y2); code(31, 0.0)
                }
                is Entity.Circle -> {
                    code(0, "CIRCLE"); code(8, "0"); colorCode(e.color)
                    code(10, e.cx); code(20, e.cy); code(30, 0.0)
                    code(40, e.r)
                }
                is Entity.Text -> {
                    code(0, "TEXT"); code(8, "0"); colorCode(e.color)
                    code(10, e.x); code(20, e.y); code(30, 0.0)
                    code(40, e.height)
                    code(1, e.value)
                }
                is Entity.Arc -> {
                    code(0, "ARC"); code(8, "0"); colorCode(e.color)
                    code(10, e.cx); code(20, e.cy); code(30, 0.0)
                    code(40, e.r)
                    code(50, e.startAngle); code(51, e.endAngle)
                }
                is Entity.Polyline -> {
                    code(0, "LWPOLYLINE"); code(8, "0"); colorCode(e.color)
                    code(90, e.points.size) // vertex count
                    code(70, "0") // 0 = open polyline (not closed)
                    e.points.forEach { (x, y) -> code(10, x); code(20, y) }
                }
            }
        }
        code(0, "ENDSEC")
        code(0, "EOF")
        return sb.toString()
    }
}
