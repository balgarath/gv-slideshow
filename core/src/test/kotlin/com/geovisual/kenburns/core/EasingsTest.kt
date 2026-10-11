package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.math.Easings
import com.geovisual.kenburns.core.model.Easing
import org.junit.Assert.*
import org.junit.Test

class EasingsTest {
    @Test fun endpointsAreExact() {
        for (e in Easing.values()) {
            assertEquals(0.0, Easings.apply(e, 0.0), 1e-12)
            assertEquals(1.0, Easings.apply(e, 1.0), 1e-12)
        }
    }

    @Test fun monotonicNonDecreasingAndInRange() {
        for (e in Easing.values()) {
            var prev = -1.0
            for (i in 0..1000) {
                val v = Easings.apply(e, i / 1000.0)
                assertTrue("$e at $i", v >= prev - 1e-12 && v in 0.0..1.0)
                prev = v
            }
        }
    }

    @Test fun inputIsClamped() {
        for (e in Easing.values()) {
            assertEquals(0.0, Easings.apply(e, -3.0), 0.0)
            assertEquals(1.0, Easings.apply(e, 7.0), 0.0)
        }
    }

    @Test fun knownMidValues() {
        assertEquals(0.5, Easings.apply(Easing.LINEAR, 0.5), 1e-12)
        assertEquals(0.5, Easings.apply(Easing.EASE_IN_OUT, 0.5), 1e-12)
        assertEquals(0.125, Easings.apply(Easing.EASE_IN, 0.5), 1e-12)
        assertEquals(0.875, Easings.apply(Easing.EASE_OUT, 0.5), 1e-12)
    }
}
