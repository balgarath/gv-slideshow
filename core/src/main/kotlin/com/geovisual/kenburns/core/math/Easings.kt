package com.geovisual.kenburns.core.math

import com.geovisual.kenburns.core.model.Easing

object Easings {
    /** Maps progress [0,1] to eased progress [0,1]. Input is clamped. */
    fun apply(easing: Easing, progress: Double): Double {
        val p = progress.coerceIn(0.0, 1.0)
        return when (easing) {
            Easing.LINEAR -> p
            Easing.EASE_IN_OUT -> p * p * p * (p * (p * 6.0 - 15.0) + 10.0)
            Easing.EASE_IN -> p * p * p
            Easing.EASE_OUT -> {
                val q = 1.0 - p
                1.0 - q * q * q
            }
        }
    }
}
