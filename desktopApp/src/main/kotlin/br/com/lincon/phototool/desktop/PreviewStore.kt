package br.com.lincon.phototool.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import br.com.lincon.phototool.domain.MediaIdentity
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.PreviewStrategy
import br.com.lincon.phototool.ui.PlatformImageLoader
import com.ashampoo.kim.Kim
import com.ashampoo.kim.common.convertToPhotoMetadata
import com.ashampoo.kim.format.cr2.Cr2PreviewExtractor
import com.ashampoo.kim.format.tiff.TiffReader
import com.ashampoo.kim.model.ImageFormat
import com.ashampoo.kim.model.TiffOrientation
import org.jetbrains.skia.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.concurrent.withLock
import kotlin.io.path.name
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val MAX_SOURCE_BYTES = MAX_MEDIA_SOURCE_BYTES
private const val MAX_IMAGE_DECODER_WORK_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_EMBEDDED_BYTES = 64 * 1024 * 1024
private const val MAX_SOURCE_DIMENSION = 100_000
private const val MAX_OUTPUT_PIXELS = 20_000_000
private const val MAX_PREVIEW_DIMENSION = 4_096
private const val MAX_CACHE_BYTES = 512L * 1024 * 1024
private const val MAX_CACHE_ENTRIES = 5_000
private const val REGION_MAX_SIDE = 2_048
private const val REGION_MAX_PIXELS = 4_000_000L
private const val PREVIEW_PARALLELISM = 4
private const val SHARED_THUMBNAIL_LOCK_STRIPES = 256
private const val SHARED_CACHE_LOCK_STRIPES = 64
private const val CLEANUP_INTERVAL_NANOS = 60_000_000_000L
private const val ABANDONED_TEMP_AGE_MILLIS = 10 * 60 * 1000L

internal data class CachedPreviewBytes(val bytes: ByteArray, val etag: String)

class PreviewStore private constructor(
    private val root: Path,
    cacheDir: Path,
    private val afterSourceRead: (() -> Unit)?,
    private val validateLibraryDescendantsOnOpen: Boolean,
) : PlatformImageLoader, AutoCloseable {
    constructor(root: Path, cacheDir: Path, afterSourceRead: (() -> Unit)? = null) :
        this(root, cacheDir, afterSourceRead, validateLibraryDescendantsOnOpen = true)

    internal constructor(root: Path, cacheDir: Path, validateLibraryDescendantsOnOpen: Boolean) :
        this(root, cacheDir, afterSourceRead = null, validateLibraryDescendantsOnOpen)

    private companion object {
        val sharedThumbnailLocks = Array(SHARED_THUMBNAIL_LOCK_STRIPES) { ReentrantLock() }
        val sharedCacheLocks = Array(SHARED_CACHE_LOCK_STRIPES) { ReentrantReadWriteLock() }
    }

    private val realRoot = root.toRealPath()
    private val directory = cacheDir.toAbsolutePath().normalize().resolve("previews")
    private val secure: SecureLibraryBoundary
    private val previewSlots = Semaphore(PREVIEW_PARALLELISM)
    private val cacheLifecycle = sharedCacheLocks[(directory.toString().hashCode() and Int.MAX_VALUE) % sharedCacheLocks.size]
    private val maintenance = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phototool-preview-maintenance").apply { isDaemon = true }
    }
    private val cleanupScheduled = AtomicBoolean(false)
    private val lastCleanupNanos = AtomicLong(0)
    @Volatile private var closed = false
    @Volatile private var fullSeparationProven = false
    @Volatile private var cacheReady = false

    init {
        assertCachePhysicallySeparate(
            realRoot,
            cacheDir.toAbsolutePath().normalize(),
            requireCacheExists = Files.exists(cacheDir.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS),
            inspectLibraryDescendants = validateLibraryDescendantsOnOpen,
        )
        fullSeparationProven = validateLibraryDescendantsOnOpen
        if (validateLibraryDescendantsOnOpen) ensureCacheReady()
        secure = SecureLibraryBoundary(realRoot, false)
    }

    override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        previewSlots.withPermit {
            runCatching {
                val bounded = maximumDimension.coerceIn(128, MAX_PREVIEW_DIMENSION)
                val bytes = cacheLifecycle.readLock().withLock {
                    Files.readAllBytes(thumbnailWithoutCleanup(photo, bounded))
                }
                check(bytes.size in 4..MAX_EMBEDDED_BYTES)
                scheduleCleanup()
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    fun thumbnail(photo: Photo, maximumDimension: Int): Path {
        val target = cacheLifecycle.readLock().withLock { thumbnailWithoutCleanup(photo, maximumDimension) }
        scheduleCleanup()
        return target
    }

    internal fun thumbnailBytes(photo: Photo, maximumDimension: Int): CachedPreviewBytes {
        val result = cacheLifecycle.readLock().withLock {
            var missing: NoSuchFileException? = null
            repeat(2) {
                try {
                    val target = thumbnailWithoutCleanup(photo, maximumDimension)
                    val bytes = Files.readAllBytes(target)
                    check(bytes.size in 4..MAX_EMBEDDED_BYTES)
                    return@withLock CachedPreviewBytes(bytes, target.fileName.toString().removeSuffix(".jpg"))
                } catch (error: NoSuchFileException) {
                    missing = error
                }
            }
            throw PreviewException(PreviewFailure.UNAVAILABLE, "Preview cache entry disappeared during read", missing)
        }
        scheduleCleanup()
        return result
    }

    @Synchronized
    private fun ensureCacheReady() {
        if (cacheReady) return
        if (!fullSeparationProven) {
            assertCachePhysicallySeparate(realRoot, directory.parent, requireCacheExists = Files.exists(directory.parent, LinkOption.NOFOLLOW_LINKS))
            fullSeparationProven = true
        }
        Files.createDirectories(directory.parent)
        require(!Files.isSymbolicLink(directory.parent)) { "Preview cache parent may not be a link" }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        else Files.createDirectory(directory)
        cacheReady = true
    }

    private fun thumbnailWithoutCleanup(photo: Photo, maximumDimension: Int): Path {
        ensureCacheReady()
        val identity = photo.previewIdentity ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "Preview identity is missing")
        val expected = resolveAndValidate(identity)
        val dimension = maximumDimension.coerceIn(128, MAX_PREVIEW_DIMENSION)
        val planKey = photo.previewPlan?.let(PreviewPlanCodec::encode) ?: "none"
        val key = sha256("thumb-v7\u0000${identity.path}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}\u0000$dimension\u0000$planKey")
        val target = directory.resolve("$key.jpg")
        val declaredPlan = photo.previewPlan
        // Sync already proved this media produces no preview. Answer definitively instead of
        // retrying a decode that cannot succeed, and keep the recorded reason visible.
        if (declaredPlan != null && !declaredPlan.available) {
            throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, declaredPlan.unavailability ?: "Preview is unavailable")
        }
        // Detail and filmstrip request different sizes concurrently. JVM shared file locks
        // still overlap within one process, so serialize by source, not by rendition/cache.
        val lockKey = "$realRoot\u0000${identity.path}\u0000${identity.fileKey}"
        val stripe = sharedThumbnailLocks[(lockKey.hashCode() and Int.MAX_VALUE) % sharedThumbnailLocks.size]
        return stripe.withLock {
            if (validCached(target, dimension)) {
                requireSingleLink(target)
                Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()))
                return@withLock target
            }
            val source = try {
                secure.withAttestedRead(identity.path, MAX_SOURCE_BYTES, expected) { channel, attested ->
                    decodePlannedSource(photo, channel, attested.size, dimension).also {
                        afterSourceRead?.invoke()
                    }
                }
            } catch (error: LibraryReadChangedException) {
                throw PreviewException(PreviewFailure.STALE_SOURCE, "Preview source changed after indexing", error)
            } catch (error: PreviewException) {
                throw error
            } catch (error: Exception) {
                throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Preview media cannot be decoded", error)
            }
            val image = applyTiffOrientation(source.image, source.orientation)
            resolveAndValidate(identity)
            try {
                publishJpeg(target, image)
                resolveAndValidate(identity)
                check(validCached(target, dimension)) { "Published thumbnail is not decodeable" }
            } catch (failure: Exception) {
                if (Files.deleteIfExists(target)) forceDirectory(directory)
                throw failure
            }
            target
        }
    }

    internal fun originalJpegBytes(photo: Photo): CachedPreviewBytes {
        val jpeg = photo.jpegPath ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Original JPEG is unavailable")
        val identity = photo.previewIdentity ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "JPEG identity is missing")
        if (identity.path != jpeg) throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "JPEG identity does not match")
        val expected = resolveAndValidate(identity)
        val bytes = try {
            secure.withAttestedRead(jpeg, br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_BYTES.toLong(), expected) { channel, attested ->
                readOriginalJpeg(channel, attested.size)
            }
        } catch (error: LibraryReadChangedException) {
            throw PreviewException(PreviewFailure.STALE_SOURCE, "Original JPEG changed after indexing", error)
        } catch (error: PreviewException) {
            throw error
        } catch (error: Exception) {
            throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Original JPEG is unsupported or exceeds its limits", error)
        }
        resolveAndValidate(identity)
        val etag = sha256("original-v1\u0000${identity.path}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}")
        return CachedPreviewBytes(bytes, etag)
    }

    /** Reads the already validated JPEG byte for byte, never re-encoding or downscaling it. */
    private fun readOriginalJpeg(channel: FileChannel, contentLength: Long): ByteArray {
        if (contentLength !in 4..br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_BYTES) {
            throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "Original JPEG size is outside the supported range")
        }
        val bytes = ByteArray(contentLength.toInt())
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, buffer.position().toLong())
            if (read < 0) throw PreviewException(PreviewFailure.STALE_SOURCE, "Original JPEG ended before its attested size")
            if (read == 0) throw PreviewException(PreviewFailure.UNAVAILABLE, "Original JPEG descriptor made no read progress")
        }
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[bytes.size - 2] != 0xFF.toByte() || bytes[bytes.size - 1] != 0xD9.toByte()) {
            throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Original bytes are not a complete JPEG")
        }
        val (width, height) = jpegDimensions(bytes)
        if (width !in 1..br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_DIMENSION || height !in 1..br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_DIMENSION ||
            width.toLong() * height > br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_PIXELS
        ) {
            throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "Original JPEG dimensions are outside the supported range")
        }
        return bytes
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int> = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Image decoder unavailable")
        try {
            reader.input = input
            reader.getWidth(0) to reader.getHeight(0)
        } finally { reader.dispose() }
    }

    private val regionCache = object : LinkedHashMap<String, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 24
    }

    /** Exact native pixels of a bounded region; never upscales and never writes to the library. */
    internal fun regionJpegBytes(photo: Photo, x: Int, y: Int, width: Int, height: Int): CachedPreviewBytes {
        val jpeg = photo.jpegPath ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Native region requires a JPEG")
        val identity = photo.previewIdentity ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "JPEG identity is missing")
        if (identity.path != jpeg) throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "JPEG identity does not match")
        if (x < 0 || y < 0 || width !in 1..REGION_MAX_SIDE || height !in 1..REGION_MAX_SIDE || width.toLong() * height > REGION_MAX_PIXELS) {
            throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Region is outside the supported bounds")
        }
        val expected = resolveAndValidate(identity)
        val key = sha256("region-v1\u0000${identity.path}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}\u0000$x\u0000$y\u0000$width\u0000$height")
        synchronized(regionCache) { regionCache[key]?.let { return CachedPreviewBytes(it, key) } }
        val bytes = try {
            secure.withAttestedRead(jpeg, br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_BYTES.toLong(), expected) { channel, attested ->
                readNativeRegion(channel, attested.size, x, y, width, height)
            }
        } catch (error: LibraryReadChangedException) {
            throw PreviewException(PreviewFailure.STALE_SOURCE, "Original JPEG changed after indexing", error)
        } catch (error: PreviewException) {
            throw error
        } catch (error: Exception) {
            throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Native region cannot be decoded", error)
        }
        resolveAndValidate(identity)
        synchronized(regionCache) { regionCache[key] = bytes }
        return CachedPreviewBytes(bytes, key)
    }

    private fun readNativeRegion(channel: FileChannel, contentLength: Long, x: Int, y: Int, width: Int, height: Int): ByteArray {
        var sourceWidth = 0
        var sourceHeight = 0
        val cropped = MemoryCacheImageInputStream(
            ByteArrayInputStream(readOriginalJpegHeader(channel, contentLength)),
        ).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Image decoder unavailable")
            try {
                reader.input = input
                sourceWidth = reader.getWidth(0)
                sourceHeight = reader.getHeight(0)
                if (x.toLong() + width > sourceWidth || y.toLong() + height > sourceHeight) {
                    throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Region exceeds the native image")
                }
                val params = reader.defaultReadParam.apply { setSourceRegion(java.awt.Rectangle(x, y, width, height)) }
                reader.read(0, params)
            } finally { reader.dispose() }
        }
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "JPEG encoder unavailable")
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply { if (canWriteCompressed()) { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .95f } }
            writer.write(null, IIOImage(cropped, null, null), params)
        }
        writer.dispose()
        cropped.flush()
        return output.toByteArray()
    }

    /** Reads only the header needed to measure the JPEG, keeping memory bounded. */
    private fun readOriginalJpegHeader(channel: FileChannel, contentLength: Long): ByteArray {
        if (contentLength !in 4..br.com.lincon.phototool.api.MAX_ORIGINAL_JPEG_BYTES) {
            throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "Original JPEG size is outside the supported range")
        }
        val bytes = ByteArray(contentLength.toInt())
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, buffer.position().toLong())
            if (read < 0) throw PreviewException(PreviewFailure.STALE_SOURCE, "Original JPEG ended before its attested size")
            if (read == 0) throw PreviewException(PreviewFailure.UNAVAILABLE, "Original JPEG descriptor made no read progress")
        }
        return bytes
    }

    fun verify(photo: Photo): Boolean = runCatching { thumbnail(photo, 512); true }.getOrDefault(false)

    private data class DecodedSource(val image: BufferedImage, val orientation: TiffOrientation?)

    /**
     * Executes the plan proven during sync. No format discovery, extractor choice or full-RAW
     * fallback happens here: a TIFF interval is read as recorded, a container preview uses its
     * reader, and a missing plan fails immediately instead of probing the media again.
     */
    private fun decodePlannedSource(
        photo: Photo,
        channel: FileChannel,
        contentLength: Long,
        maximumDimension: Int,
    ): DecodedSource {
        val plan = photo.previewPlan ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "Preview plan is missing")
        if (!plan.available) throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, plan.unavailability ?: "Preview is unavailable")
        val orientation = plan.orientation?.let { name -> runCatching { TiffOrientation.valueOf(name) }.getOrNull() }
        return when (plan.strategy) {
            PreviewStrategy.PAIRED_JPEG, PreviewStrategy.DIRECT_JPEG -> {
                check(photo.jpegPath != null) { "JPEG preview plan requires a JPEG source" }
                val metadata = Kim.readMetadata(BoundedFileChannelByteReader(channel, contentLength, MAX_METADATA_WORK_BYTES))
                DecodedSource(
                    decodeDownsampled(channel, contentLength, maximumDimension),
                    metadata?.convertToPhotoMetadata()?.orientation,
                )
            }
            PreviewStrategy.TIFF_EMBEDDED -> {
                check(photo.rawPath != null && photo.jpegPath == null) { "Embedded preview plan requires RAW-only media" }
                if (plan.offset < 0 || plan.length !in 4..MAX_EMBEDDED_BYTES.toLong() || plan.offset + plan.length > contentLength) {
                    throw PreviewException(PreviewFailure.STALE_SOURCE, "Embedded preview interval is outside the source")
                }
                val bytes = PreviewPlanner.readAt(channel, plan.offset, plan.length.toInt())
                    ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "Embedded preview interval could not be read")
                DecodedSource(decodeDownsampled(bytes, maximumDimension), orientation)
            }
            PreviewStrategy.TIFF_FULL_DECODE -> {
                check(photo.rawPath != null && photo.jpegPath == null) { "Full decode plan requires RAW-only media" }
                DecodedSource(decodeDownsampled(channel, contentLength, maximumDimension), orientation)
            }
            PreviewStrategy.CONTAINER_EMBEDDED -> {
                check(photo.rawPath != null && photo.jpegPath == null) { "Container preview plan requires RAW-only media" }
                val embedded = Kim.extractPreviewImage(BoundedFileChannelByteReader(channel, contentLength, MAX_PREVIEW_PARSER_WORK_BYTES))
                    ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "RAW has no supported embedded preview")
                if (embedded.size !in 4..MAX_EMBEDDED_BYTES) throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "Embedded preview exceeds the byte limit")
                DecodedSource(decodeDownsampled(embedded, maximumDimension), orientation)
            }
        }
    }

    private fun resolveAndValidate(identity: MediaIdentity): SecureLibraryBoundary.ReadExpectation {
        if (!identity.isComplete) throw PreviewException(PreviewFailure.UNAVAILABLE, "Preview identity is incomplete")
        val expected = secure.readExpectation(identity.path)
            ?: throw PreviewException(PreviewFailure.STALE_SOURCE, "Preview source disappeared after indexing")
        if (expected.fileKey != identity.fileKey || expected.size != identity.size || expected.modifiedMillis != identity.modifiedMillis) {
            throw PreviewException(PreviewFailure.STALE_SOURCE, "Preview source changed after indexing")
        }
        if (expected.size <= 0 || expected.size > MAX_SOURCE_BYTES) throw PreviewException(PreviewFailure.UNSAFE_SOURCE, "Preview source size is unsafe")
        return expected
    }

    private fun decodeDownsampled(channel: FileChannel, contentLength: Long, maximumDimension: Int): BufferedImage =
        FileChannelImageInputStream(channel, contentLength, minOf(MAX_IMAGE_DECODER_WORK_BYTES, contentLength * 4)).use {
            decode(it, maximumDimension)
        }

    private fun decodeDownsampled(bytes: ByteArray, maximumDimension: Int): BufferedImage = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { decode(it, maximumDimension) }

    private fun decode(input: javax.imageio.stream.ImageInputStream, maximumDimension: Int): BufferedImage {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Image decoder unavailable")
        return try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            checkDimensions(width, height)
            // Decode at or above the requested size, then downscale once. Using ceil here
            // undershot the target (for example 6000 / 720 became 667 px) and Compose
            // had to enlarge an already recompressed thumbnail, which looked visibly soft.
            val sample = max(1, floor(max(width, height).toDouble() / maximumDimension).toInt())
            val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            scale(reader.read(0, params), maximumDimension)
        } finally { reader.dispose() }
    }

    private fun scale(source: BufferedImage, maximumDimension: Int): BufferedImage {
        val factor = minOf(1.0, maximumDimension.toDouble() / max(source.width, source.height))
        val width = max(1, (source.width * factor).toInt())
        val height = max(1, (source.height * factor).toInt())
        check(width.toLong() * height <= MAX_OUTPUT_PIXELS)
        if (width == source.width && height == source.height && source.type == BufferedImage.TYPE_INT_RGB) return source
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { output ->
            output.createGraphics().use { graphics ->
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.drawImage(source, 0, 0, width, height, null)
            }
        }
    }

    private fun publishJpeg(target: Path, image: BufferedImage) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !singleLink(target))) throw PreviewException(PreviewFailure.UNSAFE_CACHE, "Unsafe thumbnail cache entry")
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: throw PreviewException(PreviewFailure.UNAVAILABLE, "JPEG encoder unavailable")
        ImageIO.createImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            val params = writer.defaultWriteParam.apply { if (canWriteCompressed()) { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .94f } }
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        val bytes = output.toByteArray()
        check(bytes.size in 4..MAX_EMBEDDED_BYTES)
        val temp = Files.createTempFile(directory, ".${target.name}.", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel -> channel.write(java.nio.ByteBuffer.wrap(bytes)); channel.force(true) }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
            forceDirectory(directory)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun validCached(path: Path, maximumDimension: Int): Boolean {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || !singleLink(path)) return false
        if (Files.size(path) !in 4..MAX_EMBEDDED_BYTES.toLong()) return false
        return runCatching {
            ImageIO.createImageInputStream(path.toFile()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
                try { reader.input = input; val width = reader.getWidth(0); val height = reader.getHeight(0); checkDimensions(width, height); check(max(width, height) <= maximumDimension); reader.read(0) != null } finally { reader.dispose() }
            }
        }.getOrDefault(false)
    }

    private fun scheduleCleanup() {
        if (closed) return
        val now = System.nanoTime()
        val previous = lastCleanupNanos.get()
        if (previous != 0L && now - previous < CLEANUP_INTERVAL_NANOS) return
        if (!cleanupScheduled.compareAndSet(false, true)) return
        if (!lastCleanupNanos.compareAndSet(previous, now)) {
            cleanupScheduled.set(false)
            return
        }
        runCatching {
            maintenance.execute {
                val writeLock = cacheLifecycle.writeLock()
                try {
                    if (writeLock.tryLock()) {
                        try { if (!closed) runCatching(::cleanup) } finally { writeLock.unlock() }
                    }
                } finally {
                    cleanupScheduled.set(false)
                }
            }
        }.onFailure { cleanupScheduled.set(false) }
    }

    private fun cleanup() {
        val allEntries = Files.list(directory).use { it.toList() }
        val abandonedBefore = System.currentTimeMillis() - ABANDONED_TEMP_AGE_MILLIS
        fun abandoned(path: Path) = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() <= abandonedBefore
        var changed = false
        allEntries.filter { it.fileName.toString().endsWith(".tmp") && abandoned(it) }.forEach { path ->
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && singleLink(path)) {
                changed = Files.deleteIfExists(path) || changed
            }
        }
        allEntries.filter { it.fileName.toString().startsWith(".source-") && abandoned(it) }.forEach { sourceDirectory ->
            if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceDirectory)) return@forEach
            val children = Files.list(sourceDirectory).use { it.limit(3).toList() }
            if (children.size <= 2 && children.all { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && singleLink(it) }) {
                children.forEach { Files.deleteIfExists(it) }
                changed = Files.deleteIfExists(sourceDirectory) || changed
            }
        }
        val entries = allEntries.asSequence().filter { it.fileName.toString().endsWith(".jpg") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && singleLink(it) }
            .map { it to Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.sortedByDescending { it.second.lastModifiedTime().toMillis() }.toList()
        var bytes = 0L
        entries.forEachIndexed { index, (path, attrs) ->
            bytes += attrs.size()
            if (index >= MAX_CACHE_ENTRIES || bytes > MAX_CACHE_BYTES) changed = Files.deleteIfExists(path) || changed
        }
        if (changed) forceDirectory(directory)
    }

    private fun checkDimensions(width: Int, height: Int) {
        check(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION)
        check(width.toLong() * height <= 1_000_000_000L)
    }

    private fun singleLink(path: Path): Boolean = runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(false)
    private fun requireSingleLink(path: Path) { if (!singleLink(path)) throw PreviewException(PreviewFailure.UNSAFE_CACHE, "Hard-linked thumbnail cache entry is unsafe") }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun forceDirectory(path: Path) { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
    override fun close() {
        closed = true
        maintenance.shutdownNow()
        cacheLifecycle.writeLock().withLock { secure.close() }
    }
}

internal fun applyTiffOrientation(source: BufferedImage, orientation: TiffOrientation?): BufferedImage {
    if (orientation == null || orientation == TiffOrientation.STANDARD) return source
    val flipped = orientation.hasFlippedDimensions()
    val outputWidth = if (flipped) source.height else source.width
    val outputHeight = if (flipped) source.width else source.height
    val sourcePixels = source.getRGB(0, 0, source.width, source.height, null, 0, source.width)
    val outputPixels = IntArray(outputWidth * outputHeight)
    for (y in 0 until source.height) for (x in 0 until source.width) {
        val (outputX, outputY) = when (orientation) {
            TiffOrientation.STANDARD -> x to y
            TiffOrientation.MIRROR_HORIZONTAL -> source.width - 1 - x to y
            TiffOrientation.UPSIDE_DOWN -> source.width - 1 - x to source.height - 1 - y
            TiffOrientation.MIRROR_VERTICAL -> x to source.height - 1 - y
            TiffOrientation.MIRROR_HORIZONTAL_AND_ROTATE_LEFT -> y to x
            TiffOrientation.ROTATE_RIGHT -> source.height - 1 - y to x
            TiffOrientation.MIRROR_HORIZONTAL_AND_ROTATE_RIGHT -> source.height - 1 - y to source.width - 1 - x
            TiffOrientation.ROTATE_LEFT -> y to source.width - 1 - x
        }
        outputPixels[outputY * outputWidth + outputX] = sourcePixels[y * source.width + x]
    }
    return BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB).also {
        it.setRGB(0, 0, outputWidth, outputHeight, outputPixels, 0, outputWidth)
    }
}

enum class PreviewFailure { STALE_SOURCE, UNSUPPORTED_MEDIA, UNSAFE_SOURCE, UNSAFE_CACHE, UNAVAILABLE }

class PreviewException(
    val failure: PreviewFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try { block(this) } finally { dispose() }
