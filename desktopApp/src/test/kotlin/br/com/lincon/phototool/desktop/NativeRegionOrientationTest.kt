package br.com.lincon.phototool.desktop

import com.ashampoo.kim.model.TiffOrientation
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

/**
 * A crop is only useful if it shows the pixels the presented photograph shows in that rectangle.
 * Every rectangle the server publishes is expressed in presented (upright) coordinates, so the crop
 * has to be mapped back to the stored grid and rotated forward again. These tests pin that contract
 * for all eight EXIF orientations, including the ones that swap the axes.
 */
class NativeRegionOrientationTest {
    private val sourceWidth = 320
    private val sourceHeight = 240

    /** Landscape stored pixels, four distinct quadrants, so a crop from the wrong place is obvious. */
    private fun quadrantJpeg(): ByteArray {
        val image = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until sourceHeight) for (x in 0 until sourceWidth) {
            val color = when {
                x < sourceWidth / 2 && y < sourceHeight / 2 -> 0xFF0000
                x >= sourceWidth / 2 && y < sourceHeight / 2 -> 0x00FF00
                x < sourceWidth / 2 -> 0x0000FF
                else -> 0xFFFF00
            }
            image.setRGB(x, y, color)
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", bytes)
        return bytes.toByteArray()
    }

    /** Inserts the APP1 segment that declares the EXIF orientation, the way a camera would. */
    private fun withOrientation(jpeg: ByteArray, exifValue: Int): ByteArray {
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

    private fun average(image: BufferedImage): DoubleArray {
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var pixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            red += (rgb shr 16) and 0xFF
            green += (rgb shr 8) and 0xFF
            blue += rgb and 0xFF
            pixels++
        }
        return doubleArrayOf(red / pixels, green / pixels, blue / pixels)
    }

    private fun assertSameColor(expected: DoubleArray, actual: DoubleArray, tolerance: Double = 24.0) {
        assertTrue(
            abs(expected[0] - actual[0]) <= tolerance &&
                abs(expected[1] - actual[1]) <= tolerance &&
                abs(expected[2] - actual[2]) <= tolerance,
            "expected ${expected.toList()} but was ${actual.toList()}",
        )
    }

    @Test
    fun jpegOrientationIsReadFromTheHeaderAndDefaultsToNone() {
        val plain = quadrantJpeg()
        assertNull(jpegExifOrientation(plain), "a JPEG without EXIF declares no rotation")
        assertEquals(TiffOrientation.ROTATE_LEFT, jpegExifOrientation(withOrientation(plain, 8)))
        assertEquals(TiffOrientation.ROTATE_RIGHT, jpegExifOrientation(withOrientation(plain, 6)))
        assertEquals(TiffOrientation.STANDARD, jpegExifOrientation(withOrientation(plain, 1)))
    }

    @Test
    fun uprightRectangleMapsBackToTheExactStoredPixelsItCameFrom() {
        val source = BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) for (x in 0 until source.width) source.setRGB(x, y, (x * 100 + y) or 0xFF000000.toInt())
        TiffOrientation.entries.forEach { orientation ->
            val upright = applyTiffOrientation(source, orientation)
            for (y in 0 until upright.height) for (x in 0 until upright.width) {
                val rect = sourceRectForUpright(x, y, 1, 1, source.width, source.height, orientation)
                assertEquals(
                    upright.getRGB(x, y),
                    source.getRGB(rect.x, rect.y),
                    "orientation $orientation moved upright ($x,$y) to stored (${rect.x},${rect.y})",
                )
            }
            val size = uprightSize(source.width, source.height, orientation)
            assertEquals(upright.width to upright.height, size.width to size.height, "orientation $orientation size")
        }
    }

    @Test
    fun regionMatchesWhatThePresentedPhotographShowsInTheSameRectangle() {
        val stored = quadrantJpeg()
        val rotations = listOf(1 to TiffOrientation.STANDARD, 3 to TiffOrientation.UPSIDE_DOWN, 6 to TiffOrientation.ROTATE_RIGHT, 8 to TiffOrientation.ROTATE_LEFT)
        for ((exif, orientation) in rotations) {
            val bytes = withOrientation(stored, exif)
            val upright = applyTiffOrientation(ImageIO.read(ByteArrayInputStream(stored)), orientation)
            val rectangles = listOf(
                intArrayOf(0, 0, 100, 80),
                intArrayOf(upright.width - 100, upright.height - 80, 100, 80),
                intArrayOf(40, 30, 120, 90),
            )
            rectangles.forEach { rect ->
                val crop = ImageIO.read(ByteArrayInputStream(cropJpegRegion(bytes, rect[0], rect[1], rect[2], rect[3])))
                assertEquals(rect[2], crop.width, "orientation $exif width")
                assertEquals(rect[3], crop.height, "orientation $exif height")
                val expected = average(upright.getSubimage(rect[0], rect[1], rect[2], rect[3]))
                assertSameColor(expected, average(crop))
            }
        }
    }

    @Test
    fun rectangleThatOnlyExistsAfterTheRotationIsServed() {
        // Stored pixels are landscape, the photograph is presented in portrait: the lower band of the
        // presented image lives outside the stored height and used to be rejected as unsupported.
        val bytes = withOrientation(quadrantJpeg(), 8)
        val crop = ImageIO.read(ByteArrayInputStream(cropJpegRegion(bytes, 0, sourceHeight, 100, 60)))
        assertEquals(100, crop.width)
        assertEquals(60, crop.height)
    }
}
