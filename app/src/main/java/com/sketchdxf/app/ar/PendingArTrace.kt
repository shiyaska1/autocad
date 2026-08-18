package com.sketchdxf.app.ar

/**
 * Transient handoff of an AR Measure session's tapped points back into the already-open
 * SketchEditorScreen — same "Pending*" singleton tradeoff PendingSketchEditor/PendingUpdateInfo
 * already accept (not process-death-safe; fine here since the AR screen is only ever a few taps
 * away and nothing about it needs to survive a process restart).
 *
 * [points] are real-world (x, z) meters — a horizontal plan-view projection of each tapped AR
 * anchor (dropping y/height, since this is a floor-plan app), in the order they were tapped, and
 * already relative to the first point (so points[0] is always (0, 0)). The editor scales them by
 * its own current px-per-mm and lets the user tap where to drop the resulting connected line
 * chain, same as Insert Image/Insert Block.
 */
object PendingArTrace {
    @Volatile var points: List<Pair<Float, Float>> = emptyList()

    fun set(points: List<Pair<Float, Float>>) {
        this.points = points
    }

    fun consume(): List<Pair<Float, Float>> {
        val p = points
        points = emptyList()
        return p
    }
}
