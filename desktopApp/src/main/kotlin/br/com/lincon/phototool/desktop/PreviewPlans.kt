package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.PreviewPlan
import br.com.lincon.phototool.domain.PreviewStrategy
import com.ashampoo.kim.format.tiff.TiffContents
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Upper bound for the encoded plan stored in the snapshot. */
internal const val MAX_PREVIEW_PLAN_CHARS = 128
private const val JPEG_HEADER_WINDOW = 256 * 1024

/**
 * Compact snapshot representation of a preview plan:
 * `strategy|offset|length|width|height|orientation|unavailability`.
 */
internal object PreviewPlanCodec {
    private const val FIELDS = 7

    fun encode(plan: PreviewPlan): String = listOf(
        plan.strategy.name,
        plan.offset.toString(),
        plan.length.toString(),
        plan.width.toString(),
        plan.height.toString(),
        plan.orientation.orEmpty(),
        plan.unavailability.orEmpty(),
    ).joinToString("|")

    fun parse(value: String?): PreviewPlan? {
        if (value == null) return null
        val parts = value.split('|')
        if (parts.size != FIELDS || value.length > MAX_PREVIEW_PLAN_CHARS) return null
        val strategy = PreviewStrategy.entries.firstOrNull { it.name == parts[0] } ?: return null
        val offset = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val length = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val width = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val height = parts[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val orientation = parts[5].takeIf { it.isNotEmpty() }
        val unavailability = parts[6].takeIf { it.isNotEmpty() }
        return PreviewPlan(strategy, offset, length, width, height, orientation, unavailability)
    }
}

/** Derives the validated preview plan from a preview source, without keeping image bytes. */
internal object PreviewPlanner {
    /** JPEG preview sources are read from the start of the file; only their header is inspected. */
    fun jpeg(channel: FileChannel, contentLength: Long, strategy: PreviewStrategy): PreviewPlan {
        val failure = PreviewPlan(strategy, 0, 0, 0, 0, unavailability = "jpeg-header-unavailable")
        if (contentLength !in 4..MAX_MEDIA_SOURCE_BYTES) return failure.copy(unavailability = "jpeg-source-size")
        val header = readAt(channel, 0, minOf(contentLength, JPEG_HEADER_WINDOW.toLong()).toInt()) ?: return failure
        if (header.size < 4 || header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) return failure.copy(unavailability = "jpeg-header-missing")
        val size = jpegFrameSize(header) ?: return failure.copy(unavailability = "jpeg-frame-missing")
        val tail = readAt(channel, contentLength - 2, 2) ?: return failure.copy(unavailability = "jpeg-trailer-missing")
        if (tail.size != 2 || tail[0] != 0xFF.toByte() || tail[1] != 0xD9.toByte()) return failure.copy(unavailability = "jpeg-truncated")
        return PreviewPlan(strategy, 0, contentLength, size.first, size.second)
    }

    /**
     * TIFF containers (CR2/DNG) publish the embedded JPEG as a byte interval. The interval is
     * recorded so a preview load reads those bytes instead of parsing TIFF directories again.
     */
    fun tiffEmbedded(
        channel: FileChannel,
        contentLength: Long,
        contents: TiffContents,
        orientation: String?,
    ): PreviewPlan {
        val failure = PreviewPlan(PreviewStrategy.TIFF_EMBEDDED, 0, 0, 0, 0, orientation, "raw-embedded-preview-missing")
        data class Candidate(val offset: Int, val length: Int, val width: Int, val height: Int, val pixels: Long)

        val candidates = contents.directories.mapNotNull { directory ->
            val offset = directory.entries.firstOrNull { it.tag == 0x0111 }?.toInt()
            val length = directory.entries.firstOrNull { it.tag == 0x0117 }?.toInt()
            if (offset == null || length == null || offset < 0 || length !in 4..MAX_EMBEDDED_PREVIEW_BYTES || offset.toLong() + length > contentLength) {
                null
            } else {
                val width = directory.entries.firstOrNull { it.tag == 0x0100 }?.toInt()?.takeIf { it > 0 } ?: 0
                val height = directory.entries.firstOrNull { it.tag == 0x0101 }?.toInt()?.takeIf { it > 0 } ?: 0
                Candidate(offset, length, width, height, width.toLong() * height.toLong())
            }
        }.sortedWith(compareByDescending<Candidate> { it.pixels }.thenByDescending { it.length })

        candidates.forEach { candidate ->
            val magic = readAt(channel, candidate.offset.toLong(), 2) ?: return@forEach
            if (magic.size == 2 && magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
                val dimensions = if (candidate.width > 0 && candidate.height > 0) {
                    candidate.width to candidate.height
                } else {
                    readAt(channel, candidate.offset.toLong(), minOf(candidate.length.toLong(), JPEG_HEADER_WINDOW.toLong()).toInt())
                        ?.let(::jpegFrameSize)
                } ?: return@forEach
                return PreviewPlan(
                    PreviewStrategy.TIFF_EMBEDDED,
                    candidate.offset.toLong(),
                    candidate.length.toLong(),
                    dimensions.first,
                    dimensions.second,
                    orientation,
                )
            }
        }
        return failure
    }

    /** Containers without a published interval keep their reader-based extraction, decided at sync. */
    fun containerEmbedded(width: Int, height: Int, orientation: String?): PreviewPlan =
        PreviewPlan(PreviewStrategy.CONTAINER_EMBEDDED, 0, 0, width, height, orientation)

    fun fullDecode(width: Int, height: Int, orientation: String?): PreviewPlan =
        PreviewPlan(
            PreviewStrategy.TIFF_FULL_DECODE,
            0,
            0,
            width.coerceIn(0, 100_000),
            height.coerceIn(0, 100_000),
            orientation,
        )

    fun unavailable(strategy: PreviewStrategy, reason: String, orientation: String? = null): PreviewPlan =
        PreviewPlan(strategy, 0, 0, 0, 0, orientation, reason.take(48))

    fun readAt(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0) return null
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) return null
            position += read
        }
        return bytes
    }
}

internal const val MAX_EMBEDDED_PREVIEW_BYTES = 64 * 1024 * 1024

/** Reads the frame size from a JPEG header without decoding pixels. */
internal fun jpegFrameSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var index = 2
    while (index + 3 < bytes.size) {
        if (bytes[index] != 0xFF.toByte()) { index++; continue }
        val marker = bytes[index + 1].toInt() and 0xFF
        if (marker == 0xFF) { index++; continue }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { index += 2; continue }
        if (marker == 0xD9 || marker == 0xDA) return null
        val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
        if (length < 2 || index + 2 + length > bytes.size) return null
        val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isFrame) {
            if (length < 7) return null
            val height = ((bytes[index + 5].toInt() and 0xFF) shl 8) or (bytes[index + 6].toInt() and 0xFF)
            val width = ((bytes[index + 7].toInt() and 0xFF) shl 8) or (bytes[index + 8].toInt() and 0xFF)
            if (width !in 1..100_000 || height !in 1..100_000) return null
            return width to height
        }
        index += 2 + length
    }
    return null
}
