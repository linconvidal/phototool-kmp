package br.com.lincon.phototool.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
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
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

private data class LaunchOptions(val library: Path?, val cache: Path, val enableWrite: Boolean, val smoke: Boolean)

fun main(args: Array<String>) {
    val options = parseArguments(args)
    if (options.smoke) {
        val code = runCatching { SmokeHarness.run(options) }.fold({ 0 }, { error -> System.err.println("smoke-error: ${error.message?.take(300)}"); 2 })
        if (code != 0) exitProcess(code)
        return
    }
    application {
        val controller = remember { DesktopController(options) }
        DisposableEffect(Unit) { onDispose { controller.close() } }
        Window(onCloseRequest = ::exitApplication, title = "PhotoTool", state = androidx.compose.ui.window.rememberWindowState(width = 1440.dp, height = 920.dp)) {
            PhotoToolApp(controller.state, controller.callbacks, Modifier.onKeyEvent { controller.key(it) })
        }
    }
}

internal class CoalescedEditController(
    private val executor: Executor,
    private val completed: (String, Long, EditorialState, WriteState, String?) -> Unit,
) {
    private data class Request(
        val generation: Long,
        val photo: Photo,
        val desired: EditorialState,
        val persist: (Photo, EditorialState) -> EditorialState,
        val updateCache: (String, EditorialState) -> Unit,
    )
    private val generations = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Request>()
    private val scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun submit(
        photo: Photo,
        desired: EditorialState,
        persist: (Photo, EditorialState) -> EditorialState,
        updateCache: (String, EditorialState) -> Unit,
    ): Long {
        val generation = generations.computeIfAbsent(photo.id) { AtomicLong() }.incrementAndGet()
        pending[photo.id] = Request(generation, photo, desired, persist, updateCache)
        if (scheduled.add(photo.id)) executor.execute { drain(photo.id) }
        return generation
    }

    fun isCurrent(id: String, generation: Long): Boolean = generations[id]?.get() == generation

    private fun drain(id: String) {
        try {
            while (true) {
                val request = pending.remove(id) ?: break
                val persisted = runCatching { request.persist(request.photo, request.desired) }
                if (persisted.isFailure) {
                    if (isCurrent(id, request.generation)) completed(id, request.generation, request.desired, WriteState.FAILED, "XMP mutation failed")
                    continue
                }
                val authoritative = persisted.getOrThrow()
                val cached = runCatching { request.updateCache(id, authoritative) }
                if (cached.isFailure) {
                    if (isCurrent(id, request.generation)) completed(id, request.generation, authoritative, WriteState.FAILED, "XMP persisted but cache update failed")
                    continue
                }
                if (isCurrent(id, request.generation) && !pending.containsKey(id)) completed(id, request.generation, authoritative, WriteState.PERSISTED, null)
            }
        } finally {
            scheduled.remove(id)
            if (pending.containsKey(id) && scheduled.add(id)) executor.execute { drain(id) }
        }
    }
}

private class DesktopController(options: LaunchOptions) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "phototool-io") }
    private var cancelled = AtomicBoolean(false)
    private var library: Path? = options.library?.toAbsolutePath()?.normalize()
    private var cachePath: Path = options.cache.toAbsolutePath().normalize()
    private val writeEnabled = options.enableWrite
    private var cache: PhotoCache? = null
    private var xmp: XmpSidecarStore? = null
    private var previews: PreviewStore? = null
    private var summaries: SyncSummaryStore? = null
    private var auxiliary = AuxiliaryActions()
    private val libraryResources = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()
    private var textFieldFocused = false
    private var shortcutScopeFocused = false
    private var galleryColumns = 1
    private val edits = CoalescedEditController(worker, ::publishEditCompletion)
    private fun publishEditCompletion(id: String, generation: Long, editorial: EditorialState, writeState: WriteState, message: String?) {
        scope.launch {
            if (edits.isCurrent(id, generation)) {
                state = reduce(state, Action.EditorialChanged(id, editorial, writeState))
                if (message != null) state = state.copy(sync = state.sync.copy(message = message))
            }
        }
    }
    var state by mutableStateOf(AppState(library = library?.toString(), cache = cachePath.toString(), writeEnabled = writeEnabled))
        private set

    init { configureServices() }

    val callbacks: AppCallbacks
        get() = AppCallbacks(
            dispatch = ::dispatch,
            chooseLibrary = ::chooseLibrary,
            synchronize = ::synchronize,
            cancelSync = { cancelled.set(true) },
            mutate = ::mutate,
            imageLoader = previews ?: PlatformImageLoader.None,
            auxiliary = auxiliary,
            navigate = ::navigate,
            openMap = ::openMap,
            textFieldFocused = { textFieldFocused = it },
            shortcutScopeFocused = { shortcutScopeFocused = it },
            galleryColumnsChanged = { galleryColumns = it.coerceAtLeast(1) },
        )

    private fun configureServices() {
        val selected = library ?: return
        runCatching {
            cache = PhotoCache(cachePath, selected)
            xmp = XmpSidecarStore(selected, writeEnabled).also(libraryResources::add)
            previews = PreviewStore(selected, cachePath)
            summaries = SyncSummaryStore(cachePath)
            auxiliary = DesktopAuxiliaryActions(xmp!!, FujiProfileStore(selected, writeEnabled).also(libraryResources::add)).callbacks()
            val snapshot = cache!!.load().map { photo ->
                runCatching { photo.copy(editorial = xmp!!.read(photo)) }.getOrElse { photo.copy(issue = photo.issue ?: "XMP read failed: ${it.message}", writable = false) }
            }
            state = state.copy(photos = snapshot, sync = if (snapshot.isNotEmpty()) SyncStatus(SyncPhase.COMPLETE, photos = snapshot.size, message = "Prior snapshot loaded; synchronization remains manual") else state.sync)
        }.onFailure { state = state.copy(sync = SyncStatus(phase = SyncPhase.FAILED, message = it.message ?: "Configuration failed")) }
    }

    fun dispatch(action: Action) { state = reduce(state, action) }

    private fun chooseLibrary() {
        if (state.sync.running) return
        val dialog = FileDialog(null as Frame?, "Choose photo library", FileDialog.LOAD).apply { isMultipleMode = false; isVisible = true }
        val directory = dialog.directory ?: return
        val selected = Paths.get(directory, dialog.file ?: "").let { if (java.nio.file.Files.isDirectory(it)) it else it.parent }.toAbsolutePath().normalize()
        library = selected
        state = state.copy(library = selected.toString(), photos = emptyList(), selectedId = null)
        configureServices()
    }

    private fun synchronize() {
        val root = library ?: return
        if (state.sync.running) return
        cancelled = AtomicBoolean(false)
        state = state.copy(sync = SyncStatus(SyncPhase.DISCOVERING, running = true, message = "Starting synchronization"))
        val flightCache = cache ?: return
        val flightXmp = xmp ?: return
        val flightPreviews = previews
        val flightSummaries = summaries
        worker.submit {
            try {
                val scanned = LibraryScanner().scan(root, cancelled) { progress -> scope.launch { if (library == root) state = state.copy(sync = progress) } }
                if (cancelled.get()) throw InterruptedException()
                val withEditorial = scanned.photos.mapIndexed { index, photo ->
                    if (cancelled.get()) throw InterruptedException()
                    val indexed = runCatching { photo.copy(editorial = flightXmp.read(photo)) }.getOrElse { photo.copy(writable = false, issue = "Unsafe or malformed XMP") }
                    flightPreviews?.let { preview -> runCatching { preview.thumbnail(indexed, 640) } }
                    scope.launch { state = state.copy(sync = state.sync.copy(phase = SyncPhase.INDEXING, photos = index + 1, message = "Building bounded thumbnails and cache")) }
                    indexed
                }
                if (cancelled.get()) throw InterruptedException()
                check(immutableFlightMayPublish(root, library)) { "library-flight-changed" }
                flightCache.publish(withEditorial)
                flightSummaries?.save(SyncSummary("success", withEditorial.size, scanned.errors, ""))
                scope.launch { if (library == root) state = state.copy(photos = withEditorial, sync = SyncStatus(SyncPhase.COMPLETE, photos = withEditorial.size, errors = scanned.errors, running = false, message = "Complete snapshot published")) }
            } catch (_: InterruptedException) {
                runCatching { flightSummaries?.save(SyncSummary("cancelled", state.photos.size, state.sync.errors, "cancelled")) }
                scope.launch { state = state.copy(sync = state.sync.copy(phase = SyncPhase.CANCELLED, running = false, message = "Synchronization cancelled; previous snapshot retained")) }
            } catch (error: Exception) {
                val message = error.message?.take(160) ?: "Synchronization failed; previous snapshot retained"
                runCatching { flightSummaries?.save(SyncSummary("failed", state.photos.size, state.sync.errors, syncErrorCode(error))) }
                scope.launch { state = state.copy(sync = state.sync.copy(phase = SyncPhase.FAILED, running = false, message = message)) }
            }
        }
    }

    private fun mutate(photo: Photo, desired: EditorialState) {
        if (!writeEnabled || !photo.writable) return
        val current = state.photos.firstOrNull { it.id == photo.id } ?: photo
        val merged = mergeEditorial(photo.editorial, desired, current.editorial)
        state = reduce(state, Action.EditorialChanged(photo.id, merged, WriteState.SAVING))
        val store = xmp ?: return
        val flightCache = cache
        edits.submit(current, merged, store::mutate) { id, editorial -> flightCache?.updateEditorial(id, editorial) }
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
        if (!shortcutAllowed(event.type == KeyEventType.KeyDown, textFieldFocused, shortcutScopeFocused, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed, event.isShiftPressed)) return false
        val visible = state.visiblePhotos
        val currentIndex = visible.indexOfFirst { it.id == state.selectedId }
        fun move(delta: Int): Boolean { if (visible.isEmpty()) return false; val next = (if (currentIndex < 0) 0 else currentIndex + delta).coerceIn(0, visible.lastIndex); state = reduce(state, Action.Select(visible[next].id)); return true }
        if (state.screen == Screen.GALLERY) return when (event.key) {
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> moveSpatial(event.key, visible)
            Key.Enter -> { state = reduce(state, Action.OpenDetail); true }
            Key.Escape -> { state = if (state.selectedId != null) reduce(state, Action.ClearSelection) else if (state.filtersOpen) reduce(state, Action.ToggleFilters) else state; true }
            else -> false
        }
        if (state.screen != Screen.DETAIL) return event.key == Key.Escape && run { state = reduce(state, Action.CloseSettings); true }
        val photo = state.selected ?: return false
        editorialForShortcut(event.key, photo.editorial)?.let { mutate(photo, it); return true }
        return when (event.key) {
            Key.DirectionLeft -> move(-1); Key.DirectionRight -> move(1); Key.Escape -> { state = reduce(state, Action.CloseDetail); true }
            else -> false
        }
    }

    private fun moveSpatial(key: Key, visible: List<Photo>): Boolean {
        if (visible.isEmpty()) return false
        val current = visible.indexOfFirst { it.id == state.selectedId }
        val delta = when (key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -galleryColumns; else -> galleryColumns }
        val next = (if (current < 0) 0 else current + delta).coerceIn(0, visible.lastIndex)
        state = reduce(state, Action.Select(visible[next].id))
        return true
    }

    private fun editorial(photo: Photo, update: EditorialState.() -> EditorialState): Boolean { mutate(photo, photo.editorial.update()); return true }
    override fun close() {
        cancelled.set(true)
        worker.shutdownNow()
        runCatching { worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
        libraryResources.forEach { runCatching { it.close() } }
        scope.cancel()
    }
}

private object SmokeHarness {
    fun run(options: LaunchOptions) {
        val library = options.library?.toAbsolutePath()?.normalize() ?: error("--library is required for --smoke")
        check(!options.enableWrite) { "Smoke harness only supports --read-only" }
        val cache = PhotoCache(options.cache, library)
        val xmp = XmpSidecarStore(library, false)
        val previews = PreviewStore(library, options.cache)
        val scanned = LibraryScanner().scan(library, AtomicBoolean()) { }
        val photos = scanned.photos.map { photo -> runCatching { photo.copy(editorial = xmp.read(photo)) }.getOrElse { photo.copy(writable = false, issue = it.message) } }
        val previewFailures = photos.count { !previews.verify(it) }
        cache.publish(photos)
        val loaded = cache.load()
        check(loaded.size == photos.size) { "Published cache count mismatch" }
        val metadataErrors = photos.count { it.metadata.status == MetadataStatus.ERROR }
        check(photos.isNotEmpty()) { "No supported media was verified" }
        check(previewFailures == 0) { "Required previews failed verification" }
        check(metadataErrors == 0) { "Required metadata failed verification" }
        val summary = "{\"photos\":${photos.size},\"metadataErrors\":$metadataErrors,\"previewFailures\":$previewFailures,\"cacheReloaded\":${loaded.size},\"xmpWrites\":0}"
        check(summary.length < 1024)
        println(summary)
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
