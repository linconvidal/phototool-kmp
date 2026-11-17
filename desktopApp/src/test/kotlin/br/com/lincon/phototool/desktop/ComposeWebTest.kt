package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ComposeWebTest {
    @Test fun sharedSettingsDoNotOfferLocalOperationsToReadOnlyWeb() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        var invoked = false
        setContent {
            PhotoToolApp(AppState(library = "Servidor", screen = Screen.SETTINGS, remote = RemoteBrowseState(loading = false)),
                AppCallbacks(dispatch = {}, chooseLibrary = { invoked = true }, synchronize = { invoked = true },
                    setReadOnlyMode = { invoked = true }, capabilities = AppCapabilities.RemoteReadOnly))
        }
        onNodeWithText("Escolher pasta da biblioteca").assertIsNotEnabled()
        onNodeWithContentDescription("Modo somente leitura").assertIsNotEnabled()
        onNodeWithText("Sincronizar", substring = false).performScrollTo().assertIsNotEnabled()
        assertFalse(invoked)
    }

    @Test fun sharedGalleryShowsServerTotalAndPageControlsWithoutLocalFiltering() = runSkikoComposeUiTest(size = Size(1440f, 1000f)) {
        val photo = Photo("one", "", "IMG_1001.JPG", "", writable = false)
        setContent {
            PhotoToolApp(AppState(library = "Servidor", photos = listOf(photo), query = Query(gps = GpsFilter.PRESENT),
                remote = RemoteBrowseState(total = 25114, libraryTotal = 25114, totalPages = 1047, loading = false)),
                AppCallbacks(dispatch = {}, capabilities = AppCapabilities.RemoteReadOnly))
        }
        onNodeWithText("25114 fotos").assertExists()
        onNodeWithContentDescription("Fotografia IMG_1001.JPG").assertExists()
        onNodeWithContentDescription("Próxima página").assertIsEnabled()
        onNodeWithContentDescription("Página anterior").assertIsNotEnabled()
        onNodeWithContentDescription("Calendário").assertIsNotEnabled()
        onNodeWithContentDescription("Pastas").assertIsNotEnabled()
    }

    @Test fun fixtureWriteOptInCannotSelectARealLibraryOrCache() {
        assertFalse(parseComposeCandidateOptions(emptyArray()).writeEnabled)
        assertTrue(parseComposeCandidateOptions(arrayOf("8899", "127.0.0.1", "--enable-fixture-write")).writeEnabled)
        listOf(arrayOf("--enable-write"), arrayOf("--library", "/real/library", "--enable-fixture-write"),
            arrayOf("8899", "127.0.0.1", "/real/library", "--enable-fixture-write"),
            arrayOf("--cache", "/real/cache"), arrayOf("8899", "0.0.0.0", "--enable-fixture-write"),
            arrayOf("--enable-fixture-write", "--enable-fixture-write")).forEach {
            assertFailsWith<IllegalArgumentException> { parseComposeCandidateOptions(it) }
        }
    }

    @Test fun candidateServesComposeOnlyAndFixtureReadsPreserveEverySourceByte() = testApplication {
        val (library, cache) = createComposeWebFixture()
        val before = fingerprints(library)
        val sourceBefore = fingerprints(library.parent.resolve("synthetic-originals"))
        CurationRuntime.open(library, cache, writeEnabled = false).use { runtime ->
            application { mobileCurationModule(runtime.application, HttpSecurityConfiguration(setOf("localhost"))) }
            val root = client.get("/") { header(HttpHeaders.Host, "localhost") }
            assertEquals(HttpStatusCode.OK, root.status)
            assertTrue(root.bodyAsText().contains("webApp"))
            assertFalse(root.bodyAsText().contains("photo-card"))
            val csp = root.headers["Content-Security-Policy"].orEmpty()
            assertTrue(csp.contains("'wasm-unsafe-eval'")); assertFalse(csp.contains("'unsafe-eval'"))
            assertEquals(HttpStatusCode.NotFound, client.get("/assets/app.js") { header(HttpHeaders.Host, "localhost") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/phototool.js") { header(HttpHeaders.Host, "localhost") }.status)
            val page = runtime.application.page(PhotoQueryInput(search = "-personal -\"São Paulo\""))
            assertEquals(12, page.total)
            val photo = page.items.first()
            assertTrue(runtime.application.preview(photo.id, 900).bytes.isNotEmpty())
            val detail = runtime.application.detail(PhotoQueryInput(), photo.id).editorial
            assertEquals(photo.editorial.keywords, detail.keywords)
            assertEquals(photo.editorial.flag, detail.flag)
            assertEquals(photo.editorial.rating, detail.rating)
            assertEquals(photo.editorial.label, detail.label)
            assertFalse(runtime.application.status().writeEnabled)
            assertEquals("IDLE", runtime.application.status().sync.phase)
        }
        assertEquals(before, fingerprints(library))
        assertEquals(sourceBefore, fingerprints(library.parent.resolve("synthetic-originals")))
    }

    @Test fun writeEnabledFixtureStillHonorsPerPhotoAuthority() {
        val (library, cache) = createComposeWebFixture()
        val before = fingerprints(library)
        val projection = PhotoCache(cache, library)
        val photos = projection.loadCacheOnly()
        val blocked = photos.first().copy(writable = false, issue = "Ambiguous authority")
        projection.publish(photos.map { if (it.id == blocked.id) blocked else it })
        CurationRuntime.open(library, cache, writeEnabled = true).use { runtime ->
            val detail = runtime.application.detail(PhotoQueryInput(), blocked.id)
            assertTrue(runtime.application.status().writeEnabled)
            assertFalse(detail.writable)
            val refused = assertFailsWith<IllegalStateException> {
                runtime.application.mutate(blocked.id, detail.editorial.revision, EditorialCommand.SetFlag(Flag.PICK))
            }
            assertEquals("Ambiguous authority", refused.message)
            assertEquals(ProjectionStateDto.READY, runtime.application.status().projectionState)
        }
        assertEquals(before, fingerprints(library))
    }

    @Test fun detailAndFilmstripSerializeAttestedReadsOfTheSameSource() {
        val (library, cache) = createComposeWebFixture()
        val photo = PhotoCache(cache, library).loadCacheOnly().first()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val workers = Executors.newFixedThreadPool(2)
        PreviewStore(library, cache, afterSourceRead = {
            if (firstRead.getAndSet(false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }).use { previews ->
            try {
                val detail = workers.submit<ByteArray> { previews.thumbnailBytes(photo, 4096).bytes }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val filmstrip = workers.submit<ByteArray> { previews.thumbnailBytes(photo, 240).bytes }
                // Hold the first attested descriptor while the second size is requested.
                Thread.sleep(150)
                release.countDown()
                assertTrue(detail.get(5, TimeUnit.SECONDS).isNotEmpty())
                assertTrue(filmstrip.get(5, TimeUnit.SECONDS).isNotEmpty())
            } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun portraitDetailReservesTheFirstFoldForThePhotograph() = compactDetail(Size(390f, 740f))
    @Test fun safariShortPortraitNeverPutsAnInspectorBesideThePhotograph() = compactDetail(Size(393f, 680f))
    @Test fun landscapeDetailDisclosesPanelsWithoutShrinkingTouchTargets() = compactDetail(Size(844f, 390f))
    @Test fun smallWindowUsesTheSameCompactDetail() = compactDetail(Size(600f, 360f))
    @Test fun wideButShortWindowAlsoDisclosesTools() = compactDetail(Size(1200f, 390f))

    private fun compactDetail(size: Size) = runSkikoComposeUiTest(size = size) {
        val photo = Photo("one", "", "IMG_1001", "", jpegPath = "IMG_1001.JPG", writable = false,
            metadata = ObservedMetadata(width = 600, height = 900, camera = "Synthetic camera"))
        val next = photo.copy(id = "two", stem = "IMG_1002", jpegPath = "IMG_1002.JPG")
        var state by mutableStateOf(AppState(library = "Fixture", photos = listOf(photo, next), selectedId = photo.id,
            screen = Screen.DETAIL, writeEnabled = false, remote = RemoteBrowseState(loading = false)))
        val bitmap = ImageBitmap(600, 900)
        var mutation = false
        setContent {
            PhotoToolApp(state, AppCallbacks(dispatch = { state = reduce(state, it) },
                capabilities = AppCapabilities.RemoteReadOnly, imageLoader = PlatformImageLoader { _, _ -> bitmap },
                mutate = { _, _ -> mutation = true }))
        }
        val preview = onNodeWithContentDescription("Área ampliável de IMG_1001.JPG")
        val original = preview.getUnclippedBoundsInRoot()
        assertTrue((original.right - original.left).value >= size.width * .95f, "Preview width: $original")
        assertTrue((original.bottom - original.top).value >= size.height * .65f, "Preview height: $original")
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertDoesNotExist()
        onNodeWithContentDescription("Faixa de fotografias próximas").assertDoesNotExist()
        listOf("Voltar à biblioteca", "Abrir miniaturas", "Abrir curadoria e metadados", "Fotografia anterior",
            "Próxima fotografia", "Diminuir zoom", "Aumentar zoom", "Ajustar fotografia à janela").forEach { label ->
            val bounds = onNodeWithContentDescription(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 44.dp && bounds.bottom - bounds.top >= 44.dp, "$label: $bounds")
            assertTrue(bounds.bottom <= original.top || bounds.top >= original.bottom, "$label covers the photograph")
        }
        onNodeWithContentDescription("Aumentar zoom").performClick()
        onNodeWithContentDescription("Zoom atual: 125%").assertExists()
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertIsDisplayed()
        onNodeWithContentDescription("Marcar como escolhida").assertIsNotEnabled()
        onNodeWithContentDescription("Câmera: Synthetic camera").performScrollTo().assertIsDisplayed()
        val close = onNodeWithContentDescription("Fechar curadoria e metadados").assertIsDisplayed()
        val closeBounds = close.getUnclippedBoundsInRoot()
        assertTrue(closeBounds.bottom - closeBounds.top >= 44.dp)
        close.performClick()
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertDoesNotExist()
        assertEquals(original, preview.getUnclippedBoundsInRoot())
        onNodeWithContentDescription("Zoom atual: 125%").assertExists()
        onNodeWithContentDescription("Ajustar fotografia à janela").performClick()
        onNodeWithContentDescription("Zoom atual: 100%").assertExists()
        repeat(2) {
            onNodeWithContentDescription("Abrir miniaturas").performClick()
            onNodeWithContentDescription("Faixa de fotografias próximas").assertIsDisplayed()
            onNodeWithContentDescription("Filmstrip IMG_1001.JPG").assertIsDisplayed().assertIsSelected()
            onNodeWithContentDescription("Fechar miniaturas").assertIsDisplayed().performClick()
            onNodeWithContentDescription("Faixa de fotografias próximas").assertDoesNotExist()
            assertEquals(original, preview.getUnclippedBoundsInRoot())
        }
        onNodeWithContentDescription("Abrir curadoria e metadados").performClick()
        onNodeWithContentDescription("Fechar curadoria e metadados").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertDoesNotExist()
        assertEquals(original, preview.getUnclippedBoundsInRoot())
        onNodeWithContentDescription("Abrir miniaturas").performClick()
        onNodeWithContentDescription("Filmstrip IMG_1002.JPG").performClick()
        onNodeWithContentDescription("Faixa de fotografias próximas").assertDoesNotExist()
        onNodeWithContentDescription("Área ampliável de IMG_1002.JPG").assertIsDisplayed()
        assertFalse(mutation)
    }

    @Test fun compactDetailKeepsDegradationAndPhotoProblemsVisibleWithToolsClosed() = runSkikoComposeUiTest(size = Size(393f, 680f)) {
        val photo = Photo("one", "", "IMG", "", jpegPath = "IMG.JPG", writable = false, issue = "XMP read failed")
        setContent {
            PhotoToolApp(AppState(library = "Fixture", photos = listOf(photo), selectedId = photo.id, screen = Screen.DETAIL,
                remote = RemoteBrowseState(loading = false, projection = "DEGRADED")),
                AppCallbacks(dispatch = {}, capabilities = AppCapabilities.RemoteReadOnly))
        }
        // The routine status bar was removed; degradation now surfaces as the explicit recovery notice.
        onNodeWithText("Snapshot degradado. Edição indisponível.", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Painel de curadoria da fotografia").assertDoesNotExist()
        onNodeWithContentDescription("Exibir problema de IMG.JPG").assertIsDisplayed().performClick()
        onNodeWithText("O sidecar XMP não pôde ser lido com segurança.", substring = true).assertIsDisplayed()
        onNodeWithContentDescription("Fechar explicação do problema").performClick()
        onNodeWithContentDescription("Problema da fotografia IMG.JPG").assertDoesNotExist()
    }

    private fun fingerprints(root: Path): Map<String, String> = Files.list(root).use { files ->
        files.toList().associate { file -> file.fileName.toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) } }
    }
}
