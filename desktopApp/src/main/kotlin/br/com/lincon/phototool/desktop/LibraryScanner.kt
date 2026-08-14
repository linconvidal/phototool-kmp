package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.SyncPhase
import br.com.lincon.phototool.state.SyncStatus
import com.ashampoo.kim.common.convertToPhotoMetadata
import com.ashampoo.kim.jvm.KimJvm
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_METADATA_SOURCE_BYTES = 1024L * 1024 * 1024

data class ScanResult(val photos: List<Photo>, val errors: Int)

interface MediaObservationAdapter { fun observe(path: Path, kind: MediaKind): ObservedMetadata }

/** Reads JPEG and supported RAW metadata through Kim's bounded streaming JVM API. */
class KimMediaObservationAdapter : MediaObservationAdapter {
    override fun observe(path: Path, kind: MediaKind): ObservedMetadata {
        val size = Files.size(path)
        if (size <= 0 || size > MAX_METADATA_SOURCE_BYTES) return ObservedMetadata(
            status = MetadataStatus.ERROR,
            errorCode = "metadata-source-size",
        )
        return try {
            val image = KimJvm.readMetadata(path.toFile())
                ?: return ObservedMetadata(status = MetadataStatus.ERROR, errorCode = "metadata-unsupported")
            val value = image.convertToPhotoMetadata()
            val width = value.orientedSize?.width ?: value.widthPx
            val height = value.orientedSize?.height ?: value.heightPx
            val observed = ObservedMetadata(
                capturedAt = value.takenDate?.let { Instant.ofEpochMilli(it).toString() },
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

typealias SafeMediaObservationAdapter = KimMediaObservationAdapter

class LibraryScanner(private val observer: MediaObservationAdapter = KimMediaObservationAdapter()) {
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
                progress(SyncStatus(SyncPhase.DISCOVERING, directories, files, 0, 0, realRoot.relativize(dir).toString().take(160), true, "Discovering media"))
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
                    if (classifyMedia(inner) != null) ambiguousSidecars += folder to inner.substringBeforeLast('.').lowercase()
                    else canonicalSidecars.getOrPut(folder to inner.lowercase()) { mutableListOf() }.add(file.fileName.toString())
                }
                return FileVisitResult.CONTINUE
            }
        })
        if (cancelled.get()) throw InterruptedException("Synchronization cancelled")
        val paired = pairCandidates(candidates, ambiguousSidecars, canonicalSidecars)
        var errors = 0
        val photos = paired.mapIndexed { index, pair ->
            if (cancelled.get()) throw InterruptedException("Synchronization cancelled")
            val authorityName = pair.raw ?: pair.jpeg!!
            val source = realRoot.resolve(pair.folder).resolve(authorityName).normalize()
            val preview = pair.jpeg?.let { realRoot.resolve(pair.folder).resolve(it).normalize() } ?: source
            require(source.startsWith(realRoot) && preview.startsWith(realRoot))
            val kind = if (pair.raw != null) MediaKind.RAW else MediaKind.JPEG
            val metadata = observer.observe(source, kind)
            if (metadata.errorCode != null) errors++
            progress(SyncStatus(SyncPhase.METADATA, directories, files, index + 1, errors, authorityName.take(160), true, "Reading metadata"))
            val relativeSource = relative(realRoot, source)
            val relativePreview = relative(realRoot, preview)
            val authorityPath = if (pair.folder.isEmpty()) (pair.xmp ?: authorityName.substringBeforeLast('.') + ".xmp") else pair.folder + "/" + (pair.xmp ?: authorityName.substringBeforeLast('.') + ".xmp")
            Photo(
                id = stableId("$relativeSource\u0000$authorityPath"),
                folder = pair.folder,
                stem = pair.stem,
                authorityPath = authorityPath,
                rawPath = pair.raw?.let { relativeSource },
                jpegPath = pair.jpeg?.let { relative(realRoot, realRoot.resolve(pair.folder).resolve(it)) },
                previewPath = relativePreview,
                sourceIdentity = identity(realRoot, source),
                previewIdentity = identity(realRoot, preview),
                metadata = metadata,
                writable = pair.writable,
                issue = pair.issue,
            )
        }
        return ScanResult(photos, errors)
    }

    private fun identity(root: Path, path: Path): MediaIdentity {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !Files.isSymbolicLink(path))
        val fileKey = attrs.fileKey()?.toString() ?: runCatching {
            Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse { "path:${path.toRealPath()}" }
        return MediaIdentity(relative(root, path), fileKey, attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path.normalize()).toString().replace('\\', '/')
    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.lowercase().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
