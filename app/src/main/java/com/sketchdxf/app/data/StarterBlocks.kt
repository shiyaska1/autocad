package com.sketchdxf.app.data

/**
 * A small starter set of generic architectural plan symbols (door, window, light, camera) that
 * the user can seed into their own Block library with one tap (see BlockLibraryScreen's "Add
 * starter symbols" button). These are standard, uncreative drafting conventions — a door swing
 * arc, a window's parallel wall-break lines, a light fixture's circle-and-cross, a camera's
 * lens-and-view-cone — not sourced or copied from any third-party "free CAD blocks" site, which
 * typically carry licensing terms that don't cover redistributing the files inside a published
 * app. Every shape is authored directly in millimetres (pxPerMm = 1f when saved), relative to a
 * local origin so it drops in anywhere at the current drawing's real-world scale.
 */
object StarterBlocks {

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        SketchShape(workId = 0, kind = ShapeKind.LINE, x1 = x1, y1 = y1, x2 = x2, y2 = y2)

    private fun circle(cx: Float, cy: Float, r: Float) =
        SketchShape(workId = 0, kind = ShapeKind.CIRCLE, cx = cx, cy = cy, r = r)

    /** A closed rectangle (four LINEs) from one corner to the opposite one — the basic building
     *  block for the plan-view furniture/kitchen symbols below. */
    private fun rect(x1: Float, y1: Float, x2: Float, y2: Float): List<SketchShape> = listOf(
        line(x1, y1, x2, y1), line(x2, y1, x2, y2), line(x2, y2, x1, y2), line(x1, y2, x1, y1)
    )

    /** A quarter-circle door-swing arc, hinged at [hx],[hy]: the leaf sweeps from the wall
     *  opening at ([hx]+[reach],[hy]) to fully open at ([hx],[hy]+[reach]) (or mirrored/flipped
     *  per [mirrorX]/[flipY], for double doors and different hinge sides). */
    private fun doorSwing(hx: Float, hy: Float, reach: Float, mirrorX: Boolean = false, flipY: Boolean = false): List<SketchShape> {
        val sx = if (mirrorX) -1f else 1f
        val sy = if (flipY) -1f else 1f
        val openX = hx; val openY = hy + reach * sy
        val jambX = hx + reach * sx; val jambY = hy
        return listOf(
            line(hx, hy, openX, openY), // the leaf, drawn open
            SketchShape(
                workId = 0, kind = ShapeKind.ARC, cx = hx, cy = hy, r = reach,
                x1 = jambX, y1 = jambY, x2 = openX, y2 = openY, major = false
            )
        )
    }

    private fun door(nameWidth: Int): List<SketchShape> = doorSwing(0f, 0f, nameWidth.toFloat())

    private fun doubleDoor(totalWidth: Int): List<SketchShape> {
        val half = totalWidth / 2f
        return doorSwing(0f, 0f, half) + doorSwing(totalWidth.toFloat(), 0f, half, mirrorX = true)
    }

    /** A window's plan symbol: two parallel lines marking the wall break either side of the
     *  glazing, a jamb line closing each end, and a glazing line (or two, for a double window's
     *  central mullion) down the middle. */
    private fun window(width: Int, mullions: List<Float> = listOf(0.5f)): List<SketchShape> {
        val w = width.toFloat(); val half = 100f
        val shapes = mutableListOf(
            line(0f, -half, w, -half),
            line(0f, half, w, half),
            line(0f, -half, 0f, half),
            line(w, -half, w, half)
        )
        mullions.forEach { frac -> shapes.add(line(w * frac, -half, w * frac, half)) }
        return shapes
    }

    private fun ceilingLight(): List<SketchShape> {
        val r = 150f; val d = r * 0.75f
        return listOf(circle(0f, 0f, r), line(-d, -d, d, d), line(-d, d, d, -d))
    }

    private fun wallLight(): List<SketchShape> = listOf(circle(0f, 0f, 100f), line(0f, -100f, 0f, -200f))

    private fun downlight(): List<SketchShape> = listOf(circle(0f, 0f, 150f), circle(0f, 0f, 70f))

    private fun pendantLight(): List<SketchShape> = listOf(circle(0f, 0f, 120f), line(0f, 0f, -150f, -150f))

    private fun domeCamera(): List<SketchShape> = listOf(
        circle(0f, 0f, 150f), circle(0f, 0f, 60f),
        line(150f, 0f, 300f, -80f), line(150f, 0f, 300f, 80f), line(300f, -80f, 300f, 80f)
    )

    private fun bulletCamera(): List<SketchShape> = listOf(
        line(0f, -60f, 200f, -60f), line(200f, -60f, 200f, 60f),
        line(200f, 60f, 0f, 60f), line(0f, 60f, 0f, -60f),
        circle(200f, 0f, 60f),
        line(260f, -100f, 500f, -220f), line(260f, 100f, 500f, 220f)
    )

    /** A sofa/armchair: outer body, a backrest line set in from one long edge, and armrest
     *  dividers at each end. [depth] is front-to-back, [width] is end-to-end. */
    private fun sofa(width: Float, depth: Float, armWidth: Float = 150f): List<SketchShape> {
        val back = depth * 0.18f
        return rect(0f, 0f, width, depth) + listOf(
            line(0f, back, width, back), // backrest
            line(armWidth, 0f, armWidth, depth), // left arm divider
            line(width - armWidth, 0f, width - armWidth, depth) // right arm divider
        )
    }

    private fun diningChair(size: Float = 450f): List<SketchShape> {
        val backDepth = size * 0.2f
        return rect(0f, 0f, size, size) + line(0f, backDepth, size, backDepth)
    }

    /** A bed: body rectangle plus a shallower rectangle at the head end for the pillow area. */
    private fun bed(width: Float, length: Float): List<SketchShape> {
        val pillowDepth = length * 0.16f
        return rect(0f, 0f, width, length) + rect(width * 0.08f, width * 0.02f, width * 0.92f, pillowDepth)
    }

    /** A wardrobe/cabinet: outer rectangle with a diagonal marking it as storage (a common plan
     *  convention, distinguishing it from a plain rectangle at a glance). */
    private fun wardrobe(width: Float, depth: Float): List<SketchShape> =
        rect(0f, 0f, width, depth) + line(0f, 0f, width, depth)

    /** A kitchen sink: outer counter cutout with one or two basin rectangles inset. */
    private fun sink(width: Float, depth: Float, basins: Int): List<SketchShape> {
        val margin = depth * 0.15f
        val shapes = rect(0f, 0f, width, depth).toMutableList()
        val basinWidth = (width - margin * (basins + 1)) / basins
        for (i in 0 until basins) {
            val bx = margin + i * (basinWidth + margin)
            shapes.addAll(rect(bx, margin, bx + basinWidth, depth - margin))
        }
        return shapes
    }

    /** A cooktop/stove: outer body with round burners arranged in a grid. */
    private fun cooktop(size: Float, burners: Int): List<SketchShape> {
        val r = size * 0.12f
        val positions = if (burners == 4) listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f)
        else listOf(0.5f to 0.3f, 0.3f to 0.7f, 0.7f to 0.7f)
        return rect(0f, 0f, size, size) + positions.map { (fx, fy) -> circle(size * fx, size * fy, r) }
    }

    /** A fridge/appliance: a plain body rectangle with a diagonal door-swing marker, same
     *  convention as [wardrobe] but square, matching how these read in a kitchen plan. */
    private fun applianceBox(width: Float, depth: Float): List<SketchShape> =
        rect(0f, 0f, width, depth) + line(width, 0f, 0f, depth)

    /** name, category, shapes — one entry per starter symbol. */
    fun all(): List<Triple<String, String, List<SketchShape>>> = listOf(
        Triple("Door 700", "Doors", door(700)),
        Triple("Door 800", "Doors", door(800)),
        Triple("Door 900", "Doors", door(900)),
        Triple("Double Door 1600", "Doors", doubleDoor(1600)),
        Triple("Window 900", "Windows", window(900)),
        Triple("Window 1200", "Windows", window(1200)),
        Triple("Window 1800 (double)", "Windows", window(1800, mullions = listOf(1f / 3f, 2f / 3f))),
        Triple("Ceiling Light", "Lighting", ceilingLight()),
        Triple("Wall Light", "Lighting", wallLight()),
        Triple("Downlight", "Lighting", downlight()),
        Triple("Pendant Light", "Lighting", pendantLight()),
        Triple("Dome Camera", "Camera", domeCamera()),
        Triple("Bullet Camera", "Camera", bulletCamera()),
        Triple("Sofa 2-Seater", "Furniture", sofa(1600f, 800f)),
        Triple("Sofa 3-Seater", "Furniture", sofa(2100f, 800f)),
        Triple("Armchair", "Furniture", sofa(800f, 800f, armWidth = 150f)),
        Triple("Dining Table", "Furniture", rect(0f, 0f, 1400f, 800f)),
        Triple("Dining Chair", "Furniture", diningChair()),
        Triple("Coffee Table", "Furniture", rect(0f, 0f, 1000f, 500f)),
        Triple("Bed Single", "Furniture", bed(900f, 2000f)),
        Triple("Bed Double", "Furniture", bed(1500f, 2000f)),
        Triple("Wardrobe", "Furniture", wardrobe(1200f, 600f)),
        Triple("TV Unit", "Furniture", rect(0f, 0f, 1200f, 400f)),
        Triple("Kitchen Counter", "Kitchen", rect(0f, 0f, 1800f, 600f)),
        Triple("Kitchen Island", "Kitchen", rect(0f, 0f, 1200f, 800f)),
        Triple("Kitchen Sink (Single)", "Kitchen", sink(600f, 500f, 1)),
        Triple("Kitchen Sink (Double)", "Kitchen", sink(800f, 500f, 2)),
        Triple("Cooktop (4 Burner)", "Kitchen", cooktop(600f, 4)),
        Triple("Refrigerator", "Kitchen", applianceBox(700f, 700f)),
        Triple("Dishwasher", "Kitchen", applianceBox(600f, 600f))
    )
}
