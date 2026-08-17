package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.state.*
import br.com.lincon.phototool.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

internal data class LaunchOptions(val library: Path?, val cache: Path, val enableWrite: Boolean, val smoke: Boolean)
private const val SAVED_STATUS_DURATION_MILLIS = 1_600L
private data class LibrarySessionDescriptor(val context: String, val rootKey: String)

internal fun automaticDesktopRenderApi(
    osName: String,
    sessionType: String?,
    waylandDisplay: String?,
    explicitProperty: String?,
    explicitEnvironment: String?,
): String? {
    if (!explicitProperty.isNullOrBlank() || !explicitEnvironment.isNullOrBlank()) return null
    val linux = osName.contains("linux", ignoreCase = true)
    val wayland = sessionType.equals("wayland", ignoreCase = true) || !waylandDisplay.isNullOrBlank()
    return if (linux && wayland) "SOFTWARE" else null
}

private fun configureDesktopRenderApi() {
    automaticDesktopRenderApi(
        osName = System.getProperty("os.name").orEmpty(),
        sessionType = System.getenv("XDG_SESSION_TYPE"),
        waylandDisplay = System.getenv("WAYLAND_DISPLAY"),
        explicitProperty = System.getProperty("skiko.renderApi"),
        explicitEnvironment = System.getenv("SKIKO_RENDER_API"),
    )?.let { System.setProperty("skiko.renderApi", it) }
}

/**
 * Watches logind LockedHint and reports one transition from locked to unlocked. After
 * screen unlock on Linux/XWayland the compositor can stop re-exposing the window, which
 * freezes the visible UI even though the JVM stays healthy; the consumer hides and shows
 * the window to force a fresh X11 Expose and Skiko repaint.
 */
internal class UnlockWatcher(
    sessionId: String?,
    private val lockedHintQuery: (String) -> Boolean? = { UnlockWatcher.queryLockedHint(it) },
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val pendingUnlock = AtomicBoolean(false)
    private val thread = Thread {
        var wasLocked: Boolean? = null
        while (running.get()) {
            val locked = sessionId?.let(lockedHintQuery)
            if (wasLocked == true && locked == false) pendingUnlock.set(true)
            if (locked != null) wasLocked = locked
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
        }
    }.apply { isDaemon = true; start() }

    /** Consumes one pending unlock notification; returns true at most once per unlock. */
    fun takeUnlock(): Boolean = pendingUnlock.getAndSet(false)

    override fun close() { running.set(false); thread.interrupt() }

    companion object {
        fun sessionId(): String? = runCatching {
            val path = Path.of("/proc/self/sessionid")
            if (Files.exists(path)) Files.readString(path).trim().takeIf(String::isNotEmpty) else null
        }.getOrNull()

        fun queryLockedHint(sessionId: String): Boolean? = runCatching {
            val process = ProcessBuilder(
                "busctl", "get-property", "org.freedesktop.login1",
                "/org/freedesktop/login1/session/${dbusEscapePathElement(sessionId)}",
                "org.freedesktop.login1.Session", "LockedHint",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { process.destroy() }
            parseLockedHint(output)
        }.getOrNull()

        internal fun parseLockedHint(output: String): Boolean? =
            output.lineSequence().mapNotNull { line -> line.trim().removePrefix("b ").toBooleanStrictOrNull() }.firstOrNull()

        internal fun dbusEscapePathElement(value: String): String = buildString {
            value.forEachIndexed { index, c ->
                if (c.isLetterOrDigit() || c == '_') {
                    if (index == 0 && c.isDigit()) append("_%02x".format(c.code)) else append(c)
                } else append("_%02x".format(c.code))
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "archive") exitProcess(runArchiveCli(args))
    val options = parseArguments(args)
    if (options.smoke) {
        val code = runCatching { SmokeHarness.run(options) }.fold({ 0 }, { error -> System.err.println("smoke-error: ${error.message?.take(300)}"); 2 })
        if (code != 0) exitProcess(code)
        return
    }
    configureDesktopRenderApi()
    application {
        val controller = remember { DesktopController(options) }
        DisposableEffect(Unit) { onDispose { controller.close() } }
        val windowIcon = painterResource("icons/phototool-app-icon.png")
        val unlockWatcher = remember { UnlockWatcher(UnlockWatcher.sessionId()) }
        DisposableEffect(Unit) { onDispose { unlockWatcher.close() } }
        var windowVisible by remember { mutableStateOf(true) }
        LaunchedEffect(unlockWatcher) {
            while (true) {
                if (unlockWatcher.takeUnlock()) {
                    // Force the compositor to re-expose the X11 window after unlock,
                    // restarting Skiko repaints that would otherwise stay frozen.
                    windowVisible = false
                    delay(80)
                    windowVisible = true
                }
                delay(200)
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "PhotoTool", icon = windowIcon, visible = windowVisible, state = androidx.compose.ui.window.rememberWindowState(width = 1440.dp, height = 920.dp)) {
            PhotoToolApp(controller.state, controller.callbacks, Modifier.onKeyEvent { controller.key(it) })
        }
    }
}

internal class CoalescedEditController(
    private val executor: Executor,
    private val completed: (String, String, Long, EditorialState, WriteState, String?) -> Unit,
) {
    private data class RequestKey(val context: String, val photoId: String)
    private data class Request(
        val context: String,
        val generation: Long,
        val photo: Photo,
        val desired: EditorialState,
        val rollback: EditorialState,
        val persist: (Photo, EditorialState) -> EditorialState,
        val confirmed: (String, EditorialState) -> Unit,
        val updateCache: (String, EditorialState) -> Unit,
    )
    private val generations = java.util.concurrent.ConcurrentHashMap<RequestKey, AtomicLong>()
    private val pending = java.util.concurrent.ConcurrentHashMap<RequestKey, Request>()
    private val scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<RequestKey>()
    private val lastConfirmed = java.util.concurrent.ConcurrentHashMap<RequestKey, EditorialState>()

    fun submit(
        photo: Photo,
        desired: EditorialState,
        context: String = "default",
        rollback: EditorialState = photo.editorial,
        persist: (Photo, EditorialState) -> EditorialState,
        confirmed: (String, EditorialState) -> Unit = { _, _ -> },
        updateCache: (String, EditorialState) -> Unit,
    ): Long {
        val key = RequestKey(context, photo.id)
        val generation = generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        pending[key] = Request(context, generation, photo, desired, rollback, persist, confirmed, updateCache)
        if (scheduled.add(key)) executor.execute { drain(key) }
        return generation
    }

    fun isCurrent(context: String, id: String, generation: Long): Boolean = generations[RequestKey(context, id)]?.get() == generation

    private fun drain(key: RequestKey) {
        try {
            while (true) {
                val request = pending.remove(key) ?: break
                val persisted = runCatching { request.persist(request.photo, request.desired) }
                if (persisted.isFailure) {
                    val rollback = lastConfirmed[key] ?: request.rollback
                    if (isCurrent(request.context, key.photoId, request.generation)) completed(request.context, key.photoId, request.generation, rollback, WriteState.FAILED, "Falha ao salvar no XMP; o último estado confirmado foi restaurado")
                    continue
                }
                val authoritative = persisted.getOrThrow()
                lastConfirmed[key] = authoritative
                request.confirmed(key.photoId, authoritative)
                val cached = runCatching { request.updateCache(key.photoId, authoritative) }
                if (cached.isFailure) {
                    if (isCurrent(request.context, key.photoId, request.generation)) completed(request.context, key.photoId, request.generation, authoritative, WriteState.FAILED, "XMP salvo, mas o cache editorial não pôde ser atualizado")
                    continue
                }
                if (isCurrent(request.context, key.photoId, request.generation) && !pending.containsKey(key)) completed(request.context, key.photoId, request.generation, authoritative, WriteState.PERSISTED, null)
            }
        } finally {
            scheduled.remove(key)
            if (pending.containsKey(key) && scheduled.add(key)) executor.execute { drain(key) }
        }
    }
}

internal class EditorialRevisionLedger {
    private data class Confirmed(val revision: Long, val editorial: EditorialState)
    private val confirmed = mutableMapOf<String, Confirmed>()
    private var revision = 0L

    @Synchronized fun clear() { confirmed.clear(); revision = 0L }

    @Synchronized fun seedMissing(photos: List<Photo>) {
        photos.forEach { photo ->
            val existing = confirmed[photo.id]
            if (existing == null || existing.revision == 0L) confirmed[photo.id] = Confirmed(0L, photo.editorial)
        }
    }

    @Synchronized fun confirmed(id: String): EditorialState? = confirmed[id]?.editorial

    @Synchronized fun record(id: String, editorial: EditorialState): Long {
        val next = ++revision
        confirmed[id] = Confirmed(next, editorial)
        return next
    }

    @Synchronized fun overlayWithRevision(photos: List<Photo>): Pair<List<Photo>, Long> =
        photos.map { photo -> confirmed[photo.id]?.let { photo.copy(editorial = it.editorial) } ?: photo } to revision

    @Synchronized fun overlay(photos: List<Photo>): List<Photo> =
        photos.map { photo -> confirmed[photo.id]?.let { photo.copy(editorial = it.editorial) } ?: photo }

    @Synchronized fun changesAfter(baseRevision: Long, ids: Set<String>): List<Pair<String, EditorialState>> =
        confirmed.asSequence().filter { (id, value) -> id in ids && value.revision > baseRevision }.map { (id, value) -> id to value.editorial }.toList()

    @Synchronized fun overlayAndAccept(photos: List<Photo>): List<Photo> {
        val reconciled = photos.map { photo -> confirmed[photo.id]?.let { photo.copy(editorial = it.editorial) } ?: photo }
        confirmed.clear()
        reconciled.forEach { photo -> confirmed[photo.id] = Confirmed(0L, photo.editorial) }
        revision = 0L
        return reconciled
    }
}

internal data class ReconciledPublication(val photos: List<Photo>, val warning: String? = null)

internal fun publishReconciledSnapshot(
    cache: PhotoCache,
    observed: List<Photo>,
    ledger: EditorialRevisionLedger,
    beforePublish: () -> Unit = {},
): ReconciledPublication {
    ledger.seedMissing(observed)
    val (prepared, preparedRevision) = ledger.overlayWithRevision(observed)
    beforePublish()
    cache.publish(prepared)
    val ids = observed.mapTo(hashSetOf()) { it.id }
    val replayWarning = runCatching {
        ledger.changesAfter(preparedRevision, ids).forEach { (id, editorial) -> cache.updateEditorial(id, editorial) }
    }.exceptionOrNull()?.let { "Snapshot publicado; uma edição confirmada precisa ser reconciliada do XMP" }
    val confirmed = cache.load()
    check(confirmed.map { it.id }.toSet() == observed.map { it.id }.toSet()) { "Published cache could not be confirmed" }
    return ReconciledPublication(confirmed, replayWarning)
}

internal fun summaryWarning(save: () -> Unit): String? = runCatching(save).exceptionOrNull()?.let { "Snapshot publicado; não foi possível atualizar o resumo da sincronização" }

internal data class SnapshotDelta(val added: Int, val removed: Int, val updated: Int)

internal fun snapshotDelta(previous: List<Photo>, current: List<Photo>): SnapshotDelta {
    val before = previous.associate { it.id to photoSnapshotVersion(it) }
    val after = current.associate { it.id to photoSnapshotVersion(it) }
    return SnapshotDelta(
        added = (after.keys - before.keys).size,
        removed = (before.keys - after.keys).size,
        updated = (before.keys intersect after.keys).count { before[it] != after[it] },
    )
}

internal fun snapshotFingerprint(photos: List<Photo>): String = sha256(
    photos.sortedBy { it.id }.joinToString("\n") { "${it.id}:${photoSnapshotVersion(it)}" },
)

private fun photoSnapshotVersion(photo: Photo): String = sha256(photo.copy(writeState = WriteState.IDLE, writeError = null).toString())
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun syncStatusFromSummary(summary: SyncSummary?, snapshot: List<Photo>, generation: String?): SyncStatus {
    val snapshotPhotos = snapshot.size
    val summaryMatches = summary != null && generation != null && summary.snapshotGeneration == generation && summary.snapshotFingerprint == snapshotFingerprint(snapshot)
    if (!summaryMatches) return if (snapshotPhotos > 0) SyncStatus(SyncPhase.COMPLETE, photos = snapshotPhotos, totalPhotos = snapshotPhotos, message = "Snapshot confirmado carregado. O resumo anterior não corresponde a esta geração") else SyncStatus()
    return when (summary.outcome) {
        "success" -> SyncStatus(SyncPhase.COMPLETE, photos = snapshotPhotos, errors = summary.errors, message = "Última sincronização concluída; snapshot carregado", totalPhotos = snapshotPhotos, durationMillis = summary.durationMillis, added = summary.added, removed = summary.removed, updated = summary.updated)
        "failed" -> SyncStatus(SyncPhase.FAILED, photos = snapshotPhotos, errors = summary.errors, message = "A última sincronização falhou; o snapshot confirmado foi carregado", durationMillis = summary.durationMillis)
        "cancelled" -> SyncStatus(SyncPhase.CANCELLED, photos = snapshotPhotos, errors = summary.errors, message = "A última sincronização foi cancelada; o snapshot confirmado foi carregado", durationMillis = summary.durationMillis)
        else -> SyncStatus()
    }
}

internal class DesktopController(
    options: LaunchOptions,
    private val configurationObserver: ((String) -> Unit)? = null,
) : AutoCloseable {
    private data class PreparedServices(
        val selected: Path,
        val session: LibrarySessionDescriptor,
        val resources: List<AutoCloseable>,
        val cache: PhotoCache,
        val xmp: XmpSidecarStore,
        val previews: PreviewStore,
        val summaries: SyncSummaryStore,
        val auxiliary: AuxiliaryActions,
        val ledger: EditorialRevisionLedger,
        val snapshot: List<Photo>,
        val status: SyncStatus,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "phototool-sync") }
    private val editWorker = Executors.newSingleThreadExecutor { Thread(it, "phototool-editorial") }
    private var cancelled = AtomicBoolean(false)
    private var library: Path? = options.library?.toAbsolutePath()?.normalize()
    private var cachePath: Path = options.cache.toAbsolutePath().normalize()
    private val settingsStore = AppSettingsStore(cachePath)
    private val writeAuthorized = true
    @Volatile private var sessionWriteEnabled = settingsStore.loadWriteEnabled() ?: options.enableWrite
    private var cache: PhotoCache? = null
    private var xmp: XmpSidecarStore? = null
    private var previews: PreviewStore? = null
    private var summaries: SyncSummaryStore? = null
    private var auxiliary = AuxiliaryActions()
    private val libraryResources = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()
    private val sessionCounter = AtomicLong()
    private val configurationCounter = AtomicLong()
    @Volatile private var pendingConfiguration = 0L
    @Volatile private var librarySession: String? = null
    @Volatile private var libraryRootKey: String? = null
    private var textFieldFocused = false
    private var shortcutScopeFocused = false

    private var editorialLedger = EditorialRevisionLedger()
    private val edits = CoalescedEditController(editWorker, ::publishEditCompletion)
    private fun publishEditCompletion(context: String, id: String, generation: Long, editorial: EditorialState, writeState: WriteState, message: String?) {
        scope.launch {
            if (librarySession == context && library?.let { rootIdentityMatches(it, libraryRootKey) } == true && edits.isCurrent(context, id, generation)) {
                state = reduce(state, Action.EditorialChanged(id, editorial, writeState, message))
                if (writeState == WriteState.PERSISTED) {
                    delay(SAVED_STATUS_DURATION_MILLIS)
                    if (librarySession == context && edits.isCurrent(context, id, generation)) {
                        state = reduce(state, Action.ClearPersistedStatus(id, editorial))
                    }
                }
            }
        }
    }
    var state by mutableStateOf(AppState(library = library?.toString(), cache = cachePath.toString(), writeEnabled = sessionWriteEnabled, writeAuthorized = writeAuthorized))
        private set

    init {
        library?.let(::scheduleLibraryConfiguration)
    }

    val callbacks: AppCallbacks
        get() = AppCallbacks(
            dispatch = ::dispatch,
            chooseLibrary = ::chooseLibrary,
            synchronize = ::synchronize,
            cancelSync = { cancelled.set(true) },
            setReadOnlyMode = ::setReadOnlyMode,
            mutate = ::mutate,
            mutateFlag = { photo, flag -> mutate(photo, photo.editorial.copy(flag = flag), canonicalizeFlag = true) },
            batchMutate = ::batchMutate,
            imageLoader = previews ?: PlatformImageLoader.None,
            auxiliary = auxiliary,
            navigate = ::navigate,
            openMap = ::openMap,
            textFieldFocused = { textFieldFocused = it },
            shortcutScopeFocused = { shortcutScopeFocused = it },
        )

    private fun prepareServices(selected: Path, newSession: LibrarySessionDescriptor): PreparedServices {
        val newResources = mutableListOf<AutoCloseable>()
        try {
            val newCache = PhotoCache(cachePath, selected)
            val newXmp = XmpSidecarStore(selected, true).also(newResources::add)
            val newPreviews = PreviewStore(selected, cachePath).also(newResources::add)
            val newSummaries = SyncSummaryStore(cachePath)
            val newFuji = FujiProfileStore(selected, true).also(newResources::add)
            val newAuxiliary = DesktopAuxiliaryActions(newXmp, newFuji) {
                sessionWriteEnabled && librarySession == newSession.context && rootIdentityMatches(selected, newSession.rootKey)
            }.callbacks()
            val sharedSnapshotListings = HashMap<String, List<SecureLibraryBoundary.Entry>>()
            val snapshot = newCache.load().map { photo ->
                runCatching { photo.copy(editorial = newXmp.readWithSharedListings(photo, sharedSnapshotListings)) }.getOrElse { photo.copy(issue = photo.issue ?: "XMP read failed: ${it.message}", writable = false) }
            }
            val newLedger = EditorialRevisionLedger().also { it.seedMissing(snapshot) }
            val lastSummary = runCatching { newSummaries.load() }.getOrNull()
            val newStatus = syncStatusFromSummary(lastSummary, snapshot, newCache.snapshotGeneration())
            return PreparedServices(selected, newSession, newResources, newCache, newXmp, newPreviews, newSummaries, newAuxiliary, newLedger, snapshot, newStatus)
        } catch (failure: Exception) {
            newResources.asReversed().forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun installServices(prepared: PreparedServices) {
        val oldResources = libraryResources.toList()
        cache = prepared.cache
        xmp = prepared.xmp
        previews = prepared.previews
        summaries = prepared.summaries
        auxiliary = prepared.auxiliary
        editorialLedger = prepared.ledger
        library = prepared.selected
        librarySession = prepared.session.context
        libraryRootKey = prepared.session.rootKey
        libraryResources.clear()
        libraryResources.addAll(prepared.resources)
        state = state.copy(
            library = prepared.selected.toString(),
            photos = prepared.snapshot,
            selectedId = null,
            selectedIds = emptySet(),
            selectionAnchorId = null,
            sync = prepared.status,
        )
        worker.submit { oldResources.forEach { runCatching { it.close() } } }
    }

    fun dispatch(action: Action) { state = reduce(state, action) }

    private fun setReadOnlyMode(enabled: Boolean) {
        if (state.photos.any { it.writeState == WriteState.SAVING }) return
        sessionWriteEnabled = !enabled
        runCatching { settingsStore.saveWriteEnabled(sessionWriteEnabled) }
        state = state.copy(writeEnabled = sessionWriteEnabled, writeAuthorized = true)
    }

    private fun chooseLibrary() {
        if (state.sync.running) return
        val dialog = FileDialog(null as Frame?, "Choose photo library", FileDialog.LOAD).apply { isMultipleMode = false; isVisible = true }
        val directory = dialog.directory ?: return
        switchLibrary(Paths.get(directory, dialog.file ?: ""))
    }

    internal fun switchLibrary(selectedPath: Path) {
        check(!state.sync.running) { "Cannot switch libraries during synchronization" }
        cancelled.set(true)
        scheduleLibraryConfiguration(selectedPath)
    }

    private fun scheduleLibraryConfiguration(selectedPath: Path) {
        val request = configurationCounter.incrementAndGet()
        pendingConfiguration = request
        librarySession = null // immediately invalidates queued writes for the old root
        state = state.copy(sync = SyncStatus(SyncPhase.DISCOVERING, running = true, message = "Preparando biblioteca e cache fora da interface"))
        worker.submit {
            val prepared = runCatching {
                configurationObserver?.invoke(Thread.currentThread().name)
                drainEditorialWrites()
                val candidate = selectedPath.toAbsolutePath().normalize()
                val selected = (if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) candidate else candidate.parent ?: candidate).toRealPath()
                prepareServices(selected, nextLibrarySession(selected))
            }
            scope.launch {
                if (pendingConfiguration != request) {
                    prepared.getOrNull()?.resources?.asReversed()?.forEach { runCatching { it.close() } }
                } else prepared.onSuccess(::installServices).onFailure {
                    state = state.copy(sync = SyncStatus(phase = SyncPhase.FAILED, running = false, message = it.message ?: "Falha ao configurar a biblioteca"))
                }
            }
        }
    }

    internal fun awaitBackgroundWork(timeoutSeconds: Long = 30) {
        worker.submit { }.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) javax.swing.SwingUtilities.invokeAndWait { }
    }

    private fun nextLibrarySession(root: Path): LibrarySessionDescriptor {
        val canonical = root.toRealPath()
        val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val key = attrs.fileKey()?.toString() ?: error("Stable library identity unavailable")
        return LibrarySessionDescriptor("${sessionCounter.incrementAndGet()}:$key:$canonical", key)
    }

    private fun rootIdentityMatches(root: Path, expectedKey: String?): Boolean = expectedKey != null && runCatching {
        val canonical = root.toRealPath()
        val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attrs.isDirectory && attrs.fileKey()?.toString() == expectedKey
    }.getOrDefault(false)

    private fun drainEditorialWrites() {
        editWorker.submit { }.get(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun synchronize() {
        val root = library ?: return
        if (state.sync.running) return
        cancelled = AtomicBoolean(false)
        state = state.copy(sync = SyncStatus(SyncPhase.DISCOVERING, running = true, message = "Iniciando sincronização"))
        val flightCache = cache ?: return
        val flightXmp = xmp ?: return
        val flightSummaries = summaries
        val flightSession = librarySession ?: return
        val flightRootKey = libraryRootKey ?: return
        val flightLedger = editorialLedger
        val previousErrors = state.sync.errors
        val startedNanos = System.nanoTime()
        worker.submit {
            val previousSnapshot: List<Photo>
            val previousGeneration: String?
            try {
                previousSnapshot = flightCache.load()
                previousGeneration = flightCache.snapshotGeneration()
            } catch (error: Exception) {
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = SyncStatus(SyncPhase.FAILED, errors = previousErrors + 1, running = false, message = "Não foi possível carregar o snapshot anterior fora da interface")) }
                return@submit
            }
            fun elapsedMillis(): Long = ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
            fun summary(outcome: String, snapshot: List<Photo>, generation: String?, errors: Int, errorCode: String = "", delta: SnapshotDelta = SnapshotDelta(0, 0, 0)): SyncSummary? = generation?.let {
                SyncSummary(outcome, snapshot.size, errors, errorCode, elapsedMillis(), delta.added, delta.removed, delta.updated, it, snapshotFingerprint(snapshot))
            }
            fun savePreviousOutcome(outcome: String, errors: Int, errorCode: String) {
                summary(outcome, previousSnapshot, previousGeneration, errors, errorCode)?.let { value -> runCatching { flightSummaries?.save(value) } }
            }
            val scanned = try {
                LibraryScanner().scan(root, cancelled) { progress -> scope.launch { if (librarySession == flightSession) state = state.copy(sync = progress) } }
            } catch (_: InterruptedException) {
                savePreviousOutcome("cancelled", previousErrors, "cancelled")
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.CANCELLED, running = false, message = "Sincronização cancelada. O snapshot anterior foi preservado")) }
                return@submit
            } catch (error: Exception) {
                savePreviousOutcome("failed", previousErrors, syncErrorCode(error))
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.FAILED, running = false, message = "${error.message?.take(120) ?: "Falha na sincronização"}. O snapshot anterior foi preservado")) }
                return@submit
            }
            var xmpFailures = 0
            val sharedXmpListings = HashMap<String, List<SecureLibraryBoundary.Entry>>()
            val observed = try {
                if (cancelled.get()) throw InterruptedException()
                scanned.photos.mapIndexed { index, photo ->
                    if (cancelled.get()) throw InterruptedException()
                    val xmpRead = runCatching { flightXmp.readWithSharedListings(photo, sharedXmpListings) }
                    val indexed = if (xmpRead.isSuccess) photo.copy(editorial = xmpRead.getOrThrow()) else {
                        xmpFailures++
                        photo.copy(writable = false, issue = "Unsafe or malformed XMP")
                    }
                    scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.INDEXING, photos = index + 1, totalPhotos = scanned.photos.size, message = "Lendo ajustes editoriais")) }
                    indexed
                }.also {
                    if (cancelled.get()) throw InterruptedException()
                    check(immutableFlightMayPublish(root, library) && librarySession == flightSession && rootIdentityMatches(root, flightRootKey)) { "library-flight-changed" }
                }
            } catch (_: InterruptedException) {
                savePreviousOutcome("cancelled", scanned.errors, "cancelled")
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.CANCELLED, running = false, message = "Sincronização cancelada. O snapshot anterior foi preservado")) }
                return@submit
            } catch (error: Exception) {
                savePreviousOutcome("failed", scanned.errors, syncErrorCode(error))
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.FAILED, running = false, message = "${error.message?.take(120) ?: "Falha na sincronização"}. O snapshot anterior foi preservado")) }
                return@submit
            }
            val totalErrors = scanned.errors + xmpFailures
            scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.PUBLISHING, running = true, message = "Publicando snapshot validado")) }
            val publication = try {
                publishReconciledSnapshot(flightCache, observed, flightLedger)
            } catch (error: Exception) {
                savePreviousOutcome("failed", totalErrors, syncErrorCode(error))
                scope.launch { if (librarySession == flightSession) state = state.copy(sync = state.sync.copy(phase = SyncPhase.FAILED, running = false, message = "${error.message?.take(120) ?: "Falha ao publicar"}. O snapshot anterior foi preservado")) }
                return@submit
            }
            val completedSummary = summary(
                "success",
                publication.photos,
                flightCache.snapshotGeneration(),
                totalErrors,
                delta = snapshotDelta(previousSnapshot, publication.photos),
            ) ?: error("Published snapshot generation is unavailable")
            val summarySaveWarning = flightSummaries?.let { store -> summaryWarning { store.save(completedSummary) } }
            val warning = listOfNotNull(publication.warning, summarySaveWarning).joinToString(". ").ifBlank { null }
            scope.launch {
                if (librarySession == flightSession) {
                    val reconciled = flightLedger.overlayAndAccept(publication.photos)
                    state = reduce(state, Action.PublishSnapshot(reconciled)).copy(
                        sync = SyncStatus(
                            SyncPhase.COMPLETE,
                            photos = reconciled.size,
                            errors = totalErrors,
                            running = false,
                            message = warning ?: if (totalErrors == 0) "Snapshot completo publicado" else "Snapshot publicado com $totalErrors falhas (${scanned.errors} metadados, $xmpFailures XMP)",
                            totalPhotos = reconciled.size,
                            durationMillis = completedSummary.durationMillis,
                            added = completedSummary.added,
                            removed = completedSummary.removed,
                            updated = completedSummary.updated,
                        ),
                    )
                }
            }
        }
    }

    private fun mutate(photo: Photo, desired: EditorialState, canonicalizeFlag: Boolean = false) {
        if (!sessionWriteEnabled || !photo.writable) return
        val flightRoot = library ?: return
        val flightRootKey = libraryRootKey ?: return
        if (!rootIdentityMatches(flightRoot, flightRootKey)) {
            state = state.copy(sync = SyncStatus(SyncPhase.FAILED, errors = state.sync.errors + 1, message = "A identidade da biblioteca mudou; a edição foi bloqueada"))
            return
        }
        val current = state.photos.firstOrNull { it.id == photo.id } ?: photo
        val merged = mergeEditorial(photo.editorial, desired, current.editorial)
        val flightLedger = editorialLedger
        val flightSession = librarySession ?: return
        val rollback = flightLedger.confirmed(photo.id) ?: current.editorial
        state = reduce(state, Action.EditorialChanged(photo.id, merged, WriteState.SAVING))
        val store = xmp ?: return
        val flightCache = cache
        edits.submit(
            photo = current,
            desired = merged,
            context = flightSession,
            rollback = rollback,
            persist = { source, editorial ->
                check(rootIdentityMatches(flightRoot, flightRootKey)) { "Library root identity changed before editorial write" }
                store.mutate(source, editorial, canonicalizeFlag).also {
                    check(rootIdentityMatches(flightRoot, flightRootKey)) { "Library root identity changed during editorial write" }
                }
            },
            confirmed = flightLedger::record,
            updateCache = { id, editorial ->
                check(rootIdentityMatches(flightRoot, flightRootKey)) { "Library root identity changed before cache update" }
                flightCache?.updateEditorial(id, editorial)
            },
        )
    }

    private fun batchMutate(photos: List<Photo>, edit: BatchEdit) {
        if (!sessionWriteEnabled) return
        val requestedIds = photos.mapTo(mutableSetOf()) { it.id }
        batchMutationPlan(state.photos.filter { it.id in requestedIds }, edit).forEach { (current, desired) ->
            mutate(current, desired, canonicalizeFlag = edit is BatchEdit.SetFlag)
        }
    }

    private fun navigate(delta: Int) {
        val visible = state.visiblePhotos
        if (visible.isEmpty()) return
        val current = visible.indexOfFirst { it.id == state.selectedId }
        val next = (if (current < 0) 0 else current + delta).coerceIn(0, visible.lastIndex)
        state = reduce(state, Action.Select(visible[next].id))
    }

    private fun openMap(latitude: Double, longitude: Double) {
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=16/$latitude/$longitude")) }
    }

    fun key(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown && !textFieldFocused && shortcutScopeFocused && state.screen == Screen.GALLERY) {
            if ((event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed && event.key == Key.A) {
                state = reduce(state, Action.SelectVisible)
                return true
            }

        }
        if (!shortcutAllowed(event.type == KeyEventType.KeyDown, textFieldFocused, shortcutScopeFocused, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed, event.isShiftPressed)) return false
        val visible = state.visiblePhotos
        val currentIndex = visible.indexOfFirst { it.id == state.selectedId }
        fun move(delta: Int): Boolean { if (visible.isEmpty()) return false; val next = (if (currentIndex < 0) 0 else currentIndex + delta).coerceIn(0, visible.lastIndex); state = reduce(state, Action.Select(visible[next].id)); return true }
        if (state.screen == Screen.GALLERY) return when (event.key) {
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> false
            Key.Enter -> { state = reduce(state, Action.OpenDetail); true }
            Key.Escape -> { state = if (state.selectionModeActive) reduce(state, Action.ToggleSelectionMode) else if (state.selectionIds.isNotEmpty()) reduce(state, Action.ClearSelection) else if (state.filtersOpen) reduce(state, Action.ToggleFilters) else state; true }
            else -> false
        }
        if (state.screen != Screen.DETAIL) return event.key == Key.Escape && run { state = reduce(state, Action.CloseSettings); true }
        val photo = state.selected ?: return false
        editorialForShortcut(event.key, photo.editorial)?.let { mutate(photo, it, canonicalizeFlag = event.key in listOf(Key.P, Key.U, Key.X)); return true }
        return when (event.key) {
            Key.DirectionLeft -> move(-1); Key.DirectionRight -> move(1); Key.Escape -> { state = reduce(state, Action.CloseDetail); true }
            else -> false
        }
    }


    private fun editorial(photo: Photo, update: EditorialState.() -> EditorialState): Boolean { mutate(photo, photo.editorial.update()); return true }
    override fun close() {
        cancelled.set(true)
        worker.shutdownNow()
        runCatching { worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { drainEditorialWrites() }
        editWorker.shutdown()
        runCatching { editWorker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
        libraryResources.forEach { runCatching { it.close() } }
        scope.cancel()
    }
}

internal object SmokeHarness {
    fun run(options: LaunchOptions) {
        val library = options.library?.toAbsolutePath()?.normalize() ?: error("--library is required for --smoke")
        check(!options.enableWrite) { "Smoke harness only supports --read-only" }
        val cache = PhotoCache(options.cache, library)
        val xmp = XmpSidecarStore(library, false)
        val previews = PreviewStore(library, options.cache)
        try {
            val scanned = LibraryScanner().scan(library, AtomicBoolean()) { }
            var xmpFailures = 0
            val photos = scanned.photos.map { photo -> runCatching { photo.copy(editorial = xmp.read(photo)) }.getOrElse { xmpFailures++; photo.copy(writable = false, issue = it.message) } }
            val previewFailures = photos.count { !previews.verify(it) }
            val metadataErrors = photos.count { it.metadata.status == MetadataStatus.ERROR }
            check(photos.isNotEmpty()) { "No supported media was verified" }
            check(previewFailures == 0) { "Required previews failed verification" }
            check(metadataErrors == 0) { "Required metadata failed verification" }
            check(xmpFailures == 0) { "Required XMP sidecars failed verification" }
            cache.publish(photos)
            val loaded = cache.load()
            check(loaded.size == photos.size) { "Published cache count mismatch" }
            val summary = "{\"photos\":${photos.size},\"metadataErrors\":$metadataErrors,\"xmpFailures\":$xmpFailures,\"previewFailures\":$previewFailures,\"cacheReloaded\":${loaded.size},\"xmpWrites\":0}"
            check(summary.length < 1024)
            println(summary)
        } finally {
            runCatching { previews.close() }
            runCatching { xmp.close() }
        }
    }
}

internal fun shortcutAllowed(keyDown: Boolean, textInputFocused: Boolean, shortcutScopeFocused: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean, shift: Boolean): Boolean =
    keyDown && !textInputFocused && shortcutScopeFocused && !ctrl && !alt && !meta && !shift

internal fun mergeEditorial(base: EditorialState, desired: EditorialState, latest: EditorialState): EditorialState = latest.copy(
    flag = if (desired.flag != base.flag) desired.flag else latest.flag,
    rating = if (desired.rating != base.rating) desired.rating else latest.rating,
    label = if (desired.label != base.label) desired.label else latest.label,
    keywords = if (desired.keywords != base.keywords) desired.keywords else latest.keywords,
)

internal fun batchMutationPlan(photos: List<Photo>, edit: BatchEdit): List<Pair<Photo, EditorialState>> = photos.asSequence()
    .distinctBy { it.id }
    .filter { it.writable }
    .take(MAX_BATCH_PHOTOS)
    .mapNotNull { photo -> runCatching { photo to edit.applyTo(photo.editorial) }.getOrNull() }
    .toList()

internal fun immutableFlightMayPublish(flightRoot: Path, currentRoot: Path?): Boolean = currentRoot == flightRoot

private fun syncErrorCode(error: Exception): String = when (error) {
    is java.nio.file.AccessDeniedException -> "access-denied"
    is java.nio.file.NoSuchFileException -> "file-missing"
    is java.sql.SQLException -> "cache-failed"
    else -> "sync-failed"
}

internal fun editorialForShortcut(key: Key, current: EditorialState): EditorialState? = when (key) {
    Key.P -> current.copy(flag = Flag.PICK)
    Key.U -> current.copy(flag = Flag.UNFLAGGED)
    Key.X -> current.copy(flag = Flag.REJECT)
    Key.N -> current.copy(label = null)
    Key.R -> current.copy(label = ColorLabel.RED)
    Key.Y -> current.copy(label = ColorLabel.YELLOW)
    Key.G -> current.copy(label = ColorLabel.GREEN)
    Key.Zero -> current.copy(rating = 0)
    Key.One -> current.copy(rating = 1)
    Key.Two -> current.copy(rating = 2)
    Key.Three -> current.copy(rating = 3)
    Key.Four -> current.copy(rating = 4)
    Key.Five -> current.copy(rating = 5)
    else -> null
}

private fun parseArguments(args: Array<String>): LaunchOptions {
    var library: Path? = null
    var cache: Path = Paths.get(System.getProperty("user.home"), ".cache", "phototool-kmp")
    var write = false
    var smoke = false
    var explicitReadOnly = false
    var i = 0
    while (i < args.size) when (val arg = args[i++]) {
        "--library" -> library = Paths.get(args.getOrNull(i++) ?: error("--library requires a path"))
        "--cache" -> cache = Paths.get(args.getOrNull(i++) ?: error("--cache requires a path"))
        "--enable-write" -> write = true
        "--read-only" -> explicitReadOnly = true
        "--smoke" -> smoke = true
        else -> error("Unknown argument: $arg")
    }
    require(!(write && explicitReadOnly)) { "--enable-write and --read-only are mutually exclusive" }
    return LaunchOptions(library, cache, write, smoke)
}
