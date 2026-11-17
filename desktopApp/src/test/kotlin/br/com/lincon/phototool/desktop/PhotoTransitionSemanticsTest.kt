package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import kotlinx.coroutines.*
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

    private data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

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
