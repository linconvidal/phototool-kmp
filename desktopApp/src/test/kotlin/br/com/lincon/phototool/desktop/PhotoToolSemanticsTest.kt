package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class PhotoToolSemanticsTest {
    private val photo=Photo("one","2025","IMG_1","2025/IMG_1.xmp",jpegPath="2025/IMG_1.JPG",metadata=ObservedMetadata(width=3,height=2))

    @Test fun gallerySingleSelectsAndDoubleClickOpensDetail() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={ state=reduce(state,it) })) }
        onNodeWithContentDescription("Photograph IMG_1.JPG").performClick().assertIsSelected()
        onNodeWithText("INSPECTOR").assertExists()
        onNodeWithContentDescription("Photograph IMG_1.JPG").performTouchInput { doubleClick() }
        onNodeWithText("‹ Gallery").assertExists()
    }

    @Test fun readOnlyGatesInspectorMutationControls() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,writeEnabled=false,filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={ state=reduce(state,it) })) }
        onNodeWithText("Editing unavailable").assertExists()
        onAllNodesWithText("P")[0].assertIsNotEnabled()
        onNodeWithText("READ ONLY").assertExists()
    }

    @Test fun settingsExposeRunningSynchronizationAndCancel() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",screen=Screen.SETTINGS,sync=SyncStatus(SyncPhase.METADATA,2,8,3,1,"IMG.CR3",true,"Reading metadata"))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithText("METADATA: Reading metadata").assertExists()
        onNodeWithText("Cancel").assertIsEnabled()
        onNodeWithText("Choose library folder").assertIsNotEnabled()
        onNodeWithText("PhotoTool never changes RAW or JPEG bytes.",substring=true).assertExists()
    }

    @Test fun detailNavigationAndGpsControlsUsePlatformCallbacks() = runComposeUiTest {
        var navigation=0; var openedGps=false
        val located=photo.copy(metadata=photo.metadata.copy(latitude=-23.5,longitude=-46.6,status=MetadataStatus.AVAILABLE))
        val prior=photo.copy(id="zero",stem="IMG_0",jpegPath="2025/IMG_0.JPG")
        val state=AppState(library="/copy",cache="/cache",photos=listOf(prior,located),selectedId=located.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},navigate={navigation=it},openMap={_,_->openedGps=true})) }
        onNodeWithContentDescription("Previous photograph").performClick(); assert(navigation == -1)
        onNodeWithText("Open -23.5, -46.6 in OSM").performScrollTo().performClick(); assert(openedGps)
        onNodeWithContentDescription("Photo inspector").assertExists()
    }

    @Test fun keyboardPolicyCoversEditorialSetAndIgnoresTextAndModifiers() {
        val initial=EditorialState(Flag.UNFLAGGED,0,null)
        assert(editorialForShortcut(Key.P,initial)?.flag==Flag.PICK)
        assert(editorialForShortcut(Key.X,initial)?.flag==Flag.REJECT)
        assert(editorialForShortcut(Key.Five,initial)?.rating==5)
        assert(editorialForShortcut(Key.G,initial)?.label==ColorLabel.GREEN)
        assert(shortcutAllowed(true,false,true,false,false,false,false))
        assert(!shortcutAllowed(true,true,true,false,false,false,false))
        assert(!shortcutAllowed(true,false,true,true,false,false,false))
        assert(!shortcutAllowed(false,false,true,false,false,false,false))
    }

    @Test fun boundaryNavigationIsDisabled() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Previous photograph").assertIsNotEnabled()
        onNodeWithContentDescription("Next photograph").assertIsNotEnabled()
    }

    @Test fun narrowPanelDismissalClearsSelectionAndPanelsStayExclusive() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,filtersOpen=false))
        setContent { Box(Modifier.width(320.dp).height(700.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        onNodeWithContentDescription("Close photo inspector and clear selection").performClick()
        assert(state.selectedId == null)
        onNodeWithText("Filters").performClick()
        assert(state.filtersOpen && state.selectedId == null)
    }

    @Test fun focusedControlShortcutFallbackRequiresViewportScope() {
        assert(!shortcutAllowed(true,false,false,false,false,false,false))
        assert(shortcutAllowed(true,false,true,false,false,false,false))
    }

    @Test fun focusedButtonConsumesEnterBeforeShortcutFallback() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),filtersOpen=false))
        var fallback=0
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)}),Modifier.onKeyEvent { fallback++; true }) }
        onNodeWithText("Settings").requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(Screen.SETTINGS,state.screen); assertEquals(0,fallback)
    }

    @Test fun focusedHdrSliderConsumesArrowBeforeShortcutFallback() = runComposeUiTest {
        val selected=photo.copy(writable=true)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(selected),selectedId=selected.id,screen=Screen.DETAIL,writeEnabled=true)
        var fallback=0
        val controls=listOf("SDRBrightness","SDRContrast","SDRClarity","SDRHighlights","SDRShadows","SDRWhites","SDRBlend").associateWith { 0 }
        val auxiliary=AuxiliaryActions(load={ AuxiliaryView(hdr=HdrView(true,"4.00",controls)) })
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=auxiliary),Modifier.onKeyEvent { fallback++; true }) }
        onNodeWithContentDescription("SDRBrightness").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0,fallback)
    }

    @Test fun sequentialRapidEditsPreserveChangesAcrossFields() {
        val base=EditorialState()
        val pending=mergeEditorial(base,base.copy(flag=Flag.PICK),base)
        assertEquals(EditorialState(flag=Flag.PICK,rating=5),mergeEditorial(base,base.copy(rating=5),pending))
    }

    @Test fun coalescedControllerPublishesOnlyCurrentGenerationAcrossThreeInterleavedFields() {
        val executor=Executors.newSingleThreadExecutor(); val firstStarted=CountDownLatch(1); val releaseFirst=CountDownLatch(1); val completed=CountDownLatch(1)
        val publications=java.util.Collections.synchronizedList(mutableListOf<Pair<EditorialState,WriteState>>()); var calls=0
        val controller=CoalescedEditController(executor) { _,_,editorial,status,_ -> publications += editorial to status; completed.countDown() }
        val base=EditorialState(); val selected=photo.copy(editorial=base)
        controller.submit(selected,base.copy(flag=Flag.PICK),persist={ _,_ -> calls++; firstStarted.countDown(); releaseFirst.await(5,TimeUnit.SECONDS); error("delayed stale failure") },updateCache={_,_->})
        assertTrue(firstStarted.await(5,TimeUnit.SECONDS))
        controller.submit(selected.copy(editorial=base.copy(flag=Flag.PICK)),base.copy(flag=Flag.PICK,rating=4),persist={ _,desired -> calls++; desired },updateCache={_,_->})
        val final=base.copy(flag=Flag.PICK,rating=4,label=ColorLabel.GREEN)
        controller.submit(selected.copy(editorial=base.copy(flag=Flag.PICK,rating=4)),final,persist={ _,desired -> calls++; desired },updateCache={_,_->})
        releaseFirst.countDown(); assertTrue(completed.await(5,TimeUnit.SECONDS)); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(2,calls); assertEquals(listOf(final to WriteState.PERSISTED),publications)
    }

    @Test fun arrowKeysMoveFocusAndScrollToOffscreenGalleryCards() = runComposeUiTest {
        val photos=(0 until 30).map { index -> photo.copy(id="photo-$index",stem="IMG_$index",jpegPath="2025/IMG_$index.JPG") }
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=photos,selectedId=photos.first().id,filtersOpen=false))
        var columns=1
        setContent { Box(Modifier.width(1000.dp).height(300.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)},galleryColumnsChanged={columns=it})) } }
        waitForIdle(); val ordered=state.visiblePhotos; var index=0
        repeat(8) {
            onNodeWithContentDescription("Photograph ${ordered[index].displayName}").performKeyInput { pressKey(Key.DirectionDown) }
            index = (index + columns).coerceAtMost(ordered.lastIndex); waitForIdle()
        }
        assertEquals(ordered[index].id,state.selectedId)
        onNodeWithContentDescription("Photograph ${ordered[index].displayName}").assertIsDisplayed().assertIsFocused()
    }

    @Test fun narrowInspectorOwnsKeyboardAndEscapeDismissesWithoutGalleryShortcut() = runComposeUiTest {
        var mutations=0
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,writeEnabled=true,filtersOpen=false))
        setContent { Box(Modifier.width(320.dp).height(700.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)},mutate={_,_->mutations++})) } }
        waitForIdle()
        onNodeWithContentDescription("Photo inspector").assertIsFocused().performKeyInput { pressKey(Key.P); pressKey(Key.Escape) }
        waitForIdle(); assertEquals(0,mutations); assertEquals(null,state.selectedId)
        onNodeWithContentDescription("Photo inspector").assertDoesNotExist()
    }

    @Test fun inspectorEditorialControlsDispatchMutationInWriteMode() = runComposeUiTest {
        var desired: EditorialState?=null
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,writeEnabled=true,filtersOpen=false)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},mutate={_,editorial->desired=editorial})) }
        onAllNodesWithText("P")[0].performClick()
        waitForIdle()
        assert(desired?.flag==Flag.PICK)
    }
}
