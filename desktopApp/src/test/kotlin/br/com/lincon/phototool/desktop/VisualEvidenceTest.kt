package br.com.lincon.phototool.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import org.jetbrains.skia.Image
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VisualEvidenceTest {
    @Test fun rendersProductionApplicationStatesWithRealJpegFixtures() {
        val output = Paths.get("build", "evidence")
        val fixtures = output.resolve("fixtures")
        Files.createDirectories(fixtures)
        Files.writeString(output.resolve("README.md"), """# Evidências visuais

Geradas por `VisualEvidenceTest` a partir do código e dos componentes de produção atuais.

- Fixtures controladas: galeria, filtros, seleção, lotes editoriais/Fuji/HDR com resultado por arquivo, HDR individual RAF editável, calendário, pastas, detalhe baixo, filmstrip em índice alto, configurações, erro/recuperação, vazio e breakpoints.
- Evidências de fechamento funcional: `detail-zoom-150.png`, `detail-low-600x360.png`, `detail-filmstrip-index-10-600x700.png`, `batch-hdr-result-1280x900.png`, `detail-hdr-editable.png` e `photo-error-explained-900x600.png`.
- Showcase opcional: `showcase-gallery.png` e `showcase-detail.png`, gerados com `PHOTOTOOL_SHOWCASE_LIBRARY` e `PHOTOTOOL_SHOWCASE_CACHE` sobre uma biblioteca copiada.
- Nenhuma captura usa o acervo canônico como destino de escrita.
""".trimIndent() + "\n")
        val images = (1..12).associate { index ->
            val path = fixtures.resolve("photo-$index.jpg")
            writeFixture(path, 900 + index * 17, if (index % 3 == 0) 900 else 600 + index * 13, index)
            "photo-$index" to Files.readAllBytes(path)
        }
        val loader = PlatformImageLoader { photo, _ -> images[photo.id]?.let { Image.makeFromEncoded(it).toComposeImageBitmap() } }
        val photos = images.keys.mapIndexed { index, id ->
            val bytes = images.getValue(id)
            val dimensions = Image.makeFromEncoded(bytes)
            val captureMonth = listOf("2025-06", "2025-05", "2024-11", "2024-07", "2023-12", "2022-03")[index % 6]
            val folder = captureMonth.replace('-', '.')
            val rawExtension = listOf("RAF", "CR2", "DNG", "CR3").getOrNull(index)
            val rawPath = rawExtension?.let { "$folder/IMG_${1000 + index}.$it" }
            val jpegPath = "$folder/IMG_${1000 + index}.jpg".takeUnless { index == 1 }
            val sourcePath = rawPath ?: jpegPath!!
            Photo(
                id = id, folder = folder, stem = "IMG_${1000 + index}", authorityPath = "$folder/IMG_${1000 + index}.xmp", rawPath = rawPath, jpegPath = jpegPath,
                sourceIdentity = MediaIdentity(sourcePath, "fixture-$index", bytes.size.toLong(), 1_750_000_000_000L + index * 1000),
                metadata = ObservedMetadata(capturedAt = "$captureMonth-${((index % 8) + 1).toString().padStart(2, '0')}T14:20:00Z", camera = if (index % 2 == 0) "FUJIFILM X-Pro2" else "Canon EOS R5", lens = if (index % 2 == 0) "XF23mmF2 R WR" else "RF50mm F1.8", width = dimensions.width, height = dimensions.height, focalLength = 23.0 + index, aperture = 2.0, exposureSeconds = .008, iso = 400, latitude = if (index == 0) -23.55 else null, longitude = if (index == 0) -46.63 else null, status = MetadataStatus.AVAILABLE),
                editorial = EditorialState(when (index) { 1,4 -> Flag.PICK; 10 -> Flag.REJECT; else -> Flag.UNFLAGGED }, index % 6, if (index == 2) ColorLabel.GREEN else null, listOf("Viagem", "Brasil|São Paulo")),
            )
        }
        val callbacks = AppCallbacks(dispatch = {}, imageLoader = loader)
        val states = listOf(
            "gallery-normal" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = false, thumbnailSize = 175),
            "calendar" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.CALENDAR, section = LibrarySection.CALENDAR),
            "folders" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.FOLDERS, section = LibrarySection.FOLDERS),
            "multi-selection" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, selectedIds = photos.take(4).mapTo(linkedSetOf()) { it.id }, selectionModeActive = true, filtersOpen = false, writeEnabled = true),
            "filters-overlay" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = true),
            "active-filters" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = false, query = Query(minimumStars = 1, flag = Flag.PICK)),
            "detail" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL, writeEnabled = true),
            "empty" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = emptyList(), filtersOpen = false),
            "settings" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, writeEnabled = false, writeAuthorized = true, sync = SyncStatus(SyncPhase.COMPLETE, 8, 120, 12, 0, "", false, "Biblioteca atualizada")),
            "settings-running" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, sync = SyncStatus(SyncPhase.METADATA, 8, 120, 7, 1, "IMG_1042.CR3", true, "Lendo metadados com segurança")),
            "error" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, sync = SyncStatus(SyncPhase.FAILED, errors = 1, message = "Falha na sincronização. O snapshot anterior foi preservado")),
            "photo-error-indicator" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = listOf(photos.first().copy(issue = "Unsafe or malformed XMP", writable = false)) + photos.drop(1), filtersOpen = false),
        )
        states.forEach { (name, state) ->
            runSkikoComposeUiTest(size = Size(1440f, 920f)) {
                setContent { PhotoToolApp(state, callbacks) }
                waitForIdle()
                val bitmap = if (state.filtersOpen) {
                    onNode(isRoot() and hasAnyDescendant(hasContentDescription("Painel de filtros"))).captureToImage()
                } else onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage()
                val target = output.resolve("$name.png")
                writeBitmap(bitmap, target)
                assertTrue(Files.size(target) > 10_000, "$name evidence should contain rendered production UI")
            }
        }
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val detailState = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL)
            setContent { PhotoToolApp(detailState, callbacks) }
            waitForIdle()
            repeat(2) { onNodeWithContentDescription("Aumentar zoom").performClick() }
            onNodeWithContentDescription("Zoom atual: 150%").assertExists()
            val target = output.resolve("detail-zoom-150.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "zoom evidence should show an enlarged photograph and its current level")
        }
        listOf(
            Triple("all-photos-1280x800", Size(1280f, 800f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, thumbnailSize = 165, filtersOpen = false)),
            Triple("library-900x600", Size(900f, 600f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, thumbnailSize = 150, filtersOpen = false)),
            Triple("library-600x800", Size(600f, 800f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, thumbnailSize = 135, filtersOpen = false)),
            Triple("multi-selection-900x600", Size(900f, 600f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, selectedIds = photos.take(4).mapTo(linkedSetOf()) { it.id }, selectionModeActive = true, filtersOpen = false, writeEnabled = true, thumbnailSize = 150)),
            Triple("filters-narrow-600x800", Size(600f, 800f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = true, thumbnailSize = 135)),
            Triple("rail-low-600x360", Size(600f, 360f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS)),
            Triple("detail-low-600x360", Size(600f, 360f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL)),
            Triple("detail-filmstrip-index-10-600x700", Size(600f, 700f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos[10].id, screen = Screen.DETAIL)),
            Triple("iphone-gallery-390x844", Size(390f, 844f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, thumbnailSize = 150)),
            Triple("iphone-filters-390x844", Size(390f, 844f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = true, thumbnailSize = 150)),
            Triple("iphone-detail-390x844", Size(390f, 844f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL)),
            Triple("iphone-settings-390x844", Size(390f, 844f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, writeEnabled = false, writeAuthorized = true)),
            Triple("iphone-gallery-landscape-844x390", Size(844f, 390f), AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, thumbnailSize = 150)),
        ).forEach { (name, size, state) ->
            runSkikoComposeUiTest(size = size) {
                setContent { PhotoToolApp(state, callbacks) }
                waitForIdle()
                val target = output.resolve("$name.png")
                writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
                assertTrue(Files.size(target) > 10_000, "$name responsive evidence should contain rendered production UI")
            }
        }
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val batchState = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, selectedIds = photos.take(4).mapTo(linkedSetOf()) { it.id }, selectionModeActive = true, writeEnabled = true)
            val batchCallbacks = callbacks.copy(auxiliary = AuxiliaryActions(batchUpdate = { selected, edit ->
                val channel = if (edit is AuxiliaryBatchEdit.SetHdr) "HDR em RAF" else "Fuji FP2"
                AuxiliaryBatchResult(channel, selected.size, 3, 0, 1, selected.mapIndexed { index, photo ->
                    AuxiliaryBatchItemResult(photo.id, channel, if (index == selected.lastIndex) AuxiliaryBatchOutcome.FAILED else AuxiliaryBatchOutcome.SUCCEEDED, if (index == selected.lastIndex) "xmp-validation-failed" else null)
                })
            }))
            setContent { PhotoToolApp(batchState, batchCallbacks) }
            waitForIdle()
            onNodeWithContentDescription("Mostrar controles Fuji e HDR em lote").performClick()
            waitForIdle()
            val controlsTarget = output.resolve("batch-fuji-hdr-controls-1280x900.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), controlsTarget)
            assertTrue(Files.size(controlsTarget) > 10_000, "batch Fuji and HDR evidence should show explicit field controls")
            onNodeWithContentDescription("Definir HDR configurado em lote").performClick()
            waitForIdle()
            onNodeWithContentDescription("Resultado do lote HDR em RAF").performScrollTo()
            onNodeWithContentDescription("Resultado ${photos[3].displayName}: falhou · XMP inválido; revise o sidecar adjacente e sincronize novamente").performScrollTo()
            waitForIdle()
            val resultTarget = output.resolve("batch-hdr-result-1280x900.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), resultTarget)
            assertTrue(Files.size(resultTarget) > 10_000, "batch HDR evidence should show bounded partial result")
        }
        runSkikoComposeUiTest(size = Size(1280f, 760f)) {
            val batchState = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, selectedIds = photos.take(2).mapTo(linkedSetOf()) { it.id }, selectionModeActive = true, writeEnabled = true)
            setContent { PhotoToolApp(batchState, callbacks.copy(batchMutate = { _, _ -> })) }
            waitForIdle(); onNodeWithText("Escolher").performClick(); waitForIdle()
            val target = output.resolve("batch-editorial-result-1280x760.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "editorial batch evidence should communicate a request without claiming global success")
        }
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            val calendar = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.CALENDAR, section = LibrarySection.CALENDAR)
            setContent { PhotoToolApp(calendar, callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Navegar pelo ano 2025, 4 fotos").performClick()
            waitForIdle()
            val target = output.resolve("calendar-months.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "calendar month drilldown evidence should contain rendered production UI")
            onNodeWithContentDescription("Navegar por Junho de 2025, 2 fotos").performClick()
            waitForIdle()
            val daysTarget = output.resolve("calendar-days.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), daysTarget)
            assertTrue(Files.size(daysTarget) > 10_000, "calendar day drilldown evidence should contain rendered production UI")
        }
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            val quick = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, writeEnabled = true, filtersOpen = false, thumbnailSize = 220)
            setContent { PhotoToolApp(quick, callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Fotografia ${photos.first().displayName}").requestFocus()
            waitForIdle()
            onNodeWithContentDescription("Abrir curadoria rápida de ${photos.first().displayName}").performClick()
            waitForIdle()
            onNodeWithContentDescription("Marcar como escolhida").performMouseInput { enter() }
            waitForIdle()
            val target = output.resolve("quick-editor.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "quick editor evidence should contain rendered production UI")
        }
        runSkikoComposeUiTest(size = Size(600f, 480f)) {
            val quick = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, writeEnabled = true, filtersOpen = false, thumbnailSize = 180)
            setContent { PhotoToolApp(quick, callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Fotografia ${photos.first().displayName}").requestFocus()
            onNodeWithContentDescription("Abrir curadoria rápida de ${photos.first().displayName}").performClick()
            waitForIdle()
            val target = output.resolve("quick-editor-narrow-600x480.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "narrow quick editor evidence should remain bounded")
        }
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            val quick = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, writeEnabled = true, thumbnailSize = 150)
            setContent { PhotoToolApp(quick, callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Fotografia ${photos.first().displayName}").requestFocus()
            onNodeWithContentDescription("Abrir curadoria rápida de ${photos.first().displayName}").performClick()
            waitForIdle()
            val target = output.resolve("iphone-quick-editor-390x844.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "phone quick editor evidence should remain usable over its anchor")
        }
        runSkikoComposeUiTest(size = Size(900f, 600f)) {
            val blocked = photos.first().copy(issue = "XMP read failed: /private/library/secret.xmp", writable = false)
            setContent { PhotoToolApp(AppState(library = "/copied/library", cache = "/tmp/cache", photos = listOf(blocked) + photos.drop(1)), callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Exibir problema de ${blocked.displayName}").performClick()
            waitForIdle()
            val target = output.resolve("photo-error-explained-900x600.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "photo error evidence should explain the protected state")
        }
        runSkikoComposeUiTest(size = Size(600f, 360f)) {
            val settings = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS)
            setContent { PhotoToolApp(settings, callbacks) }
            waitForIdle()
            onNodeWithContentDescription("Configurações").performScrollTo()
            waitForIdle()
            val target = output.resolve("rail-low-scrolled-600x360.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "low rail evidence should keep bottom actions reachable")
        }
    }

    @Test fun rendersEditableHdrDetailInIsolation() {
        val output = Paths.get("build", "evidence")
        val fixtures = output.resolve("fixtures")
        Files.createDirectories(fixtures)
        val fixture = fixtures.resolve("hdr-photo.jpg")
        writeFixture(fixture, 1200, 800, 21)
        val bytes = Files.readAllBytes(fixture)
        val loader = PlatformImageLoader { _, _ -> Image.makeFromEncoded(bytes).toComposeImageBitmap() }
        val photo = Photo(
            id = "hdr-photo", folder = "2025.06", stem = "IMG_HDR", authorityPath = "2025.06/IMG_HDR.xmp",
            rawPath = "2025.06/IMG_HDR.RAF", metadata = ObservedMetadata(capturedAt = "2025-06-01T14:20:00", width = 1200, height = 800), writable = true,
        )
        val hdrControls = listOf("SDRBrightness", "SDRContrast", "SDRClarity", "SDRHighlights", "SDRShadows", "SDRWhites", "SDRBlend").associateWith { 0 }
        val callbacks = AppCallbacks(
            dispatch = {}, imageLoader = loader,
            auxiliary = AuxiliaryActions(load = { AuxiliaryView(hdr = HdrView(true, "2.00", hdrControls), status = "HDR lido com segurança") }),
        )
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            setContent { PhotoToolApp(AppState(library = "/copied/library", cache = "/tmp/cache", photos = listOf(photo), selectedId = photo.id, screen = Screen.DETAIL, writeEnabled = true), callbacks) }
            waitForIdle(); onNodeWithContentDescription("Mostrar receitas e HDR").performClick(); waitForIdle()
            onNodeWithContentDescription("Contraste SDR").performScrollTo(); waitForIdle()
            val target = output.resolve("detail-hdr-editable.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "detail evidence should show editable HDR maximum and SDR controls")
        }
    }

    @Test fun rendersOptionalShowcaseWithProductionPreviewPipeline() {
        val libraryText = System.getenv("PHOTOTOOL_SHOWCASE_LIBRARY") ?: return
        val cacheText = System.getenv("PHOTOTOOL_SHOWCASE_CACHE") ?: return
        val library = Paths.get(libraryText)
        val cache = Paths.get(cacheText)
        if (!Files.isDirectory(library) || !Files.isDirectory(cache)) return
        val photos = PhotoCache(cache, library).load()
        assertTrue(photos.isNotEmpty(), "showcase cache should contain indexed photographs")
        val output = Paths.get("build", "evidence")
        Files.createDirectories(output)
        val previewCache = Paths.get("build", "showcase-preview-cache")
        val callbacks = AppCallbacks(dispatch = {}, imageLoader = PreviewStore(library, previewCache))
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            setContent { PhotoToolApp(AppState(library = library.toString(), cache = cache.toString(), photos = photos, thumbnailSize = 210), callbacks) }
            waitForIdle()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Carregando prévia").fetchSemanticsNodes().isEmpty() }
            val target = output.resolve("showcase-gallery.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 100_000, "showcase gallery should contain real photographic evidence")
        }
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            setContent { PhotoToolApp(AppState(library = library.toString(), cache = cache.toString(), photos = photos, screen = Screen.CALENDAR, section = LibrarySection.CALENDAR), callbacks) }
            waitForIdle()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Carregando prévia").fetchSemanticsNodes().isEmpty() }
            val target = output.resolve("showcase-calendar.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 100_000, "showcase calendar should contain real photographic evidence")
        }
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            setContent { PhotoToolApp(AppState(library = library.toString(), cache = cache.toString(), photos = photos, screen = Screen.FOLDERS, section = LibrarySection.FOLDERS), callbacks) }
            waitForIdle()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("Carregando prévia").fetchSemanticsNodes().isEmpty() }
            val target = output.resolve("showcase-folders.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 100_000, "showcase folders should contain real photographic evidence")
        }
        runSkikoComposeUiTest(size = Size(1440f, 920f)) {
            setContent { PhotoToolApp(AppState(library = library.toString(), cache = cache.toString(), photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL), callbacks) }
            waitForIdle()
            waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Carregando prévia de ${photos.first().displayName}").fetchSemanticsNodes().isEmpty() }
            val target = output.resolve("showcase-detail.png")
            writeBitmap(onNode(isRoot() and hasAnyDescendant(hasContentDescription("Aplicativo PhotoTool"))).captureToImage(), target)
            assertTrue(Files.size(target) > 100_000, "showcase detail should contain real photographic evidence")
        }
    }

    private fun writeFixture(path: Path, width: Int, height: Int, seed: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().use { graphics ->
            val first = Color((40 + seed * 13) % 255, (70 + seed * 19) % 255, (100 + seed * 23) % 255)
            val second = Color((170 + seed * 7) % 255, (120 + seed * 11) % 255, (60 + seed * 17) % 255)
            graphics.paint = GradientPaint(0f, 0f, first, width.toFloat(), height.toFloat(), second)
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.WHITE
            graphics.drawOval(width / 4, height / 4, width / 2, height / 2)
            graphics.drawString("PhotoTool fixture $seed", 30, 50)
        }
        assertTrue(ImageIO.write(image, "jpeg", path.toFile()))
    }

    private fun writeBitmap(bitmap: ImageBitmap, path: Path) {
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        assertTrue(ImageIO.write(image, "png", path.toFile()))
    }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try { block(this) } finally { dispose() }
