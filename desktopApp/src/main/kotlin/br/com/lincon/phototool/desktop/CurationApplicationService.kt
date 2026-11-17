package br.com.lincon.phototool.desktop

import br.com.lincon.phototool.api.*

import br.com.lincon.phototool.domain.EditorialState
import br.com.lincon.phototool.domain.EditorialCommand
import br.com.lincon.phototool.domain.Flag
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.domain.keywordCasefold
import br.com.lincon.phototool.domain.normalizeFlatKeyword
import br.com.lincon.phototool.domain.normalizeKeyword
import br.com.lincon.phototool.state.SyncStatus
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

internal interface SnapshotProjection {
    fun loadCacheOnly(): List<Photo>
    fun generation(): String?
    fun publish(photos: List<Photo>)
    fun updateEditorial(photoId: String, editorial: EditorialState)
    fun projectionDegraded(): Boolean
    fun markProjectionDegraded()
    fun clearProjectionDegraded()
    fun <T> withExclusiveProjection(block: () -> T): T
    fun <T> withSharedProjection(block: () -> T): T
}

internal interface EditorialAuthority : AutoCloseable {
    fun read(photo: Photo): EditorialState
    fun readSnapshot(photo: Photo): AuthorityEditorialSnapshot
    fun mutateCurrent(
        photo: Photo,
        expectedRevision: String?,
        canonicalizeFlag: Boolean,
        transform: (EditorialState) -> EditorialState,
    ): CurrentEditorialMutation
}

internal fun interface CurationScanner {
    fun scan(cancelled: AtomicBoolean, progress: (SyncStatus) -> Unit): ScanResult
}

internal interface PreviewAuthority : AutoCloseable {
    fun preview(photo: Photo, maximumDimension: Int): PreviewPayload
    fun originalJpeg(photo: Photo): PreviewPayload =
        throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Original JPEG is unavailable")

    fun regionJpeg(photo: Photo, x: Int, y: Int, width: Int, height: Int): PreviewPayload =
        throw PreviewException(PreviewFailure.UNSUPPORTED_MEDIA, "Native regions are unavailable")
}

internal data class PreviewPayload(val bytes: ByteArray, val etag: String)

internal class PhotoCacheProjection(private val cache: PhotoCache) : SnapshotProjection {
    override fun loadCacheOnly(): List<Photo> = cache.loadCacheOnly()
    override fun generation(): String? = cache.persistedGeneration()
    override fun publish(photos: List<Photo>) = cache.publish(photos)
    override fun updateEditorial(photoId: String, editorial: EditorialState) = cache.updateEditorial(photoId, editorial)
    override fun projectionDegraded(): Boolean = cache.projectionDegraded()
    override fun markProjectionDegraded() = cache.markProjectionDegraded()
    override fun clearProjectionDegraded() = cache.clearProjectionDegraded()
    override fun <T> withExclusiveProjection(block: () -> T): T = cache.withExclusiveProjection(block)
    override fun <T> withSharedProjection(block: () -> T): T = cache.withSharedProjection(block)
}

internal class XmpEditorialAuthority(private val store: XmpSidecarStore) : EditorialAuthority {
    override fun read(photo: Photo): EditorialState = store.read(photo)
    override fun readSnapshot(photo: Photo): AuthorityEditorialSnapshot = store.readSnapshot(photo)
    override fun mutateCurrent(
        photo: Photo,
        expectedRevision: String?,
        canonicalizeFlag: Boolean,
        transform: (EditorialState) -> EditorialState,
    ): CurrentEditorialMutation = store.mutateCurrent(
        photo,
        expectedRevision?.let { expected -> { snapshot -> snapshot.revision == expected } },
        canonicalizeFlag,
        transform,
    )

    override fun close() = store.close()
}

internal class PreviewStoreAuthority(private val store: PreviewStore) : PreviewAuthority {
    private val slots = java.util.concurrent.Semaphore(4, true)

    override fun originalJpeg(photo: Photo): PreviewPayload {
        slots.acquire()
        return try {
            val original = store.originalJpegBytes(photo)
            PreviewPayload(original.bytes, original.etag)
        } finally {
            slots.release()
        }
    }

    override fun regionJpeg(photo: Photo, x: Int, y: Int, width: Int, height: Int): PreviewPayload {
        slots.acquire()
        return try {
            val region = store.regionJpegBytes(photo, x, y, width, height)
            PreviewPayload(region.bytes, region.etag)
        } finally {
            slots.release()
        }
    }

    override fun preview(photo: Photo, maximumDimension: Int): PreviewPayload {
        slots.acquire()
        return try {
            val preview = store.thumbnailBytes(photo, maximumDimension)
            PreviewPayload(preview.bytes, preview.etag)
        } finally {
            slots.release()
        }
    }

    override fun close() = store.close()
}

internal sealed interface PersistenceAttempt {
    data class Conflict(val authoritative: AuthorityEditorialSnapshot, val projectionGeneration: String?) : PersistenceAttempt
    data class Persisted(
        val authoritative: AuthorityEditorialSnapshot,
        val projectionUpdated: Boolean,
        val projectionGeneration: String?,
        val projectionFailure: Throwable? = null,
    ) : PersistenceAttempt
}

/** Shared desktop/server editorial boundary. XMP is authoritative; SQLite is updated only after readback. */
internal class EditorialPersistenceService(
    private val authority: EditorialAuthority,
    private val projection: SnapshotProjection,
) {
    fun persistAndProject(photo: Photo, desired: EditorialState, canonicalizeFlag: Boolean = false): EditorialState =
        projection.withExclusiveProjection {
            if (projection.projectionDegraded()) throw ProjectionDegradedException()
            val persisted = when (val mutation = authority.mutateCurrent(photo, null, canonicalizeFlag) { current ->
                validateEditorial(mergeEditorial(photo.editorial, desired, current)).also { projection.markProjectionDegraded() }
            }) {
                is CurrentEditorialMutation.Conflict -> error("Unconditional desktop mutation conflicted")
                is CurrentEditorialMutation.Persisted -> mutation.authoritative
            }
            try {
                projection.updateEditorial(photo.id, persisted.state)
                projection.clearProjectionDegraded()
                persisted.state
            } catch (failure: Throwable) {
                throw ProjectionUpdateAfterXmpException(persisted.state, failure)
            }
        }

    fun mutateExpected(photo: Photo, expectedRevision: String, command: EditorialCommand): PersistenceAttempt {
        require(expectedRevision.matches(Regex("[0-9a-f]{64}"))) { "Expected editorial revision is invalid" }
        return projection.withExclusiveProjection {
            if (projection.projectionDegraded()) throw ProjectionDegradedException()
            when (val mutation = authority.mutateCurrent(photo, expectedRevision, command is EditorialCommand.SetFlag) { current ->
                validateEditorial(applyCommand(current, command)).also { projection.markProjectionDegraded() }
            }) {
                is CurrentEditorialMutation.Conflict -> {
                    projection.updateEditorial(photo.id, mutation.authoritative.state)
                    projection.clearProjectionDegraded()
                    PersistenceAttempt.Conflict(mutation.authoritative, projection.generation())
                }
                is CurrentEditorialMutation.Persisted -> try {
                    projection.updateEditorial(photo.id, mutation.authoritative.state)
                    projection.clearProjectionDegraded()
                    PersistenceAttempt.Persisted(mutation.authoritative, projectionUpdated = true, projectionGeneration = projection.generation())
                } catch (failure: Throwable) {
                    PersistenceAttempt.Persisted(mutation.authoritative, projectionUpdated = false, projectionGeneration = null, projectionFailure = failure)
                }
            }
        }
    }

    fun read(photo: Photo): EditorialState = authority.read(photo)
    fun readSnapshotIf(photo: Photo, allowed: () -> Boolean): AuthorityEditorialSnapshot? {
        if (!allowed()) return null
        return try {
            projection.withSharedProjection { if (allowed()) authority.readSnapshot(photo) else null }
        } catch (busy: ProjectionBusyException) {
            if (allowed()) throw busy else null
        }
    }

    private fun validateEditorial(editorial: EditorialState): EditorialState {
        require(editorial.rating in 0..5 && editorial.keywords.size <= 256)
        editorial.keywords.forEach(::normalizeKeyword)
        return editorial
    }

    private fun applyCommand(current: EditorialState, command: EditorialCommand): EditorialState = when (command) {
        is EditorialCommand.SetFlag -> current.copy(flag = command.value)
        is EditorialCommand.SetRating -> current.copy(rating = command.value.also { require(it in 0..5) })
        is EditorialCommand.SetLabel -> current.copy(label = command.value)
        is EditorialCommand.AddKeyword -> {
            val keyword = normalizeFlatKeyword(command.value)
            val retained = current.keywords.filterNot { keywordCasefold(it) == keywordCasefold(keyword) }
            require(retained.size < 256) { "Keyword limit exceeded" }
            current.copy(keywords = retained + keyword)
        }
        is EditorialCommand.RemoveKeyword -> {
            val keyword = normalizeKeyword(command.value)
            current.copy(keywords = current.keywords.filterNot { keywordCasefold(it) == keywordCasefold(keyword) })
        }
    }
}

class WriteDisabledException : IllegalStateException("Write mode is disabled")
class ProjectionDegradedException : IllegalStateException("The editorial projection requires repair")
class ProjectionBusyException : IllegalStateException("Another process owns the editorial projection")
internal class ProjectionUpdateAfterXmpException(val authoritative: EditorialState, cause: Throwable) :
    IllegalStateException("XMP was saved but the editorial projection failed", cause)
class SyncInProgressException : IllegalStateException("Synchronization is in progress")
class EditorialRevisionConflictException(val authoritative: EditorialDto) : IllegalStateException("Editorial revision conflict")
class PartialProjectionException(val result: MutationResultDto, cause: Throwable?) : IllegalStateException("XMP was saved but the editorial projection failed", cause)

private val LOG = LoggerFactory.getLogger("br.com.lincon.phototool.desktop.Synchronization")

class CurationApplicationService internal constructor(
    private val projection: SnapshotProjection,
    private val persistence: EditorialPersistenceService,
    private val previews: PreviewAuthority,
    private val scanner: CurationScanner,
    private val writeEnabled: Boolean,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phototool-web-sync").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val stateLock = Any()
    private val operationGate = Any()
    private val queryService = CurationQueryService()
    private val initialSnapshot = loadConsistentCacheOnly()
    private var photos: List<Photo> = initialSnapshot.first
    private var generation: String? = initialSnapshot.second
    private var projectionState = when {
        projection.projectionDegraded() -> ProjectionStateDto.DEGRADED
        generation == null -> ProjectionStateDto.EMPTY
        else -> ProjectionStateDto.READY
    }
    private var syncStatus = SyncStatusDto(photos = photos.size, totalPhotos = photos.size.takeIf { it > 0 })
    private var activeFlight: String? = null
    private var cancelled = AtomicBoolean(false)
    @Volatile private var projectionOperationRunning = false

    fun status(): SnapshotStatusDto = synchronized(stateLock) {
        refreshExternalSnapshot()
        refreshTransientProjectionState()
        SnapshotStatusDto(generation, photos.size, projectionState, writeEnabled, syncStatus)
    }

    fun page(input: PhotoQueryInput): PhotoPageDto {
        val (current, currentGeneration) = snapshot()
        return queryService.page(current, currentGeneration, input)
    }

    fun detail(input: PhotoQueryInput, photoId: String): PhotoDetailDto {
        val (current) = snapshot()
        val photo = queryService.photo(current, photoId)
        val cached = queryService.detail(current, input.copy(page = 1), photoId)
        if (!photo.writable) return cached
        val authoritative = persistence.readSnapshotIf(photo) { synchronized(stateLock) { !syncStatus.running } && !projectionOperationRunning } ?: return cached
        return cached.copy(editorial = authoritative.state.toDto(authoritative.revision))
    }

    fun facets(): FilterFacetsDto {
        val (current) = snapshot()
        return queryService.facets(current)
    }

    internal fun preview(photoId: String, maximumDimension: Int): PreviewPayload {
        if (maximumDimension !in MIN_PREVIEW_DIMENSION..MAX_PREVIEW_DIMENSION_API) throw InvalidQueryException("Preview dimension is outside the supported range")
        val photo = queryService.photo(snapshot().first, photoId)
        return previews.preview(photo, maximumDimension)
    }

    internal fun originalJpeg(photoId: String): PreviewPayload {
        val photo = queryService.photo(snapshot().first, photoId)
        return previews.originalJpeg(photo)
    }

    internal fun region(photoId: String, x: Int, y: Int, width: Int, height: Int): PreviewPayload {
        val photo = queryService.photo(snapshot().first, photoId)
        return previews.regionJpeg(photo, x, y, width, height)
    }

    fun mutate(photoId: String, expectedRevision: String, command: EditorialCommand): MutationResultDto = synchronized(operationGate) {
        synchronized(stateLock) {
            if (!writeEnabled) throw WriteDisabledException()
            refreshTransientProjectionState()
            if (projectionState == ProjectionStateDto.DEGRADED) throw ProjectionDegradedException()
            if (syncStatus.running || projectionOperationRunning) throw SyncInProgressException()
        }
        val photo = queryService.photo(snapshot().first, photoId)
        try {
            when (val attempt = persistence.mutateExpected(photo, expectedRevision, command)) {
                is PersistenceAttempt.Conflict -> {
                    synchronized(stateLock) {
                        photos = photos.map { if (it.id == photo.id) it.copy(editorial = attempt.authoritative.state) else it }
                        generation = attempt.projectionGeneration
                    }
                    throw EditorialRevisionConflictException(attempt.authoritative.state.toDto(attempt.authoritative.revision))
                }
                is PersistenceAttempt.Persisted -> completeMutation(photo.id, attempt)
            }
        } catch (failure: Throwable) {
            if (failure !is ProjectionBusyException && runCatching { projection.projectionDegraded() }.getOrDefault(true)) {
                synchronized(stateLock) { projectionState = ProjectionStateDto.DEGRADED }
            }
            throw failure
        }
    }

    fun requestSync(): SyncCommandDto = synchronized(operationGate) {
        synchronized(stateLock) {
            activeFlight?.takeIf { syncStatus.running }?.let { return SyncCommandDto(it, false, syncStatus) }
            val flight = UUID.randomUUID().toString()
            cancelled = AtomicBoolean(false)
            activeFlight = flight
            syncStatus = SyncStatusDto(flightId = flight, phase = "DISCOVERING", running = true, photos = 0, message = "Sincronização explícita iniciada")
            worker.submit { synchronize(flight, cancelled) }
            SyncCommandDto(flight, true, syncStatus)
        }
    }

    fun cancelSync() = synchronized(stateLock) { if (syncStatus.running) cancelled.set(true) else Unit }

    internal fun awaitIdle(timeoutSeconds: Long = 30) {
        worker.submit { }.get(timeoutSeconds, TimeUnit.SECONDS)
    }

    private fun completeMutation(photoId: String, attempt: PersistenceAttempt.Persisted): MutationResultDto {
        val result = synchronized(stateLock) {
            photos = photos.map { if (it.id == photoId) it.copy(editorial = attempt.authoritative.state) else it }
            generation = attempt.projectionGeneration
            if (!attempt.projectionUpdated) projectionState = ProjectionStateDto.DEGRADED
            MutationResultDto(
                photoId = photoId,
                editorial = attempt.authoritative.state.toDto(attempt.authoritative.revision),
                xmpPersisted = true,
                projectionUpdated = attempt.projectionUpdated,
                projectionState = projectionState,
            )
        }
        if (!attempt.projectionUpdated) throw PartialProjectionException(result, attempt.projectionFailure)
        return result
    }

    private fun synchronize(flight: String, flightCancelled: AtomicBoolean) {
        try {
            projectionOperationRunning = true
            projection.withExclusiveProjection {
                val scanned = scanner.scan(flightCancelled) { progress ->
                synchronized(stateLock) {
                    if (activeFlight == flight) syncStatus = syncStatus.copy(
                        phase = progress.phase.name,
                        running = true,
                        photos = progress.photos,
                        totalPhotos = progress.totalPhotos,
                        errors = progress.errors,
                        message = progress.message.take(240),
                    )
                }
            }
            if (flightCancelled.get()) throw InterruptedException("Synchronization cancelled")
            var xmpErrors = 0
            val observed = scanned.photos.map { photo ->
                if (flightCancelled.get()) throw InterruptedException("Synchronization cancelled")
                runCatching { photo.copy(editorial = persistence.read(photo)) }.getOrElse {
                    xmpErrors++
                    photo.copy(writable = false, issue = "Unsafe or malformed XMP")
                }
            }
            if (xmpErrors > 0) projection.markProjectionDegraded()
            projection.publish(observed)
            val confirmed = projection.loadCacheOnly()
            val confirmedGeneration = projection.generation()
            check(
                confirmedGeneration != null &&
                    confirmed.associate { it.id to it.editorial } == observed.associate { it.id to it.editorial },
            ) { "Published editorial projection could not be confirmed" }
            if (xmpErrors == 0) projection.clearProjectionDegraded()
            val repairedProjectionState = if (projection.projectionDegraded()) ProjectionStateDto.DEGRADED else ProjectionStateDto.READY
            synchronized(stateLock) {
                if (activeFlight != flight) return@synchronized
                photos = confirmed
                generation = confirmedGeneration
                projectionState = repairedProjectionState
                syncStatus = SyncStatusDto(
                    flightId = flight,
                    phase = "COMPLETE",
                    running = false,
                    photos = confirmed.size,
                    totalPhotos = confirmed.size,
                    errors = scanned.errors + xmpErrors,
                    message = buildString {
                        append(if (scanned.errors + xmpErrors == 0) "Snapshot completo publicado" else "Snapshot publicado com falhas limitadas")
                        if (scanned.unavailablePreviews > 0) append("; ${scanned.unavailablePreviews} sem prévia validada")
                    },
                    completedAt = Instant.now().toString(),
                )
                activeFlight = null
            }
        }
        } catch (_: InterruptedException) {
            reconcileProjectionAfterFailure()
            finishFailedFlight(flight, "CANCELLED", "Sincronização cancelada. O cache será revalidado antes do próximo acesso")
        } catch (error: Throwable) {
            LOG.error("Synchronization flight {} failed", flight, error)
            reconcileProjectionAfterFailure()
            finishFailedFlight(flight, "FAILED", "Sincronização falhou. O cache será revalidado antes do próximo acesso")
        } finally {
            projectionOperationRunning = false
        }
    }

    private fun reconcileProjectionAfterFailure() {
        val recovered = runCatching { loadConsistentCacheOnly() }.getOrNull() ?: return
        if (recovered.second == null) return
        synchronized(stateLock) {
            photos = recovered.first
            generation = recovered.second
            projectionState = when {
                runCatching { projection.projectionDegraded() }.getOrDefault(true) -> ProjectionStateDto.DEGRADED
                generation == null -> ProjectionStateDto.EMPTY
                else -> ProjectionStateDto.READY
            }
        }
    }

    private fun finishFailedFlight(flight: String, phase: String, message: String) = synchronized(stateLock) {
        if (activeFlight != flight) return@synchronized
        if (runCatching { projection.projectionDegraded() }.getOrDefault(true)) projectionState = ProjectionStateDto.DEGRADED
        syncStatus = syncStatus.copy(phase = phase, running = false, message = message, completedAt = Instant.now().toString())
        activeFlight = null
    }

    private fun loadConsistentCacheOnly(): Pair<List<Photo>, String?> {
        repeat(3) {
            val before = runCatching { projection.generation() }.getOrNull()
            val loaded = runCatching { projection.loadCacheOnly() }.getOrDefault(emptyList())
            val after = runCatching { projection.generation() }.getOrNull()
            if (before == after) return loaded to after
        }
        return emptyList<Photo>() to null
    }

    private fun refreshExternalSnapshot() {
        val persistedGeneration = runCatching { projection.generation() }.getOrNull() ?: return
        if (persistedGeneration == generation) return
        val persistedPhotos = runCatching { projection.loadCacheOnly() }.getOrNull() ?: return
        if (runCatching { projection.generation() }.getOrNull() != persistedGeneration) return
        photos = persistedPhotos
        generation = persistedGeneration
        projectionState = if (runCatching { projection.projectionDegraded() }.getOrDefault(true)) ProjectionStateDto.DEGRADED else ProjectionStateDto.READY
    }

    private fun refreshTransientProjectionState() {
        val degraded = runCatching { projection.projectionDegraded() }.getOrDefault(true)
        projectionState = when {
            degraded -> ProjectionStateDto.DEGRADED
            generation == null -> ProjectionStateDto.EMPTY
            else -> ProjectionStateDto.READY
        }
    }

    private fun snapshot(): Pair<List<Photo>, String?> = synchronized(stateLock) {
        refreshExternalSnapshot()
        photos to generation
    }

    override fun close() {
        cancelled.set(true)
        worker.shutdownNow()
        worker.awaitTermination(5, TimeUnit.SECONDS)
    }
}

internal class CurationRuntime private constructor(
    val application: CurationApplicationService,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() {
        application.close()
        resources.asReversed().forEach { runCatching { it.close() } }
    }

    companion object {
        fun open(library: Path, cacheDirectory: Path, writeEnabled: Boolean): CurationRuntime {
            val root = library.toRealPath()
            val cache = PhotoCache(cacheDirectory, root, validateLibraryDescendantsOnOpen = false)
            val resources = mutableListOf<AutoCloseable>()
            return try {
                val xmp = XmpEditorialAuthority(XmpSidecarStore(root, writeEnabled)).also(resources::add)
                val previews = PreviewStoreAuthority(
                    PreviewStore(root, cacheDirectory, validateLibraryDescendantsOnOpen = false),
                ).also(resources::add)
                val projection = PhotoCacheProjection(cache)
                val persistence = EditorialPersistenceService(xmp, projection)
                val scanner = CurationScanner { cancelled, progress -> LibraryScanner().scan(root, cancelled, progress) }
                val application = CurationApplicationService(projection, persistence, previews, scanner, writeEnabled)
                CurationRuntime(application, resources)
            } catch (failure: Throwable) {
                resources.asReversed().forEach { runCatching { it.close() } }
                throw failure
            }
        }
    }
}
