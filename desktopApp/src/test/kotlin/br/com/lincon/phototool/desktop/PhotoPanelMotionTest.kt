package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.test.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhotoPanelMotionTest {
    @Test fun inspectorEntersFromBelowAndRemainsMountedWhileExiting() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        fixture()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        mainClock.advanceTimeBy(80)
        val entering = sheet("Curadoria e metadados").fetchSemanticsNode().boundsInRoot.top
        mainClock.advanceTimeBy(800)
        val open = sheet("Curadoria e metadados").fetchSemanticsNode().boundsInRoot.top
        assertTrue(entering > open + 10f, "The panel must occupy intermediate positions, not appear at its endpoint")
        onNodeWithContentDescription("Fechar curadoria e metadados").performClick()
        mainClock.advanceTimeBy(80)
        val leaving = sheet("Curadoria e metadados").fetchSemanticsNode().boundsInRoot.top
        assertTrue(leaving > open + 10f)
        mainClock.advanceTimeBy(1000)
        sheet("Curadoria e metadados").assertDoesNotExist()
        onNodeWithContentDescription("Prévia de b.JPG").assertExists()
    }

    @Test fun thumbnailsEnterFromAboveAndCanCloseBeforeSettling() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        fixture()
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Abrir miniaturas").performClick()
        mainClock.advanceTimeBy(80)
        val entering = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot.bottom
        mainClock.advanceTimeBy(800)
        val open = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(entering < open - 10f)
        onNodeWithContentDescription("Fechar miniaturas").performClick()
        mainClock.advanceTimeBy(80)
        val leaving = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(leaving < open - 10f)
        mainClock.advanceTimeBy(1000)
        sheet("Miniaturas").assertDoesNotExist()
        onNodeWithContentDescription("Abrir miniaturas").performClick()
        mainClock.advanceTimeBy(160)
        val interrupted = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot.bottom
        onNodeWithContentDescription("Fechar miniaturas").performClick()
        mainClock.advanceTimeBy(16)
        val reversed = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(kotlin.math.abs(reversed - interrupted) < 35f, "Closing during entry must not jump to a logical endpoint")
        mainClock.advanceTimeBy(1000)
        sheet("Miniaturas").assertDoesNotExist()
    }

    @Test fun panelsSettleAndUnmountWithin280Milliseconds() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        fixture()
        mainClock.autoAdvance = false
        for ((openLabel, closeLabel, title) in listOf(
            Triple("Abrir curadoria e metadados", "Fechar curadoria e metadados", "Curadoria e metadados"),
            Triple("Abrir miniaturas", "Fechar miniaturas", "Miniaturas"),
        )) {
            onNodeWithContentDescription(openLabel).performClick()
            mainClock.advanceTimeBy(280)
            val settled = sheet(title).fetchSemanticsNode().boundsInRoot
            mainClock.advanceTimeBy(500)
            assertEquals(settled, sheet(title).fetchSemanticsNode().boundsInRoot, "No lingering animation tail after 280ms")
            onNodeWithContentDescription(closeLabel).performClick()
            mainClock.advanceTimeBy(280)
            sheet(title).assertDoesNotExist()
        }
    }

    @Test fun reducedMotionKeepsGeometryStationaryAndSolidMaterialIsAvailable() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        fixture(PhotoPanelPreferences(reduceMotion = true, reduceTransparency = true, highContrast = true))
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Abrir miniaturas").performClick()
        mainClock.advanceTimeBy(48)
        val first = sheet("Miniaturas").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(200)
        assertEquals(first, sheet("Miniaturas").fetchSemanticsNode().boundsInRoot)
        onNodeWithContentDescription("Fechar miniaturas").performClick()
        mainClock.advanceTimeBy(48)
        assertEquals(first, sheet("Miniaturas").fetchSemanticsNode().boundsInRoot)
        mainClock.advanceTimeBy(200)
        sheet("Miniaturas").assertDoesNotExist()
    }

    private fun ComposeUiTest.sheet(title: String) = onNodeWithContentDescription("Painel flutuante: $title")

    private fun ComposeUiTest.fixture(preferences: PhotoPanelPreferences = PhotoPanelPreferences()) {
        val image = ImageBitmap(600, 900)
        Canvas(image).drawRect(0f, 0f, 600f, 900f, Paint().apply { color = Color(0xff1a8899) })
        var state by mutableStateOf(AppState(library = "Fixture", screen = Screen.DETAIL, selectedId = "b",
            photos = listOf("a", "b", "c").map { Photo(it, "", "$it.JPG", "", jpegPath = "$it.JPG", writable = false) }))
        val callbacks = AppCallbacks(dispatch = { state = reduce(state, it) },
            capabilities = AppCapabilities.RemoteReadOnly, imageLoader = PlatformImageLoader { _, _ -> image })
        setContent {
            CompositionLocalProvider(LocalPhotoPanelPreferences provides preferences) { PhotoToolApp(state, callbacks) }
        }
        waitForIdle()
    }
}
