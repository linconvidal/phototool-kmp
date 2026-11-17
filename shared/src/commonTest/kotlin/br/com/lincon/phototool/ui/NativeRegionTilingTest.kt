package br.com.lincon.phototool.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * From the region threshold upwards one crop stopped covering the frame: capped at 2048 px, it left
 * the middle sharp and the edges on the upscaled preview, with a visible seam crossing the photo.
 * These tests pin the tiled answer, on the landscape and on the rotated phone framing.
 */
class NativeRegionTilingTest {
    private val phone = IntSize(393, 680)
    private val landscape = IntSize(6000, 4000)
    private val portrait = IntSize(4000, 6000)

    private fun tiles(native: IntSize, zoom: Float, pan: Offset = Offset.Zero) =
        visibleNativeRegions(phone, native, zoom, pan)

    @Test fun theWindowThatUsedToBleedPastOneCropIsNowSplitIntoCoveringTiles() {
        val native = landscape
        val window = visibleNativeWindow(phone, native, 4f, Offset.Zero)!!
        assertTrue(window.height > 2048, "The 4x window is taller than one crop: ${window.height}")
        val tiles = tiles(native, 4f)
        assertTrue(tiles.size >= 2, "A window above the crop cap needs more than one tile: $tiles")
        assertEquals(1f, regionCoverage(tiles, window), "The tiles must cover the whole window")
        tiles.forEach { tile ->
            assertTrue(tile.width <= 2048 && tile.height <= 2048, "Tile beyond the server side: $tile")
            assertTrue(tile.width.toLong() * tile.height <= DETAIL_REGION_TILE_PIXELS, "Tile beyond the server area: $tile")
            assertTrue(tile.x >= 0 && tile.y >= 0, "Tile outside the photograph: $tile")
            assertTrue(tile.x + tile.width <= native.width && tile.y + tile.height <= native.height, "Tile past the edge: $tile")
        }
    }

    @Test fun noSeamSurvivesAtAnyZoomAboveTheThreshold() {
        listOf(4f, 6f, 8f, 12f, 16f).forEach { zoom ->
            listOf(landscape, portrait).forEach { native ->
                val window = visibleNativeWindow(phone, native, zoom, Offset.Zero)!!
                assertEquals(1f, regionCoverage(tiles(native, zoom), window), "Seam at ${zoom}x for $native")
            }
        }
    }

    @Test fun aPannedWindowStaysCoveredByTheTilesItAsksFor() {
        val native = landscape
        listOf(Offset(-1800f, 900f), Offset(2500f, -1600f), Offset(-100000f, 100000f)).forEach { pan ->
            val window = visibleNativeWindow(phone, native, 4f, pan)!!
            assertEquals(1f, regionCoverage(tiles(native, 4f, pan), window), "Seam after panning $pan")
        }
    }

    @Test fun windowsThatFitStillAskForOneCropWithThePanningMargin() {
        val native = landscape
        val pixelZoom = detailPixelZoom(phone, native)
        val tiles = tiles(native, pixelZoom)
        assertEquals(1, tiles.size, "1:1 fits one crop: $tiles")
        val window = visibleNativeWindow(phone, native, pixelZoom, Offset.Zero)!!
        assertTrue(tiles.single().width > window.width, "The single crop keeps room to pan")
        assertTrue(tiles.single().height >= window.height * DETAIL_REGION_MARGIN - 3)
    }

    @Test fun onePumpIsBoundedByTheServerAndTheBudget() {
        val tiles = tiles(landscape, 4f)
        val pixels = tiles.sumOf { it.width.toLong() * it.height }
        assertTrue(pixels <= DETAIL_REGION_TOTAL_PIXELS, "One pump asked for $pixels native pixels")
        assertTrue(tiles.none { it.width <= 0 || it.height <= 0 }, "Degenerate tile: $tiles")
        assertFalse(tiles.isEmpty())
    }

    @Test fun everyTileStaysInsideTheServerBoundsAtEveryTiledZoom() {
        listOf(4f, 4.5f, 6f, 8f, 12f, 16f).forEach { zoom ->
            listOf(landscape, portrait).forEach { native ->
                val window = visibleNativeWindow(phone, native, zoom, Offset.Zero)!!
                val tiles = tiles(native, zoom)
                if (tiles.size == 1) return@forEach
                assertEquals(1f, regionCoverage(tiles, window), "Window not covered at ${zoom}x for $native")
                tiles.forEach { tile ->
                    assertTrue(tile.width in 1..2048 && tile.height in 1..2048, "Tile beyond the server side at $zoom x: $tile")
                    assertTrue(tile.width.toLong() * tile.height <= DETAIL_REGION_TILE_PIXELS, "Tile beyond the server area: $tile")
                }
                assertTrue(tiles.sumOf { it.width.toLong() * it.height } <= DETAIL_REGION_TOTAL_PIXELS, "Pump above its budget at $zoom x")
            }
        }
    }

    @Test fun adjacentTilesLandOnTheSameScreenColumnAndRow() {
        // Rounding each width on its own left a one-pixel column where the preview showed through:
        // the visible line at the seam. Edges rounded from the native edge cannot drift apart.
        val native = landscape
        val tiles = tiles(native, 4f)
        val area = IntSize(393, 632)
        val placed = tiles.map { it to regionPlacement(it, native, area) }
        placed.forEach { (tile, box) ->
            assertTrue(box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0, "Tile placed outside: $tile -> $box")
        }
        placed.forEach { (left, leftBox) ->
            placed.forEach { (right, rightBox) ->
                if (left !== right) {
                    if (left.y == right.y && left.height == right.height && left.x + left.width == right.x) {
                        assertEquals(leftBox.x + leftBox.width, rightBox.x, "Horizontal gap between $left and $right")
                    }
                    if (left.x == right.x && left.width == right.width && left.y + left.height == right.y) {
                        assertEquals(leftBox.y + leftBox.height, rightBox.y, "Vertical gap between $left and $right")
                    }
                }
            }
        }
    }

    @Test fun thePumpBudgetHoldsForOtherViewportShapes() {
        val viewports = listOf(IntSize(393, 584), IntSize(1200, 800), IntSize(2560, 1080), IntSize(800, 1200))
        viewports.forEach { area ->
            listOf(landscape, portrait).forEach { native ->
                listOf(4f, 4.2f, 5f).forEach { zoom ->
                    val window = visibleNativeWindow(area, native, zoom, Offset.Zero)!!
                    val plan = visibleNativeRegions(area, native, zoom, Offset.Zero)
                    assertEquals(1f, regionCoverage(plan, window), "Seam at ${zoom}x for $native in $area")
                    assertTrue(
                        plan.sumOf { it.width.toLong() * it.height } <= DETAIL_REGION_TOTAL_PIXELS,
                        "Pump above its budget at ${zoom}x for $native in $area: $plan",
                    )
                    plan.forEach { tile ->
                        assertTrue(tile.width in 1..2048 && tile.height in 1..2048, "Tile beyond the server side: $tile")
                        assertTrue(tile.width.toLong() * tile.height <= DETAIL_REGION_TILE_PIXELS, "Tile beyond the server area: $tile")
                    }
                }
            }
        }
    }

    @Test fun aTileAlreadyCoveredIsNeverAskedForAgain() {
        val native = landscape
        val tiles = tiles(native, 4f)
        assertTrue(tiles.size >= 2, "The fixture must need more than one tile: $tiles")
        val first = tiles.first()
        // Covered by a retained crop that is bigger than the tile: nothing to ask.
        val retained = NativeRegion(first.x - 10, first.y - 10, first.width + 20, first.height + 20)
        assertEquals(tiles[1], missingNativeRegion(tiles, listOf(retained), emptyList()))
        // Covered by the whole plan: nothing missing.
        assertEquals(null, missingNativeRegion(tiles, tiles, emptyList()))
        // A failed request is skipped, but only that tile.
        assertEquals(tiles[1], missingNativeRegion(tiles, emptyList(), listOf(first)))
    }

    @Test fun aOnePixelUncoveredBandIsNotMistakenForCoverage() {
        // The old coverage sampled a 3 x 4 grid, so a narrow strip of preview could survive under
        // a full-looking score. The exact area cannot miss it.
        val native = landscape
        val window = visibleNativeWindow(phone, native, 4f, Offset.Zero)!!
        val almost = NativeRegion(window.x, window.y, window.width, window.height - 1)
        assertTrue(regionCoverage(listOf(almost), window) < 1f, "A one-pixel band must still count as uncovered")
        assertTrue(regionCoverage(listOf(almost), window) > .99f, "The rest of the window is still covered")
        val covered = NativeRegion(window.x, window.y, window.width, window.height)
        assertEquals(1f, regionCoverage(listOf(covered), window))
        // Two overlapping crops must not add up to coverage they do not have.
        val leftHalf = NativeRegion(window.x, window.y, window.width * 2 / 3, window.height)
        val rightHalf = NativeRegion(window.x + window.width * 2 / 3, window.y, window.width / 3, window.height)
        assertEquals(1f, regionCoverage(listOf(leftHalf, rightHalf), window))
        val overlapping = NativeRegion(window.x + window.width / 3, window.y, window.width / 3, window.height)
        assertTrue(regionCoverage(listOf(leftHalf, overlapping), window) < 1f, "Overlap must not hide the uncovered right edge")
    }

    @Test fun aWindowWhoseMarginCannotFitFallsBackToCoveringTiles() {
        // A 2048 px wide window fits one crop alone, but its 2.6x panning margin cannot fit the
        // server bound; the capped crop used to cover only the middle. Tiles must cover it all.
        val native = IntSize(4000, 3000)
        val area = IntSize(2048, 1900)
        val window = visibleNativeWindow(area, native, 1f, Offset.Zero)!!
        val single = visibleNativeRegion(area, native, 1f, Offset.Zero)!!
        assertFalse(single.contains(window), "The fixture must reproduce a crop that misses the window: $single vs $window")
        val tiles = visibleNativeRegions(area, native, 1f, Offset.Zero)
        assertTrue(tiles.size > 1, "A window that the margined crop cannot cover needs tiles: $tiles")
        assertEquals(1f, regionCoverage(tiles, window))
        tiles.forEach { tile ->
            assertTrue(tile.width <= 2048 && tile.height <= 2048, "Tile beyond the server side: $tile")
            assertTrue(tile.width.toLong() * tile.height <= DETAIL_REGION_TILE_PIXELS, "Tile beyond the server area: $tile")
        }
    }
}
