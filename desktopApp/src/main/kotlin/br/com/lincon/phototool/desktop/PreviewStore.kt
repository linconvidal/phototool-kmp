package br.com.lincon.phototool.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import br.com.lincon.phototool.domain.MediaIdentity
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.ui.PlatformImageLoader
import com.ashampoo.kim.Kim
import com.ashampoo.kim.input.JvmInputStreamByteReader
import org.jetbrains.skia.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.io.path.name
import kotlin.math.ceil
import kotlin.math.max

private const val MAX_SOURCE_BYTES = 1024L * 1024 * 1024
private const val MAX_EMBEDDED_BYTES = 64 * 1024 * 1024
private const val MAX_SOURCE_DIMENSION = 100_000
private const val MAX_OUTPUT_PIXELS = 20_000_000
private const val MAX_CACHE_BYTES = 512L * 1024 * 1024
private const val MAX_CACHE_ENTRIES = 5_000

class PreviewStore(private val root: Path, cacheDir: Path) : PlatformImageLoader {
    private val realRoot = root.toRealPath()
    private val directory = cacheDir.toAbsolutePath().normalize().resolve("previews")

    init {
        Files.createDirectories(directory.parent)
        require(!Files.isSymbolicLink(directory.parent)) { "Preview cache parent may not be a link" }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        else Files.createDirectory(directory)
    }

    override suspend fun load(photo: Photo, maximumDimension: Int): ImageBitmap? = runCatching {
        val bounded = maximumDimension.coerceIn(128, 2048)
        val cached = thumbnail(photo, bounded)
        val bytes = Files.readAllBytes(cached)
        check(bytes.size in 4..MAX_EMBEDDED_BYTES)
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    fun thumbnail(photo: Photo, maximumDimension: Int): Path {
        val identity = photo.previewIdentity ?: throw PreviewException("Preview identity is missing")
        val source = resolveAndValidate(identity)
        val dimension = maximumDimension.coerceIn(128, 2048)
        val key = sha256("thumb-v3\u0000${identity.path}\u0000${identity.fileKey}\u0000${identity.size}\u0000${identity.modifiedMillis}\u0000$dimension")
        val target = directory.resolve("$key.jpg")
        if (validCached(target, dimension)) {
            requireSingleLink(target)
            return target
        }
        val image = if (photo.jpegPath != null) decodeDownsampled(source, dimension) else {
            check(photo.rawPath != null && photo.jpegPath == null) { "Embedded preview is only used for RAW-only media" }
            val preview = Files.newInputStream(source, StandardOpenOption.READ).buffered().use { stream ->
                Kim.extractPreviewImage(JvmInputStreamByteReader(stream, identity.size))
            } ?: throw PreviewException("RAW has no embedded JPEG preview")
            check(preview.size in 4..MAX_EMBEDDED_BYTES) { "Embedded preview exceeds the byte limit" }
            decodeDownsampled(preview, dimension)
        }
        publishJpeg(target, image)
        check(validCached(target, dimension)) { "Published thumbnail is not decodeable" }
        cleanup()
        return target
    }

    fun verify(photo: Photo): Boolean = runCatching { thumbnail(photo, 512); true }.getOrDefault(false)

    private fun resolveAndValidate(identity: MediaIdentity): Path {
        require(identity.isComplete)
        val path = realRoot.resolve(identity.path).normalize()
        require(path.startsWith(realRoot) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        var parent = path.parent
        while (parent != realRoot) {
            require(parent != null && !Files.isSymbolicLink(parent))
            parent = parent.parent
        }
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val key = attrs.fileKey()?.toString() ?: runCatching { Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS).toString() }.getOrElse { "path:${path.toRealPath()}" }
        if (key != identity.fileKey || attrs.size() != identity.size || attrs.lastModifiedTime().toMillis() != identity.modifiedMillis) throw PreviewException("Preview source changed after indexing")
        if (attrs.size() <= 0 || attrs.size() > MAX_SOURCE_BYTES) throw PreviewException("Preview source size is unsafe")
        return path
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
            val sample = max(1, ceil(max(width, height).toDouble() / maximumDimension).toInt())
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
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
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
            val params = writer.defaultWriteParam.apply { if (canWriteCompressed()) { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .88f } }
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
    private fun forceDirectory(path: Path) { runCatching { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } } }
}

class PreviewException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R = try { block(this) } finally { dispose() }
