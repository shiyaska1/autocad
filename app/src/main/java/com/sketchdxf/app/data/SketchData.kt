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
    val path: String = ""
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
}
