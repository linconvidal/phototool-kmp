package br.com.lincon.phototool.web

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.wasm.onWasmReady
import kotlin.coroutines.resume
import kotlin.test.*

class PreviewDecodeTest {
    @Test fun previewOwnsDecodedImmutablePixelsBeforeAnyCanvasDraw() = runTest {
        skikoReady()
        val preview = decodePreview(jpeg(32, 24), 1600)
        val pixels = preview.asSkiaBitmap()
        assertTrue(pixels.isReadyToDraw)
        assertTrue(pixels.isImmutable)
        assertEquals(32, pixels.width)
        assertEquals(24, pixels.height)
        val pixmap = assertNotNull(pixels.peekPixels())
        pixmap.close()
        // JPEG tolerance. No Canvas or renderer has consumed the preview.
        val color = pixels.getColor(12, 12)
        assertTrue((color shr 16 and 255) > 245)
        assertTrue((color shr 8 and 255) < 10)
        assertTrue((color and 255) < 10)
        pixels.close()
    }

    @Test fun boundsAreCheckedBeforeAllocatingTheDecodedCacheEntry() = runTest {
        skikoReady()
        assertFailsWith<IllegalArgumentException> { decodePreview(jpeg(129, 1), 128) }
        assertFailsWith<IllegalArgumentException> { decodePreview(jpeg(1, 129), 128) }
        assertFailsWith<IllegalArgumentException> { decodePreview(jpeg(4097, 1), Int.MAX_VALUE) }
        decodePreview(jpeg(128, 1), 1).asSkiaBitmap().close()
    }

    @Test fun malformedOrOversizedEncodedDataNeverCreatesAPreview() = runTest {
        skikoReady()
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(16 * 1024 * 1024 + 1))) {
            assertFailsWith<IllegalArgumentException> { decodePreview(bytes, 1600) }
        }
        decodePreview(jpeg(2, 2), 1600).asSkiaBitmap().close()
    }

    private suspend fun skikoReady() = suspendCancellableCoroutine { continuation ->
        onWasmReady { continuation.resume(Unit) }
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(width, height, true))
            bitmap.erase(0xffff0000.toInt())
            val image = Image.makeFromBitmap(bitmap)
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.JPEG, 100))
                try { return data.bytes } finally { data.close() }
            } finally { image.close() }
        } finally { bitmap.close() }
    }
}
