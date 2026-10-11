package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.*

fun photo(
    id: String = "p", w: Int = 4000, h: Int = 3000, aspect: AspectRatio = AspectRatio.H16x9,
    zS: Double = 1.1, zE: Double = 1.5, durationMs: Long = 5000,
    easing: Easing = Easing.LINEAR, override: TransitionType? = null,
    sc: Pair<Double, Double> = 0.4 to 0.4, ec: Pair<Double, Double> = 0.6 to 0.6,
) = PhotoItem(
    id, "$id.jpg", null, w, h,
    KenBurnsMath.rectForZoom(w, h, aspect, zS, sc.first, sc.second),
    KenBurnsMath.rectForZoom(w, h, aspect, zE, ec.first, ec.second),
    durationMs, easing, override,
)
