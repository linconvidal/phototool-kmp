package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.MediaIdentity
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.PreviewPlan
import br.com.lincon.phototool.domain.PreviewStrategy
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*

/**
 * The snapshot must carry a validated preview plan per photo: strategy, byte interval and
 * dimensions, never image bytes. Failures belong to sync, not to browsing.
 */
class PreviewPlanSemanticsTest {
    private fun writeJpeg(path: java.nio.file.Path, width: Int, height: Int) {
        path.parent?.createDirectories()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "jpeg", path.toFile())
    }

    private fun scan(root: java.nio.file.Path): ScanResult =
        LibraryScanner().scan(root, AtomicBoolean()) { }

    @Test fun planCodecRoundTripsAndRejectsMalformedValues() {
        val plan = PreviewPlan(PreviewStrategy.TIFF_EMBEDDED, offset = 4096, length = 300_000, width = 1920, height = 1280, orientation = "ROTATE_RIGHT")
        val encoded = PreviewPlanCodec.encode(plan)
        assertEquals(plan, PreviewPlanCodec.parse(encoded))
        assertTrue(encoded.length <= MAX_PREVIEW_PLAN_CHARS)
        val unavailable = PreviewPlan(PreviewStrategy.PAIRED_JPEG, width = 0, height = 0, unavailability = "jpeg-header-missing")
        assertEquals(unavailable, PreviewPlanCodec.parse(PreviewPlanCodec.encode(unavailable)))
        assertNull(PreviewPlanCodec.parse("garbage"))
        assertNull(PreviewPlanCodec.parse("BROKEN|0|0|1|1||"))
        assertNull(PreviewPlanCodec.parse("PAIRED_JPEG|x|0|1|1||"))
    }

    @Test fun syncProvesJpegDimensionsAndMarksHeaderlessMediaWithoutHidingIt() {
        val root = createTempDirectory("phototool-plan")
        writeJpeg(root.resolve("GOOD.JPG"), 600, 400)
        root.resolve("BROKEN.JPG").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val result = scan(root)
        assertEquals(2, result.photos.size)
        val good = result.photos.single { it.stem == "GOOD" }
        val goodPlan = assertNotNull(good.previewPlan)
        assertEquals(PreviewStrategy.DIRECT_JPEG, goodPlan.strategy)
        assertTrue(goodPlan.available)
        assertEquals(600, goodPlan.width)
        assertEquals(400, goodPlan.height)
        val broken = result.photos.single { it.stem == "BROKEN" }
        val brokenPlan = assertNotNull(broken.previewPlan)
        assertFalse(brokenPlan.available)
        assertEquals("jpeg-header-missing", brokenPlan.unavailability)
        assertEquals(1, result.unavailablePreviews)
    }

    @Test fun pairedJpegBecomesTheProvenPreviewAndKeepsItsDimensions() {
        val root = createTempDirectory("phototool-pair")
        writeJpeg(root.resolve("_DSF1.JPG"), 800, 600)
        root.resolve("_DSF1.RAF").writeBytes(ByteArray(64))
        val photo = scan(root).photos.single()
        val plan = assertNotNull(photo.previewPlan)
        assertEquals(PreviewStrategy.PAIRED_JPEG, plan.strategy)
        assertEquals(800, plan.width)
        assertEquals(600, plan.height)
        assertEquals(800, photo.metadata.width)
        assertEquals(600, photo.metadata.height)
    }

    @Test fun snapshotPersistsThePlanSoLoadingNeverRediscoversTheFormat() {
        val root = createTempDirectory("phototool-plan-cache")
        writeJpeg(root.resolve("A.JPG"), 640, 480)
        root.resolve("B.JPG").writeBytes(byteArrayOf(9, 9, 9))
        val scanned = scan(root)
        val cache = PhotoCache(createTempDirectory("phototool-plan-store"), root)
        cache.publish(scanned.photos)
        val loaded = cache.load().associateBy { it.stem }
        assertEquals(scanned.photos.associate { it.id to it.previewPlan }, loaded.values.associate { it.id to it.previewPlan })
        assertEquals(PreviewStrategy.DIRECT_JPEG, loaded.getValue("A").previewPlan?.strategy)
        assertFalse(loaded.getValue("B").previewPlan!!.available)
    }

    @Test fun previewLoadExecutesTheProvenPlanAndFailsOnlyForMarkedMedia() {
        val root = createTempDirectory("phototool-plan-load")
        writeJpeg(root.resolve("A.JPG"), 900, 600)
        root.resolve("B.JPG").writeBytes(byteArrayOf(7, 7, 7, 7))
        val scanned = scan(root).photos.associateBy { it.stem }
        PreviewStore(root, createTempDirectory("phototool-plan-cache-dir")).use { store ->
            val rendered = store.thumbnailBytes(scanned.getValue("A"), 512)
            assertTrue(rendered.bytes.size > 0)
            val failure = assertFailsWith<PreviewException> { store.thumbnailBytes(scanned.getValue("B"), 512) }
            assertEquals(PreviewFailure.UNSUPPORTED_MEDIA, failure.failure)
            assertEquals(scanned.getValue("B").previewPlan?.unavailability, failure.message)
        }
    }

    @Test fun snapshotWithoutThePlanColumnIsDiscardedInsteadOfReadWithoutAPlan() {
        val root = createTempDirectory("phototool-plan-schema-library")
        writeJpeg(root.resolve("A.JPG"), 320, 240)
        val cacheDirectory = createTempDirectory("phototool-plan-schema-cache")
        val cache = PhotoCache(cacheDirectory, root)
        cache.publish(scan(root).photos)
        assertEquals(PreviewStrategy.DIRECT_JPEG, cache.load().single().previewPlan?.strategy)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${cacheDirectory.resolve("phototool.sqlite3")}").use { connection ->
            connection.createStatement().use { it.executeUpdate("ALTER TABLE photos DROP COLUMN preview_plan") }
        }
        // An older snapshot has no validated plan, so it must be dropped and re-synchronized
        // rather than served as if every photograph could still produce a preview.
        assertTrue(cache.load().isEmpty())
    }

    @Test fun olderSchemaMarkerIsRejectedSoTheSnapshotIsSynchronizedAgain() {
        val root = createTempDirectory("phototool-plan-version-library")
        writeJpeg(root.resolve("A.JPG"), 320, 240)
        val cacheDirectory = createTempDirectory("phototool-plan-version-cache")
        val cache = PhotoCache(cacheDirectory, root)
        cache.publish(scan(root).photos)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${cacheDirectory.resolve("phototool.sqlite3")}").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE meta SET value='4' WHERE key='schema'") }
        }
        assertTrue(cache.load().isEmpty())
    }

    @Test fun embeddedIntervalIsReadByOffsetAndLengthWithoutParsingTheContainer() {
        val root = createTempDirectory("phototool-plan-interval")
        val jpeg = root.resolve("EMBEDDED.JPG")
        writeJpeg(jpeg, 600, 400)
        val embedded = jpeg.readBytes()
        jpeg.deleteExisting()
        val container = root.resolve("SHOT.CR2")
        container.writeBytes(ByteArray(2048) + embedded)
        val identity = mediaIdentity(container)
        val photo = Photo(
            id = "shot", folder = "", stem = "SHOT", authorityPath = "SHOT.CR2",
            rawPath = "SHOT.CR2", jpegPath = null, previewPath = "SHOT.CR2",
            sourceIdentity = identity, previewIdentity = identity,
            previewPlan = PreviewPlan(PreviewStrategy.TIFF_EMBEDDED, 2048, embedded.size.toLong(), 600, 400),
        )
        PreviewStore(root, createTempDirectory("phototool-plan-interval-cache")).use { store ->
            val rendered = store.thumbnailBytes(photo, 256)
            val decoded = ImageIO.read(rendered.bytes.inputStream())
            assertEquals(256, maxOf(decoded.width, decoded.height))
        }
    }

    @Test fun intervalOutsideTheSourceFailsAsStaleSourceInsteadOfRediscoveringTheFormat() {
        val root = createTempDirectory("phototool-plan-stale")
        val container = root.resolve("SHOT.CR2")
        container.writeBytes(ByteArray(4096))
        val identity = mediaIdentity(container)
        val photo = Photo(
            id = "stale", folder = "", stem = "SHOT", authorityPath = "SHOT.CR2",
            rawPath = "SHOT.CR2", jpegPath = null, previewPath = "SHOT.CR2",
            sourceIdentity = identity, previewIdentity = identity,
            previewPlan = PreviewPlan(PreviewStrategy.TIFF_EMBEDDED, 4096, 1024, 600, 400),
        )
        PreviewStore(root, createTempDirectory("phototool-plan-stale-cache")).use { store ->
            val failure = assertFailsWith<PreviewException> { store.thumbnailBytes(photo, 256) }
            assertEquals(PreviewFailure.STALE_SOURCE, failure.failure)
        }
    }

    @Test fun dngWithoutPublishedIntervalFallsBackToFullDecodeInsteadOfUnavailable() {
        val root = createTempDirectory("phototool-plan-dng")
        assertTrue(ImageIO.write(BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB), "tiff", root.resolve("SHOT.DNG").toFile()))
        val scanned = scan(root)
        val plan = assertNotNull(scanned.photos.single().previewPlan)
        assertEquals(PreviewStrategy.TIFF_FULL_DECODE, plan.strategy)
        assertTrue(plan.available, "DNG without a published interval still decodes: ${plan.unavailability}")
        PreviewStore(root, createTempDirectory("phototool-plan-dng-cache")).use { store ->
            val decoded = ImageIO.read(store.thumbnailBytes(scanned.photos.single(), 256).bytes.inputStream())
            assertEquals(256, maxOf(decoded.width, decoded.height))
        }
    }

    private fun mediaIdentity(path: java.nio.file.Path): MediaIdentity {
        val attributes = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        val fileKey = attributes.fileKey()?.toString() ?: path.toString()
        return MediaIdentity(path.fileName.toString(), fileKey, attributes.size(), attributes.lastModifiedTime().toMillis())
    }
}
