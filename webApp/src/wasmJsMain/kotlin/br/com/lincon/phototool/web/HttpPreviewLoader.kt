package br.com.lincon.phototool.web

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_BYTES
import br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_PIXELS
import br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_DIMENSION
import br.com.lincon.phototool.remote.originalJpegUrl
import br.com.lincon.phototool.ui.OriginalImage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.graphics.toComposeImageBitmap
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.remote.boundedPreviewUrl
import br.com.lincon.phototool.remote.BoundedPreviewCache
import br.com.lincon.phototool.ui.PlatformImageLoader
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Image

/** Four active loads; decoded cache bounded by estimated pixel bytes, independent of library size. */
internal class HttpPreviewLoader(private val client: HttpClient, scope: CoroutineScope) : PlatformImageLoader {
    private val cache = BoundedPreviewCache<ImageBitmap>(scope, MAX_CACHE_BYTES, concurrency = 4) {
        it.width.toLong() * it.height * 4
    }

    override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap? = try {
        val url = boundedPreviewUrl(photo, maximumDimension)
        cache.load(url) {
            val response = client.get(url)
            require(response.headers["Content-Type"]?.substringBefore(';') == "image/jpeg")
            decodePreview(response.body<ByteArray>(), maximumDimension)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { null }

    private val originalSlot = Semaphore(1)

    override suspend fun prefetchDisplay(photo: Photo): ImageBitmap? =
        load(photo, br.com.lincon.phototool.api.DISPLAY_PREVIEW_DIMENSION)

    override suspend fun loadRegion(photo: Photo, x: Int, y: Int, width: Int, height: Int): ImageBitmap? = try {
        val url = br.com.lincon.phototool.remote.regionUrl(photo, x, y, width, height) ?: return null
        val response = client.get(url)
        if (response.status.value != 200) return null
        if (response.headers["Content-Type"]?.substringBefore(';') != "image/jpeg") return null
        val bytes = response.body<ByteArray>()
        if (bytes.isEmpty() || bytes.size > MAX_ORIGINAL_JPEG_BYTES) return null
        val image = Image.makeFromEncoded(bytes)
        try {
            require(image.width == width && image.height == height) { "Region size mismatch" }
            image.toComposeImageBitmap()
        } finally { image.close() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Throwable) { null }

    override suspend fun loadOriginal(photo: Photo): OriginalImage? {
        val url = originalJpegUrl(photo) ?: return null
        return originalSlot.withPermit {
            currentCoroutineContext().ensureActive()
            val response = client.get(url)
            if (response.status.value != 200) return@withPermit null
            if (response.headers["Content-Type"]?.substringBefore(';') != "image/jpeg") return@withPermit null
            val length = response.headers["Content-Length"]?.toLongOrNull()
            if (length != null && length > MAX_ORIGINAL_JPEG_BYTES) return@withPermit null
            val bytes = response.body<ByteArray>()
            if (bytes.isEmpty() || bytes.size > MAX_ORIGINAL_JPEG_BYTES) return@withPermit null
            currentCoroutineContext().ensureActive()
            val image = Image.makeFromEncoded(bytes)
            val bitmap = try {
                require(image.width in 1..MAX_ORIGINAL_JPEG_DIMENSION && image.height in 1..MAX_ORIGINAL_JPEG_DIMENSION)
                require(image.width.toLong() * image.height <= MAX_ORIGINAL_JPEG_PIXELS)
                image.toComposeImageBitmap()
            } finally { image.close() }
            val original = OriginalImage(bitmap) { bitmap.asSkiaBitmap().close() }
            try {
                currentCoroutineContext().ensureActive()
                original
            } catch (failure: Throwable) {
                original.close()
                throw failure
            }
        }
    }

    private companion object { const val MAX_CACHE_BYTES = 32L * 1024 * 1024 }
}

internal fun decodePreview(bytes: ByteArray, maximumDimension: Int): ImageBitmap {
    require(bytes.size in 1..(16 * 1024 * 1024))
    val image = Image.makeFromEncoded(bytes)
    try {
        val limit = maximumDimension.coerceIn(128, 4096)
        require(image.width in 1..limit && image.height in 1..limit)
        // Compose Web 1.11 converts via Bitmap.makeFromImage/readPixels here.
        // The 32 MiB cache owns decoded pixels, not a deferred encoded SkImage.
        return image.toComposeImageBitmap()
    } finally { image.close() }
}
