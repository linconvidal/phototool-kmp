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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class PhotoToolSemanticsTest {
    private val photo=Photo("one","2025","IMG_1","2025/IMG_1.xmp",jpegPath="2025/IMG_1.JPG",metadata=ObservedMetadata(width=3,height=2))

    @Test fun gallerySingleClickOpensImageFirstDetail() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={ state=reduce(state,it) })) }
        onNodeWithContentDescription("Fotografia IMG_1.JPG").performClick()
        assertEquals(Screen.DETAIL,state.screen)
        onNodeWithContentDescription("Visualizador da fotografia").assertExists()
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertExists()
    }

    @Test fun readOnlyGatesInspectorMutationControls() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL,writeEnabled=false,filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={ state=reduce(state,it) })) }
        onNodeWithContentDescription("Marcar como escolhida").assertIsNotEnabled()
        onNodeWithContentDescription("Avaliação editorial").assertIsNotEnabled()
        onNodeWithContentDescription("Rótulo verde").assertIsNotEnabled()
    }

    @Test fun settingsToggleReadOnlyMode() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",screen=Screen.SETTINGS,writeEnabled=true,writeAuthorized=true))
        var requested: Boolean? = null
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},setReadOnlyMode={ enabled -> requested=enabled; state=state.copy(writeEnabled=!enabled) })) }
        onNodeWithContentDescription("Modo somente leitura").assertIsOff().assertIsEnabled().performClick()
        assertEquals(true,requested)
        onNodeWithContentDescription("Modo somente leitura").assertIsOn()
        onNodeWithText("Novas alterações em XMP e FP2 estão bloqueadas",substring=true).assertExists()
        // toggle back to write
        runOnIdle { state=state.copy(writeEnabled=true) }
        onNodeWithContentDescription("Modo somente leitura").assertIsOff().assertIsEnabled()
        onNodeWithText("A escrita está liberada",substring=true).assertExists()
    }

    @Test fun phoneSettingsKeepReadOnlySwitchAccessibleAndAtLeast44Dp() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",screen=Screen.SETTINGS,writeEnabled=false,writeAuthorized=true)
        setContent { Box(Modifier.width(390.dp).height(844.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        val switch=onNodeWithContentDescription("Modo somente leitura").performScrollTo().assertIsDisplayed().assertIsEnabled().assertIsOn()
        val bounds=switch.getUnclippedBoundsInRoot()
        assertTrue(bounds.right-bounds.left >= 44.dp,"switch width=${bounds.right-bounds.left}")
        assertTrue(bounds.bottom-bounds.top >= 44.dp,"switch height=${bounds.bottom-bounds.top}")
    }

    @Test fun settingsExposeRunningSynchronizationAndCancel() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",screen=Screen.SETTINGS,sync=SyncStatus(SyncPhase.METADATA,2,8,3,1,"IMG.CR3",true,"Reading metadata"))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithText("Lendo metadados").assertExists()
        onNodeWithContentDescription("Voltar à galeria").assertIsFocused()
        onNodeWithText("Cancelar").assertIsEnabled()
        onNodeWithText("Escolher pasta da biblioteca").assertIsNotEnabled()
        onNodeWithText("PhotoTool nunca altera os bytes de arquivos RAW ou JPEG.",substring=true).assertExists()
    }

    @Test fun settingsDoNotRepeatTheSynchronizationPhaseAsMessage() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",screen=Screen.SETTINGS,sync=SyncStatus(SyncPhase.COMPLETE,message="Sincronização concluída"))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithText("Concluída").assertExists()
        onNodeWithText("Sincronização concluída").assertDoesNotExist()
    }

    @Test fun detailNavigationAndGpsControlsUsePlatformCallbacks() = runComposeUiTest {
        var navigation=0; var openedGps=false
        val located=photo.copy(metadata=photo.metadata.copy(latitude=-23.5,longitude=-46.6,status=MetadataStatus.AVAILABLE))
        val prior=photo.copy(id="zero",stem="IMG_0",jpegPath="2025/IMG_0.JPG")
        val state=AppState(library="/copy",cache="/cache",photos=listOf(prior,located),selectedId=located.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},navigate={navigation=it},openMap={_,_->openedGps=true})) }
        onNodeWithContentDescription("Fotografia anterior").performClick(); assert(navigation == -1)
        onNodeWithText("Abrir localização no OSM").performScrollTo().performClick(); assert(openedGps)
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertExists()
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

    @Test fun emptyStateFocusesItsPrimaryRecoveryAction() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache")
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithText("Sincronizar agora").assertIsFocused()
    }

    @Test fun galleryViewportReceivesInitialKeyboardFocus() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo)))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Galeria de fotografias").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(photo.id,state.selectedId)
    }

    @Test fun searchKeepsFocusWhileResultsDisappearAndReturn() = runComposeUiTest {
        val second = photo.copy(id="two", stem="IMG_2", jpegPath="2025/IMG_2.JPG")
        var state by mutableStateOf(AppState(library="/copy", cache="/cache", photos=listOf(photo, second)))
        setContent { PhotoToolApp(state, AppCallbacks(dispatch={ state=reduce(state,it) })) }
        onNodeWithContentDescription("Pesquisar fotografias").requestFocus().performTextReplacement("sem resultado")
        onNodeWithContentDescription("Pesquisar fotografias").assertIsFocused()
        onNodeWithText("Nenhum resultado").assertExists()
        onNodeWithContentDescription("Pesquisar fotografias").performTextReplacement("IMG_2")
        onNodeWithContentDescription("Pesquisar fotografias").assertIsFocused()
        onNodeWithContentDescription("Fotografia IMG_2.JPG").assertExists()
        assertEquals("IMG_2", state.query.search)
    }

    @Test fun quickEditorFocusIsVisibleAndEscapeAlwaysClosesIt() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Abrir curadoria rápida de ${photo.displayName}").assertDoesNotExist()
        onNodeWithContentDescription("Fotografia ${photo.displayName}").requestFocus()
        onNodeWithContentDescription("Abrir curadoria rápida de ${photo.displayName}").performClick()
        onNodeWithContentDescription("Fechar curadoria rápida").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
        onNodeWithContentDescription("Curadoria rápida de ${photo.displayName}").assertDoesNotExist()
    }

    @Test fun phoneQuickEditorKeepsEveryColorTargetAtLeast44Dp() = runComposeUiTest {
        val writable=photo.copy(writable=true)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(writable),writeEnabled=true,thumbnailSize=150)
        setContent { Box(Modifier.width(390.dp).height(844.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        onNodeWithContentDescription("Abrir curadoria rápida de ${photo.displayName}").performClick()
        listOf("Remover rótulo de cor","Rótulo vermelho","Rótulo amarelo","Rótulo verde").forEach { description ->
            val bounds=onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            assertTrue(bounds.right-bounds.left >= 44.dp, "$description width=${bounds.right-bounds.left}")
            assertTrue(bounds.bottom-bounds.top >= 44.dp, "$description height=${bounds.bottom-bounds.top}")
        }
    }

    @Test fun quickEditorIsCenteredOverItsOwnPhotograph() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),writeEnabled=true,thumbnailSize=220)
        setContent { Box(Modifier.width(1200.dp).height(800.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        val card=onNodeWithContentDescription("Fotografia ${photo.displayName}").requestFocus()
        onNodeWithContentDescription("Abrir curadoria rápida de ${photo.displayName}").performClick()
        waitForIdle()
        val cardBounds=card.getUnclippedBoundsInRoot()
        val scrimBounds=onNodeWithContentDescription("Área de curadoria rápida sobre ${photo.displayName}").getUnclippedBoundsInRoot()
        val editorBounds=onNodeWithContentDescription("Curadoria rápida de ${photo.displayName}").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((scrimBounds.left-cardBounds.left).value) < 2f)
        assertTrue(kotlin.math.abs((scrimBounds.top-cardBounds.top).value) < 2f)
        assertTrue(kotlin.math.abs((scrimBounds.right-cardBounds.right).value) < 2f)
        assertTrue(scrimBounds.bottom < cardBounds.bottom,"the proof annotation must remain outside the photograph overlay")
        assertTrue(kotlin.math.abs((((editorBounds.left+editorBounds.right)/2)-((scrimBounds.left+scrimBounds.right)/2)).value) < 2f)
        assertTrue(kotlin.math.abs((((editorBounds.top+editorBounds.bottom)/2)-((scrimBounds.top+scrimBounds.bottom)/2)).value) < 2f)
    }

    @Test fun boundaryNavigationIsDisabled() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Fotografia anterior").assertIsNotEnabled()
        onNodeWithContentDescription("Próxima fotografia").assertIsNotEnabled()
    }

    @Test fun detailZoomShowsCurrentLevelAndCanReturnToFit() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Zoom atual: 100%").assertExists()
        onNodeWithContentDescription("Diminuir zoom").assertIsNotEnabled()
        onNodeWithContentDescription("Ajustar fotografia à janela").assertIsNotEnabled()
        onNodeWithContentDescription("Aumentar zoom").performClick()
        onNodeWithContentDescription("Zoom atual: 125%").assertExists()
        onNodeWithContentDescription("Diminuir zoom").assertIsEnabled()
        onNodeWithContentDescription("Ajustar fotografia à janela").performClick()
        onNodeWithContentDescription("Zoom atual: 100%").assertExists()
    }

    @Test fun textInputsKeepAtLeastTheMaterialReadableHeight() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo)))
        setContent { Box(Modifier.width(1200.dp).height(800.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        val searchBounds=onNodeWithContentDescription("Pesquisar fotografias").getUnclippedBoundsInRoot()
        assertTrue(searchBounds.bottom-searchBounds.top >= 56.dp)
        onNodeWithContentDescription("Filtros").performClick(); onNodeWithText("Data").performClick()
        val filterBounds=onNodeWithContentDescription("Filtro De: AAAA-MM-DD").getUnclippedBoundsInRoot()
        assertTrue(filterBounds.bottom-filterBounds.top >= 56.dp)
        runOnIdle { state=state.copy(filtersOpen=false,selectionModeActive=true,selectedId=photo.id,selectedIds=setOf(photo.id),writeEnabled=true) }
        waitForIdle()
        val batchBounds=onNodeWithContentDescription("Palavra-chave em lote").getUnclippedBoundsInRoot()
        assertTrue(batchBounds.bottom-batchBounds.top >= 56.dp)
        runOnIdle { state=state.copy(screen=Screen.DETAIL,selectionModeActive=false) }
        waitForIdle()
        val detailBounds=onNodeWithContentDescription("Nova palavra-chave").getUnclippedBoundsInRoot()
        assertTrue(detailBounds.bottom-detailBounds.top >= 56.dp)
    }

    @Test fun narrowFilterPanelIsModalAndDismissible() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo)))
        setContent { Box(Modifier.width(390.dp).height(844.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        onNodeWithContentDescription("Painel de filtros").assertDoesNotExist()
        onNodeWithContentDescription("Filtros").performClick()
        onNodeWithContentDescription("Painel de filtros").assertExists()
        onNodeWithContentDescription("Filtros modais abertos").assertExists()
        onNodeWithContentDescription("Fotografia IMG_1.JPG").assertExists()
        onNodeWithContentDescription("Aplicar filtros").assertIsFocused()
        onNodeWithContentDescription("Fechar filtros sem aplicar").requestFocus().performKeyInput { pressKey(Key.Escape) }
        assertTrue(!state.filtersOpen)
        onNodeWithContentDescription("Fotografia IMG_1.JPG").assertExists()
    }

    @Test fun filterChangesStayDraftUntilApplied() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo)))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Filtros").performClick()
        onNodeWithText("Rejeitada").performClick()
        assertNull(state.query.flag)
        onNodeWithContentDescription("Aplicar filtros").performClick()
        assertEquals(Flag.REJECT,state.query.flag)
        assertTrue(!state.filtersOpen)
    }

    @Test fun invalidDateDraftShowsErrorAndCannotChangeQuery() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo)))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Filtros").performClick(); onNodeWithText("Data").performClick()
        onNodeWithText("De: AAAA-MM-DD").performTextInput("2025-02-30")
        onNodeWithContentDescription("Erro de intervalo de datas").assertExists()
        onNodeWithContentDescription("Aplicar filtros").assertIsNotEnabled()
        assertNull(state.query.fromDate)
    }

    @Test fun focusedControlShortcutFallbackRequiresViewportScope() {
        assert(!shortcutAllowed(true,false,false,false,false,false,false))
        assert(shortcutAllowed(true,false,true,false,false,false,false))
    }

    @Test fun focusedButtonConsumesEnterBeforeShortcutFallback() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),filtersOpen=false))
        var fallback=0
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)}),Modifier.onKeyEvent { fallback++; true }) }
        onNodeWithContentDescription("Configurações").requestFocus().performKeyInput { pressKey(Key.Enter) }
        assertEquals(Screen.SETTINGS,state.screen); assertEquals(0,fallback)
    }

    @Test fun focusedHdrSliderConsumesArrowBeforeShortcutFallback() = runComposeUiTest {
        val selected=photo.copy(writable=true,rawPath="2025/IMG_1.RAF",jpegPath=null)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(selected),selectedId=selected.id,screen=Screen.DETAIL,writeEnabled=true)
        var fallback=0
        val controls=listOf("SDRBrightness","SDRContrast","SDRClarity","SDRHighlights","SDRShadows","SDRWhites","SDRBlend").associateWith { 0 }
        val auxiliary=AuxiliaryActions(load={ AuxiliaryView(hdr=HdrView(true,"4.00",controls)) })
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=auxiliary),Modifier.onKeyEvent { fallback++; true }) }
        onNodeWithContentDescription("Mostrar receitas e HDR").performClick()
        onNodeWithContentDescription("Brilho SDR").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0,fallback)
    }

    @Test fun sequentialRapidEditsPreserveChangesAcrossFields() {
        val base=EditorialState()
        val pending=mergeEditorial(base,base.copy(flag=Flag.PICK),base)
        assertEquals(EditorialState(flag=Flag.PICK,rating=5),mergeEditorial(base,base.copy(rating=5),pending))
    }

    @Test fun batchMutationPlanIsBoundedDistinctAndKeepsPerPhotoEditorialState() {
        val photos=(0..550).map { index -> photo.copy(id="p-$index",editorial=EditorialState(rating=index%6)) } + photo.copy(id="p-0") + photo.copy(id="blocked",writable=false)
        val plan=batchMutationPlan(photos,BatchEdit.SetFlag(Flag.PICK))
        assertEquals(MAX_BATCH_PHOTOS,plan.size); assertEquals(MAX_BATCH_PHOTOS,plan.map { it.first.id }.distinct().size)
        assertTrue(plan.all { (source,desired) -> desired.flag==Flag.PICK && desired.rating==source.editorial.rating })
    }

    @Test fun failedXmpMutationRestoresConfirmedEditorialAndKeepsErrorOnPhoto() {
        val executor=Executors.newSingleThreadExecutor(); val completed=CountDownLatch(1)
        val confirmed=EditorialState(rating=2); var result: EditorialState?=null; var status: WriteState?=null; var message: String?=null
        val controller=CoalescedEditController(executor) { _,_,_,editorial,writeState,error -> result=editorial; status=writeState; message=error; completed.countDown() }
        controller.submit(photo.copy(editorial=confirmed),confirmed.copy(rating=5),rollback=confirmed,persist={_,_->error("write failed")},updateCache={_,_->})
        assertTrue(completed.await(5,TimeUnit.SECONDS)); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(confirmed,result); assertEquals(WriteState.FAILED,status); assertTrue(message?.contains("restaurado") == true)
        val reduced=reduce(AppState(photos=listOf(photo)),Action.EditorialChanged(photo.id,confirmed,WriteState.FAILED,message))
        assertEquals(message,reduced.photos.single().writeError); assertEquals("Nenhuma sincronização executada",reduced.sync.message)
    }

    @Test fun coalescedFailureRollsBackToImmediatelyPreviousConfirmedWrite() {
        val executor=Executors.newSingleThreadExecutor()
        val firstStarted=CountDownLatch(1); val releaseFirst=CountDownLatch(1); val completed=CountDownLatch(1)
        val base=EditorialState(rating=1)
        val confirmedA=base.copy(rating=3)
        var rollback: EditorialState?=null; var status: WriteState?=null
        val controller=CoalescedEditController(executor) { _,_,_,editorial,state,_ -> rollback=editorial; status=state; completed.countDown() }
        controller.submit(photo.copy(editorial=base),confirmedA,persist={_,desired->firstStarted.countDown(); releaseFirst.await(5,TimeUnit.SECONDS); desired},updateCache={_,_->})
        assertTrue(firstStarted.await(5,TimeUnit.SECONDS))
        controller.submit(photo.copy(editorial=confirmedA),confirmedA.copy(rating=5),rollback=base,persist={_,_->error("second write failed")},updateCache={_,_->})
        releaseFirst.countDown()
        assertTrue(completed.await(5,TimeUnit.SECONDS)); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(confirmedA,rollback); assertEquals(WriteState.FAILED,status)
    }

    @Test fun coalescedControllerPublishesOnlyCurrentGenerationAcrossThreeInterleavedFields() {
        val executor=Executors.newSingleThreadExecutor(); val firstStarted=CountDownLatch(1); val releaseFirst=CountDownLatch(1); val completed=CountDownLatch(1)
        val publications=java.util.Collections.synchronizedList(mutableListOf<Pair<EditorialState,WriteState>>()); var calls=0
        val controller=CoalescedEditController(executor) { _,_,_,editorial,status,_ -> publications += editorial to status; completed.countDown() }
        val base=EditorialState(); val selected=photo.copy(editorial=base)
        controller.submit(selected,base.copy(flag=Flag.PICK),persist={ _,_ -> calls++; firstStarted.countDown(); releaseFirst.await(5,TimeUnit.SECONDS); error("delayed stale failure") },updateCache={_,_->})
        assertTrue(firstStarted.await(5,TimeUnit.SECONDS))
        controller.submit(selected.copy(editorial=base.copy(flag=Flag.PICK)),base.copy(flag=Flag.PICK,rating=4),persist={ _,desired -> calls++; desired },updateCache={_,_->})
        val final=base.copy(flag=Flag.PICK,rating=4,label=ColorLabel.GREEN)
        controller.submit(selected.copy(editorial=base.copy(flag=Flag.PICK,rating=4)),final,persist={ _,desired -> calls++; desired },updateCache={_,_->})
        releaseFirst.countDown(); assertTrue(completed.await(5,TimeUnit.SECONDS)); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(2,calls); assertEquals(listOf(final to WriteState.PERSISTED),publications)
    }

    @Test fun coalescedEditsWithSamePhotoIdAreIsolatedByLibrarySession() {
        val executor=Executors.newSingleThreadExecutor(); val firstStarted=CountDownLatch(1); val release=CountDownLatch(1); val done=CountDownLatch(2)
        val contexts=java.util.Collections.synchronizedList(mutableListOf<String>())
        val controller=CoalescedEditController(executor) { context,_,_,_,_,_ -> contexts += context; done.countDown() }
        controller.submit(photo,EditorialState(rating=1),context="root-a",persist={_,desired->firstStarted.countDown(); release.await(5,TimeUnit.SECONDS); desired},updateCache={_,_->})
        assertTrue(firstStarted.await(5,TimeUnit.SECONDS))
        controller.submit(photo,EditorialState(rating=5),context="root-b",persist={_,desired->desired},updateCache={_,_->})
        release.countDown(); assertTrue(done.await(5,TimeUnit.SECONDS)); executor.shutdown(); assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))
        assertEquals(listOf("root-a","root-b"),contexts)
        assertTrue(controller.isCurrent("root-a",photo.id,1)); assertTrue(controller.isCurrent("root-b",photo.id,1))
    }

    @Test fun galleryUsesPackedMasonryAndPreservesPhotoProportions() = runComposeUiTest {
        val portrait = photo.copy(id="portrait", stem="A", jpegPath="2025/A.JPG", metadata=photo.metadata.copy(width=1, height=3))
        val landscape = photo.copy(id="landscape", stem="B", jpegPath="2025/B.JPG", metadata=photo.metadata.copy(width=6, height=1))
        val following = landscape.copy(id="following", stem="C", jpegPath="2025/C.JPG")
        val state=AppState(library="/copy",cache="/cache",photos=listOf(portrait,landscape,following),thumbnailSize=220,filtersOpen=false)
        setContent { Box(Modifier.width(600.dp).height(800.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        waitForIdle()
        val portraitBounds=onNodeWithContentDescription("Quadro de A.JPG",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val landscapeBounds=onNodeWithContentDescription("Quadro de B.JPG",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val followingBounds=onNodeWithContentDescription("Fotografia C.JPG").fetchSemanticsNode().boundsInRoot
        assertTrue(portraitBounds.height > landscapeBounds.height * 10f,"portrait=${portraitBounds.height} landscape=${landscapeBounds.height}")
        assertTrue(followingBounds.top < portraitBounds.bottom, "The shorter column should accept the next photo before the tall card ends")
    }

    @Test fun spatialNeighborUsesRenderedGeometryInsteadOfListIndex() {
        val current=SpatialBounds("current",0f,0f,100f,300f)
        val sameLane=SpatialBounds("same-lane",0f,305f,100f,405f)
        val misleadingIndexNeighbor=SpatialBounds("side",105f,200f,205f,500f)
        assertEquals("same-lane",spatialNeighbor(current,listOf(misleadingIndexNeighbor,sameLane),SpatialDirection.DOWN))
        assertEquals("side",spatialNeighbor(current,listOf(misleadingIndexNeighbor,sameLane),SpatialDirection.RIGHT))
    }

    @Test fun arrowKeysMoveFocusAndScrollToOffscreenGalleryCards() = runComposeUiTest {
        val photos=(0 until 30).map { index -> photo.copy(id="photo-$index",stem="IMG_$index",jpegPath="2025/IMG_$index.JPG") }
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=photos,selectedId=photos.first().id,filtersOpen=false))
        setContent { Box(Modifier.width(1000.dp).height(300.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        waitForIdle()
        repeat(6) {
            val previous=state.selectedId
            val previousPhoto=state.visiblePhotos.first { it.id==previous }
            onNodeWithContentDescription("Fotografia ${previousPhoto.displayName}").performKeyInput { pressKey(Key.DirectionDown) }
            waitUntil(timeoutMillis=5_000) { state.selectedId != previous }
            waitForIdle()
            val selected=state.visiblePhotos.first { it.id==state.selectedId }
            onNodeWithContentDescription("Fotografia ${selected.displayName}").assertIsDisplayed().assertIsFocused()
        }
        assertTrue(state.selectedId != photos.first().id)
    }

    @Test fun estimatedMasonrySearchReachesNeighborBeyondTallViewport() {
        val tall=photo.copy(id="tall",stem="TALL",jpegPath="2025/TALL.JPG",metadata=photo.metadata.copy(width=1,height=3))
        val below=photo.copy(id="below",stem="BELOW",jpegPath="2025/BELOW.JPG",metadata=photo.metadata.copy(width=1,height=1))
        assertEquals(below.id,estimatedMasonryNeighbor(listOf(tall,below),tall.id,1,SpatialDirection.DOWN))
        assertEquals(tall.id,estimatedMasonryNeighbor(listOf(tall,below),below.id,1,SpatialDirection.UP))
    }

    @Test fun shiftArrowExtendsRepeatedlyAndShrinksBack() = runComposeUiTest {
        val photos=(0 until 8).map { index -> photo.copy(id="shift-$index",stem="S_$index",jpegPath="2025/S_$index.JPG") }
        var state by mutableStateOf(reduce(AppState(library="/copy",cache="/cache",photos=photos,selectionModeActive=true),Action.Select(photos.first().id)))
        setContent { Box(Modifier.width(420.dp).height(800.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        waitForIdle()
        fun shift(key: Key) {
            val before=state.selectedId
            val selected=state.visiblePhotos.first { it.id==state.selectedId }
            onNodeWithContentDescription("Fotografia ${selected.displayName}").performKeyInput { keyDown(Key.ShiftLeft); pressKey(key); keyUp(Key.ShiftLeft) }
            waitUntil(timeoutMillis=5_000) { state.selectedId != before }
            waitForIdle()
        }
        shift(Key.DirectionDown); val afterFirst=state.selectionIds.size
        shift(Key.DirectionDown); assertTrue(state.selectionIds.size > afterFirst, "afterFirst=$afterFirst selected=${state.selectedId} ids=${state.selectionIds}")
        shift(Key.DirectionUp); assertEquals(afterFirst,state.selectionIds.size)
    }

    @Test fun explicitSelectionModeKeepsSingleClickOutOfDetail() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),selectionModeActive=true,writeEnabled=true,filtersOpen=false))
        setContent { Box(Modifier.width(600.dp).height(800.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) } }
        onNodeWithContentDescription("Fotografia IMG_1.JPG").performClick()
        assertEquals(Screen.GALLERY,state.screen)
        assertTrue(photo.id in state.selectionIds)
    }

    @Test fun inspectorEditorialControlsDispatchMutationInWriteMode() = runComposeUiTest {
        var desired: EditorialState?=null
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL,writeEnabled=true,filtersOpen=false)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},mutate={_,editorial->desired=editorial})) }
        onNodeWithContentDescription("Marcar como escolhida").performClick()
        waitForIdle()
        assert(desired?.flag==Flag.PICK)
    }

    @Test fun enterAddsAValidKeywordWithoutClickingTheButton() = runComposeUiTest {
        var desired: EditorialState?=null; var calls=0
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},mutate={_,editorial->calls++; desired=editorial})) }
        val keywordInput=onNodeWithContentDescription("Nova palavra-chave")
        keywordInput.performTextInput("Viagem")
        keywordInput.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1,calls)
        assertEquals(listOf("Viagem"),desired?.keywords)
    }

    @Test fun newKeywordMustBeFlatButExistingHierarchyCanBeRemoved() = runComposeUiTest {
        var desired: EditorialState?=null
        val hierarchical=photo.copy(editorial=photo.editorial.copy(keywords=listOf("Lugar|Centro")))
        val state=AppState(library="/copy",cache="/cache",photos=listOf(hierarchical),selectedId=hierarchical.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},mutate={_,editorial->desired=editorial})) }
        val keywordInput=onNodeWithContentDescription("Nova palavra-chave")
        keywordInput.performTextInput("Nova|Hierarquia")
        keywordInput.performKeyInput { pressKey(Key.Enter) }
        onNodeWithContentDescription("Erro de palavra-chave").assertExists(); assertNull(desired)
        onNodeWithText("Lugar|Centro").performClick(); assertTrue(desired?.keywords?.isEmpty() == true)
    }

    @Test fun multiSelectionShowsBoundedBatchBarAndDispatchesRealEdit() = runComposeUiTest {
        val second=photo.copy(id="two",stem="IMG_2",jpegPath="2025/IMG_2.JPG")
        var edit: BatchEdit?=null; var count=0
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo,second),selectedId=photo.id,selectedIds=setOf(photo.id,second.id),selectionModeActive=true,writeEnabled=true,filtersOpen=false)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},batchMutate={photos,requested->count=photos.size; edit=requested})) }
        onNodeWithText("2 selecionadas").assertExists()
        onAllNodesWithContentDescription("Concluir seleção").assertCountEquals(1)
        onNodeWithText("Escolher").performClick()
        assertEquals(2,count); assertEquals(BatchEdit.SetFlag(Flag.PICK),edit)
        onNodeWithContentDescription("Resultado solicitado do lote editorial").assertExists()
    }

    @Test fun sensitiveBatchIsExplicitPerChannelAndShowsPartialResult() = runComposeUiTest {
        val raf=photo.copy(id="raf",rawPath="2025/IMG_1.RAF",jpegPath=null,writable=true)
        var requested: AuxiliaryBatchEdit?=null
        val auxiliary=AuxiliaryActions(batchUpdate={ photos,edit ->
            requested=edit
            AuxiliaryBatchResult("HDR em RAF",photos.size,1,0,0,listOf(AuxiliaryBatchItemResult(raf.id,"HDR em RAF",AuxiliaryBatchOutcome.SUCCEEDED)))
        })
        val state=AppState(library="/copy",cache="/cache",photos=listOf(raf),selectedId=raf.id,selectedIds=setOf(raf.id),selectionModeActive=true,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=auxiliary)) }
        onNodeWithContentDescription("Mostrar controles Fuji e HDR em lote").performClick()
        onNodeWithContentDescription("Definir HDR configurado em lote").performClick(); waitForIdle()
        assertEquals(AuxiliaryBatchEdit.SetHdr(true, "4.00", listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").associateWith { 0 }),requested)
        onNodeWithContentDescription("Resultado do lote HDR em RAF").assertExists()
        onNodeWithText("IMG_1.RAF · salva").assertExists()
        onNodeWithContentDescription("Menu Simulação em lote").performClick()
        onNodeWithText("Astia").performClick(); waitForIdle()
        assertEquals(AuxiliaryBatchEdit.UpdateFuji(mapOf("FilmSimulation" to "Astia"), "Simulação Astia"),requested)
    }

    @Test fun individualHdrMaximumIsEditableWithinOneToFour() = runComposeUiTest {
        val controls=listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").associateWith { 0 }
        var requested: HdrView?=null
        val auxiliary=AuxiliaryActions(
            load={ AuxiliaryView(hdr=HdrView(true,"2.00",controls)) },
            updateHdr={ _,hdr -> requested=hdr; AuxiliaryView(hdr=hdr) },
        )
        val writable=photo.copy(writable=true,rawPath="2025/IMG_1.RAF",jpegPath=null)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(writable),selectedId=writable.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=auxiliary)) }
        waitForIdle(); onNodeWithContentDescription("Mostrar receitas e HDR").performClick(); waitForIdle()
        onNodeWithContentDescription("Aumentar máximo HDR").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        waitUntil(timeoutMillis=5_000) { requested != null }
        assertEquals("3.00",requested?.maximum)
    }

    @Test fun hdrSliderPersistsLatestGestureEndpointAndNotIntermediateValues() = runComposeUiTest {
        val controls=listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").associateWith { 0 }
        val requests=mutableListOf<HdrView>()
        val auxiliary=AuxiliaryActions(
            load={ AuxiliaryView(hdr=HdrView(true,"2.00",controls)) },
            updateHdr={ _,hdr -> requests += hdr; AuxiliaryView(hdr=hdr) },
        )
        val writable=photo.copy(writable=true,rawPath="2025/IMG_1.RAF",jpegPath=null)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(writable),selectedId=writable.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=auxiliary)) }
        waitForIdle(); onNodeWithContentDescription("Mostrar receitas e HDR").performClick(); waitForIdle()
        onNodeWithContentDescription("Brilho SDR").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { set -> set(-35f) }
        waitUntil(timeoutMillis=5_000) { requests.isNotEmpty() }
        onNodeWithContentDescription("Brilho SDR").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { set -> set(67f) }
        waitUntil(timeoutMillis=5_000) { requests.lastOrNull()?.controls?.get("SDRBrightness")==67 }
        assertEquals(listOf(-35,67),requests.map { it.controls.getValue("SDRBrightness") })
    }

    @Test fun nonRafDetailOmitsHdrPanel() = runComposeUiTest {
        val selected=photo.copy(writable=true)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(selected),selectedId=selected.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Mostrar receitas Fuji").performClick()
        onNodeWithText("HDR DO LIGHTROOM").assertDoesNotExist()
    }

    @Test fun fujiExposureButtonsHaveDistinctAccessibleActions() = runComposeUiTest {
        val recipe=FujiRecipeView("fp2",true,"0",100,"Classic","OFF","Auto",0,0,0,0,0,0,0,false)
        val selected=photo.copy(writable=true,rawPath="2025/IMG_1.RAF",jpegPath=null)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(selected),selectedId=selected.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},auxiliary=AuxiliaryActions(load={ AuxiliaryView(fuji=recipe) }))) }
        waitForIdle(); onNodeWithContentDescription("Mostrar receitas e HDR").performClick(); waitForIdle()
        onNodeWithContentDescription("Diminuir exposição Fuji").performScrollTo().assertExists().assertIsEnabled()
        onNodeWithContentDescription("Aumentar exposição Fuji").assertExists().assertIsEnabled()
    }

    @Test fun lowDetailKeepsPhotographAndScrollableInspectorVisible() = runComposeUiTest {
        val selected=photo.copy(writable=true,rawPath="2025/IMG_1.RAF",jpegPath=null)
        val state=AppState(library="/copy",cache="/cache",photos=listOf(selected),selectedId=selected.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { Box(Modifier.width(600.dp).height(360.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        waitForIdle()
        val viewer=onNodeWithContentDescription("Visualizador da fotografia").assertIsDisplayed().getUnclippedBoundsInRoot()
        val inspector=onNodeWithContentDescription("Painel de curadoria da fotografia").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(viewer.right-viewer.left > 250.dp); assertTrue(inspector.right-inspector.left >= 280.dp); assertTrue(viewer.bottom-viewer.top >= 350.dp)
        onNodeWithText("CURADORIA").assertIsDisplayed()
    }

    @Test fun filmstripScrollsCurrentHighIndexIntoViewport() = runComposeUiTest {
        val photos=(0 until 18).map { index -> photo.copy(id="film-$index",stem="IMG_$index",jpegPath="2025/IMG_$index.JPG") }
        val selected=photos[14]
        val state=AppState(library="/copy",cache="/cache",photos=photos,selectedId=selected.id,screen=Screen.DETAIL)
        setContent { Box(Modifier.width(600.dp).height(700.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        waitForIdle()
        onNodeWithContentDescription("Filmstrip ${selected.displayName}").assertIsDisplayed().assertIsSelected()
    }

    @Test fun batchBarDisclosesSelectionLimitAndNonWritablePhotos() = runComposeUiTest {
        val photos=(0 until 520).map { index -> photo.copy(id="limit-$index",stem="L_$index",jpegPath="2025/L_$index.JPG",writable=index!=0) }
        val selected=photos.take(MAX_BATCH_PHOTOS).mapTo(linkedSetOf()) { it.id }
        val state=AppState(library="/copy",cache="/cache",photos=photos,selectedId=photos.first().id,selectedIds=selected,selectionModeActive=true,writeEnabled=true)
        setContent { Box(Modifier.width(1200.dp).height(500.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        onNodeWithText("100 de 520 selecionadas · limite seguro de 100 atingido").assertExists()
        onNodeWithText("99 editáveis · 1 será ignorada").assertExists()
    }

    @Test fun readOnlyBatchBarDisablesMutationControls() = runComposeUiTest {
        val second=photo.copy(id="two",stem="IMG_2",jpegPath="2025/IMG_2.JPG")
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo,second),selectedId=photo.id,selectedIds=setOf(photo.id,second.id),selectionModeActive=true,writeEnabled=false)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithText("Escolher").assertIsNotEnabled()
        onNodeWithContentDescription("Menu Avaliação em lote").assertIsNotEnabled()
        onNodeWithContentDescription("Ações de palavra-chave em lote").assertIsNotEnabled()
        onNodeWithText("Somente leitura").assertExists()
    }

    @Test fun calendarYearMonthDayDrilldownAndExactDayQueryAreReal() = runComposeUiTest {
        val dated=photo.copy(folder="Trips/Brazil",metadata=photo.metadata.copy(capturedAt="2025-06-03T10:00:00Z"))
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(dated),screen=Screen.CALENDAR,section=LibrarySection.CALENDAR))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Navegar pelo ano 2025, 1 foto").performClick()
        onNodeWithContentDescription("Voltar para todos os anos").assertExists()
        onNodeWithContentDescription("Navegar por Junho de 2025, 1 foto").performClick()
        onNodeWithContentDescription("Voltar para meses de 2025").assertExists()
        onNodeWithContentDescription("Navegar pelo dia 3 de Junho de 2025, 1 foto").performClick()
        assertEquals("2025-06-03",state.query.fromDate); assertEquals("2025-06-03",state.query.toDate); assertEquals(Screen.GALLERY,state.screen)
    }

    @Test fun folderCoverCardDrivesDescendantFolderQuery() = runComposeUiTest {
        val dated=photo.copy(folder="Trips/Brazil",metadata=photo.metadata.copy(capturedAt="2025-06-03T10:00:00Z"))
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(dated),screen=Screen.FOLDERS,section=LibrarySection.FOLDERS))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Navegar pela pasta Trips, 1 foto, intervalo 03/06/2025").performClick()
        assertEquals("Trips",state.query.folder); assertEquals(Screen.GALLERY,state.screen)
    }

    @Test fun focusedGalleryCardOpensVectorQuickEditorAndDispatchesRating() = runComposeUiTest {
        var desired: EditorialState?=null
        val writable=photo.copy(writable=true)
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(writable),writeEnabled=true,filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)},mutate={_,editorial->desired=editorial})) }
        onNodeWithContentDescription("Fotografia IMG_1.JPG").requestFocus()
        waitForIdle()
        onNodeWithContentDescription("Abrir curadoria rápida de IMG_1.JPG").performClick()
        onNodeWithContentDescription("Curadoria rápida de IMG_1.JPG").assertExists()
        onNodeWithContentDescription("Avaliação da curadoria rápida").performTouchInput { click(percentOffset(.8f, .5f)) }
        assertEquals(4,desired?.rating)
    }

    @Test fun activeFilterChipRemovesOnlyItsOwnCriterion() = runComposeUiTest {
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo),query=Query(flag=Flag.PICK,gps=GpsFilter.PRESENT),filtersOpen=false))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Remover filtro Flag: escolhida").performClick()
        assertEquals(null,state.query.flag)
        assertEquals(GpsFilter.PRESENT,state.query.gps)
    }

    @Test fun activeFilterSummaryCoversEquipmentOpenDatesAndClearAll() = runComposeUiTest {
        val equipped=photo.copy(metadata=photo.metadata.copy(camera="Fuji X-Pro2",lens="XF23"))
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(equipped),query=Query(search="IMG",camera="Fuji X-Pro2",lens="XF23",fromDate="2025-01-01")))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Remover filtro Câmera: Fuji X-Pro2").assertExists()
        onNodeWithContentDescription("Remover filtro Lente: XF23").assertExists()
        onNodeWithContentDescription("Remover filtro Desde 2025-01-01").assertExists()
        onNodeWithContentDescription("Limpar todos os filtros ativos").performClick()
        assertEquals("IMG",state.query.search); assertEquals(null,state.query.camera); assertEquals(null,state.query.fromDate)
    }

    @Test fun detailKeepsPrimaryCurationVisibleWithoutTabNavigation() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),selectedId=photo.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Marcar como escolhida").assertIsDisplayed()
        onNodeWithContentDescription("Avaliação editorial").assertIsDisplayed()
        onNodeWithContentDescription("Rótulo vermelho").assertIsDisplayed()
        onNodeWithContentDescription("Mostrar receitas Fuji").assertExists()
    }

    @Test fun editorialClassifiersUseSpatialRailsAndMagneticRating() = runComposeUiTest {
        var desired: EditorialState?=null
        val classified=photo.copy(writable=true,editorial=photo.editorial.copy(rating=2,label=ColorLabel.YELLOW))
        val state=AppState(library="/copy",cache="/cache",photos=listOf(classified),selectedId=classified.id,screen=Screen.DETAIL,writeEnabled=true)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={},mutate={_,editorial->desired=editorial})) }
        val flagBounds=onNodeWithContentDescription("Estado editorial").getUnclippedBoundsInRoot()
        val labelBounds=onNodeWithContentDescription("Rótulo cromático").getUnclippedBoundsInRoot()
        assertTrue(flagBounds.bottom-flagBounds.top >= 44.dp)
        assertTrue(labelBounds.bottom-labelBounds.top >= 44.dp)
        onNodeWithContentDescription("Remover flag").assertIsSelected()
        onNodeWithContentDescription("Estado editorial").performTouchInput { swipe(percentOffset(.5f,.5f),percentOffset(.95f,.5f),200) }; assertEquals(Flag.PICK,desired?.flag)
        onNodeWithContentDescription("Marcar como rejeitada").performClick(); assertEquals(Flag.REJECT,desired?.flag)
        onNodeWithContentDescription("Avaliação editorial").performTouchInput { click(percentOffset(.8f,.5f)) }; assertEquals(4,desired?.rating)
        onNodeWithContentDescription("Rótulo verde").performClick(); assertEquals(ColorLabel.GREEN,desired?.label)
    }

    @Test fun observedMetadataUsesIconsWithoutVisibleFieldLabels() = runComposeUiTest {
        val observed=photo.copy(metadata=ObservedMetadata(camera="FUJIFILM X-Pro2",lens="XF23mmF2",capturedAt="2025-06-01T14:20:00",width=6000,height=4000,focalLength=23.0,aperture=2.0,exposureSeconds=.008,iso=400))
        val state=AppState(library="/copy",cache="/cache",photos=listOf(observed),selectedId=observed.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onNodeWithContentDescription("Câmera: FUJIFILM X-Pro2").assertExists()
        onNodeWithContentDescription("Lente: XF23mmF2").assertExists()
        onNodeWithContentDescription("Dimensões: 6000 × 4000").assertExists()
        onNodeWithText("Câmera").assertDoesNotExist()
        onNodeWithText("Lente").assertDoesNotExist()
        onNodeWithText("Capturada").assertDoesNotExist()
    }

    @Test fun batchBarWrapsAtDesktopNarrowWidthAndKeepsKeywordDisclosureReachable() = runComposeUiTest {
        val second=photo.copy(id="two",stem="IMG_2",jpegPath="2025/IMG_2.JPG")
        var edit: BatchEdit?=null
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo,second),selectedId=photo.id,selectedIds=setOf(photo.id,second.id),selectionModeActive=true,writeEnabled=true,filtersOpen=false)
        setContent { Box(Modifier.width(900.dp).height(600.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={},batchMutate={_,requested->edit=requested})) } }
        onNodeWithContentDescription("Batch command bar").assertIsDisplayed()
        onNodeWithContentDescription("Palavra-chave em lote").assertIsDisplayed()
        onNodeWithContentDescription("Ações de palavra-chave em lote").assertIsDisplayed().performClick()
        onNodeWithText("Limpar palavras-chave").assertIsDisplayed().performClick()
        assertEquals(BatchEdit.ClearKeywords,edit)
    }

    @Test fun quickEditorClosesWhenItsAnchorLeavesTheVisibleQuery() = runComposeUiTest {
        val second=photo.copy(id="two",stem="IMG_2",jpegPath="2025/IMG_2.JPG")
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(photo,second),writeEnabled=true))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Fotografia IMG_1.JPG").requestFocus()
        onNodeWithContentDescription("Abrir curadoria rápida de IMG_1.JPG").performClick()
        onNodeWithContentDescription("Curadoria rápida de IMG_1.JPG").assertExists()
        state=reduce(state,Action.SetQuery(state.query.copy(search="IMG_2")))
        waitForIdle()
        onNodeWithContentDescription("Curadoria rápida de IMG_1.JPG").assertDoesNotExist()
        onNodeWithContentDescription("Galeria de fotografias").assertIsFocused()
    }

    @Test fun photoProblemIsSafeSpecificAndKeyboardReachable() = runComposeUiTest {
        val blocked=photo.copy(issue="XMP read failed: /private/library/secret.xmp",writable=false)
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(blocked)))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Exibir problema de IMG_1.JPG").requestFocus().performClick()
        onNodeWithContentDescription("Problema da fotografia IMG_1.JPG").assertExists()
        onNodeWithText("O sidecar XMP não pôde ser lido com segurança.",substring=true).assertExists()
        onNodeWithText("/private/library",substring=true).assertDoesNotExist()
        onNodeWithContentDescription("Ir às configurações para sincronizar").performClick()
        assertEquals(Screen.SETTINGS,state.screen)
    }

    @Test fun cardsAndDetailDiscloseRawPairAuthority() = runComposeUiTest {
        val pair=photo.copy(stem="IMG_1",rawPath="2025/IMG_1.RAF",jpegPath="2025/IMG_1.JPG")
        var state by mutableStateOf(AppState(library="/copy",cache="/cache",photos=listOf(pair)))
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={state=reduce(state,it)})) }
        onNodeWithContentDescription("Formato RAF + JPEG").assertIsDisplayed()
        onNodeWithContentDescription("Fotografia IMG_1.RAF").performClick()
        onAllNodesWithText("RAF + JPEG",substring=true).assertCountEquals(2)
        onAllNodesWithText("IMG_1.RAF").assertCountEquals(2)
        onNodeWithText("IMG_1.JPG").assertExists()
    }

    @Test fun detailShowsAuthorityFilenameAndNeighborFilmstrip() = runComposeUiTest {
        val second=photo.copy(id="two",stem="IMG_2",jpegPath="2025/IMG_2.JPG")
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo,second),selectedId=photo.id,screen=Screen.DETAIL)
        setContent { PhotoToolApp(state,AppCallbacks(dispatch={})) }
        onAllNodesWithText("IMG_1.JPG").assertCountEquals(2)
        onNodeWithContentDescription("Faixa de fotografias próximas").assertIsDisplayed()
        onNodeWithContentDescription("Filmstrip IMG_2.JPG").assertExists()
    }

    @Test fun lowWindowRailKeepsSettingsReachableWithoutShrinkingTargets() = runComposeUiTest {
        val state=AppState(library="/copy",cache="/cache",photos=listOf(photo),screen=Screen.SETTINGS)
        setContent { Box(Modifier.width(600.dp).height(360.dp)) { PhotoToolApp(state,AppCallbacks(dispatch={})) } }
        waitForIdle()
        val settings=onNodeWithContentDescription("Configurações")
        settings.assertIsSelected().assertIsDisplayed()
        val bounds=settings.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 44f && bounds.height >= 44f); assertTrue(bounds.bottom <= 346f)
    }
}
