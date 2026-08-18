package com.sketchdxf.app.ar

/**
 * Transient handoff of an AR Measure session's tapped points back into the already-open
 * SketchEditorScreen — same "Pending*" singleton tradeoff PendingSketchEditor/PendingUpdateInfo
 * already accept (not process-death-safe; fine here since the AR screen is only ever a few taps
 * away and nothing about it needs to survive a process restart).
 *
 * [points] are real-world (x, z) meters — a horizontal plan-view projection of each tapped AR
 * anchor (dropping y/height, since this is a floor-plan app), in the order they were tapped, and
 * already relative to the first point (so points[0] is always (0, 0)). Deliberately NOT rotated to
 * any particular orientation: ARCore's world X/Z axes are fixed to wherever the session happened
 * to be facing when tracking started, not to whatever's actually being measured, so the resulting
 * line chain can land at any angle on the plan. An earlier version tried auto-rotating the first
 * segment to horizontal, but that's just a guess that's wrong exactly as often as it's right (a
 * vertical trace got flattened too) — real angles between tapped points are preserved as measured,
 * and the editor's own Rotate tool (Ortho on for 90° snapping) is the correct place to reorient the
 * inserted chain to match the drawing. The editor scales these points by its own current px-per-mm
 * and lets the user tap where to drop the resulting connected line chain, same as Insert Image/
 * Insert Block.
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
