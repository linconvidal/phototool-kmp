package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import com.ashampoo.kim.model.TiffOrientation
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhotoTransitionSemanticsTest {
    @Test fun phoneKeepsPixelsIdentityMetadataAndZoomDuringPendingFailureAndRetry() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val first = photo("a")
        val second = photo("b")
        var gate = CompletableDeferred<ImageBitmap?>()
        val harness = ViewerHarness(listOf(first, second), PlatformImageLoader { photo, _ ->
            if (photo.id == "a") bitmap(Color.Red) else gate.await()
        })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        onNodeWithContentDescription("Prévia de a.JPG").assertExists()
        onNodeWithContentDescription("Aumentar zoom").performClick()
        runOnIdle { harness.stage(second) }
        onNodeWithText("Carregando fotografia").assertDoesNotExist()
        onNodeWithContentDescription("Prévia de a.JPG").assertExists()
        onNodeWithContentDescription("Prévia de b.JPG").assertDoesNotExist()
        onNodeWithContentDescription("Zoom atual: 125%").assertExists()
        assertPhotoColor("a", Color.Red)
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Câmera: camera-a").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Câmera: camera-b").assertDoesNotExist()
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        runOnIdle { gate.complete(null) }
        waitUntil { harness.state.remote!!.error != null }
        onNodeWithText("Fotografia indisponível").assertDoesNotExist()
        assertPhotoColor("a", Color.Red)
        runOnIdle { gate = CompletableDeferred() }
        onNodeWithContentDescription("Atualizar snapshot").performClick()
        runOnIdle { gate.complete(bitmap(Color.Blue)) }
        waitUntil { harness.state.remote!!.detail?.id == "b" }
        onNodeWithContentDescription("Prévia de b.JPG").assertExists()
        onNodeWithContentDescription("Prévia de a.JPG").assertDoesNotExist()
        assertPhotoColor("b", Color.Blue)
        onNodeWithContentDescription("Zoom atual: 100%").assertExists()
        repeat(2) {
            onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
            onNodeWithContentDescription("Câmera: camera-b").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("Marcar como escolhida").assertIsNotEnabled()
            onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
            assertPhotoColor("b", Color.Blue)
        }
    }

    @Test fun desktopIgnoresLateDecodeAndCloseDoesNotReopenTheViewer() = runSkikoComposeUiTest(size = Size(1440f, 1000f)) {
        val late = CompletableDeferred<ImageBitmap?>()
        val close = CompletableDeferred<ImageBitmap?>()
        val photos = listOf("a", "b", "c", "d").map(::photo)
        val harness = ViewerHarness(photos, PlatformImageLoader { photo, _ ->
            when (photo.id) {
                "b" -> withContext(NonCancellable) { late.await() }
                "d" -> withContext(NonCancellable) { close.await() }
                "c" -> bitmap(Color.Green)
                else -> bitmap(Color.Red)
            }
        })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        assertPhotoColor("a", Color.Red)
        runOnIdle { harness.stage(photos[1]) }; waitForIdle()
        runOnIdle { harness.stage(photos[2]) }
        waitUntil { harness.state.remote!!.detail?.id == "c" }
        runOnIdle { late.complete(bitmap(Color.Blue)) }
        assertPhotoColor("c", Color.Green)
        onNodeWithContentDescription("Câmera: camera-c").assertExists()
        runOnIdle { harness.stage(photos[3]) }; waitForIdle()
        onNodeWithContentDescription("Voltar à biblioteca").performClick()
        runOnIdle { close.complete(bitmap(Color.Yellow)) }
        onNodeWithContentDescription("Visualizador da fotografia").assertDoesNotExist()
        assertEquals(Screen.GALLERY, harness.state.screen)
    }

    @Test fun initialFailureIsTruthfulAndSameIdNewVersionWaitsForItsOwnBitmap() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val original = photo("a")
        val changed = original.copy(metadata = original.metadata.copy(camera = "new-camera"),
            presentation = original.presentation!!.copy(previewUrl = "/api/v1/photos/a/preview?max=1600&v=${"b".repeat(24)}"))
        var gate = CompletableDeferred<ImageBitmap?>()
        val harness = ViewerHarness(listOf(original), PlatformImageLoader { _, _ -> gate.await() })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        onNodeWithText("Carregando fotografia").assertIsDisplayed()
        runOnIdle { gate.complete(null) }
        waitUntil { harness.state.remote!!.error != null }
        onNodeWithText("Fotografia indisponível").assertIsDisplayed()
        runOnIdle { gate = CompletableDeferred(); harness.stage(original) }
        runOnIdle { gate.complete(bitmap(Color.Red)) }
        waitUntil { harness.state.remote!!.detail != null }
        runOnIdle { gate = CompletableDeferred(); harness.stage(changed) }
        assertPhotoColor("a", Color.Red)
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Câmera: camera-a").performScrollTo().assertIsDisplayed()
        runOnIdle { gate.complete(bitmap(Color.Blue)) }
        waitUntil { harness.state.remote!!.detail == changed }
        onNodeWithContentDescription("Câmera: new-camera").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        assertPhotoColor("a", Color.Blue)
    }

    @Test fun metadataAndPixelsRemainAPairWhenStateFlowDeliveryLagsAcknowledgement() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val first = photo("a")
        val second = photo("b")
        val harness = ViewerHarness(listOf(first, second), PlatformImageLoader { photo, _ ->
            bitmap(if (photo.id == "a") Color.Red else Color.Blue)
        })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        assertPhotoColor("a", Color.Red)
        runOnIdle { harness.deferStateDelivery = true; harness.stage(second) }
        waitUntil { harness.deferredState != null }
        assertEquals("a", harness.state.remote!!.detail!!.id, "Simulate a delayed StateFlow collector")
        assertPhotoColor("b", Color.Blue)
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Câmera: camera-b").performScrollTo().assertIsDisplayed()
        runOnIdle { harness.state = harness.deferredState!! }
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        assertPhotoColor("b", Color.Blue)
    }

    @Test fun editorialReadbackUpdatesThePresentedPhotoAndBusyControlsAreDisabled() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val first = photo("a").copy(writable = true, editorialRevision = "a".repeat(64))
        val second = photo("b").copy(writable = true, editorialRevision = "b".repeat(64))
        val gate = CompletableDeferred<ImageBitmap?>()
        val harness = ViewerHarness(listOf(first, second), PlatformImageLoader { p, _ ->
            if (p.id == "a") bitmap(Color.Red) else gate.await()
        })
        var edited: Photo? = null
        harness.state = harness.state.copy(writeEnabled = true, writeAuthorized = true)
        setContent { PhotoToolApp(harness.state, harness.callbacks.copy(mutateFlag = { p, _ -> edited = p })) }
        assertPhotoColor("a", Color.Red)
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Marcar como escolhida").assertIsEnabled().performClick()
        assertEquals("a", edited?.id)
        runOnIdle {
            harness.state = harness.state.copy(remote = harness.state.remote!!.let { remote ->
                remote.copy(detail = remote.detail!!.copy(editorial = EditorialState(rating = 5),
                    editorialRevision = "c".repeat(64), writeState = WriteState.PERSISTED))
            })
        }
        onNodeWithContentDescription("Avaliação editorial").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "5 estrelas"))
        onNodeWithText("Alterações salvas e verificadas").assertExists()
        runOnIdle { harness.state = harness.state.copy(remote = harness.state.remote!!.copy(savingPhotoId = "a")) }
        listOf("Marcar como escolhida", "Rótulo vermelho", "Avaliação editorial").forEach {
            onNodeWithContentDescription(it).assertIsNotEnabled()
        }
        onNodeWithContentDescription("Nova palavra-chave").performScrollTo().assertIsNotEnabled()
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        assertPhotoColor("a", Color.Red)
        runOnIdle {
            harness.state = harness.state.copy(remote = harness.state.remote!!.copy(savingPhotoId = null))
            harness.stage(second)
        }
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Marcar como escolhida").assertIsNotEnabled()
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        runOnIdle { gate.complete(bitmap(Color.Blue)) }
        waitUntil { harness.state.remote!!.detail?.id == "b" }
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Marcar como escolhida").assertIsEnabled().performClick()
        assertEquals("b", edited?.id)
        assertEquals("b".repeat(64), edited?.editorialRevision)
    }

    @Test fun arrivingSecondNeighborDoesNotRestartTheFirstAndRequestedPhotoCancelsSpeculation() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val photos = listOf("a", "b", "c", "d").map(::photo)
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val harness = ViewerHarness(photos, PlatformImageLoader { p, _ ->
            started += p.id
            if (p.id in listOf("b", "c")) {
                try { awaitCancellation() } finally { cancelled += p.id }
            } else bitmap(if (p.id == "a") Color.Red else Color.Green)
        })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        assertPhotoColor("a", Color.Red)
        runOnIdle { harness.state = harness.state.copy(remote = harness.state.remote!!.copy(preparePhotos = listOf(photos[1]))) }
        waitUntil(timeoutMillis = 8_000) { "b" in started }
        runOnIdle { harness.state = harness.state.copy(remote = harness.state.remote!!.copy(preparePhotos = listOf(photos[1], photos[2]))) }
        waitUntil { "c" in started }
        assertEquals(1, started.count { it == "b" })
        assertTrue(cancelled.isEmpty())
        runOnIdle {
            harness.state = harness.state.copy(remote = harness.state.remote!!.copy(preparePhotos = emptyList()))
            harness.stage(photos[3])
        }
        waitUntil { harness.state.remote!!.detail?.id == "d" }
        assertPhotoColor("d", Color.Green)
        assertEquals(setOf("b", "c"), cancelled.toSet())
    }

    @Test fun remoteWriteFailureOffersReadbackNotLocalSynchronization() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val failed = photo("a").copy(writeState = WriteState.FAILED, writeError = "Gravação não confirmada")
        val harness = ViewerHarness(listOf(failed), PlatformImageLoader { _, _ -> bitmap(Color.Red) })
        var refreshed = false
        setContent { PhotoToolApp(harness.state, harness.callbacks.copy(refresh = { refreshed = true })) }
        assertPhotoColor("a", Color.Red)
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithText("Recarregar estado do servidor").performScrollTo().performClick()
        assertTrue(refreshed)
        assertEquals(Screen.DETAIL, harness.state.screen)
        onNodeWithText("Ir às configurações").assertDoesNotExist()
    }

    @Test fun routineRemoteStatusDoesNotReserveAPermanentBar() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val harness = ViewerHarness(listOf(photo("a")), PlatformImageLoader { _, _ -> bitmap(Color.Red) })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        assertPhotoColor("a", Color.Red)
        for (screen in listOf(Screen.DETAIL, Screen.GALLERY)) {
            for (notice in listOf(null, "a.JPG: salvando no XMP", "a.JPG: alterações salvas e verificadas")) {
                runOnIdle {
                    harness.state = harness.state.copy(screen = screen, remote = harness.state.remote!!.copy(
                        projection = "READY", loading = notice == null, editorialNotice = notice))
                }
                onNodeWithContentDescription("Atualizar snapshot").assertDoesNotExist()
                onNodeWithText("Somente leitura · Snapshot READY").assertDoesNotExist()
                onNodeWithText("Somente leitura · Carregando").assertDoesNotExist()
                notice?.let { onNodeWithText(it).assertDoesNotExist() }
            }
        }
    }

    @Test fun remoteFailuresKeepExplicitRecoveryWithoutRoutineStatus() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val harness = ViewerHarness(listOf(photo("a")), PlatformImageLoader { _, _ -> bitmap(Color.Red) })
        var refreshes = 0
        setContent { PhotoToolApp(harness.state, harness.callbacks.copy(refresh = { refreshes++ })) }
        assertPhotoColor("a", Color.Red)
        val baseline = harness.state.remote!!.copy(projection = "READY", loading = false)
        val failures = listOf(
            baseline.copy(error = "Falha de rede") to "Falha de rede",
            baseline.copy(editorialNotice = "a.JPG: Gravação não confirmada", editorialNeedsRefresh = true) to "a.JPG: Gravação não confirmada",
            baseline.copy(projection = "DEGRADED") to "Snapshot degradado. Edição indisponível.",
        )
        for ((remote, message) in failures) {
            runOnIdle { harness.state = harness.state.copy(remote = remote) }
            onNodeWithText(message).assertIsDisplayed()
            onNodeWithContentDescription("Atualizar snapshot").assertIsEnabled().performClick()
        }
        assertEquals(3, refreshes)
        runOnIdle { harness.state = harness.state.copy(remote = baseline) }
        onNodeWithContentDescription("Atualizar snapshot").assertDoesNotExist()
        assertPhotoColor("a", Color.Red)
    }

    @Test fun oneToOnePanKeepsAskingForNativeCropsInsteadOfShowingTheDisplayBitmap() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val photo = nativePhoto("a")
        val requested = mutableListOf<Region>()
        val completed = mutableListOf<Region>()
        val cancelled = mutableListOf<Region>()
        val loader = object : PlatformImageLoader {
            override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap? = bitmap(Color.Red)
            override suspend fun loadRegion(photo: Photo, x: Int, y: Int, width: Int, height: Int): ImageBitmap? {
                val region = Region(x, y, width, height)
                requested += region
                return try {
                    // Slower than a pointer tick, faster than the whole drag.
                    delay(90)
                    completed += region
                    ImageBitmap(width, height)
                } catch (stopped: CancellationException) {
                    cancelled += region
                    throw stopped
                }
            }
        }
        val harness = ViewerHarness(listOf(photo), loader)
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        onNodeWithContentDescription("Prévia de a.JPG").assertExists()
        val area = onNodeWithContentDescription("Área ampliável de a.JPG")
        fun atOneToOne(): Boolean =
            area.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)?.contains("pixels 1:1") == true
        repeat(12) {
            if (!atOneToOne()) onNodeWithContentDescription("Aumentar zoom").performClick()
        }
        assertTrue(atOneToOne(), "Zoom must reach the native 1:1 stop")
        waitUntil(timeoutMillis = 10_000) { completed.isNotEmpty() }
        assertTrue(cancelled.isEmpty(), "The first crop must not be cancelled: $cancelled")
        val first = completed.first()
        assertTrue(first.width >= 393 * 2.5f, "The crop must keep the 2.6x panning margin: ${first.width}")
        requested.clear(); completed.clear()
        // Drag far enough to leave the fetched crop more than twice over.
        repeat(6) {
            area.performTouchInput { swipe(percentOffset(.85f, .55f), percentOffset(.2f, .55f), 220) }
            waitForIdle()
        }
        waitUntil(timeoutMillis = 20_000) { completed.size >= 2 }
        assertTrue(cancelled.isEmpty(), "Panning at 1:1 must not cancel the crop in flight: $cancelled")
        assertTrue(requested.size >= 2, "A drag outside the last crop must ask for a new one: $requested")
        assertTrue(requested.size <= 16, "Requests must conflate with the drag instead of firing per tick: ${requested.size}")
        assertTrue(requested.all { it.width >= 393 * 2.5f }, "Every request keeps the panning margin: $requested")
        assertTrue(completed.last().x > completed.first().x, "Crops must follow the drag: $completed")
    }

    private fun ComposeUiTest.assertPresentedResolution(id: String, width: Int, height: Int, timeoutMillis: Long = 10_000) {
        val expected = "Resolução apresentada: $width × $height"
        waitUntil(timeoutMillis = timeoutMillis) {
            onAllNodesWithContentDescription("Prévia de $id.JPG").fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription) == expected
            }
        }
    }

    private fun ComposeUiTest.assertPhotoColor(id: String, expected: Color) {
        val pixels = onNodeWithContentDescription("Área ampliável de $id.JPG").captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2])
    }

    /** The painted crop and the upright preview must show the same pixels at the same 1:1 spot. */
    private fun assertSameColor(expected: java.awt.Color, actual: Color, tolerance: Int = 32) {
        val argb = actual.toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        assertTrue(
            abs(red - expected.red) <= tolerance && abs(green - expected.green) <= tolerance && abs(blue - expected.blue) <= tolerance,
            "Crop content $red,$green,$blue differs from the preview ${expected.red},${expected.green},${expected.blue}",
        )
    }

    private data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    @Test fun fourTimesAsksForTilesCoveringTheViewportAndBelowItAsksForNothing() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        // The 4x window of a 6000 x 4000 photograph is taller than one 2048 px crop. It used to be
        // a single crop over the middle of a frame with the upscaled preview around it: a blurred
        // band and a line where the two met. Tiles must cover the window instead.
        val requested = CopyOnWriteArrayList<Region>()
        val loader = object : PlatformImageLoader {
            override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap = bitmap(Color.Red)
            override suspend fun loadRegion(photo: Photo, x: Int, y: Int, width: Int, height: Int): ImageBitmap {
                requested += Region(x, y, width, height)
                return ImageBitmap(width, height)
            }
        }
        val harness = ViewerHarness(listOf(nativePhoto("a")), loader)
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        val area = onNodeWithContentDescription("Área ampliável de a.JPG")
        fun zoomText(): String = area.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        repeat(4) { onNodeWithContentDescription("Aumentar zoom").performClick() }
        assertTrue(zoomText().startsWith("Zoom atual: 300"), "The fixture must reach 3x first: ${zoomText()}")
        waitForIdle()
        assertEquals(0, requested.size, "Below 4x no native crop may be asked for: $requested")
        onNodeWithContentDescription("Aumentar zoom").performClick()
        assertTrue(zoomText().startsWith("Zoom atual: 400"), "The fixture must stop at 4x: ${zoomText()}")
        waitUntil(timeoutMillis = 10_000) { requested.isNotEmpty() }
        val viewport = area.fetchSemanticsNode().size
        val fit = minOf(viewport.width / 6000f, viewport.height / 4000f)
        val windowWidth = (viewport.width / (4f * fit)).roundToInt()
        val windowHeight = (viewport.height / (4f * fit)).roundToInt()
        val left = ((6000 - windowWidth) / 2f).roundToInt()
        val top = ((4000 - windowHeight) / 2f).roundToInt()
        fun inside(x: Int, y: Int) = requested.any { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
        // Same 3 x 4 probe the coverage uses: every column and row of the frame is native pixels.
        waitUntil(timeoutMillis = 10_000) {
            (0 until 4).all { row ->
                (0 until 3).all { column -> inside(left + windowWidth * (2 * column + 1) / 6, top + windowHeight * (2 * row + 1) / 8) }
            }
        }
        assertTrue(requested.size >= 2, "A window taller than one crop must be split into tiles: $requested")
        assertEquals(requested.size, requested.toSet().size, "A covered tile must not be asked for twice: $requested")
        requested.forEach { region ->
            assertTrue(region.width in 1..2048 && region.height in 1..2048, "Tile beyond the server side: $region")
            assertTrue(region.width.toLong() * region.height <= 4_000_000, "Tile beyond the server area: $region")
        }
        assertTrue(requested.sumOf { it.width.toLong() * it.height } <= 6_000_000, "One pump above its budget: $requested")
    }

    @Test fun portraitFixtureWithExif8ShowsTheSamePixelsAsThePreviewAtOneToOne() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        // Every fixture in this suite was landscape, without orientation: the orientation bug could
        // not show up. This one is stored sideways (EXIF 8) and presented in portrait, and it
        // compares the native crop actually painted with the upright preview at the same 1:1 spot.
        val directory = Files.createTempDirectory("phototool-exif8-")
        try {
            val fixtureFile = directory.resolve("retrato-exif8.jpg")
            Files.write(fixtureFile, withExifOrientation(quadrantJpeg(2400, 1700), 8))
            val fixture = Files.readAllBytes(fixtureFile)
            // EXIF 8 is ROTATE_LEFT. The fixture builds it, and the preview represents the upright
            // presentation the server publishes; the crop has to read the header to find the same one.
            val stored = ImageIO.read(ByteArrayInputStream(fixture))
            assertEquals(2400, stored.width, "The fixture must be stored sideways")
            assertEquals(1700, stored.height, "The fixture must be stored sideways")
            val presented = applyTiffOrientation(stored, TiffOrientation.ROTATE_LEFT)
            assertEquals(1700, presented.width)
            assertEquals(2400, presented.height)
            val previewBytes = ByteArrayOutputStream().also { ImageIO.write(presented, "png", it) }.toByteArray()
            val preview = Image.makeFromEncoded(previewBytes).toComposeImageBitmap()
            val rotated = photo("r").copy(
                metadata = ObservedMetadata(camera = "camera-r", width = presented.width, height = presented.height),
                presentation = PhotoPresentation(
                    "r.JPG", "JPEG", presented.width.toFloat() / presented.height,
                    "/api/v1/photos/r/preview?max=1600&v=" + "a".repeat(24),
                ),
            )
            val loader = RealCropLoader(fixture, preview)
            val harness = ViewerHarness(listOf(rotated), loader)
            setContent { PhotoToolApp(harness.state, harness.callbacks) }
            onNodeWithContentDescription("Prévia de r.JPG").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Resolução apresentada: 1700 × 2400"))
            val area = onNodeWithContentDescription("Área ampliável de r.JPG")
            fun atOneToOne(): Boolean =
                area.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)?.contains("pixels 1:1") == true
            repeat(10) { if (!atOneToOne()) onNodeWithContentDescription("Aumentar zoom").performClick() }
            assertTrue(atOneToOne(), "The portrait fixture must reach 1:1 above the native-crop threshold")
            // Pan to the top of the frame: there the presented rectangle still exists inside the
            // stored pixel grid, so this same test can run with the orientation forced to null.
            repeat(4) { area.performTouchInput { swipe(center, center + Offset(0f, 500f), 200) }; waitForIdle() }
            waitUntil(timeoutMillis = 20_000) { loader.completed.any { it.y == 0 } }
            waitForIdle()
            val crop = loader.completed.first { it.y == 0 }
            val viewport = area.fetchSemanticsNode().size
            assertTrue(crop.width >= viewport.width && crop.height >= viewport.height,
                "The crop must cover the viewport, not a band: $crop vs $viewport")
            val pixels = area.captureToImage().toPixelMap()
            val visibleLeft = presented.width / 2 - pixels.width / 2
            // Four probes, two on each side of the presented vertical quadrant edge. A crop taken
            // in the stored orientation paints the other quadrants and none of these match.
            val probes = listOf(
                pixels.width / 4 to pixels.height / 4, 3 * pixels.width / 4 to pixels.height / 4,
                pixels.width / 4 to 3 * pixels.height / 4, 3 * pixels.width / 4 to 3 * pixels.height / 4,
            )
            probes.forEach { (x, y) ->
                assertSameColor(java.awt.Color(presented.getRGB(visibleLeft + x, y)), pixels[x, y])
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun rotatedPhotographReachesOneToOneInTheOrientationItIsShown() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        // Every fixture in this suite was landscape. A photograph shot sideways is stored one way and
        // presented another, so 1:1 has to follow the shown orientation, not the stored pixel grid.
        val rotated = photo("r").copy(
            metadata = ObservedMetadata(camera = "camera-r", width = 4000, height = 6000),
            presentation = PhotoPresentation("r.JPG", "JPEG", 2f / 3, "/api/v1/photos/r/preview?max=1600&v=${"a".repeat(24)}"),
        )
        val harness = ViewerHarness(listOf(rotated), PlatformImageLoader { _, _ -> bitmap(Color.Red, 1000, 1500) })
        setContent { PhotoToolApp(harness.state, harness.callbacks) }
        onNodeWithContentDescription("Prévia de r.JPG").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Resolução apresentada: 1000 × 1500"))
        val viewport = onNodeWithContentDescription("Área ampliável de r.JPG").fetchSemanticsNode().size
        // 1:1 for the shown orientation: one image pixel per screen pixel at Fit.
        val pixelZoom = 1f / minOf(viewport.width / 4000f, viewport.height / 6000f)
        assertTrue(pixelZoom > 4f, "The portrait fixture must need more than the old ceiling: $pixelZoom")
        var reached = 0f
        var steps = 0
        while (steps < 30) {
            val state = onNodeWithContentDescription("Área ampliável de r.JPG")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            val percent = state.substringAfter("Zoom atual: ").substringBefore("%").toIntOrNull()
                ?: fail("The zoom area must state its zoom: $state")
            val zoom = percent / 100f
            assertEquals(zoom >= pixelZoom - .011f, state.endsWith("pixels 1:1"),
                "Wrong 1:1 claim at $state while 1:1 is ${pixelZoom}x")
            if (state.endsWith("pixels 1:1")) { reached = zoom; break }
            onNodeWithContentDescription("Aumentar zoom").performClick()
            steps++
        }
        assertTrue(reached >= pixelZoom - .011f, "The zoom stops never reach 1:1 in the shown orientation: $reached < $pixelZoom")
    }

    private class RealCropLoader(private val bytes: ByteArray, private val preview: ImageBitmap) : PlatformImageLoader {
        val completed = CopyOnWriteArrayList<Region>()
        override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap = preview
        override suspend fun loadRegion(photo: Photo, x: Int, y: Int, width: Int, height: Int): ImageBitmap? {
            val crop = try {
                cropJpegRegion(bytes, x, y, width, height)
            } catch (_: Throwable) {
                return null
            }
            val image = Image.makeFromEncoded(crop)
            return try {
                if (image.width != width || image.height != height) null else {
                    completed += Region(x, y, width, height)
                    image.toComposeImageBitmap()
                }
            } finally {
                image.close()
            }
        }
    }

    private class ViewerHarness(photos: List<Photo>, loader: PlatformImageLoader) {
        var state by mutableStateOf(AppState(library = "Fixture", screen = Screen.DETAIL, photos = photos,
            selectedId = photos.first().id, remote = RemoteBrowseState(loading = true)))
        private var ticket = 0
        private var target = photos.first()
        var deferStateDelivery = false
        var deferredState: AppState? = null
        init { stage(target) }
        fun stage(photo: Photo) {
            target = photo
            state = state.copy(remote = state.remote!!.copy(loading = true, error = null, requestedId = photo.id,
                pendingDetail = PendingRemoteDetail(++ticket, photo, "a", "b")))
        }
        val callbacks = AppCallbacks(
            imageLoader = loader, capabilities = AppCapabilities.RemoteReadOnly,
            dispatch = { action ->
                state = reduce(state, action)
                if (action == Action.CloseDetail) state = state.copy(remote = state.remote!!.copy(pendingDetail = null, detail = null))
            },
            refresh = { stage(target) },
            presentDetail = { requested, ready ->
                val pending = state.remote!!.pendingDetail
                val accepted = pending?.ticket == requested && state.screen == Screen.DETAIL
                if (accepted) {
                    val updated = state.copy(
                    selectedId = if (ready) pending!!.photo.id else state.selectedId,
                    remote = state.remote!!.copy(loading = false, error = if (ready) null else "Prévia indisponível. Tente novamente.",
                        pendingDetail = null, detail = if (ready) pending!!.photo else state.remote!!.detail),
                    )
                    if (deferStateDelivery) deferredState = updated else state = updated
                }
                accepted && ready
            },
        )
    }

    companion object {
        private fun nativePhoto(id: String) = photo(id).copy(
            metadata = ObservedMetadata(camera = "camera-$id", width = 6000, height = 4000),
            presentation = PhotoPresentation("$id.JPG", "JPEG", 1.5f, "/api/v1/photos/$id/preview?max=1600&v=${"a".repeat(24)}"),
        )
        private fun photo(id: String) = Photo(id, "", id, "", writable = false,
            metadata = ObservedMetadata(camera = "camera-$id", width = 60, height = 90),
            presentation = PhotoPresentation("$id.JPG", "JPEG", 2f / 3,
                "/api/v1/photos/$id/preview?max=1600&v=${"a".repeat(24)}"))
        private fun bitmap(color: Color) = bitmap(color, 60, 90)
        private fun bitmap(color: Color, width: Int, height: Int) = ImageBitmap(width, height).also {
            Canvas(it).drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), Paint().apply { this.color = color })
        }
    }
}

/** Stored pixels in four flat quadrants, so a crop taken from the wrong place is unmistakable. */
private fun quadrantJpeg(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) {
        val color = when {
            x < width / 2 && y < height / 2 -> 0xFF0000
            x >= width / 2 && y < height / 2 -> 0x00FF00
            x < width / 2 -> 0x0000FF
            else -> 0xFFFF00
        }
        image.setRGB(x, y, color)
    }
    val bytes = ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", bytes)
    return bytes.toByteArray()
}

/** Inserts the APP1 segment that declares the EXIF orientation, the way a camera would. */
private fun withExifOrientation(jpeg: ByteArray, exifValue: Int): ByteArray {
    val tiff = byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
        0x00, 0x01,
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
        0x00, exifValue.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )
    val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
    val length = payload.size + 2
    val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
}
