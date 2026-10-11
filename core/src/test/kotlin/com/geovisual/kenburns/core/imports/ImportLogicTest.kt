package com.geovisual.kenburns.core.imports

import com.geovisual.kenburns.core.math.FramingRandomizer
import com.geovisual.kenburns.core.math.KenBurnsMath
import com.geovisual.kenburns.core.model.AspectRatio
import org.junit.Assert.*
import org.junit.Test

class ImportLogicTest {
    @Test fun smallImagesAreNotUpscaled() {
        assertEquals(ImportLogic.Size(1200, 800), ImportLogic.targetSize(1200, 800))
        assertEquals(ImportLogic.Size(2560, 1000), ImportLogic.targetSize(2560, 1000))
    }

    @Test fun largeImagesFitLongEdge() {
        assertEquals(ImportLogic.Size(2560, 1920), ImportLogic.targetSize(4000, 3000))
        assertEquals(ImportLogic.Size(1920, 2560), ImportLogic.targetSize(3000, 4000))
        assertEquals(ImportLogic.Size(2560, 1440), ImportLogic.targetSize(5120, 2880))
        val s = ImportLogic.targetSize(12000, 100)
        assertEquals(2560, s.width); assertTrue(s.height >= 1)
    }

    @Test fun targetPreservesAspectApproximately() {
        val s = ImportLogic.targetSize(4032, 3024)
        assertEquals(4032.0 / 3024.0, s.width.toDouble() / s.height, 0.002)
    }

    @Test fun naming() {
        assertEquals("abc.jpg", ImportLogic.photoFileName("abc"))
        assertEquals("projects/p1/photos", ImportLogic.photosDirPath("p1"))
    }

    @Test fun newPhotoItemUsesStoredDimensionsAndValidRects() {
        val r = FramingRandomizer(3)
        for (a in AspectRatio.values()) {
            val p = ImportLogic.newPhotoItem("id1", "content://x/1", 1920, 2560, a, r)
            assertEquals("id1.jpg", p.fileName)
            assertEquals(1920, p.imageWidth); assertEquals(2560, p.imageHeight)
            assertEquals("content://x/1", p.sourceUri)
            assertTrue(p.startRect.isValidFor(1920, 2560, a) && p.endRect.isValidFor(1920, 2560, a))
            assertTrue(p.durationMs in 4000..6000)
        }
    }

    @Test fun reframeMatchesNewAspectAndKeepsTiming() {
        val rnd = FramingRandomizer(7)
        val p = ImportLogic.newPhotoItem("id", null, 2560, 1920, AspectRatio.H16x9, rnd)
        for (a in AspectRatio.entries) {
            val r = ImportLogic.reframe(p, a, rnd)
            assertTrue(r.startRect.isValidFor(2560, 1920, a) && r.endRect.isValidFor(2560, 1920, a))
            assertTrue(r.startRect.isInBounds() && r.endRect.isInBounds())
            assertEquals(p.durationMs, r.durationMs)
            assertEquals(p.id, r.id); assertEquals(p.fileName, r.fileName)
        }
    }

    @Test fun convertAspectKeepsCenterAndZoomWhereRoomAllows() {
        val rnd = FramingRandomizer(11)
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 3000 to 3000, 6000 to 1500)) {
            for (from in AspectRatio.entries) for (to in AspectRatio.entries) {
                repeat(20) {
                    val p = ImportLogic.newPhotoItem("id", null, w, h, from, rnd)
                    val c = ImportLogic.convertAspect(p, from, to)
                    for ((old, new) in listOf(p.startRect to c.startRect, p.endRect to c.endRect)) {
                        assertTrue(new.isValidFor(w, h, to)); assertTrue(new.isInBounds())
                        val zOld = KenBurnsMath.zoomOf(old, w, h, from)
                        assertEquals(zOld, KenBurnsMath.zoomOf(new, w, h, to), 1e-9)
                        // Center only moves when clamping needs it.
                        if (new.left > 1e-9 && new.right < 1 - 1e-9) assertEquals(old.cx, new.cx, 1e-9)
                        if (new.top > 1e-9 && new.bottom < 1 - 1e-9) assertEquals(old.cy, new.cy, 1e-9)
                    }
                    assertEquals(p.durationMs, c.durationMs); assertEquals(p.easing, c.easing)
                }
            }
        }
    }

    @Test fun convertSameAspectIsIdentityAndRoundTripKeepsZoom() {
        val p = ImportLogic.newPhotoItem("id", null, 4000, 3000, AspectRatio.H16x9, FramingRandomizer(5))
        val same = ImportLogic.convertAspect(p, AspectRatio.H16x9, AspectRatio.H16x9)
        assertEquals(p.startRect.w, same.startRect.w, 1e-12); assertEquals(p.startRect.cx, same.startRect.cx, 1e-12)
        val back = ImportLogic.convertAspect(ImportLogic.convertAspect(p, AspectRatio.H16x9, AspectRatio.V9x16), AspectRatio.V9x16, AspectRatio.H16x9)
        assertEquals(p.endRect.w, back.endRect.w, 1e-9)
    }
}
