package br.com.lincon.phototool.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import br.com.lincon.phototool.domain.MediaIdentity
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.ui.PlatformImageLoader
import com.ashampoo.kim.Kim
import com.ashampoo.kim.common.convertToPhotoMetadata
import com.ashampoo.kim.input.JvmInputStreamByteReader
import com.ashampoo.kim.jvm.KimJvm
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

private const val MAX_SOURCE_BYTES = 1024L * 1024 * 1024
private const val MAX_EMBEDDED_BYTES = 64 * 1024 * 1024
private const val MAX_SOURCE_DIMENSION = 100_000
private const val MAX_OUTPUT_PIXELS = 20_000_000
private const val MAX_PREVIEW_DIMENSION = 4_096
private const val MAX_CACHE_BYTES = 512L * 1024 * 1024
private const val MAX_CACHE_ENTRIES = 5_000
private const val PREVIEW_PARALLELISM = 4
private const val SHARED_THUMBNAIL_LOCK_STRIPES = 256
private const val SHARED_CACHE_LOCK_STRIPES = 64
private const val CLEANUP_INTERVAL_NANOS = 60_000_000_000L

class PreviewStore(
    private val root: Path,
    cacheDir: Path,
    private val afterSourceRead: (() -> Unit)? = null,
) : PlatformImageLoader, AutoCloseable {
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

    init {
        assertCachePhysicallySeparate(realRoot, cacheDir.toAbsolutePath().normalize(), requireCacheExists = Files.exists(cacheDir.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS))
        Files.createDirectories(directory.parent)
        require(!Files.isSymbolicLink(directory.parent)) { "Preview cache parent may not be a link" }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        else Files.createDirectory(directory)
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

    private fun thumbnailWithoutCleanup(photo: Photo, maximumDimension: Int): Path {
        val identity = photo.previewIdentity ?: throw PreviewException("Preview identity is missing")
        val expected = resolveAndValidate(identity)
        val dimension = maximumDimension.coerceIn(128, MAX_PREVIEW_DIMENSION)
        val key = sha256("thumb-v5\u0000${identity.path}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}\u0000$dimension")
        val target = directory.resolve("$key.jpg")
        val lockKey = "$directory\u0000$key"
        val stripe = sharedThumbnailLocks[(lockKey.hashCode() and Int.MAX_VALUE) % sharedThumbnailLocks.size]
        return stripe.withLock {
            if (validCached(target, dimension)) {
                requireSingleLink(target)
                Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()))
                return@withLock target
            }
            val temporaryDirectory = Files.createTempDirectory(directory, ".source-")
            val source = temporaryDirectory.resolve(Path.of(identity.path).fileName.toString())
            try {
                secure.copyVerifiedTo(identity.path, MAX_SOURCE_BYTES, expected, source)
                val orientation = runCatching { KimJvm.readMetadata(source.toFile())?.convertToPhotoMetadata()?.orientation }.getOrNull()
                val decoded = if (photo.jpegPath != null) decodeDownsampled(source, dimension) else {
                    check(photo.rawPath != null && photo.jpegPath == null) { "Embedded preview is only used for RAW-only media" }
                    val preview = Files.newInputStream(source, StandardOpenOption.READ).buffered().use { stream ->
                        Kim.extractPreviewImage(JvmInputStreamByteReader(stream, identity.size))
                    } ?: throw PreviewException("RAW has no embedded JPEG preview")
                    check(preview.size in 4..MAX_EMBEDDED_BYTES) { "Embedded preview exceeds the byte limit" }
                    decodeDownsampled(preview, dimension)
                }
                val image = applyTiffOrientation(decoded, orientation)
                afterSourceRead?.invoke()
                resolveAndValidate(identity)
                try {
                    publishJpeg(target, image)
                    resolveAndValidate(identity)
                    check(validCached(target, dimension)) { "Published thumbnail is not decodeable" }
                } catch (failure: Exception) {
                    if (Files.deleteIfExists(target)) forceDirectory(directory)
                    throw failure
                }
            } finally {
                runCatching { Files.deleteIfExists(source) }
                Files.deleteIfExists(temporaryDirectory)
            }
            target
        }
    }

    fun verify(photo: Photo): Boolean = runCatching { thumbnail(photo, 512); true }.getOrDefault(false)

    private fun resolveAndValidate(identity: MediaIdentity): SecureLibraryBoundary.ReadExpectation {
        require(identity.isComplete)
        val expected = secure.readExpectation(identity.path) ?: throw PreviewException("Preview source disappeared after indexing")
        if (expected.fileKey != identity.fileKey || expected.size != identity.size || expected.modifiedMillis != identity.modifiedMillis) throw PreviewException("Preview source changed after indexing")
        if (expected.size <= 0 || expected.size > MAX_SOURCE_BYTES) throw PreviewException("Preview source size is unsafe")
        return expected
    }

    private fun decodeDownsampled(path: Path, maximumDimension: Int): BufferedImage = ImageIO.createImageInputStream(path.toFile()).use { input ->
        decode(input ?: throw PreviewException("Image input unavailable"), maximumDimension)
    }

    private fun decodeDownsampled(bytes: ByteArray, maximumDimension: Int): BufferedImage = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { decode(it, maximumDimension) }

    private fun decode(input: javax.imageio.stream.ImageInputStream, maximumDimension: Int): BufferedImage {
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw PreviewException("JPEG decoder unavailable")
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
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !singleLink(target))) throw PreviewException("Unsafe thumbnail cache entry")
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: throw PreviewException("JPEG encoder unavailable")
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
        val entries = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".jpg") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && singleLink(it) }.map { it to Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.toList() }
            .sortedByDescending { it.second.lastModifiedTime().toMillis() }
        var bytes = 0L
        entries.forEachIndexed { index, (path, attrs) ->
            bytes += attrs.size()
            if (index >= MAX_CACHE_ENTRIES || bytes > MAX_CACHE_BYTES) Files.deleteIfExists(path)
        }
    }

    private fun checkDimensions(width: Int, height: Int) {
        check(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION)
        check(width.toLong() * height <= 1_000_000_000L)
    }

    private fun singleLink(path: Path): Boolean = runCatching { (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(false)
    private fun requireSingleLink(path: Path) { if (!singleLink(path)) throw PreviewException("Hard-linked thumbnail cache entry is unsafe") }
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

class PreviewException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try { block(this) } finally { dispose() }
