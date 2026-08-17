package br.com.lincon.phototool.state

import br.com.lincon.phototool.domain.*

enum class Screen { GALLERY, CALENDAR, FOLDERS, DETAIL, SETTINGS }
enum class LibrarySection { ALL_PHOTOS, CALENDAR, FOLDERS, PICKS, LATEST }
enum class SelectionMode { REPLACE, TOGGLE, EXTEND }
enum class SyncPhase { IDLE, DISCOVERING, METADATA, INDEXING, PUBLISHING, COMPLETE, CANCELLED, FAILED }

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.IDLE,
    val directories: Int = 0,
    val files: Int = 0,
    val photos: Int = 0,
    val errors: Int = 0,
    val currentItem: String = "",
    val running: Boolean = false,
    val message: String = "Nenhuma sincronização executada",
    val totalPhotos: Int? = null,
    val durationMillis: Long? = null,
    val added: Int? = null,
    val removed: Int? = null,
    val updated: Int? = null,
)

data class AppState(
    val library: String? = null,
    val cache: String? = null,
    val writeEnabled: Boolean = false,
    val writeAuthorized: Boolean = writeEnabled,
    val photos: List<Photo> = emptyList(),
    val query: Query = Query(),
    val selectedId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val selectionAnchorId: String? = null,
    val selectionModeActive: Boolean = false,
    val section: LibrarySection = LibrarySection.ALL_PHOTOS,
    val thumbnailSize: Int = 190,
    val screen: Screen = Screen.GALLERY,
    val filtersOpen: Boolean = false,
    val helpOpen: Boolean = false,
    val sync: SyncStatus = SyncStatus(),
) {
    val visiblePhotos get() = filterAndOrder(photos, effectiveQuery(section, query))
    val selected get() = photos.firstOrNull { it.id == selectedId }
    val selectionIds get() = if (selectedIds.isEmpty() && selectedId != null) setOf(selectedId) else selectedIds
    val selectedPhotos get() = selectionIds.mapNotNull { id -> photos.firstOrNull { it.id == id } }
}

sealed interface Action {
    data class Configure(val library: String?, val cache: String?, val writeEnabled: Boolean, val writeAuthorized: Boolean = writeEnabled): Action
    data class PublishSnapshot(val photos: List<Photo>): Action
    data class SetQuery(val query: Query): Action
    data class Select(val id: String, val mode: SelectionMode = SelectionMode.REPLACE): Action
    data object ToggleSelectionMode: Action
    data object SelectVisible: Action
    data class Navigate(val section: LibrarySection): Action
    data class BrowseDate(val fromDate: String, val toDate: String): Action
    data class BrowseFolder(val folder: String): Action
    data class SetThumbnailSize(val size: Int): Action
    data object ClearSelection: Action
    data object OpenDetail: Action
    data object CloseDetail: Action
    data object OpenSettings: Action
    data object CloseSettings: Action
    data object ToggleFilters: Action
    data object ToggleHelp: Action
    data class SyncChanged(val status: SyncStatus): Action
    data class EditorialChanged(val id: String, val editorial: EditorialState, val writeState: WriteState, val writeError: String? = null): Action
    data class ClearPersistedStatus(val id: String, val expectedEditorial: EditorialState): Action
}

fun reduce(state: AppState, action: Action): AppState = when (action) {
    is Action.Configure -> state.copy(library = action.library, cache = action.cache, writeEnabled = action.writeEnabled, writeAuthorized = action.writeAuthorized)
    is Action.PublishSnapshot -> {
        val ids = action.photos.mapTo(mutableSetOf()) { it.id }
        val selectedIds = state.selectionIds.intersect(ids).take(MAX_BATCH_PHOTOS).toSet()
        val selectedId = state.selectedId?.takeIf(ids::contains) ?: selectedIds.firstOrNull()
        val transient = state.photos.associateBy { it.id }
        val published = action.photos.map { photo -> transient[photo.id]?.takeIf { it.writeState != WriteState.IDLE }?.let {
            photo.copy(editorial = if (it.writeState == WriteState.SAVING) it.editorial else photo.editorial, writeState = it.writeState, writeError = it.writeError)
        } ?: photo }
        state.copy(
            photos = published,
            selectedId = selectedId,
            selectedIds = selectedIds,
            selectionAnchorId = state.selectionAnchorId?.takeIf(ids::contains),
            screen = if (state.screen == Screen.DETAIL && selectedId == null) Screen.GALLERY else state.screen,
        )
    }
    is Action.SetQuery -> applyQuery(state, action.query)
    is Action.Select -> select(state, action.id, action.mode)
    Action.ToggleSelectionMode -> if (state.selectionModeActive) {
        state.copy(selectionModeActive = false, selectedId = null, selectedIds = emptySet(), selectionAnchorId = null)
    } else {
        state.copy(selectionModeActive = true, selectedId = null, selectedIds = emptySet(), selectionAnchorId = null, filtersOpen = false)
    }
    Action.SelectVisible -> {
        val visible = state.visiblePhotos.take(MAX_BATCH_PHOTOS).mapTo(linkedSetOf()) { it.id }
        state.copy(selectionModeActive = true, selectedIds = visible, selectedId = state.selectedId?.takeIf(visible::contains) ?: visible.firstOrNull(), selectionAnchorId = visible.firstOrNull())
    }
    is Action.Navigate -> navigate(state, action.section)
    is Action.BrowseDate -> if (queryDateError(action.fromDate, action.toDate) != null) state else state.copy(screen = Screen.GALLERY, query = state.query.copy(fromDate = action.fromDate, toDate = action.toDate, folder = null), selectionModeActive = false, selectedId = null, selectedIds = emptySet(), selectionAnchorId = null)
    is Action.BrowseFolder -> state.copy(screen = Screen.GALLERY, query = state.query.copy(folder = action.folder, fromDate = null, toDate = null), selectionModeActive = false, selectedId = null, selectedIds = emptySet(), selectionAnchorId = null)
    is Action.SetThumbnailSize -> state.copy(thumbnailSize = action.size.coerceIn(110, 320))
    Action.ClearSelection -> state.copy(selectedId = null, selectedIds = emptySet(), selectionAnchorId = null)
    Action.OpenDetail -> if (state.selectedId != null && state.visiblePhotos.any { it.id == state.selectedId }) state.copy(screen = Screen.DETAIL, selectionModeActive = false) else state
    Action.CloseDetail -> state.copy(screen = Screen.GALLERY)
    Action.OpenSettings -> state.copy(screen = Screen.SETTINGS)
    Action.CloseSettings -> state.copy(screen = when (state.section) { LibrarySection.CALENDAR -> Screen.CALENDAR; LibrarySection.FOLDERS -> Screen.FOLDERS; else -> Screen.GALLERY })
    Action.ToggleFilters -> state.copy(filtersOpen = !state.filtersOpen)
    Action.ToggleHelp -> state.copy(helpOpen = !state.helpOpen)
    is Action.SyncChanged -> state.copy(sync = action.status)
    is Action.EditorialChanged -> state.copy(photos = state.photos.map { if (it.id == action.id) it.copy(editorial = action.editorial, writeState = action.writeState, writeError = action.writeError) else it })
    is Action.ClearPersistedStatus -> state.copy(photos = state.photos.map { photo ->
        if (photo.id == action.id && photo.writeState == WriteState.PERSISTED && photo.editorial == action.expectedEditorial) {
            photo.copy(writeState = WriteState.IDLE, writeError = null)
        } else photo
    })
}

private fun applyQuery(state: AppState, query: Query): AppState {
    if (queryDateError(query.fromDate, query.toDate) != null) return state
    val normalized = if (state.section == LibrarySection.PICKS) query.copy(flag = null) else query
    val visibleIds = filterAndOrder(state.photos, effectiveQuery(state.section, normalized)).mapTo(linkedSetOf()) { it.id }
    val selectedIds = state.selectionIds.intersect(visibleIds).take(MAX_BATCH_PHOTOS).toSet()
    val primary = state.selectedId?.takeIf(visibleIds::contains) ?: selectedIds.firstOrNull()
    return state.copy(
        query = normalized,
        selectedId = primary,
        selectedIds = selectedIds,
        selectionAnchorId = state.selectionAnchorId?.takeIf(visibleIds::contains) ?: primary,
    )
}

private fun select(state: AppState, id: String, mode: SelectionMode): AppState {
    if (state.photos.none { it.id == id }) return state
    return when (mode) {
        SelectionMode.REPLACE -> state.copy(selectedId = id, selectedIds = setOf(id), selectionAnchorId = id, filtersOpen = false)
        SelectionMode.TOGGLE -> {
            val updated = state.selectionIds.toMutableSet()
            if (id in updated) updated.remove(id)
            else if (updated.size < MAX_BATCH_PHOTOS) updated.add(id)
            else return state
            state.copy(selectionModeActive = true, selectedIds = updated, selectedId = state.selectedId?.takeIf(updated::contains) ?: updated.firstOrNull(), selectionAnchorId = state.selectionAnchorId ?: id, filtersOpen = false)
        }
        SelectionMode.EXTEND -> {
            val visible = state.visiblePhotos
            val anchor = visible.indexOfFirst { it.id == (state.selectionAnchorId ?: state.selectedId) }.takeIf { it >= 0 } ?: 0
            val target = visible.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return state
            val boundedTarget = if (target >= anchor) minOf(target, anchor + MAX_BATCH_PHOTOS - 1) else maxOf(target, anchor - MAX_BATCH_PHOTOS + 1)
            val range = visible.subList(minOf(anchor, boundedTarget), maxOf(anchor, boundedTarget) + 1).mapTo(linkedSetOf()) { it.id }
            state.copy(selectionModeActive = true, selectedIds = range, selectedId = visible[boundedTarget].id, selectionAnchorId = visible[anchor].id, filtersOpen = false)
        }
    }
}

private fun navigate(state: AppState, section: LibrarySection): AppState {
    val base = state.query.copy(folder = null, fromDate = null, toDate = null, flag = null, sort = PhotoSort.CAPTURE_TIME)
    val query = when (section) {
        LibrarySection.PICKS -> base
        LibrarySection.LATEST -> base.copy(sort = PhotoSort.RECENTLY_ADDED)
        else -> base
    }
    val screen = when (section) {
        LibrarySection.CALENDAR -> Screen.CALENDAR
        LibrarySection.FOLDERS -> Screen.FOLDERS
        else -> Screen.GALLERY
    }
    return state.copy(section = section, screen = screen, query = query, selectionModeActive = false, selectedId = null, selectedIds = emptySet(), selectionAnchorId = null, filtersOpen = false)
}

fun effectiveQuery(section: LibrarySection, query: Query): Query =
    if (section == LibrarySection.PICKS) query.copy(flag = Flag.PICK) else query

class AppStore(initial: AppState = AppState()) {
    var state: AppState = initial
        private set
    fun dispatch(action: Action) { state = reduce(state, action) }
}
