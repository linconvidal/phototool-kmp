package br.com.lincon.phototool

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.remote.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteEditorialTest {
    @Test fun permissionRequiresSessionStatusReadyProjectionIdleSyncAndWritablePhoto() = runTest {
        val cases: List<(Api) -> Unit> = listOf(
            { it.sessionWrite = false }, { it.statusWrite = false }, { it.projection = ProjectionStateDto.DEGRADED },
            { it.syncRunning = true }, { it.photoWritable = false }, { it.token = "" },
        )
        for (configure in cases) {
            val api = Api().also(configure)
            val store = open(api)
            val photo = store.state.value.remote!!.detail!!
            store.dispatch(Action.Configure("unsafe", "unsafe", true, true))
            store.mutateFlag(photo, Flag.PICK); advanceUntilIdle()
            assertEquals(0, api.writes.size)
        }
    }

    @Test fun allEditorialIntentsSendExpectedRevisionAndPublishOnlyVerifiedReadback() = runTest {
        val api = Api()
        val store = open(api)
        assertTrue(store.state.value.writeEnabled)
        assertTrue(store.state.value.photos.filter { it.id != "a" }.all { !it.writable })
        suspend fun change(edit: (Photo) -> Unit) {
            val before = store.state.value.remote!!.detail!!
            edit(before)
            assertEquals(before.editorial, store.state.value.remote!!.detail!!.editorial, "No optimistic success")
            assertEquals(WriteState.SAVING, store.state.value.remote!!.detail!!.writeState)
            advanceUntilIdle()
            val saved = store.state.value.remote!!.detail!!
            assertEquals(WriteState.PERSISTED, saved.writeState)
            assertEquals(api.editorials["a"]!!.revision, saved.editorialRevision)
            assertEquals(before.editorialRevision, api.writes.last().revision)
        }
        for (flag in listOf(Flag.PICK, Flag.REJECT, Flag.UNFLAGGED)) change { store.mutateFlag(it, flag) }
        change { store.mutate(it, it.editorial.copy(rating = 5)) }
        change { store.mutate(it, it.editorial.copy(label = ColorLabel.GREEN)) }
        change { store.mutate(it, it.editorial.copy(label = null)) }
        change { store.mutate(it, it.editorial.copy(keywords = it.editorial.keywords + "  móvel   2026 ")) }
        assertTrue("móvel 2026" in store.state.value.remote!!.detail!!.editorial.keywords)
        change { store.mutate(it, it.editorial.copy(keywords = it.editorial.keywords - "móvel 2026")) }
        assertEquals(8, api.writes.size)
        assertTrue(api.writes.all { it.id == "a" && it.token == "c".repeat(64) })
        store.dispatch(Action.CloseDetail); advanceUntilIdle()
        assertEquals(Screen.GALLERY, store.state.value.screen)
        assertEquals(api.generation, store.state.value.remote!!.generation)
    }

    @Test fun duplicateInputIsNotQueuedAndNavigationNeverRetargetsOrCancelsTheWrite() = runTest {
        val api = Api()
        val store = open(api)
        val photo = store.state.value.remote!!.detail!!
        api.gate = CompletableDeferred()
        store.mutate(photo, photo.editorial.copy(rating = 4)); runCurrent()
        store.mutateFlag(photo, Flag.REJECT)
        store.navigate(1); runCurrent()
        present(store); runCurrent()
        assertEquals("b", store.state.value.remote!!.detail!!.id)
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK)
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("a"), api.writes.map { it.id })
        assertEquals("b", store.state.value.remote!!.detail!!.id)
        assertEquals(0, store.state.value.remote!!.detail!!.editorial.rating)
        assertEquals(4, api.editorials["a"]!!.rating)
        store.mutateFlag(photo, Flag.PICK); advanceUntilIdle()
        assertEquals(1, api.writes.size, "Late callback for a cannot edit either photograph")
        store.navigate(-1); advanceUntilIdle(); present(store); advanceUntilIdle()
        assertEquals(4, store.state.value.remote!!.detail!!.editorial.rating)
    }

    @Test fun anOldDetailResponseCannotOverwriteACompletedWrite() = runTest {
        val api = Api()
        val store = open(api)
        api.gate = CompletableDeferred()
        val original = store.state.value.remote!!.detail!!
        store.mutate(original, original.editorial.copy(rating = 5)); runCurrent()
        val delayed = CompletableDeferred<Unit>()
        api.delayedDetail = delayed
        store.openPhoto("a"); runCurrent()
        api.delayedDetail = null
        api.gate!!.complete(Unit); runCurrent()
        delayed.complete(Unit); advanceUntilIdle()
        present(store); advanceUntilIdle()
        assertEquals(5, store.state.value.remote!!.detail!!.editorial.rating)
        assertEquals(api.editorials["a"]!!.revision, store.state.value.remote!!.detail!!.editorialRevision)
    }

    @Test fun closingTheViewerDoesNotCancelAnAlreadySentMutation() = runTest {
        val api = Api()
        val store = open(api)
        api.gate = CompletableDeferred()
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); runCurrent()
        store.dispatch(Action.CloseDetail); runCurrent()
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(Screen.GALLERY, store.state.value.screen)
        assertEquals(Flag.PICK, api.editorials["a"]!!.flag)
        assertNull(store.state.value.remote!!.detail)
        assertTrue(store.state.value.remote!!.editorialNotice!!.contains("a.JPG"))
    }

    @Test fun completingAfterBackRequeriesFilteredGalleryMembership() = runTest {
        val api = Api().apply { editorials.keys.toList().forEach { id -> editorials[id] = editorials.getValue(id).copy(flag = Flag.PICK) } }
        val store = open(api)
        store.dispatch(Action.Navigate(LibrarySection.PICKS)); advanceUntilIdle()
        store.openPhoto("a"); advanceUntilIdle(); present(store); advanceUntilIdle()
        api.gate = CompletableDeferred()
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.REJECT); runCurrent()
        store.dispatch(Action.CloseDetail); runCurrent()
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(Screen.GALLERY, store.state.value.screen)
        assertEquals(listOf("b"), store.state.value.photos.map { it.id })
        assertEquals(1, api.writes.size)
    }

    @Test fun conflictUsesAuthoritativeReadbackAndRequiresExplicitRefreshWithoutRetry() = runTest {
        val api = Api()
        val store = open(api)
        api.failure = ApiErrorDto("editorial-revision-conflict", "conflict",
            editorial = api.editorials["a"]!!.copy(rating = 3, revision = "b".repeat(64)))
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
        assertEquals(3, store.state.value.remote!!.detail!!.editorial.rating)
        assertEquals(WriteState.FAILED, store.state.value.remote!!.detail!!.writeState)
        assertFalse(store.state.value.writeEnabled)
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
        assertEquals(1, api.writes.size)
        api.failure = null
        store.refresh(); advanceUntilIdle(); present(store); advanceUntilIdle()
        assertEquals(1, api.writes.size)
        assertTrue(store.state.value.writeEnabled)
        assertFalse(store.state.value.remote!!.editorialNeedsRefresh)
    }

    @Test fun unconfirmedAndPartialWritesNeverClaimSuccessOrAutomaticallyRepeat() = runTest {
        for (error in listOf(null, ApiErrorDto("csrf-invalid", "no"), ApiErrorDto("write-disabled", "no"),
            ApiErrorDto("projection-update-failed", "partial", true, false,
                EditorialDto(Flag.PICK, 0, null, listOf("Rua"), "d".repeat(64))))) {
            val api = Api().apply { failure = error; transportFailure = error == null }
            val store = open(api)
            store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
            assertEquals(WriteState.FAILED, store.state.value.remote!!.detail!!.writeState)
            assertFalse(store.state.value.writeEnabled)
            assertTrue(store.state.value.remote!!.editorialNeedsRefresh)
            assertEquals(1, api.writes.size)
            if (error?.xmpPersisted == true) {
                assertEquals("DEGRADED", store.state.value.remote!!.projection)
                assertTrue(store.state.value.remote!!.editorialNotice!!.contains("XMP salvo"))
            }
        }
    }

    @Test fun wrongPhotoOrMismatchedReadbackIsNotSuccess() = runTest {
        for (wrongId in listOf(true, false)) {
            val api = Api().apply { wrongPhoto = wrongId; wrongReadback = !wrongId }
            val store = open(api)
            store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
            assertEquals(WriteState.FAILED, store.state.value.remote!!.detail!!.writeState)
            assertTrue(store.state.value.remote!!.editorialNeedsRefresh)
            assertEquals(1, api.writes.size)
        }
    }

    @Test fun pendingNavigationAndStaleRevisionCannotSubmitAnEdit() = runTest {
        val api = Api()
        val store = open(api)
        val photo = store.state.value.remote!!.detail!!
        store.mutateFlag(photo.copy(editorialRevision = "b".repeat(64)), Flag.PICK)
        store.navigate(1)
        store.mutateFlag(photo, Flag.PICK); advanceUntilIdle()
        assertEquals(0, api.writes.size)
    }

    @Test fun warmPresentationNeverReusesPermissionBeforeFreshSessionAndStatus() = runTest {
        val cases: List<(Api) -> Unit> = listOf(
            {}, { it.sessionWrite = false }, { it.statusWrite = false },
            { it.projection = ProjectionStateDto.DEGRADED }, { it.syncRunning = true }, { it.token = "" },
        )
        for ((index, configure) in cases.withIndex()) {
            val api = Api()
            val store = open(api)
            api.sessionGate = CompletableDeferred()
            store.navigate(1)
            assertEquals("b", store.state.value.remote!!.pendingDetail!!.photo.id)
            present(store); runCurrent()
            assertFalse(store.state.value.writeEnabled)
            store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK)
            assertTrue(api.writes.isEmpty())
            configure(api)
            api.sessionGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(index == 0, store.state.value.writeEnabled)
            store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
            assertEquals(if (index == 0) 1 else 0, api.writes.size)
            if (index == 0) assertEquals("b", api.writes.single().id)
        }
    }

    @Test fun readbackInvalidatesBothPreparedNeighborsAndFormerCenter() = runTest {
        val api = Api()
        val store = open(api)
        store.navigate(1); advanceUntilIdle(); present(store); advanceUntilIdle()
        val before = store.state.value.remote!!.detail!!
        store.mutate(before, before.editorial.copy(rating = 5))
        advanceUntilIdle()
        assertTrue(store.state.value.remote!!.preparePhotos.isEmpty())
        store.navigate(-1); advanceUntilIdle(); present(store); advanceUntilIdle()
        store.navigate(1); present(store); advanceUntilIdle()
        val photo = store.state.value.remote!!.detail!!
        assertEquals(5, photo.editorial.rating)
        assertEquals(api.editorials.getValue("b").revision, photo.editorialRevision)
        assertEquals(api.generation, store.state.value.remote!!.generation)
    }

    @Test fun navigationCannotReauthorizeAnUncertainWriteOrReplayIt() = runTest {
        val api = Api()
        val store = open(api)
        api.transportFailure = true
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
        store.navigate(1); advanceUntilIdle(); present(store); advanceUntilIdle()
        assertTrue(store.state.value.remote!!.editorialNeedsRefresh)
        assertFalse(store.state.value.writeEnabled)
        store.mutateFlag(store.state.value.remote!!.detail!!, Flag.PICK); advanceUntilIdle()
        assertEquals(1, api.writes.size)
        assertTrue(store.state.value.remote!!.preparePhotos.isEmpty())
    }

    @Test fun multiFieldAndUnsupportedKeywordChangesAreNotSplitIntoUncertainRequests() {
        val state = EditorialState(keywords = listOf("Rua", "Cidade"))
        assertFailsWith<IllegalArgumentException> { editorialIntent(state, state.copy(flag = Flag.PICK, rating = 5)) }
        assertFailsWith<IllegalArgumentException> { editorialIntent(state, state.copy(keywords = emptyList())) }
        assertFailsWith<IllegalArgumentException> { editorialIntent(state, state.copy(keywords = state.keywords + "A|B")) }
    }

    private suspend fun TestScope.open(api: Api): RemoteCurationStore = RemoteCurationStore(api, this).also {
        it.start("a"); advanceUntilIdle(); present(it); advanceUntilIdle()
    }
    private fun present(store: RemoteCurationStore) {
        assertTrue(store.presentDetail(store.state.value.remote!!.pendingDetail!!.ticket, true))
    }

    private data class Write(val id: String, val revision: String, val command: EditorialCommand, val token: String)
    private class Api : CurationEditorialApi {
        var sessionWrite = true; var statusWrite = true; var photoWritable = true; var syncRunning = false
        var projection = ProjectionStateDto.READY
        var token = "c".repeat(64)
        var generation = "g0"
        var gate: CompletableDeferred<Unit>? = null
        var delayedDetail: CompletableDeferred<Unit>? = null
        var sessionGate: CompletableDeferred<Unit>? = null
        var failure: ApiErrorDto? = null
        var transportFailure = false; var wrongPhoto = false; var wrongReadback = false
        val writes = mutableListOf<Write>()
        val editorials = mutableMapOf("a" to initial(), "b" to initial())
        override suspend fun session(): SessionDto {
            sessionGate?.await()
            return SessionDto(token, sessionWrite)
        }
        override suspend fun status() = SnapshotStatusDto(generation, 2, projection, statusWrite, SyncStatusDto(running = syncRunning))
        override suspend fun page(query: Query, page: Int): PhotoPageDto {
            val eligible = editorials.filter { query.flag == null || it.value.flag == query.flag }
            return PhotoPageDto(generation, page, 24, eligible.size, 1,
                eligible.map { (id, e) -> PhotoSummaryDto(id, "$id.JPG", "JPEG", 1.5, null, null, null,
                    EditorialSummaryDto(e.flag, e.rating, e.label, e.keywords), photoWritable, url(id)) })
        }
        override suspend fun detail(query: Query, id: String): PhotoDetailDto {
            val editorial = editorials.getValue(id)
            delayedDetail?.let { withContext(NonCancellable) { it.await() } }
            return PhotoDetailDto(id, "$id.JPG", "JPEG", 1.5,
                ObservedMetadataDto(null, null, null, null, null, null, null, null, null, null, null, 600, 400, "AVAILABLE", null),
                if (wrongReadback && writes.isNotEmpty()) initial() else editorial, photoWritable, null, url(id),
                if (id == "b") "a" else null, if (id == "a") "b" else null)
        }
        override suspend fun mutate(id: String, revision: String, command: EditorialCommand, csrfToken: String): MutationResultDto {
            writes += Write(id, revision, command, csrfToken)
            gate?.await()
            if (transportFailure) throw Error("Fail to fetch")
            failure?.let { throw EditorialRequestFailed(it) }
            val current = editorials.getValue(id)
            assertEquals(current.revision, revision)
            val next = when (command) {
                is EditorialCommand.SetFlag -> current.copy(flag = command.value)
                is EditorialCommand.SetRating -> current.copy(rating = command.value)
                is EditorialCommand.SetLabel -> current.copy(label = command.value)
                is EditorialCommand.AddKeyword -> current.copy(keywords = current.keywords + command.value)
                is EditorialCommand.RemoveKeyword -> current.copy(keywords = current.keywords - command.value)
            }.copy(revision = writes.size.toString(16).padStart(64, '0'))
            editorials[id] = next; generation = "g${writes.size}"
            return MutationResultDto(if (wrongPhoto) "b" else id, next, true, true, projection)
        }
        private fun url(id: String) = "/api/v1/photos/$id/preview?max=1600&v=${"a".repeat(24)}"
        companion object { fun initial() = EditorialDto(Flag.UNFLAGGED, 0, null, listOf("Rua"), "0".repeat(64)) }
    }
}
