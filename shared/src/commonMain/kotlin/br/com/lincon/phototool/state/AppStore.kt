package br.com.lincon.phototool.state

import br.com.lincon.phototool.domain.*

enum class Screen { GALLERY, DETAIL, SETTINGS }
enum class SyncPhase { IDLE, DISCOVERING, METADATA, INDEXING, PUBLISHING, COMPLETE, CANCELLED, FAILED }

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.IDLE,
    val directories: Int = 0,
    val files: Int = 0,
    val photos: Int = 0,
    val errors: Int = 0,
    val currentItem: String = "",
    val running: Boolean = false,
    val message: String = "No synchronization has run",
)

data class AppState(
    val library: String? = null,
    val cache: String? = null,
    val writeEnabled: Boolean = false,
    val photos: List<Photo> = emptyList(),
    val query: Query = Query(),
    val selectedId: String? = null,
    val screen: Screen = Screen.GALLERY,
    val filtersOpen: Boolean = true,
    val helpOpen: Boolean = false,
    val sync: SyncStatus = SyncStatus(),
) { val visiblePhotos get() = filterAndOrder(photos, query); val selected get() = photos.firstOrNull { it.id == selectedId } }

sealed interface Action {
    data class Configure(val library: String?, val cache: String?, val writeEnabled: Boolean): Action
    data class PublishSnapshot(val photos: List<Photo>): Action
    data class SetQuery(val query: Query): Action
    data class Select(val id: String): Action
    data object ClearSelection: Action
    data object OpenDetail: Action
    data object CloseDetail: Action
    data object OpenSettings: Action
    data object CloseSettings: Action
    data object ToggleFilters: Action
    data object ToggleHelp: Action
    data class SyncChanged(val status: SyncStatus): Action
    data class EditorialChanged(val id: String, val editorial: EditorialState, val writeState: WriteState): Action
}

fun reduce(state: AppState, action: Action): AppState = when (action) {
    is Action.Configure -> state.copy(library = action.library, cache = action.cache, writeEnabled = action.writeEnabled)
    is Action.PublishSnapshot -> state.copy(photos = action.photos, selectedId = state.selectedId?.takeIf { id -> action.photos.any { it.id == id } })
    is Action.SetQuery -> state.copy(query = action.query)
    is Action.Select -> state.copy(selectedId = action.id, filtersOpen = false)
    Action.ClearSelection -> state.copy(selectedId = null)
    Action.OpenDetail -> if (state.selectedId != null) state.copy(screen = Screen.DETAIL) else state
    Action.CloseDetail -> state.copy(screen = Screen.GALLERY)
    Action.OpenSettings -> state.copy(screen = Screen.SETTINGS)
    Action.CloseSettings -> state.copy(screen = Screen.GALLERY)
    Action.ToggleFilters -> state.copy(filtersOpen = !state.filtersOpen, selectedId = if (!state.filtersOpen) null else state.selectedId)
    Action.ToggleHelp -> state.copy(helpOpen = !state.helpOpen)
    is Action.SyncChanged -> state.copy(sync = action.status)
    is Action.EditorialChanged -> state.copy(photos = state.photos.map { if (it.id == action.id) it.copy(editorial = action.editorial, writeState = action.writeState) else it })
}

class AppStore(initial: AppState = AppState()) {
    var state: AppState = initial
        private set
    fun dispatch(action: Action) { state = reduce(state, action) }
}
