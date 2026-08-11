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
    val status: String = "DRAFT" // DRAFT (not yet saved with a DXF) or FINALIZED
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
}

/**
 * One editable vector primitive inside a work's editor canvas — a line, circle, or text label.
 * Coordinates are in the editor's own canvas-pixel space (see SketchEditorScreen), not a
 * physical unit; [realLength] (mm) is only meaningful once [confirmed] is set on a LINE.
 */
@Entity(tableName = "sketch_shapes")
data class SketchShape(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workId: Long,
    val kind: String,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val r: Float = 0f,
    val label: String = "",
    val realLength: Double = 0.0,
    val confirmed: Boolean = false
)

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
