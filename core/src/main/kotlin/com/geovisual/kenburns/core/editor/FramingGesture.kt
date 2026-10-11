package com.geovisual.kenburns.core.editor

import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FramingRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Which of the two framing boxes. */
enum class FramingTarget { A, B }

/** Part of a box that was grabbed. Corner names say where the dragged corner is. */
enum class HitPart(val sx: Int, val sy: Int) {
    BODY(0, 0),
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1);

    val isCorner: Boolean get() = this != BODY
}

data class FramingHit(val target: FramingTarget, val part: HitPart)

/**
 * Geometry shared by every gesture on one photo: all coordinates are normalized to the image
 * (x over image width, y over image height). A valid box has h = w * [k] and a width between
 * [minW] (zoom [MAX_ZOOM]) and [baseW] (zoom [MIN_ZOOM]); zoom is relative to the base crop of
 * design section 4.
 */
class FramingGeometry(val imageW: Int, val imageH: Int, val aspect: AspectRatio) {
    val baseW: Double
    val baseH: Double
    /** h / w of every valid box, in normalized units. */
    val k: Double
    val minW: Double get() = baseW / MAX_ZOOM

    init {
        val (bw, bh) = KenBurnsMath.baseSize(imageW, imageH, aspect)
        baseW = bw; baseH = bh; k = bh / bw
    }

    fun zoomOf(r: FramingRect): Double = baseW / r.w

    companion object {
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 2.0
    }
}

/** An in-progress drag: what is grabbed, the rect when it was grabbed and the finger offset. */
data class FramingDrag(
    val target: FramingTarget,
    val part: HitPart,
    val startRect: FramingRect,
    val downX: Double,
    val downY: Double,
    /** Pointer minus grabbed point (center for body, corner for resize) at touch down. */
    val grabDx: Double,
    val grabDy: Double,
)

/** Which box was selected last, and the current drag if a finger is down. */
data class FramingGestureState(
    val selected: FramingTarget = FramingTarget.A,
    val drag: FramingDrag? = null,
)

data class FramingUpdate(val target: FramingTarget, val rect: FramingRect)

/**
 * Pure gesture engine for the two framing boxes. The UI converts finger positions into
 * normalized image coordinates and a touch slop (in the same units, so per axis: slop in
 * pixels divided by the displayed image width and height).
 *
 * Moves are stateless functions of the box at touch down plus the current pointer, so a finger
 * that pushes against a limit and comes back behaves predictably, with no accumulated drift.
 */
object FramingGesture {
    private const val TIE_EPS = 1e-9

    /**
     * Picks what a touch at (x, y) grabs. Corner handles (reach = slop ellipse around the corner,
     * which extends outside the box) win over bodies. If both boxes have a corner in reach the
     * nearest wins, and exact ties and overlapping bodies go to [selected], the box touched most
     * recently. Returns null when nothing is in reach.
     */
    fun hitTest(
        a: FramingRect, b: FramingRect, selected: FramingTarget,
        x: Double, y: Double, slopX: Double, slopY: Double,
    ): FramingHit? {
        val order = if (selected == FramingTarget.A) listOf(FramingTarget.A to a, FramingTarget.B to b)
        else listOf(FramingTarget.B to b, FramingTarget.A to a)

        var best: FramingHit? = null
        var bestDist = Double.MAX_VALUE
        for ((target, r) in order) {
            for (part in CORNER_PARTS) {
                val cx = if (part.sx < 0) r.left else r.right
                val cy = if (part.sy < 0) r.top else r.bottom
                val d = hypot((x - cx) / slopX, (y - cy) / slopY)
                if (d <= 1.0 && d < bestDist - TIE_EPS) { best = FramingHit(target, part); bestDist = d }
            }
        }
        if (best != null) return best
        for ((target, r) in order) {
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return FramingHit(target, HitPart.BODY)
        }
        return null
    }

    /** Touch down: selects the hit box and starts a drag, or leaves the state alone on a miss. */
    fun down(
        state: FramingGestureState, a: FramingRect, b: FramingRect,
        x: Double, y: Double, slopX: Double, slopY: Double,
    ): FramingGestureState {
        val hit = hitTest(a, b, state.selected, x, y, slopX, slopY) ?: return state.copy(drag = null)
        val r = if (hit.target == FramingTarget.A) a else b
        val (gx, gy) = when {
            hit.part == HitPart.BODY -> r.cx to r.cy
            else -> (if (hit.part.sx < 0) r.left else r.right) to (if (hit.part.sy < 0) r.top else r.bottom)
        }
        return FramingGestureState(hit.target, FramingDrag(hit.target, hit.part, r, x, y, x - gx, y - gy))
    }

    /** Pointer moved: the new rect for the grabbed box, or null when nothing is grabbed. */
    fun move(state: FramingGestureState, g: FramingGeometry, x: Double, y: Double): FramingUpdate? {
        val d = state.drag ?: return null
        val rect = if (d.part == HitPart.BODY) moveBody(d, x, y) else resize(d, g, x, y)
        return FramingUpdate(d.target, rect)
    }

    fun up(state: FramingGestureState): FramingGestureState = state.copy(drag = null)

    private fun moveBody(d: FramingDrag, x: Double, y: Double): FramingRect {
        val r = d.startRect
        val w = min(r.w, 1.0); val h = min(r.h, 1.0)
        return FramingRect(
            cx = (r.cx + (x - d.downX)).coerceIn(w / 2, 1.0 - w / 2),
            cy = (r.cy + (y - d.downY)).coerceIn(h / 2, 1.0 - h / 2),
            w = r.w, h = r.h,
        )
    }

    /**
     * Resize about the corner opposite the grabbed one (the anchor, fixed at touch down).
     * Width is the pointer's orthogonal projection (in image pixel space) onto the box diagonal measured from the
     * anchor, then clamped to the smaller of: zoom 1 width, the room between the anchor and the
     * image edge in each axis (so the anchor stays put), and floored at the zoom 2 width. When
     * the anchor sits so close to an edge that even the zoom 2 box does not fit, the floor wins
     * and the box is pushed back inside the image, which moves the anchor (the only case where
     * it does). Height always follows as w * k, so the aspect is exact.
     */
    private fun resize(d: FramingDrag, g: FramingGeometry, x: Double, y: Double): FramingRect {
        val r = d.startRect
        val sx = d.part.sx; val sy = d.part.sy
        val ax = if (sx < 0) r.right else r.left
        val ay = if (sy < 0) r.bottom else r.top
        val dx = sx * ((x - d.grabDx) - ax)
        val dy = sy * ((y - d.grabDy) - ay)
        val k = g.k
        // Project in pixel space: normalized axes have different pixel scales on a non-square image.
        val pw = g.imageW.toDouble(); val ph = g.imageH.toDouble()
        val raw = (dx * pw * pw + k * dy * ph * ph) / (pw * pw + k * k * ph * ph)

        val roomX = if (sx > 0) 1.0 - ax else ax
        val roomY = (if (sy > 0) 1.0 - ay else ay) / k
        val limit = min(g.baseW, min(roomX, roomY))
        val w = if (limit >= g.minW) raw.coerceIn(g.minW, limit) else g.minW
        val h = w * k
        return FramingRect(
            cx = (ax + sx * w / 2).coerceIn(w / 2, max(w / 2, 1.0 - w / 2)),
            cy = (ay + sy * h / 2).coerceIn(h / 2, max(h / 2, 1.0 - h / 2)),
            w = w, h = h,
        )
    }

    private val CORNER_PARTS = listOf(HitPart.TOP_LEFT, HitPart.TOP_RIGHT, HitPart.BOTTOM_LEFT, HitPart.BOTTOM_RIGHT)
}
