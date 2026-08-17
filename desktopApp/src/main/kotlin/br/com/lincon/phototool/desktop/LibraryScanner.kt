package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.SyncPhase
import br.com.lincon.phototool.state.SyncStatus
import com.ashampoo.kim.Kim
import com.ashampoo.kim.common.convertToPhotoMetadata
import com.ashampoo.kim.format.ImageMetadata
import com.ashampoo.kim.format.tiff.constant.ExifTag
import com.ashampoo.xmp.XMPMetaFactory
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_METADATA_SOURCE_BYTES = 1024L * 1024 * 1024

data class ScanResult(val photos: List<Photo>, val errors: Int)

interface MediaObservationAdapter { fun observe(bytes: ByteArray, kind: MediaKind): ObservedMetadata }

/** Reads JPEG and supported RAW metadata through Kim's bounded streaming JVM API. */
class KimMediaObservationAdapter : MediaObservationAdapter {
    override fun observe(bytes: ByteArray, kind: MediaKind): ObservedMetadata {
        if (bytes.isEmpty() || bytes.size > MAX_METADATA_SOURCE_BYTES) return ObservedMetadata(
            status = MetadataStatus.ERROR,
            errorCode = "metadata-source-size",
        )
        return try {
            val image = Kim.readMetadata(bytes)
                ?: return ObservedMetadata(status = MetadataStatus.ERROR, errorCode = "metadata-unsupported")
            val value = image.convertToPhotoMetadata()
            val width = value.orientedSize?.width ?: value.widthPx
            val height = value.orientedSize?.height ?: value.heightPx
            val observed = ObservedMetadata(
                capturedAt = canonicalMetadataCapturedAt(image),
                camera = value.cameraName,
                cameraMake = value.cameraMake?.safeText(),
                cameraModel = value.cameraModel?.safeText(),
                lens = value.lensName?.safeText(),
                focalLength = value.focalLength.valid(0.01, 10_000.0),
                aperture = value.fNumber.valid(0.1, 128.0),
                exposureSeconds = value.exposureTime.valid(0.000001, 86_400.0),
                iso = value.iso?.takeIf { it in 1..10_000_000 },
                latitude = value.gpsCoordinates?.latitude?.takeIf { it in -90.0..90.0 },
                longitude = value.gpsCoordinates?.longitude?.takeIf { it in -180.0..180.0 },
                width = width?.takeIf { it in 1..1_000_000 },
                height = height?.takeIf { it in 1..1_000_000 },
                status = MetadataStatus.AVAILABLE,
            )
            val hasAny = listOf(observed.capturedAt, observed.cameraMake, observed.cameraModel, observed.lens,
                observed.focalLength, observed.aperture, observed.exposureSeconds, observed.iso,
                observed.latitude, observed.width).any { it != null }
            if (hasAny) observed else observed.copy(status = MetadataStatus.MISSING)
        } catch (error: Exception) {
            ObservedMetadata(status = MetadataStatus.ERROR, errorCode = "metadata-read-failed:${error.javaClass.simpleName}".take(160))
        }
    }

    private fun String.safeText(): String? = trim().replace("\u0000", "").takeIf { it.isNotEmpty() && it.length <= 256 }
    private fun Double?.valid(minimum: Double, maximum: Double): Double? = this?.takeIf { it.isFinite() && it in minimum..maximum }
}

/** Preserves the civil timestamp encoded by metadata instead of converting it through UTC. */
internal fun canonicalMetadataCapturedAt(metadata: ImageMetadata): String? {
    val embeddedXmp = metadata.xmp?.let { xmp ->
        runCatching { XMPMetaFactory.parseFromString(xmp).getDateTimeOriginal()?.trim()?.trimEnd('\u0000') }
            .getOrNull()
            ?.takeIf { captureGregorianDate(it) != null }
    }
    if (embeddedXmp != null) return embeddedXmp
    return canonicalExifCapturedAt(
        metadata.findStringValue(ExifTag.EXIF_TAG_DATE_TIME_ORIGINAL),
        metadata.findStringValue(ExifTag.EXIF_TAG_SUB_SEC_TIME_ORIGINAL),
        metadata.findStringValue(ExifTag.EXIF_TAG_OFFSET_TIME_ORIGINAL),
    )
}

internal fun canonicalExifCapturedAt(dateTimeOriginal: String?, subsecond: String?, offset: String?): String? {
    val raw = dateTimeOriginal?.trim()?.trimEnd('\u0000') ?: return null
    val match = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").matchEntire(raw) ?: return null
    val cleanSubsecond = subsecond?.trim()?.trimEnd('\u0000')?.takeIf(String::isNotEmpty)
    if (cleanSubsecond != null && !cleanSubsecond.matches(Regex("""\d{1,9}"""))) return null
    val fraction = cleanSubsecond?.let { ".$it" }.orEmpty()
    val cleanOffset = offset?.trim()?.trimEnd('\u0000')?.takeIf(String::isNotEmpty)
    if (cleanOffset != null && !cleanOffset.matches(Regex("""Z|[+-]\d{2}:\d{2}"""))) return null
    val zone = cleanOffset.orEmpty()
    val candidate = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}T${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}$fraction$zone"
    return candidate.takeIf { captureGregorianDate(it) != null }
}

typealias SafeMediaObservationAdapter = KimMediaObservationAdapter

class LibraryScanner(
    private val observer: MediaObservationAdapter = KimMediaObservationAdapter(),
) {
    fun scan(root: Path, cancelled: AtomicBoolean, progress: (SyncStatus) -> Unit): ScanResult {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "Library must be a real directory" }
        val realRoot = root.toRealPath()
        val candidates = mutableListOf<MediaCandidate>()
        val ambiguousSidecars = mutableSetOf<Pair<String, String>>()
        val canonicalSidecars = mutableMapOf<Pair<String, String>, MutableList<String>>()
        var directories = 0
        var files = 0
        Files.walkFileTree(realRoot, setOf(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelled.get()) return FileVisitResult.TERMINATE
                if (dir != realRoot && (dir.fileName.toString().startsWith(".") || Files.isSymbolicLink(dir))) return FileVisitResult.SKIP_SUBTREE
                directories++
                progress(SyncStatus(SyncPhase.DISCOVERING, directories, files, 0, 0, realRoot.relativize(dir).toString().take(160), true, "Descobrindo mídia"))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelled.get()) return FileVisitResult.TERMINATE
                files++
                if (!attrs.isRegularFile || Files.isSymbolicLink(file) || file.fileName.toString().startsWith(".")) return FileVisitResult.CONTINUE
                val relative = realRoot.relativize(file)
                val folder = relative.parent?.toString()?.replace('\\', '/') ?: ""
                classifyMedia(file.fileName.toString())?.let { candidates += MediaCandidate(folder, file.fileName.toString(), it) }
                if (file.fileName.toString().substringAfterLast('.', "").equals("xmp", true)) {
                    val inner = file.fileName.toString().substringBeforeLast('.')
                    if (classifyMedia(inner) != null) ambiguousSidecars += folder to caseFoldText(inner.substringBeforeLast('.'))
                    else canonicalSidecars.getOrPut(folder to caseFoldText(inner)) { mutableListOf() }.add(file.fileName.toString())
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (cancelled.get()) throw InterruptedException("Synchronization cancelled")
        val paired = pairCandidates(candidates, ambiguousSidecars, canonicalSidecars)
        progress(SyncStatus(SyncPhase.METADATA, directories, files, 0, 0, "", true, "Pareamento concluído; lendo metadados", totalPhotos = paired.size))
        var errors = 0
        return SecureLibraryBoundary(realRoot, false).use { boundary ->
            val photos = paired.mapIndexed { index, pair ->
                if (cancelled.get()) throw InterruptedException("Synchronization cancelled")
                val authorityName = pair.raw ?: pair.jpeg!!
                val relativeSource = if (pair.folder.isEmpty()) authorityName else "${pair.folder}/$authorityName"
                val relativePreview = pair.jpeg?.let { if (pair.folder.isEmpty()) it else "${pair.folder}/$it" } ?: relativeSource
                val kind = if (pair.raw != null) MediaKind.RAW else MediaKind.JPEG
                val sourceBefore = boundary.readExpectation(relativeSource) ?: error("Media disappeared before metadata read")
                val observed = if (sourceBefore.size !in 1..MAX_METADATA_SOURCE_BYTES) {
                    ObservedMetadata(status = MetadataStatus.ERROR, errorCode = "metadata-source-size")
                } else observeSecurely(boundary, relativeSource, kind)
                val sourceAfter = boundary.readExpectation(relativeSource)
                val stableRead = sourceBefore == sourceAfter
                val metadata = if (stableRead) observed else ObservedMetadata(status = MetadataStatus.ERROR, errorCode = "media-changed-during-read")
                if (metadata.errorCode != null) errors++
                progress(SyncStatus(SyncPhase.METADATA, directories, files, index + 1, errors, authorityName.take(160), true, "Lendo metadados", totalPhotos = paired.size))
                val authorityPath = if (pair.folder.isEmpty()) (pair.xmp ?: authorityName.substringBeforeLast('.') + ".xmp") else pair.folder + "/" + (pair.xmp ?: authorityName.substringBeforeLast('.') + ".xmp")
                val previewIdentity = boundary.readExpectation(relativePreview) ?: error("Preview media disappeared during scan")
                Photo(
                    id = stableId("$relativeSource\u0000$authorityPath"),
                    folder = pair.folder,
                    stem = pair.stem,
                    authorityPath = authorityPath,
                    rawPath = pair.raw?.let { relativeSource },
                    jpegPath = pair.jpeg?.let { relativePreview },
                    previewPath = relativePreview,
                    sourceIdentity = sourceAfter?.toMediaIdentity(relativeSource),
                    previewIdentity = previewIdentity.toMediaIdentity(relativePreview),
                    metadata = metadata,
                    writable = pair.writable && stableRead,
                    issue = pair.issue ?: if (!stableRead) "Media changed during metadata read" else null,
                )
            }
            ScanResult(photos, errors)
        }
    }

    private fun observeSecurely(
        boundary: SecureLibraryBoundary,
        relative: String,
        kind: MediaKind,
    ): ObservedMetadata {
        val bytes = boundary.readOnceAttested(relative, MAX_METADATA_SOURCE_BYTES.toInt())
        return observer.observe(bytes, kind)
    }

    private fun SecureLibraryBoundary.ReadExpectation.toMediaIdentity(path: String) =
        MediaIdentity(path, fileKey, size, modifiedMillis)

    private fun identity(root: Path, path: Path): MediaIdentity {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !Files.isSymbolicLink(path))
        val fileKey = attrs.fileKey()?.toString() ?: runCatching {
            Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse { throw IllegalStateException("Stable media identity unavailable", it) }
        return MediaIdentity(relative(root, path), fileKey, attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path.normalize()).toString().replace('\\', '/')
    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(caseFoldText(value).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
