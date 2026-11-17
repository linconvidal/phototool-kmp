package br.com.lincon.phototool.remote

import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Translates UI actions to a bounded HTTP projection while retaining the production reducer. */
class RemoteCurationStore(private val api: CurationReadApi, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AppState(
        library = "Biblioteca do servidor", cache = "Gerenciado pelo servidor",
        writeEnabled = false, writeAuthorized = false, remote = RemoteBrowseState(),
    ))
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var preparation: Job? = null
    private var epoch = 0
    private var navigationDetail: PendingRemoteDetail? = null
    private var queuedSteps = 0
    private var csrfToken: String? = null
    private var mutationInFlight = false
    private var editorialEpoch = 0
    private var editorialProjectionDirty = false
    private var validatedTicket: Int? = null
    // Only the presented photograph and its two immediate neighbors, never a collection cache.
    private data class PreparedDetail(val detail: PendingRemoteDetail, val query: Query,
        val generation: String?, val editorialEpoch: Int)
    private val preparedDetails = mutableMapOf<String, PreparedDetail>()

    // Construction is inert; the host explicitly starts reading the existing snapshot.
    fun start(photoId: String? = null) = loadPage(1, initialPhotoId = photoId?.let(::requirePhotoId))

    fun openPhoto(id: String) = loadDetail(id)

    fun dispatch(action: Action) {
        // Do not accept local configuration, publication, synchronization or optimistic mutations.
        if (action is Action.Configure || action is Action.PublishSnapshot || action is Action.EditorialChanged ||
            action is Action.ClearPersistedStatus || action is Action.SyncChanged || action is Action.BrowseFolder ||
            action is Action.Navigate && action.section in setOf(LibrarySection.CALENDAR, LibrarySection.FOLDERS)) return
        val before = state.value
        if (before.screen == Screen.DETAIL && action is Action.Select) {
            if (before.photos.any { it.id == action.id }) loadDetail(action.id)
            return
        }
        mutableState.value = reduce(before, action)
        val after = state.value
        when {
            after.query != before.query || after.section != before.section -> loadPage(1, debounce = action is Action.SetQuery)
            action == Action.CloseDetail || before.screen == Screen.DETAIL && after.screen != Screen.DETAIL -> {
                invalidate()
                clearPreparedDetails()
                navigationDetail = null
                mutableState.value = after.copy(remote = after.remote!!.copy(loading = false, error = null, detail = null,
                    requestedId = null, pendingDetail = null, preparePhotos = emptyList(), previousId = null, nextId = null))
                if (editorialProjectionDirty) loadPage(after.remote.page)
            }
            after.screen == Screen.DETAIL && (action == Action.OpenDetail || after.selectedId != before.selectedId) ->
                after.selectedId?.let(::loadDetail)
        }
    }

    fun changePage(delta: Int) {
        val remote = state.value.remote!!
        val target = remote.page + delta
        if (!remote.loading && target in 1..remote.totalPages) loadPage(target)
    }

    fun navigate(delta: Int) {
        if (delta == 0 || state.value.screen != Screen.DETAIL) return
        val remote = state.value.remote!!
        val direction = if (delta < 0) -1 else 1
        val cursor = navigationDetail?.takeIf { it.photo.id == remote.requestedId }
        if (cursor == null && remote.loading) {
            queuedSteps = (queuedSteps + direction).coerceIn(-60, 60)
            return
        }
        val target = if (cursor != null) {
            if (direction < 0) cursor.previousId else cursor.nextId
        } else if (direction < 0) remote.previousId else remote.nextId
        target?.let(::loadDetail)
    }

    fun refresh() {
        if (mutationInFlight) return
        mutableState.value = state.value.copy(remote = state.value.remote!!.copy(editorialNotice = null, editorialNeedsRefresh = false))
        clearPreparedDetails()
        val current = state.value
        val target = current.remote!!.requestedId ?: current.selectedId
        if (current.screen == Screen.DETAIL && target != null) loadDetail(target)
        else loadPage(current.remote!!.page)
    }

    private fun invalidate(): Int {
        request?.cancel()
        preparation?.cancel()
        validatedTicket = null
        queuedSteps = 0
        return ++epoch
    }

    private fun clearPreparedDetails() {
        preparation?.cancel()
        preparedDetails.clear()
        mutableState.value = state.value.copy(remote = state.value.remote!!.copy(preparePhotos = emptyList()))
    }

    private fun loadPage(page: Int, debounce: Boolean = false, initialPhotoId: String? = null) {
        val ticket = invalidate()
        clearPreparedDetails()
        navigationDetail = null
        val current = state.value
        val query = effectiveQuery(current.section, current.query)
        mutableState.value = current.copy(
            photos = emptyList(), selectedId = null, selectedIds = emptySet(), selectionAnchorId = null,
            screen = if (current.screen == Screen.DETAIL) Screen.GALLERY else current.screen,
            remote = current.remote!!.copy(page = page, total = 0, loading = true, error = null, detail = null,
                previousId = null, nextId = null, requestedId = null, pendingDetail = null, preparePhotos = emptyList()),
        )
        request = scope.launch {
            try {
                if (debounce) delay(200)
                val session = (api as? CurationEditorialApi)?.session()
                val status = api.status()
                val result = api.page(query, page)
                require(result.page == page && result.items.size <= result.pageSize && result.pageSize in 1..60)
                require(result.snapshotGeneration == status.snapshotGeneration) { "Snapshot changed; refresh required" }
                if (ticket != epoch) return@launch
                if (page > maxOf(1, result.totalPages)) { loadPage(maxOf(1, result.totalPages)); return@launch }
                val photos = result.items.map { it.toPhoto() }
                if (ticket != epoch) return@launch
                editorialProjectionDirty = false
                mutableState.value = state.value.withServerPermissions(status, session).copy(
                    photos = photos,
                    sync = SyncStatus(
                        phase = SyncPhase.entries.firstOrNull { it.name == status.sync.phase } ?: SyncPhase.IDLE,
                        photos = status.sync.photos, errors = status.sync.errors, running = status.sync.running,
                        message = status.sync.message, totalPhotos = status.sync.totalPhotos,
                    ),
                    remote = RemoteBrowseState(page, result.totalPages, result.total, status.photoCount,
                        result.snapshotGeneration, status.projectionState.name, loading = false,
                        savingPhotoId = state.value.remote!!.savingPhotoId,
                        editorialNotice = state.value.remote!!.editorialNotice,
                        editorialNeedsRefresh = state.value.remote!!.editorialNeedsRefresh),
                )
                initialPhotoId?.let(::loadDetail)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { fail(ticket, "Não foi possível carregar o snapshot. Tente novamente.") }
        }
    }

    private fun loadDetail(id: String) {
        requirePhotoId(id)
        val ticket = invalidate()
        val current = state.value
        navigationDetail = navigationDetail?.takeIf { it.photo.id == id }
        val query: Query = effectiveQuery(current.section, current.query)
        val editorialTicket = editorialEpoch
        val prepared = preparedDetails[id]?.takeIf {
            it.query == query && it.generation == current.remote!!.generation &&
                it.editorialEpoch == editorialTicket && !mutationInFlight && !current.remote.editorialNeedsRefresh
        }?.detail?.copy(ticket = ticket)
        mutableState.value = current.copy(
            // Prepared data permits presentation, not writing. Revalidate capabilities off the visual path.
            writeEnabled = if (prepared != null) false else current.writeEnabled,
            screen = Screen.DETAIL,
            selectedId = current.remote!!.detail?.id ?: id,
            selectedIds = setOf(current.remote.detail?.id ?: id),
            remote = current.remote.copy(loading = true, error = null, requestedId = id, pendingDetail = null, preparePhotos = emptyList()),
        )
        if (prepared != null) {
            navigationDetail = prepared
            mutableState.value = state.value.copy(remote = state.value.remote!!.copy(pendingDetail = prepared))
            request = scope.launch { validatePreparedDetail(prepared, editorialTicket) }
            return
        }
        request = scope.launch {
            try {
                val result = api.detail(query, id)
                require(result.id == id)
                result.previousId?.let(::requirePhotoId)
                result.nextId?.let(::requirePhotoId)
                val photo = result.toPhoto()
                if (ticket != epoch) return@launch
                // A new snapshot must not reuse the old page, neighbor ordering or prepared previews.
                val session = (api as? CurationEditorialApi)?.session()
                val status = api.status()
                if (ticket != epoch) return@launch
                if (editorialTicket != editorialEpoch) { loadDetail(id); return@launch }
                if (!mutationInFlight && status.snapshotGeneration != state.value.remote!!.generation) {
                    loadPage(1, initialPhotoId = id)
                    return@launch
                }
                validatedTicket = ticket
                val pending = PendingRemoteDetail(ticket, photo, result.previousId, result.nextId)
                navigationDetail = pending
                val steps = queuedSteps
                val next = if (steps < 0) result.previousId else result.nextId
                if (steps != 0 && next != null) {
                    loadDetail(next)
                    queuedSteps = steps - if (steps < 0) -1 else 1
                    return@launch
                }
                queuedSteps = 0
                val latest = state.value
                mutableState.value = latest.withServerPermissions(status, session).copy(
                    remote = latest.remote!!.copy(pendingDetail = pending, projection = status.projectionState.name),
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { fail(ticket, "Não foi possível carregar a fotografia. Tente novamente ou volte à biblioteca.") }
        }
    }

    private suspend fun validatePreparedDetail(detail: PendingRemoteDetail, editorialTicket: Int) {
        try {
            val session = (api as? CurationEditorialApi)?.session()
            val status = api.status()
            if (detail.ticket != epoch) return
            if (editorialTicket != editorialEpoch) { loadDetail(detail.photo.id); return }
            if (status.snapshotGeneration != state.value.remote!!.generation) {
                loadPage(1, initialPhotoId = detail.photo.id)
                return
            }
            validatedTicket = detail.ticket
            mutableState.value = state.value.withServerPermissions(status, session).let {
                it.copy(remote = it.remote!!.copy(projection = status.projectionState.name))
            }
            // Presentation may already have finished. Do not let speculation compete with validation.
            if (state.value.remote!!.detail?.id == detail.photo.id && state.value.remote!!.pendingDetail == null) {
                prepareNeighbors(detail, effectiveQuery(state.value.section, state.value.query))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { fail(detail.ticket, "Não foi possível validar o snapshot. Tente novamente.") }
    }

    /** Called only after decode. A late bitmap cannot commit metadata or reopen a closed viewer. */
    fun presentDetail(ticket: Int, ready: Boolean): Boolean {
        val current = state.value
        val pending = current.remote!!.pendingDetail ?: return false
        if (ticket != epoch || ticket != pending.ticket || current.screen != Screen.DETAIL) return false
        if (!ready) {
            // Navigation must not surface a preview failure: keep the viewer truthful and quiet.
            mutableState.value = current.copy(remote = current.remote.copy(loading = true, error = null, pendingDetail = null))
            return false
        }
        val photo = pending.photo
        mutableState.value = current.copy(
            selectedId = photo.id, selectedIds = setOf(photo.id),
            photos = current.photos.map { if (it.id == photo.id) photo else it },
            remote = current.remote.copy(loading = false, error = null, detail = photo, pendingDetail = null,
                previousId = pending.previousId, nextId = pending.nextId),
        )
        val query = effectiveQuery(current.section, current.query)
        val window = listOfNotNull(photo.id, pending.previousId, pending.nextId)
        preparedDetails.keys.retainAll(window.toSet())
        if (!mutationInFlight && !current.remote.editorialNeedsRefresh) {
            preparedDetails[photo.id] = PreparedDetail(pending, query, current.remote.generation, editorialEpoch)
        }
        if (validatedTicket == ticket) prepareNeighbors(pending, query)
        return true
    }

    private fun prepareNeighbors(detail: PendingRemoteDetail, query: Query) {
        preparation?.cancel()
        if (mutationInFlight || state.value.remote!!.editorialNeedsRefresh) return
        val editorialTicket = editorialEpoch
        val generation = state.value.remote!!.generation
        val ids = listOfNotNull(detail.previousId, detail.nextId).distinct().filter { it != detail.photo.id }.take(2)
        preparation = scope.launch {
            // Server detail reads attest XMP under a projection lock. Serial speculation avoids
            // self-induced 409s and publishes each neighbor without waiting for the other one.
            for (id in ids) {
                val prepared = preparedDetails[id]?.takeIf {
                    it.query == query && it.generation == generation && it.editorialEpoch == editorialTicket
                } ?: try {
                    val result = api.detail(query, id)
                    require(result.id == id)
                    result.previousId?.let(::requirePhotoId)
                    result.nextId?.let(::requirePhotoId)
                    PreparedDetail(PendingRemoteDetail(detail.ticket, result.toPhoto(), result.previousId, result.nextId),
                        query, generation, editorialTicket)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { null } // Optional; a cold request still gets its own error/retry path.
                currentCoroutineContext().ensureActive()
                if (detail.ticket != epoch || editorialTicket != editorialEpoch || generation != state.value.remote!!.generation) return@launch
                if (prepared != null) preparedDetails[id] = prepared
                mutableState.value = state.value.copy(remote = state.value.remote!!.copy(
                    preparePhotos = ids.mapNotNull { preparedDetails[it]?.detail?.photo }))
            }
        }
    }

    private fun AppState.withServerPermissions(status: SnapshotStatusDto, session: SessionDto?): AppState {
        csrfToken = session?.csrfToken?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val authorized = csrfToken != null && session?.writeEnabled == true && status.writeEnabled
        return copy(writeAuthorized = authorized,
            writeEnabled = authorized && status.projectionState == ProjectionStateDto.READY && !status.sync.running &&
                remote?.editorialNeedsRefresh != true)
    }

    fun mutate(photo: Photo, desired: EditorialState) {
        val intent = runCatching { editorialIntent(photo.editorial, desired) }.getOrNull() ?: return
        mutate(photo, intent)
    }

    fun mutateFlag(photo: Photo, flag: Flag) = mutate(photo, EditorialCommand.SetFlag(flag))

    private fun mutate(photo: Photo, command: EditorialCommand) {
        val transport = api as? CurationEditorialApi ?: return
        val current = state.value
        val remote = current.remote!!
        val revision = photo.editorialRevision ?: return
        val token = csrfToken ?: return
        if (!current.writeEnabled || !photo.writable || mutationInFlight || remote.editorialNeedsRefresh ||
            current.screen != Screen.DETAIL || remote.loading || remote.detail?.id != photo.id ||
            !remote.detail.writable || remote.detail.editorialRevision != revision) return
        mutationInFlight = true
        ++editorialEpoch
        clearPreparedDetails()
        editorialProjectionDirty = true
        updateEditorial(photo.id, null, WriteState.SAVING, null)
        mutableState.value = state.value.copy(remote = state.value.remote!!.copy(
            savingPhotoId = photo.id, editorialNotice = "${photo.displayName}: salvando no XMP", editorialNeedsRefresh = false))
        // This job is independent of browsing. Navigation must never cancel or retarget a write.
        scope.launch {
            try {
                val result = transport.mutate(photo.id, revision, command, token)
                require(result.photoId == photo.id && result.xmpPersisted && result.projectionUpdated && result.projectionState == ProjectionStateDto.READY)
                result.editorial.toEditorialState()
                val readback = api.detail(Query(), photo.id)
                require(readback.id == photo.id && readback.editorial == result.editorial)
                val status = api.status()
                ++editorialEpoch
                clearPreparedDetails()
                updateEditorial(photo.id, readback.editorial, WriteState.PERSISTED, null, readback.writable)
                mutableState.value = state.value.withServerPermissions(status, SessionDto(token, current.writeAuthorized)).let { latest ->
                    latest.copy(remote = latest.remote!!.copy(generation = status.snapshotGeneration,
                        projection = status.projectionState.name, savingPhotoId = null,
                        editorialNotice = "${photo.displayName}: alterações salvas e verificadas"))
                }
                editorialProjectionDirty = true
                // A write can change membership of Picks/keyword/rating filters after Back.
                if (state.value.screen != Screen.DETAIL) loadPage(state.value.remote!!.page)
            } catch (cancelled: CancellationException) {
                mutationFailed(photo, null)
                throw cancelled
            } catch (failure: Throwable) {
                // Ktor's browser engine reports fetch/network failure as kotlin.Error, not Exception.
                mutationFailed(photo, (failure as? EditorialRequestFailed)?.error)
            } finally {
                mutationInFlight = false
            }
        }
    }

    private fun mutationFailed(photo: Photo, error: ApiErrorDto?) {
        ++editorialEpoch
        clearPreparedDetails()
        editorialProjectionDirty = true
        val message = editorialFailureMessage(error)
        val authoritative = error?.editorial?.takeIf { runCatching { it.toEditorialState() }.isSuccess }
        updateEditorial(photo.id, authoritative, WriteState.FAILED, message)
        val current = state.value
        mutableState.value = current.copy(writeEnabled = false, remote = current.remote!!.copy(
            savingPhotoId = null, editorialNeedsRefresh = true, editorialNotice = "${photo.displayName}: $message",
            projection = if (error?.xmpPersisted == true) "DEGRADED" else current.remote.projection))
    }

    private fun updateEditorial(id: String, editorial: EditorialDto?, status: WriteState, error: String?, writable: Boolean? = null) {
        fun Photo.updated(): Photo = if (this.id != id) this else copy(
            editorial = editorial?.toEditorialState() ?: this.editorial,
            editorialRevision = editorial?.revision ?: editorialRevision, writable = writable ?: this.writable,
            writeState = status, writeError = error)
        val current = state.value
        val remote = current.remote!!
        mutableState.value = current.copy(photos = current.photos.map { it.updated() }, remote = remote.copy(
            detail = remote.detail?.updated(), pendingDetail = remote.pendingDetail?.let { it.copy(photo = it.photo.updated()) }))
        navigationDetail = navigationDetail?.let { it.copy(photo = it.photo.updated()) }
    }

    private fun fail(ticket: Int, message: String) {
        if (ticket == epoch) mutableState.value = state.value.copy(remote = state.value.remote!!.copy(loading = false, error = message, pendingDetail = null))
    }
}
