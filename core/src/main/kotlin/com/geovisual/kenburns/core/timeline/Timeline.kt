package com.geovisual.kenburns.core.timeline

import com.geovisual.kenburns.core.math.Easings
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.Easing
import com.geovisual.kenburns.core.model.FramingRect
import com.geovisual.kenburns.core.model.PhotoItem
import com.geovisual.kenburns.core.model.Project
import com.geovisual.kenburns.core.model.TransitionType
import kotlin.math.min

data class FrameState(
    val rectCurrent: FramingRect,
    /** Non-null only while the next photo is fading in (crossfade tail of the current item). */
    val rectNext: FramingRect?,
    /** Crossfade weight of the next photo, 0..1. 0 when [rectNext] is null. */
    val alpha: Double,
    /** Fade-to-black amount, 0..1 (DIP_TO_BLACK). */
    val dipBlack: Double,
    /**
     * Push-slide progress, 0..1, already eased (SLIDE boundaries only; 0 otherwise). The seam sits
     * at x = 1 - slide in output space: the current photo's crop is pushed out to the left, the
     * next photo's crop follows it in from the right. [alpha] stays 0 for SLIDE.
     */
    val slide: Double = 0.0,
)

/**
 * Timeline over a list of photos. Items play back to back (item i occupies
 * [startMs(i), startMs(i)+d_i)). For a CROSSFADE boundary of length c, photo i+1 becomes visible
 * at start_{i+1}-c and its motion clock starts then (see design section 5). For DIP_TO_BLACK the
 * boundary uses c/2 out of the tail of i and c/2 in at the head of i+1; no motion offset.
 * SLIDE overlaps exactly like CROSSFADE (same lead-in and second texture); only the way the two
 * photos are combined differs. The effective length c is clamped to half of each neighbouring item's duration so overlaps
 * from the two sides of one item can never collide.
 */
class Timeline(
    private val photos: List<PhotoItem>,
    private val defaultTransition: TransitionType,
    private val defaultTransitionMs: Long,
) {
    constructor(project: Project) : this(project.photos, project.transition, project.transitionDurationMs)

    val size: Int get() = photos.size

    private val starts: LongArray = LongArray(photos.size)
    val totalMs: Long

    /** Per boundary i (between photo i and i+1): type and effective length. */
    private val boundaryType: Array<TransitionType>
    private val boundaryMs: DoubleArray

    init {
        var t = 0L
        for (i in photos.indices) { starts[i] = t; t += photos[i].durationMs }
        totalMs = t
        val n = maxOf(photos.size - 1, 0)
        boundaryType = Array(n) { i ->
            if (defaultTransitionMs <= 0) TransitionType.CUT
            else photos[i].transitionOverride ?: defaultTransition
        }
        boundaryMs = DoubleArray(n) { i ->
            if (boundaryType[i] == TransitionType.CUT) 0.0
            else min(
                defaultTransitionMs.toDouble(),
                min(photos[i].durationMs, photos[i + 1].durationMs) / 2.0,
            )
        }
    }

    fun startMs(i: Int): Long = starts[i]

    fun transitionAfter(i: Int): TransitionType = if (i < boundaryType.size) boundaryType[i] else TransitionType.CUT

    private fun overlaps(t: TransitionType) = t == TransitionType.CROSSFADE || t == TransitionType.SLIDE

    /** Effective crossfade (or slide) overlap length before photo i starts (0 unless CROSSFADE). */
    fun crossfadeInMs(i: Int): Double =
        if (i > 0 && overlaps(boundaryType[i - 1])) boundaryMs[i - 1] else 0.0

    fun crossfadeOutMs(i: Int): Double =
        if (i < boundaryType.size && overlaps(boundaryType[i])) boundaryMs[i] else 0.0

    /**
     * True when item i's effect must upload photo i+1 as a second texture: only a CROSSFADE
     * boundary after it (project default or [PhotoItem.transitionOverride]) with a non-zero length.
     */
    fun needsNextPhoto(i: Int): Boolean = crossfadeOutMs(i) > 0.0

    /** Motion duration of photo i: its hold plus the crossfade lead-in. */
    fun motionDurationMs(i: Int): Double = photos[i].durationMs + crossfadeInMs(i)

    /** Index of the item containing global time [globalMs] (clamped to the last item). */
    fun itemAt(globalMs: Long): Int {
        if (photos.isEmpty()) return 0
        var i = photos.lastIndex
        while (i > 0 && starts[i] > globalMs) i--
        return i
    }

    /** [tMs] is local to item [itemIndex], in [0, d_i). */
    fun frameState(itemIndex: Int, tMs: Double): FrameState {
        val photo = photos[itemIndex]
        val d = photo.durationMs.toDouble()
        val cIn = crossfadeInMs(itemIndex)
        val current = KenBurnsMath.rectAt(photo, tMs + cIn, d + cIn)

        var rectNext: FramingRect? = null
        var alpha = 0.0
        var dip = 0.0
        var slide = 0.0

        if (itemIndex < boundaryType.size) {
            val c = boundaryMs[itemIndex]
            when (boundaryType[itemIndex]) {
                TransitionType.CROSSFADE, TransitionType.SLIDE -> {
                    val tailStart = d - c
                    if (c > 0.0 && tMs > tailStart) {
                        val nt = tMs - tailStart
                        val linear = (nt / c).coerceIn(0.0, 1.0)
                        if (boundaryType[itemIndex] == TransitionType.SLIDE) {
                            slide = Easings.apply(Easing.EASE_IN_OUT, linear)
                        } else {
                            alpha = linear
                        }
                        val next = photos[itemIndex + 1]
                        rectNext = KenBurnsMath.rectAt(next, nt, next.durationMs + c)
                    }
                }
                TransitionType.DIP_TO_BLACK -> {
                    val half = c / 2
                    if (half > 0.0 && tMs > d - half) dip = ((tMs - (d - half)) / half).coerceIn(0.0, 1.0)
                }
                TransitionType.CUT -> {}
            }
        }
        if (itemIndex > 0 && boundaryType[itemIndex - 1] == TransitionType.DIP_TO_BLACK) {
            val half = boundaryMs[itemIndex - 1] / 2
            if (half > 0.0 && tMs < half) dip = maxOf(dip, (1.0 - tMs / half).coerceIn(0.0, 1.0))
        }
        return FrameState(current, rectNext, alpha, dip, slide)
    }

    fun frameState(itemIndex: Int, tMs: Long): FrameState = frameState(itemIndex, tMs.toDouble())

    /** Global-time convenience for a future full-slideshow preview. */
    fun frameStateAtGlobal(globalMs: Long): FrameState {
        val i = itemAt(globalMs)
        return frameState(i, (globalMs - starts[i]).toDouble())
    }
}
