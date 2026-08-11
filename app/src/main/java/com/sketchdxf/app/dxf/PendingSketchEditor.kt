package com.sketchdxf.app.dxf

import com.sketchdxf.app.data.SketchShape
import com.sketchdxf.app.data.SketchSource

/**
 * Transient handoff of the editor's starting state across the nav boundary into
 * SketchEditorScreen — the shape/source lists don't fit as nav-graph arguments. Set this just
 * before navigating to "editor"; the editor reads and clears it once on first composition.
 * Not process-death-safe, same tradeoff the rest of the app's Pending* singletons accept.
 */
object PendingSketchEditor {
    @Volatile var workId: Long = 0
    @Volatile var createdAt: Long = 0
    @Volatile var name: String = ""
    @Volatile var baseImagePath: String? = null
    @Volatile var shapes: List<SketchShape> = emptyList()
    @Volatile var sources: List<SketchSource> = emptyList()
    /** Previous DXF/preview files to delete once the new ones are written (edit-and-resave). */
    @Volatile var oldDxfPath: String = ""
    @Volatile var oldPreviewPath: String = ""

    fun set(
        workId: Long,
        createdAt: Long,
        name: String,
        baseImagePath: String?,
        shapes: List<SketchShape>,
        sources: List<SketchSource>,
        oldDxfPath: String = "",
        oldPreviewPath: String = ""
    ) {
        this.workId = workId
        this.createdAt = createdAt
        this.name = name
        this.baseImagePath = baseImagePath
        this.shapes = shapes
        this.sources = sources
        this.oldDxfPath = oldDxfPath
        this.oldPreviewPath = oldPreviewPath
    }
}
