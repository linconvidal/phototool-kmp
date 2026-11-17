package br.com.lincon.phototool.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailPixelZoomTest {
    private val phone = IntSize(393, 680)

    @Test fun pixelZoomFollowsTheFittedImageInsteadOfAFixedCeiling() {
        val small = detailPixelZoom(phone, IntSize(1600, 1067))
        val large = detailPixelZoom(phone, IntSize(6000, 4000))
        assertTrue(large > small)
        assertTrue(large > 8f, "A 6000px image needs more than the old 8x ceiling: $large")
        assertEquals(1f, detailPixelZoom(phone, IntSize(393, 680)))
    }

    @Test fun zoomStopsReachAndExceedOneToOneWithoutLosingThePreviousStops() {
        val pixelZoom = detailPixelZoom(phone, IntSize(6000, 4000))
        val stops = detailZoomStops(pixelZoom)
        assertTrue(1.25f in stops && 4f in stops)
        assertTrue(stops.any { it >= pixelZoom - .01f }, "Stops must reach 1:1: $stops")
        assertTrue(stops.last() >= pixelZoom * 2f, "Stops must go beyond 1:1: $stops")
        var zoom = 1f
        repeat(stops.size + 2) { zoom = nextDetailZoom(zoom, 1, stops, detailMaxZoom(pixelZoom)) }
        assertEquals(detailMaxZoom(pixelZoom), zoom)
        assertEquals(1f, nextDetailZoom(1f, -1, stops, detailMaxZoom(pixelZoom)))
    }

    @Test fun nativeRegionFollowsTheVisibleWindowAndStaysBounded() {
        val native = IntSize(6000, 4000)
        val center = visibleNativeRegion(phone, native, detailPixelZoom(phone, native), androidx.compose.ui.geometry.Offset.Zero)
        assertTrue(center != null)
        assertTrue(center!!.width in 300..2048 && center.height in 300..2048)
        assertTrue(center.x + center.width <= native.width && center.y + center.height <= native.height)
        val panned = visibleNativeRegion(phone, native, detailPixelZoom(phone, native), androidx.compose.ui.geometry.Offset(-4000f, 0f))
        assertTrue(panned != null && panned.x > center.x, "Panning must move the requested region")
        val corner = visibleNativeRegion(phone, native, detailPixelZoom(phone, native), androidx.compose.ui.geometry.Offset(100000f, 100000f))
        assertTrue(corner != null && corner.x >= 0 && corner.x + corner.width <= native.width)
        assertNull(visibleNativeRegion(IntSize.Zero, native, 1f, androidx.compose.ui.geometry.Offset.Zero))
    }

    @Test fun nearestNeighbourStartsWhenPixelsAreBeingInspected() {
        val pixelZoom = detailPixelZoom(phone, IntSize(6000, 4000))
        assertEquals(FilterQuality.None, detailFilterQuality(pixelZoom, pixelZoom))
        assertEquals(FilterQuality.None, detailFilterQuality(pixelZoom * 2f, pixelZoom))
        assertEquals(FilterQuality.Low, detailFilterQuality(1.25f, pixelZoom))
        assertEquals(FilterQuality.Low, detailFilterQuality(4f, pixelZoom))
    }

    @Test fun oneToOneCropKeepsTheFullPanningMarginInsteadOfTheDisplayCeiling() {
        val native = IntSize(6000, 4000)
        val area = IntSize(393, 584)
        val pixelZoom = detailPixelZoom(area, native)
        val visible = visibleNativeRegion(area, native, pixelZoom, Offset.Zero, margin = 1f)!!
        assertEquals(393, visible.width)
        assertEquals(584, visible.height)
        val crop = visibleNativeRegion(area, native, pixelZoom, Offset.Zero)!!
        assertEquals(1022, crop.width)
        assertEquals(1518, crop.height)
        assertTrue(crop.width >= visible.width * DETAIL_REGION_MARGIN - 3, "Crop width ${crop.width} must keep the 2.6x margin")
        assertTrue(crop.height >= visible.height * DETAIL_REGION_MARGIN - 3, "Crop height ${crop.height} must keep the 2.6x margin")
    }

    @Test fun firstCropCoversTheWholeWindowAndPanningPastItAsksForMore() {
        val native = IntSize(6000, 4000)
        val area = IntSize(393, 584)
        val pixelZoom = detailPixelZoom(area, native)
        fun coverage(pan: Offset): Float {
            val visible = visibleNativeRegion(area, native, pixelZoom, pan, margin = 1f)!!
            return regionCoverage(listOf(visibleNativeRegion(area, native, pixelZoom, Offset.Zero)!!), visible)
        }
        assertEquals(1f, coverage(Offset.Zero))
        assertEquals(1f, coverage(Offset(-200f, 0f)), "Inside the slack the last crop still covers the window")
        assertEquals(0f, coverage(Offset(-3000f, 0f)), "Leaving the last crop must demand a new one")
    }

    @Test fun leadBiasShiftsTheCropTowardsTheDragWithoutLosingTheCurrentWindow() {
        val native = IntSize(6000, 4000)
        val area = IntSize(393, 584)
        val pixelZoom = detailPixelZoom(area, native)
        val centered = visibleNativeRegion(area, native, pixelZoom, Offset.Zero)!!
        val led = visibleNativeRegion(area, native, pixelZoom, Offset.Zero, lead = Offset(-1f, 0f))!!
        assertTrue(led.x < centered.x, "Leading towards smaller native x must move the crop: ${led.x} vs ${centered.x}")
        val visible = visibleNativeRegion(area, native, pixelZoom, Offset.Zero, margin = 1f)!!
        assertTrue(led.x <= visible.x && led.x + led.width >= visible.x + visible.width, "The current window must stay inside the crop")
    }

    @Test fun cropAtTheImageEdgeKeepsItsSizeInsteadOfShrinkingBelowTheWindow() {
        val native = IntSize(6000, 4000)
        val area = IntSize(393, 584)
        val pixelZoom = detailPixelZoom(area, native)
        val centered = visibleNativeRegion(area, native, pixelZoom, Offset.Zero)!!
        val edge = visibleNativeRegion(area, native, pixelZoom, Offset(-100000f, -100000f))!!
        assertEquals(centered.width, edge.width)
        assertEquals(centered.height, edge.height)
        assertTrue(edge.x + edge.width <= native.width && edge.y + edge.height <= native.height)
        val visible = visibleNativeRegion(area, native, pixelZoom, Offset(-100000f, -100000f), margin = 1f)!!
        assertEquals(1f, regionCoverage(listOf(edge), visible))
    }

    @Test fun retainedCropsKeepTheNewestWithinThePixelBudget() {
        val sizes = List(6) { 400L * 200 }
        assertEquals(2, retainedRegionCount(sizes, budgetPixels = 400L * 200 * 2))
        assertEquals(6, retainedRegionCount(sizes, budgetPixels = 400L * 200 * 6))
        assertEquals(1, retainedRegionCount(sizes, budgetPixels = 1L), "The newest crop always survives the budget")
    }

    @Test fun leadFollowsTheDirectionTheViewIsHeading() {
        val area = IntSize(393, 584)
        assertEquals(Offset.Zero, detailRegionLead(Offset.Zero, area))
        assertTrue(detailRegionLead(Offset(90f, 0f), area).x < 0f, "Panning right moves the view to smaller native x")
        assertTrue(detailRegionLead(Offset(0f, 90f), area).y < 0f)
        val strong = detailRegionLead(Offset(600f, 0f), area)
        assertEquals(-0.75f, strong.x, 1e-4f)
        assertEquals(0f, strong.y, 1e-4f)
    }
}
