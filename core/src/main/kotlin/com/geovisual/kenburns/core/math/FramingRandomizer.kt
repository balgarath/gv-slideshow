package com.geovisual.kenburns.core.math

import com.geovisual.kenburns.core.model.AspectRatio
import com.geovisual.kenburns.core.model.FaceBox
import com.geovisual.kenburns.core.model.FramingRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Motion style picked for a photo with faces. */
enum class FramingStyle { PUSH_IN, PULL_OUT, KEEP_IN_FRAME }

/** [style] is null when the photo was framed without faces. */
data class RandomFraming(
    val start: FramingRect,
    val end: FramingRect,
    val durationMs: Long,
    val style: FramingStyle? = null,
)

/** Deterministic for a given seed and call sequence. */
class FramingRandomizer(seed: Long) {
    private val rnd = Random(seed)

    companion object {
        const val ZOOM_MIN = 1.05
        const val ZOOM_MAX = 1.6
        const val MIN_ZOOM_STEP = 1.12      // |ln zE - ln zS| >= ln(1.12), or
        const val MIN_PAN_FOR_MOTION = 0.10 // pan distance >= 0.10
        const val SOFT_PAN = 0.06
        const val CENTER_RETRIES = 8
        const val MIN_DURATION_MS = 4000
        const val MAX_DURATION_MS = 6000
        private const val ZOOM_ATTEMPTS = 50
    }

    /**
     * [faces] null or empty frames exactly as before. With faces, one [FramingStyle] is picked
     * with equal odds: PUSH_IN (wide to tight on the faces), PULL_OUT (the reverse) or
     * KEEP_IN_FRAME (random start and end that both contain the faces).
     */
    fun next(imageW: Int, imageH: Int, aspect: AspectRatio, faces: List<FaceBox>? = null): RandomFraming {
        if (!faces.isNullOrEmpty()) {
            FaceFraming.plan(faces, imageW, imageH, aspect)?.let { return nextWithFaces(it, imageW, imageH, aspect) }
        }
        return nextPlain(imageW, imageH, aspect)
    }

    private fun nextWithFaces(plan: FacePlan, imageW: Int, imageH: Int, aspect: AspectRatio): RandomFraming {
        val (bw, bh) = KenBurnsMath.baseSize(imageW, imageH, aspect)
        val box = plan.contain
        val style = FramingStyle.entries[rnd.nextInt(FramingStyle.entries.size)]

        /** Uniform center such that a rect of this size contains the whole target box. */
        fun containingRect(z: Double): FramingRect {
            val w = bw / z; val h = bh / z
            fun axis(size: Double, lo: Double, hi: Double): Double {
                val from = max(size / 2, hi - size / 2)       // keeps the far edge of the box inside
                val to = min(1.0 - size / 2, lo + size / 2)   // keeps the near edge of the box inside
                return if (to <= from) (from + to) / 2 else from + rnd.nextDouble() * (to - from)
            }
            return FramingRect(axis(w, box.left, box.right), axis(h, box.top, box.bottom), w, h)
        }

        val tight = FaceFraming.rectAround(box, plan.tightZoom, imageW, imageH, aspect)
        // Tight zoom is at least 1.121, so a wide rect up to tightZoom / 1.121 always adds visible zoom.
        val wideHi = plan.tightZoom / FaceFraming.MIN_TIGHT_ZOOM
        val wideLo = min(ZOOM_MIN, wideHi)

        when (style) {
            FramingStyle.PUSH_IN, FramingStyle.PULL_OUT -> {
                val wide = containingRect(wideLo + rnd.nextDouble() * (wideHi - wideLo))
                val (s, e) = if (style == FramingStyle.PUSH_IN) wide to tight else tight to wide
                return RandomFraming(s, e, duration(), style)
            }
            FramingStyle.KEEP_IN_FRAME -> {
                val minLnStep = ln(MIN_ZOOM_STEP)
                val lo = min(ZOOM_MIN, plan.tightZoom)
                repeat(ZOOM_ATTEMPTS) {
                    val a = containingRect(lo + rnd.nextDouble() * (plan.tightZoom - lo))
                    val b = containingRect(lo + rnd.nextDouble() * (plan.tightZoom - lo))
                    val step = abs(ln(b.w) - ln(a.w))
                    if (step >= minLnStep || hypot(b.cx - a.cx, b.cy - a.cy) >= MIN_PAN_FOR_MOTION) {
                        return RandomFraming(a, b, duration(), style)
                    }
                }
                // Guaranteed motion: full base crop against the tight rect (zoom ratio >= 1.121).
                val full = containingRect(1.0)
                val (s, e) = if (rnd.nextBoolean()) full to tight else tight to full
                return RandomFraming(s, e, duration(), style)
            }
        }
    }

    private fun nextPlain(imageW: Int, imageH: Int, aspect: AspectRatio): RandomFraming {
        val (bw, bh) = KenBurnsMath.baseSize(imageW, imageH, aspect)
        val minLnStep = ln(MIN_ZOOM_STEP)

        fun center(size: Double, ) = if (1.0 - size <= 0.0) 0.5 else size / 2 + rnd.nextDouble() * (1.0 - size)

        fun rect(z: Double, cx: Double, cy: Double) = FramingRect(cx, cy, bw / z, bh / z)

        for (attempt in 0 until ZOOM_ATTEMPTS) {
            var zS = ZOOM_MIN + rnd.nextDouble() * (ZOOM_MAX - ZOOM_MIN)
            var zE = ZOOM_MIN + rnd.nextDouble() * (ZOOM_MAX - ZOOM_MIN)
            val zoomIn = rnd.nextBoolean()
            if ((zE > zS) != zoomIn) { val t = zS; zS = zE; zE = t }
            val zoomStep = abs(ln(zE) - ln(zS))

            var sx = 0.0; var sy = 0.0; var ex = 0.0; var ey = 0.0
            var pan = 0.0
            for (c in 0 until CENTER_RETRIES) {
                sx = center(bw / zS); sy = center(bh / zS)
                ex = center(bw / zE); ey = center(bh / zE)
                pan = hypot(ex - sx, ey - sy)
                if (zoomStep >= minLnStep || pan >= SOFT_PAN) break
            }
            if (zoomStep >= minLnStep || pan >= MIN_PAN_FOR_MOTION) {
                return RandomFraming(rect(zS, sx, sy), rect(zE, ex, ey), duration())
            }
        }
        // Degenerate geometry: force a full-range zoom so motion is always visible.
        val zoomIn = rnd.nextBoolean()
        val zS = if (zoomIn) ZOOM_MIN else ZOOM_MAX
        val zE = if (zoomIn) ZOOM_MAX else ZOOM_MIN
        return RandomFraming(
            rect(zS, center(bw / zS), center(bh / zS)),
            rect(zE, center(bw / zE), center(bh / zE)),
            duration(),
        )
    }

    private fun duration(): Long = rnd.nextInt(MIN_DURATION_MS, MAX_DURATION_MS + 1).toLong()
}
