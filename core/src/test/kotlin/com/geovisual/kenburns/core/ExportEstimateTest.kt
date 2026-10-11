package com.geovisual.kenburns.core

import com.geovisual.kenburns.core.export.ExportEstimate
import com.geovisual.kenburns.core.model.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEstimateTest {
    @Test fun bitrateTable() {
        assertEquals(20_000_000, ExportEstimate.bitrateBps(AspectRatio.V9x16))
        assertEquals(20_000_000, ExportEstimate.bitrateBps(AspectRatio.H16x9))
        assertEquals(12_000_000, ExportEstimate.bitrateBps(AspectRatio.S1x1))
        // Every format has an entry at or above the 60 fps Kush Gauge, so 60 fps never gets a 30 fps budget.
        for (a in AspectRatio.entries) {
            val gauge60 = (a.w * a.h * 60f * 0.07 * 2).toInt()
            assertTrue("${a.name} below the 60 fps gauge", ExportEstimate.bitrateBps(a) >= gauge60)
        }
    }

    @Test fun sizeIsBitrateTimesDuration() {
        // 30 s at 20 Mbps is 75 MB.
        val bytes = ExportEstimate.sizeBytes(AspectRatio.V9x16, 30_000)
        assertEquals(75_000_000L, bytes)
        assertEquals("~75 MB", ExportEstimate.formatSize(bytes))
        // The on-device 1:1 export was 23.60 s: 12 Mbps gives ~35 MB.
        assertEquals(35_400_000L, ExportEstimate.sizeBytes(AspectRatio.S1x1, 23_600))
        assertEquals(0L, ExportEstimate.sizeBytes(AspectRatio.V9x16, 0))
    }

    @Test fun formatting() {
        assertEquals("63 MB", ExportEstimate.formatActualSize(63_400_000))
        assertEquals("1.2 GB", ExportEstimate.formatActualSize(1_234_000_000))
        assertEquals("0:00", ExportEstimate.formatLength(0))
        assertEquals("0:15", ExportEstimate.formatLength(14_600))
        assertEquals("1:05", ExportEstimate.formatLength(65_000))
        assertEquals("~1.2 GB", ExportEstimate.formatSize(1_200_000_000L))
    }
}
