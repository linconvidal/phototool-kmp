package br.com.lincon.phototool.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        val images = (1..12).associate { index ->
            val path = fixtures.resolve("photo-$index.jpg")
            writeFixture(path, 900 + index * 17, if (index % 3 == 0) 900 else 600 + index * 13, index)
            "photo-$index" to Files.readAllBytes(path)
        }
        val loader = PlatformImageLoader { photo, _ -> images[photo.id]?.let { Image.makeFromEncoded(it).toComposeImageBitmap() } }
        val photos = images.keys.mapIndexed { index, id ->
            val bytes = images.getValue(id)
            val dimensions = Image.makeFromEncoded(bytes)
            Photo(
                id = id, folder = "2025/event", stem = "IMG_${1000 + index}", authorityPath = "2025/event/IMG_${1000 + index}.xmp", jpegPath = "2025/event/IMG_${1000 + index}.jpg",
                metadata = ObservedMetadata(capturedAt = "2025-06-${(index + 1).toString().padStart(2, '0')}T14:20:00Z", camera = if (index % 2 == 0) "FUJIFILM X-Pro2" else "Canon EOS R5", lens = if (index % 2 == 0) "XF23mmF2 R WR" else "RF50mm F1.8", width = dimensions.width, height = dimensions.height, focalLength = 23.0 + index, aperture = 2.0, exposureSeconds = .008, iso = 400, latitude = if (index == 0) -23.55 else null, longitude = if (index == 0) -46.63 else null, status = MetadataStatus.AVAILABLE),
                editorial = EditorialState(if (index == 1) Flag.PICK else Flag.UNFLAGGED, index % 6, if (index == 2) ColorLabel.GREEN else null, listOf("Viagem", "Brasil|São Paulo")),
            )
        }
        val callbacks = AppCallbacks(dispatch = {}, imageLoader = loader)
        val states = listOf(
            "gallery" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = false),
            "filters" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, filtersOpen = true, query = Query(minimumStars = 2, gps = GpsFilter.ANY)),
            "detail" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, screen = Screen.DETAIL, writeEnabled = true),
            "empty" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = emptyList(), filtersOpen = false),
            "sync" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, sync = SyncStatus(SyncPhase.METADATA, 8, 120, 38, 1, "IMG_1038.CR3", true, "Reading metadata")),
            "error" to AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, screen = Screen.SETTINGS, sync = SyncStatus(SyncPhase.FAILED, errors = 1, message = "Synchronization failed; previous snapshot retained")),
        )
        states.forEach { (name, state) ->
            runComposeUiTest {
                setContent { PhotoToolApp(state, callbacks) }
                waitForIdle()
                val bitmap = onRoot().captureToImage()
                val target = output.resolve("$name.png")
                writeBitmap(bitmap, target)
                assertTrue(Files.size(target) > 10_000, "$name evidence should contain rendered production UI")
            }
        }
        runComposeUiTest {
            val narrow = AppState(library = "/copied/library", cache = "/tmp/cache", photos = photos, selectedId = photos.first().id, filtersOpen = false)
            setContent { Box(Modifier.width(320.dp).height(700.dp)) { PhotoToolApp(narrow, callbacks) } }
            waitForIdle()
            val target = output.resolve("narrow-inspector.png")
            writeBitmap(onNodeWithContentDescription("Photo inspector").captureToImage(), target)
            assertTrue(Files.size(target) > 10_000, "narrow inspector evidence should contain rendered production UI")
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
