package br.com.lincon.phototool.desktop

import com.ashampoo.kim.format.ImageMetadata
import com.ashampoo.kim.format.tiff.TiffContents
import com.ashampoo.kim.format.tiff.TiffReader
import com.ashampoo.kim.format.tiff.constant.TiffTag
import com.ashampoo.kim.input.RandomAccessByteReader
import com.ashampoo.kim.model.ImageFormat
import com.ashampoo.kim.model.ImageSize
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.imageio.stream.ImageInputStreamImpl

internal const val MAX_MEDIA_SOURCE_BYTES = 1024L * 1024 * 1024
internal const val MAX_METADATA_WORK_BYTES = 128L * 1024 * 1024
internal const val MAX_PREVIEW_PARSER_WORK_BYTES = 256L * 1024 * 1024
internal const val MAX_READER_CHUNK_BYTES = 64 * 1024 * 1024
private const val READER_CACHE_PAGE_BYTES = 64 * 1024

/**
 * Descriptor-backed Kim reader with random access and explicit work limits. Kim's JVM
 * InputStream adapter grows a retained buffer up to the furthest offset visited; this
 * implementation performs positional reads without materializing the media file.
 *
 * close() deliberately does not close the channel. SecureLibraryBoundary owns the locked
 * descriptor and keeps it open until its post-read identity checks have completed.
 */
internal class BoundedFileChannelByteReader(
    private val channel: FileChannel,
    override val contentLength: Long,
    private val maximumWorkBytes: Long,
    private val maximumChunkBytes: Int = MAX_READER_CHUNK_BYTES,
) : RandomAccessByteReader {
    private var position = 0L
    private val cache = ByteArray(minOf(READER_CACHE_PAGE_BYTES.toLong(), contentLength).toInt())
    private var cacheStart = -1L
    private var cacheLength = 0
    internal var bytesRead: Long = 0
        private set
    internal var bytesFetched: Long = 0
        private set

    init {
        require(contentLength in 1..Int.MAX_VALUE.toLong())
        require(maximumWorkBytes > 0 && maximumChunkBytes > 0)
    }

    override fun readByte(): Byte? = readBytes(1).firstOrNull()

    override fun readBytes(count: Int): ByteArray {
        require(count >= 0 && count <= maximumChunkBytes) { "Media parser requested an unsafe read" }
        if (count == 0 || position >= contentLength) return byteArrayOf()
        val actual = minOf(count.toLong(), contentLength - position).toInt()
        val result = readAt(position, actual)
        position += result.size
        return result
    }

    override fun moveTo(position: Int) {
        require(position >= 0 && position.toLong() <= contentLength) { "Media parser requested an invalid offset" }
        this.position = position.toLong()
    }

    override fun readBytes(offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && length <= maximumChunkBytes) { "Media parser requested an unsafe range" }
        if (length == 0 || offset.toLong() >= contentLength) return byteArrayOf()
        return readAt(offset.toLong(), minOf(length.toLong(), contentLength - offset).toInt())
    }

    private fun readAt(offset: Long, length: Int): ByteArray {
        require(bytesRead + length <= maximumWorkBytes) { "Media parser exceeded its bounded read budget" }
        val output = ByteArray(length)
        var written = 0
        while (written < length) {
            val absolute = offset + written
            val remaining = length - written
            if (remaining >= cache.size) {
                val count = readDirect(output, written, absolute, remaining)
                if (count == 0) break
                written += count
            } else {
                ensureCached(absolute)
                val cacheOffset = (absolute - cacheStart).toInt()
                val count = minOf(remaining, cacheLength - cacheOffset)
                if (count <= 0) break
                cache.copyInto(output, written, cacheOffset, cacheOffset + count)
                written += count
            }
        }
        bytesRead += written
        return if (written == length) output else output.copyOf(written)
    }

    private fun ensureCached(offset: Long) {
        if (offset >= cacheStart && offset < cacheStart + cacheLength) return
        cacheStart = (offset / cache.size) * cache.size
        cacheLength = minOf(cache.size.toLong(), contentLength - cacheStart).toInt()
        require(bytesFetched + cacheLength <= maximumWorkBytes + cache.size) { "Media parser exceeded its bounded fetch budget" }
        val buffer = ByteBuffer.wrap(cache, 0, cacheLength)
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, cacheStart + buffer.position())
            if (count < 0) break
            if (count == 0) error("Media descriptor made no read progress")
        }
        cacheLength = buffer.position()
        bytesFetched += cacheLength
    }

    private fun readDirect(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int): Int {
        require(bytesFetched + length <= maximumWorkBytes + cache.size) { "Media parser exceeded its bounded fetch budget" }
        val buffer = ByteBuffer.wrap(destination, destinationOffset, length)
        val initialPosition = buffer.position()
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, sourceOffset + buffer.position() - initialPosition)
            if (count < 0) break
            if (count == 0) error("Media descriptor made no read progress")
        }
        val fetched = buffer.position() - initialPosition
        bytesFetched += fetched
        return fetched
    }

    override fun close() = Unit
}

/** Seekable ImageIO input that never spills the source into a FileCacheImageInputStream. */
internal class FileChannelImageInputStream(
    private val channel: FileChannel,
    private val contentLength: Long,
    private val maximumWorkBytes: Long,
) : ImageInputStreamImpl() {
    private var bytesRead = 0L

    init {
        require(contentLength > 0 && maximumWorkBytes > 0)
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        checkClosed()
        require(offset >= 0 && length >= 0 && offset + length <= destination.size)
        if (length == 0) return 0
        if (streamPos >= contentLength) return -1
        val actual = minOf(length.toLong(), contentLength - streamPos).toInt()
        require(bytesRead + actual <= maximumWorkBytes) { "Image decoder exceeded its bounded read budget" }
        val buffer = ByteBuffer.wrap(destination, offset, actual)
        var total = 0
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, streamPos + total)
            if (count < 0) break
            if (count == 0) error("Image descriptor made no read progress")
            total += count
        }
        if (total == 0) return -1
        streamPos += total
        bytesRead += total
        bitOffset = 0
        return total
    }

    override fun seek(position: Long) {
        checkClosed()
        require(position >= flushedPos && position <= contentLength) { "Image decoder requested an invalid offset" }
        streamPos = position
        bitOffset = 0
    }

    override fun length(): Long = contentLength
}

internal fun readTiffMetadata(
    reader: RandomAccessByteReader,
    format: ImageFormat,
): ImageMetadata = metadataFromTiff(TiffReader.read(reader, false, 0), format)

internal fun metadataFromTiff(contents: TiffContents, format: ImageFormat): ImageMetadata {
    val sizes = contents.directories.mapNotNull { directory ->
        val width = directory.findField(TiffTag.TIFF_TAG_IMAGE_WIDTH)?.toInt()
        val height = directory.findField(TiffTag.TIFF_TAG_IMAGE_HEIGHT)?.toInt()
        if (width != null && height != null && width > 0 && height > 0) ImageSize(width, height) else null
    }
    val imageSize = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    val xmp = contents.directories.firstOrNull()
        ?.getFieldValue(TiffTag.TIFF_TAG_XMP, false)
        ?.takeIf(ByteArray::isNotEmpty)
        ?.toString(Charsets.UTF_8)
    return ImageMetadata(format, imageSize, contents, null, null, xmp)
}

/**
 * Finds JPEG interchange data even when a DNG stores it in a later SubIFD that Kim's
 * DNG extractor does not classify as IFD1. Candidates are bounded and magic-checked.
 */
internal fun extractLargestTiffJpeg(
    contents: TiffContents,
    reader: RandomAccessByteReader,
    maximumBytes: Int,
): ByteArray? {
    data class Candidate(val offset: Int, val length: Int, val pixels: Long)

    val candidates = contents.directories.mapNotNull { directory ->
        val offset = directory.entries.firstOrNull { it.tag == 0x0111 }?.toInt()
        val length = directory.entries.firstOrNull { it.tag == 0x0117 }?.toInt()
        if (offset == null || length == null || offset < 0 || length !in 4..maximumBytes || offset.toLong() + length > reader.contentLength) {
            null
        } else {
            val width = directory.entries.firstOrNull { it.tag == 0x0100 }?.toInt()?.takeIf { it > 0 }
            val height = directory.entries.firstOrNull { it.tag == 0x0101 }?.toInt()?.takeIf { it > 0 }
            Candidate(offset, length, (width?.toLong() ?: 0L) * (height?.toLong() ?: 0L))
        }
    }.sortedWith(compareByDescending<Candidate> { it.pixels }.thenByDescending { it.length })

    return candidates.firstNotNullOfOrNull { candidate ->
        val magic = reader.readBytes(candidate.offset, 2)
        if (magic.size == 2 && magic[0] == 0xff.toByte() && magic[1] == 0xd8.toByte()) {
            reader.readBytes(candidate.offset, candidate.length).takeIf { it.size == candidate.length }
        } else null
    }
}
