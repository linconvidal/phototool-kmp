package br.com.lincon.phototool.ui

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crops are asked for, kept and painted on one threshold. When asking started at 1:1 and clearing at
 * 4x, zooming out left sharp crops from an earlier area painted over the upscaled preview — the
 * "two images at once" the screen showed around 500%.
 */
class RegionZoomTest {
    private val phone = IntSize(393, 680)
    private val largePixelZoom = detailPixelZoom(phone, IntSize(4000, 6000))

    @Test fun cropsStartAtTheSameZoomForEveryPhotoSize() {
        assertEquals(4f, detailRegionZoom(largePixelZoom))
        val small = detailPixelZoom(phone, IntSize(800, 600))
        assertEquals(minOf(small, 4f), detailRegionZoom(small))
    }

    @Test fun nothingIsPaintedBelowTheThresholdAndEverythingAboveIt() {
        val regionZoom = detailRegionZoom(largePixelZoom)
        assertFalse(regionZoom - .01f >= regionZoom, "below the threshold no crop is painted")
        assertTrue(regionZoom + .01f >= regionZoom, "from the threshold on every crop is painted")
    }

    @Test fun theOldAsymmetryIsGone() {
        // Asking used to require 1:1 (10.2x here) while clearing only happened below 4x, so every
        // zoom in between kept painting crops nobody was maintaining.
        val zoomInTheGap = 5.22f
        assertTrue(largePixelZoom > zoomInTheGap, "the fixture must reproduce the gap")
        assertTrue(zoomInTheGap >= detailRegionZoom(largePixelZoom), "inside the gap crops are still requested")
    }
}
