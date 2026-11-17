package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhotoGestureSemanticsTest {
    @Test fun touchNavigatesOnlyOnReleaseInBothDirections() = viewer {
        val original = state.selectedId
        area().performTouchInput { down(percentOffset(.85f, .5f)); moveTo(percentOffset(.2f, .5f)) }
        assertEquals(original, state.selectedId)
        area().performTouchInput { up() }
        assertEquals(listOf(1), navigation)
        assertNotEquals(original, state.selectedId)
        area().performTouchInput { swipeRight() }
        assertEquals(listOf(1, -1), navigation)
        assertEquals(original, state.selectedId)
    }

    @Test fun verticalPanelsFollowTheFingerBeforeReleaseThenCommit() = viewer {
        val original = state.selectedId
        val height = area().fetchSemanticsNode().boundsInRoot.height
        area().performTouchInput { down(percentOffset(.5f, .8f)); moveTo(percentOffset(.5f, .6f)) }
        val inspector = onNodeWithContentDescription("Painel flutuante: Curadoria e metadados")
        val first = inspector.fetchSemanticsNode().boundsInRoot.top
        area().performTouchInput { moveTo(percentOffset(.5f, .4f)) }
        assertEquals(height * .2f, first - inspector.fetchSemanticsNode().boundsInRoot.top, 2f, "Panel must track contact 1:1 while held")
        area().performTouchInput { up() }
        onNodeWithContentDescription("Fechar curadoria e metadados").assertExists().performClick()
        area().performTouchInput { down(percentOffset(.5f, .2f)); moveTo(percentOffset(.5f, .3f)) }
        val thumbnails = onNodeWithContentDescription("Painel flutuante: Miniaturas")
        val firstBottom = thumbnails.fetchSemanticsNode().boundsInRoot.bottom
        area().performTouchInput { moveTo(percentOffset(.5f, .4f)) }
        assertEquals(height * .1f, thumbnails.fetchSemanticsNode().boundsInRoot.bottom - firstBottom, 2f)
        area().performTouchInput { up() }
        onNodeWithContentDescription("Fechar miniaturas").assertExists().performClick()
        assertEquals(original, state.selectedId)
        assertEquals(emptyList(), navigation)
    }

    @Test fun heldPanelReversesAndReturnsWithoutCommitBelowThreshold() = viewer {
        area().performTouchInput { down(percentOffset(.5f, .8f)); moveTo(percentOffset(.5f, .4f)) }
        val inspector = onNodeWithContentDescription("Painel flutuante: Curadoria e metadados")
        val forward = inspector.fetchSemanticsNode().boundsInRoot.top
        area().performTouchInput { moveTo(percentOffset(.5f, .6f)) }
        assertTrue(inspector.fetchSemanticsNode().boundsInRoot.top > forward + 20f)
        area().performTouchInput { moveTo(percentOffset(.5f, .79f)); up() }
        assertNoPanel()
        assertEquals(emptyList(), navigation)
    }

    @Test fun shortDiagonalAndReversedContactsNeverOpenPanels() = viewer {
        area().performTouchInput { swipe(center, center + Offset(0f, -12f), 120) }
        area().performTouchInput { swipe(percentOffset(.8f, .8f), percentOffset(.2f, .2f), 200) }
        area().performTouchInput {
            down(percentOffset(.5f, .8f)); moveTo(percentOffset(.5f, .2f)); moveTo(percentOffset(.5f, .79f)); up()
        }
        // Skiko's enqueueCancel() is a no-op; native cancellation is checked in the browsers.
        assertNoPanel()
        assertEquals(emptyList(), navigation)
        area().performTouchInput { swipeUp() }
        onNodeWithContentDescription("Fechar curadoria e metadados").assertExists()
    }

    @Test fun desktopVerticalContactsDoNotOpenCompactPanels() = viewer(Size(1440f, 1000f)) {
        area().performTouchInput { swipeUp(); swipeDown() }
        assertNoPanel()
        assertEquals(emptyList(), navigation)
    }

    @Test fun smallVerticalDiagonalReversedAndCanceledContactsNeverNavigate() = viewer {
        area().performTouchInput { swipe(center, center + Offset(12f, 0f), 120) }
        area().performTouchInput { swipe(center, center + Offset(0f, 12f), 120) }
        area().performTouchInput { swipe(percentOffset(.8f, .8f), percentOffset(.2f, .2f), 200) }
        area().performTouchInput {
            down(percentOffset(.8f, .5f)); moveTo(percentOffset(.2f, .5f)); moveTo(percentOffset(.78f, .5f)); up()
        }
        area().performTouchInput { down(percentOffset(.8f, .5f)); moveTo(percentOffset(.2f, .5f)); cancel() }
        assertEquals(emptyList(), navigation)
        area().performTouchInput { swipeLeft() }
        assertEquals(listOf(1), navigation, "Cancellation must not disable the next gesture")
    }

    @Test fun boundariesNeverWrapOrInvokeUnavailableNavigation() = viewer {
        runOnIdle { state = state.copy(selectedId = state.visiblePhotos.first().id) }
        area().performTouchInput { swipeRight() }
        runOnIdle { state = state.copy(selectedId = state.visiblePhotos.last().id) }
        area().performTouchInput { swipeLeft() }
        assertEquals(emptyList(), navigation)
    }

    @Test fun zoomPanPinchAndRemainingFingerNeverNavigate() = viewer {
        onNodeWithContentDescription("Aumentar zoom").performClick()
        area().performTouchInput { swipeLeft(); swipeUp(); swipeDown() }
        assertNoPanel()
        onNodeWithContentDescription("Zoom atual: 125%").assertExists()
        onNodeWithContentDescription("Ajustar fotografia à janela").performClick()
        area().performTouchInput {
            down(0, percentOffset(.4f, .5f)); down(1, percentOffset(.6f, .5f))
            moveTo(0, percentOffset(.15f, .5f)); moveTo(1, percentOffset(.85f, .5f))
            up(1); moveTo(0, percentOffset(.05f, .5f)); up(0)
        }
        onNodeWithContentDescription("Ajustar fotografia à janela").assertIsEnabled()
        assertEquals(emptyList(), navigation)
        onNodeWithContentDescription("Ajustar fotografia à janela").performClick()
        // Even a pinch that shrinks back to Fit owns the entire sequence until all fingers lift.
        area().performTouchInput {
            down(0, percentOffset(.2f, .5f)); down(1, percentOffset(.8f, .5f))
            moveTo(0, percentOffset(.4f, .5f)); moveTo(1, percentOffset(.6f, .5f))
            up(1); moveTo(0, percentOffset(.4f, .1f)); up(0)
        }
        assertEquals(emptyList(), navigation)
        assertNoPanel()
    }

    @Test fun overlayAndMouseDragDoNotNavigate() = viewer {
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Estado editorial").performTouchInput { swipeLeft(); swipeDown() }
        onNodeWithContentDescription("Fechar miniaturas").assertDoesNotExist()
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        onNodeWithContentDescription("Abrir miniaturas").performClick()
        onNodeWithContentDescription("Faixa de fotografias próximas").performTouchInput { swipeLeft() }
        onNodeWithContentDescription("Fechar miniaturas").performClick()
        assertEquals(emptyList(), navigation)
        onNodeWithContentDescription("Próxima fotografia").performClick()
        area().performMouseInput { moveTo(center); press(); moveTo(centerLeft); release() }
        area().performMouseInput { moveTo(center); press(); moveTo(topCenter); release() }
        assertEquals(listOf(1), navigation)
        assertNoPanel()
    }

    private fun viewer(size: Size = Size(393f, 680f), test: Harness.() -> Unit) = runSkikoComposeUiTest(size = size) {
        Harness(this).apply {
            setContent { PhotoToolApp(state, callbacks) }
            waitForIdle()
            test()
        }
    }

    private class Harness(private val ui: ComposeUiTest) {
        fun onNodeWithContentDescription(label: String) = ui.onNodeWithContentDescription(label)
        fun runOnIdle(block: () -> Unit) = ui.runOnIdle(block)
        var state by mutableStateOf(AppState(library = "Fixture", screen = Screen.DETAIL, selectedId = "b",
            photos = listOf("a", "b", "c").map { Photo(it, "", it, "", jpegPath = "$it.JPG", writable = false) }))
        val navigation = mutableListOf<Int>()
        private val bitmap = ImageBitmap(600, 900)
        val callbacks = AppCallbacks(dispatch = { state = reduce(state, it) },
            capabilities = AppCapabilities.RemoteReadOnly, imageLoader = PlatformImageLoader { _, _ -> bitmap },
            navigate = { delta ->
                navigation += delta
                val photos = state.visiblePhotos
                val index = photos.indexOfFirst { it.id == state.selectedId }
                state = state.copy(selectedId = photos[(index + delta).coerceIn(0, photos.lastIndex)].id)
            })
        fun area() = onNodeWithContentDescription("Área ampliável de ${state.selected!!.displayName}")
        fun assertNoPanel() {
            // An uncommitted preview returns along its path instead of vanishing instantly.
            ui.mainClock.advanceTimeBy(280)
            onNodeWithContentDescription("Fechar curadoria e metadados").assertDoesNotExist()
            onNodeWithContentDescription("Fechar miniaturas").assertDoesNotExist()
        }
    }
}
