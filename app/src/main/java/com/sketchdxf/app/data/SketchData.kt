package com.sketchdxf.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A single "sketch to DXF" conversion job: one name, one result file, many sources/shapes. */
@Entity(tableName = "sketch_works")
data class SketchWork(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val dxfPath: String = "",
    val previewPath: String = "",
    val status: String = "DRAFT", // DRAFT (not yet saved with a DXF) or FINALIZED
    /** "mm" or "cm" — the unit every dimension typed into this work is shown/entered in. Defaults
     *  to "mm" so migrated pre-existing rows (created before cm support) keep reading the way they
     *  were originally saved; new works are created with "cm" explicitly (see SketchEditorScreen). */
    val unit: String = "mm"
)

/** The original photo(s)/PDF page(s) the user picked or captured for a work. */
@Entity(tableName = "sketch_sources")
data class SketchSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workId: Long,
    val path: String,
    val name: String = "",
    val mime: String = ""
)

/** A reusable group of shapes ("Insert Block", AutoCAD-style) saved from a selection — a door,
 *  window, furniture symbol, etc. Geometry in [shapesData] is relative to the block's own local
 *  origin (0,0), so it can be dropped anywhere; [pxPerMm] records the pixel-per-mm scale that was
 *  active in the drawing it was saved from, so a later insert can reproduce its real-world size
 *  even in a drawing calibrated differently — see [SketchBlockCodec] and the editor's Block tool. */
@Entity(tableName = "sketch_blocks")
data class SketchBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String,
    val createdAt: Long,
    val shapesData: String,
    val pxPerMm: Float
)

/** Encodes/decodes a block's shape list as plain text, the same hand-rolled way [SketchPath]
 *  does — one shape per record, fields separated by a control character that never appears in
 *  ordinary typed text, so no escaping is needed for the common case. */
object SketchBlockCodec {
    private const val FS = "\u0001"
    private const val RS = "\u0002"

    fun serialize(shapes: List<SketchShape>): String = shapes.joinToString(RS) { s ->
        listOf(
            s.kind, s.x1, s.y1, s.x2, s.y2, s.cx, s.cy, s.r,
            s.label.replace(FS, " ").replace(RS, " "),
            s.realLength, s.confirmed, s.path, s.color ?: "", s.major
        ).joinToString(FS)
    }

    fun deserialize(text: String): List<SketchShape> {
        if (text.isBlank()) return emptyList()
        return text.split(RS).mapNotNull { rec ->
            val f = rec.split(FS)
            if (f.size < 13) return@mapNotNull null
            SketchShape(
                workId = 0, kind = f[0],
                x1 = f[1].toFloatOrNull() ?: 0f, y1 = f[2].toFloatOrNull() ?: 0f,
                x2 = f[3].toFloatOrNull() ?: 0f, y2 = f[4].toFloatOrNull() ?: 0f,
                cx = f[5].toFloatOrNull() ?: 0f, cy = f[6].toFloatOrNull() ?: 0f,
                r = f[7].toFloatOrNull() ?: 0f,
                label = f[8],
                realLength = f[9].toDoubleOrNull() ?: 0.0,
                confirmed = f[10].toBooleanStrictOrNull() ?: false,
                path = f[11],
                color = f[12].toIntOrNull(),
                major = f.getOrNull(13)?.toBooleanStrictOrNull() ?: false
            )
        }
    }
}

object ShapeKind {
    const val LINE = "LINE"
    const val CIRCLE = "CIRCLE"
    const val TEXT = "TEXT"
    /** A linear/aligned dimension annotation: x1,y1 -> x2,y2 with [SketchShape.label] as its text. */
    const val DIMENSION = "DIMENSION"
    /** A hand-drawn (pencil) stroke: an arbitrary point path stored in [SketchShape.path]. */
    const val FREEHAND = "FREEHAND"
    /** A fillet arc: centre (cx,cy) + radius r, boundary points x1,y1 / x2,y2 — see [SketchArc]. */
    const val ARC = "ARC"
    /** A cleaned-up FREEHAND stroke: same [SketchShape.path] point list, but exported to DXF as
     *  one true LWPOLYLINE entity instead of a chain of separate LINE segments — see
     *  SketchEditorScreen's "Convert to Polyline" action and [SketchCircleFit]. */
    const val POLYLINE = "POLYLINE"
}

/**
 * One editable vector primitive inside a work's editor canvas — a line, circle, text label,
 * dimension, freehand stroke, or fillet arc. Coordinates are in the editor's own canvas-pixel
 * space (see SketchEditorScreen), not a physical unit; [realLength] (mm) is only meaningful once
 * [confirmed] is set on a LINE.
 */
@Entity(tableName = "sketch_shapes")
data class SketchShape(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workId: Long,
    val kind: String,
    /** ARC: one boundary point (with x2,y2 the other). */
    val x1: Float = 0f,
    val y1: Float = 0f,
    /** ARC: the other boundary point. */
    val x2: Float = 0f,
    val y2: Float = 0f,
    /** ARC: centre. */
    val cx: Float = 0f,
    val cy: Float = 0f,
    /** ARC: radius. */
    val r: Float = 0f,
    val label: String = "",
    val realLength: Double = 0.0,
    val confirmed: Boolean = false,
    /** FREEHAND only: the stroke's points as "x,y;x,y;x,y…" — see [SketchPath]. */
    val path: String = "",
    /** Explicit ARGB colour (from Color.toArgb()), or null to use this shape kind's usual default
     *  (e.g. green when a LINE is confirmed, blue otherwise) — see SketchEditorScreen's colour picker. */
    val color: Int? = null,
    /** ARC only: false draws the shorter (minor) arc between x1,y1 and x2,y2 around the centre —
     *  true draws the longer (major) one instead. Fillet/Extend-generated arcs are always minor
     *  (the default); a 3-point ARC tool pick can need either, decided by which side of the
     *  chord its middle point fell on — see [SketchArc.minorArcContains]. */
    val major: Boolean = false,
    /** TEXT/DIMENSION only: real-world label height in mm, or 0 to use the kind's usual fixed
     *  on-screen size (unset — every shape saved before this existed reads back as 0, so old
     *  labels keep looking exactly as they always did). Feeds both the on-canvas pixel size
     *  (via currentPxPerMm()) and the exported DXF TEXT entity's height, so a size picked here
     *  actually means something in AutoCAD too, not just on this screen. */
    val fontSize: Float = 0f
)

/** Encodes/decodes a [SketchShape.path] freehand point list, kept as plain text so it round-trips
 *  through Room without a type converter. */
object SketchPath {
    fun parse(path: String): List<Pair<Float, Float>> =
        if (path.isBlank()) emptyList()
        else path.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            x to y
        }

    fun serialize(points: List<Pair<Float, Float>>): String = points.joinToString(";") { (x, y) -> "$x,$y" }
}

/** Pure-math helpers for an ARC shape: centre + radius + two boundary points, no stored angles. */
object SketchArc {
    /** Degrees of the point (px,py) around (cx,cy), atan2 convention (0° = +x axis). */
    fun angleDeg(cx: Float, cy: Float, px: Float, py: Float): Float =
        Math.toDegrees(kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()

    /**
     * (startAngleDeg, sweepDeg) for the SHORTER arc from (x1,y1) to (x2,y2) around the centre —
     * sweep is in (-180, 180]. This is always the physically-correct fillet arc: a real corner's
     * fillet sweep is `180° - theta` for the angle theta between the two lines (0° < theta < 180°),
     * which is always the minor arc. Orientation-agnostic, so the same call works whether the
     * caller's space is screen (Y-down) or DXF (Y-up).
     */
    fun minorSweep(cx: Float, cy: Float, x1: Float, y1: Float, x2: Float, y2: Float): Pair<Float, Float> {
        val a1 = angleDeg(cx, cy, x1, y1)
        val a2 = angleDeg(cx, cy, x2, y2)
        var sweep = (a2 - a1) % 360f
        if (sweep > 180f) sweep -= 360f
        if (sweep < -180f) sweep += 360f
        return a1 to sweep
    }

    /** (startAngleDeg, sweepDeg) honouring [SketchShape.major]: the minor arc as-is, or its
     *  complement — same start angle, the other way round, magnitude 360° minus the minor sweep. */
    fun sweepFor(cx: Float, cy: Float, x1: Float, y1: Float, x2: Float, y2: Float, major: Boolean): Pair<Float, Float> {
        val (start, minor) = minorSweep(cx, cy, x1, y1, x2, y2)
        if (!major) return start to minor
        val majorSweep = if (minor >= 0f) minor - 360f else minor + 360f
        return start to majorSweep
    }

    /** True if the point (px,py) lies along the MINOR arc between (x1,y1) and (x2,y2) around the
     *  centre, false if it lies along the major one — used to decide [SketchShape.major] when a
     *  3-point ARC pick's middle point could be on either side of the chord. */
    fun minorArcContains(cx: Float, cy: Float, x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float): Boolean {
        val (start, sweep) = minorSweep(cx, cy, x1, y1, x2, y2)
        val aP = angleDeg(cx, cy, px, py)
        var rel = (aP - start) % 360f
        if (rel > 180f) rel -= 360f
        if (rel < -180f) rel += 360f
        return if (sweep >= 0f) rel in 0f..sweep else rel in sweep..0f
    }

    /** Centre of the circle through 3 non-collinear points, or null if they're (near-)collinear. */
    fun circumcenter(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Pair<Float, Float>? {
        val d = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (kotlin.math.abs(d) < 1e-4f) return null
        val ax2ay2 = ax * ax + ay * ay
        val bx2by2 = bx * bx + by * by
        val cx2cy2 = cx * cx + cy * cy
        val ux = (ax2ay2 * (by - cy) + bx2by2 * (cy - ay) + cx2cy2 * (ay - by)) / d
        val uy = (ax2ay2 * (cx - bx) + bx2by2 * (ax - cx) + cx2cy2 * (bx - ax)) / d
        return ux to uy
    }
}

/** Recognises a hand-drawn circle: a closed loop that stays roughly the same distance from its
 *  own centroid the whole way round. Used by "Convert to Polyline" to turn a freehand stroke
 *  that's clearly meant as a circle into a real CIRCLE instead of a jagged polyline. */
object SketchCircleFit {
    /** (cx, cy, r) if [points] look like a circle, else null. */
    fun tryFit(points: List<Pair<Float, Float>>): Triple<Float, Float, Float>? {
        if (points.size < 8) return null
        val cx = points.map { it.first }.average().toFloat()
        val cy = points.map { it.second }.average().toFloat()
        val radii = points.map { kotlin.math.hypot((it.first - cx).toDouble(), (it.second - cy).toDouble()) }
        val meanR = radii.average()
        if (meanR < 4.0) return null // too small to meaningfully judge roundness
        val variance = radii.sumOf { (it - meanR) * (it - meanR) } / radii.size
        val relDeviation = kotlin.math.sqrt(variance) / meanR
        val first = points.first(); val last = points.last()
        val closureGap = kotlin.math.hypot((first.first - last.first).toDouble(), (first.second - last.second).toDouble())
        val closed = closureGap < meanR * 0.35
        return if (relDeviation < 0.18 && closed) Triple(cx, cy, meanR.toFloat()) else null
    }
}

@Dao
interface SketchDao {
    @Query("SELECT * FROM sketch_works ORDER BY updatedAt DESC")
    fun works(): Flow<List<SketchWork>>

    @Query("SELECT * FROM sketch_works WHERE id = :id")
    suspend fun work(id: Long): SketchWork?

    @Insert
    suspend fun insertWork(work: SketchWork): Long

    @Update
    suspend fun updateWork(work: SketchWork)

    @Delete
    suspend fun deleteWork(work: SketchWork)

    @Query("SELECT * FROM sketch_sources WHERE workId = :workId ORDER BY id")
    suspend fun sourcesFor(workId: Long): List<SketchSource>

    @Insert
    suspend fun insertSource(source: SketchSource): Long

    @Query("DELETE FROM sketch_sources WHERE workId = :workId")
    suspend fun deleteSourcesFor(workId: Long)

    @Query("SELECT * FROM sketch_shapes WHERE workId = :workId ORDER BY id")
    suspend fun shapesFor(workId: Long): List<SketchShape>

    @Insert
    suspend fun insertShapes(shapes: List<SketchShape>)

    @Query("DELETE FROM sketch_shapes WHERE workId = :workId")
    suspend fun deleteShapesFor(workId: Long)

    @Query("SELECT * FROM sketch_blocks ORDER BY category, name")
    suspend fun allBlocks(): List<SketchBlock>

    @Insert
    suspend fun insertBlock(block: SketchBlock): Long

    @Delete
    suspend fun deleteBlock(block: SketchBlock)
}
