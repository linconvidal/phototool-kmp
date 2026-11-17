package br.com.lincon.phototool

import br.com.lincon.phototool.api.*
import br.com.lincon.phototool.domain.*
import br.com.lincon.phototool.remote.*
import br.com.lincon.phototool.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTransitionTest {
    @Test fun metadataAndSelectionWaitForTheMatchingDecodedPreview() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        store.navigate(1); advanceUntilIdle()
        assertEquals("a", store.state.value.selectedId)
        assertEquals("camera-a", store.state.value.selected!!.metadata.camera)
        assertEquals("b", store.state.value.remote!!.pendingDetail!!.photo.id)
        assertTrue(store.state.value.remote!!.loading)
        present(store)
        assertEquals("b", store.state.value.selectedId)
        assertEquals("camera-b", store.state.value.selected!!.metadata.camera)
        assertEquals("a", store.state.value.remote!!.previousId)
    }

    @Test fun detailAndPreviewFailuresKeepThePresentedPhotoAndRetryTheRequestedOne() = runTest {
        val api = TransitionApi()
        val store = coldNeighborStore(api)
        api.fail = "b"
        store.navigate(1); advanceUntilIdle()
        assertEquals("a", store.state.value.selected!!.id)
        assertNotNull(store.state.value.remote!!.error)
        api.fail = null
        store.refresh(); advanceUntilIdle()
        val pending = store.state.value.remote!!.pendingDetail!!
        assertEquals("b", pending.photo.id)
        assertFalse(store.presentDetail(pending.ticket, false))
        // A preview that is not decoded yet must not become a navigation error: the viewer keeps
        // the presented photo, stays loading, and retries instead of showing a banner.
        assertEquals("a", store.state.value.selected!!.id)
        assertNull(store.state.value.remote!!.error)
        assertTrue(store.state.value.remote!!.loading)
        store.refresh(); advanceUntilIdle()
        assertEquals("b", store.state.value.remote!!.pendingDetail!!.photo.id)
        present(store)
        assertEquals("b", store.state.value.selected!!.id)
        assertNull(store.state.value.remote!!.error)
    }

    @Test fun lateDetailAndLateBitmapCannotWinAgainstNewIntent() = runTest {
        val api = TransitionApi()
        val store = coldNeighborStore(api)
        val release = CompletableDeferred<Unit>()
        api.gate = "b" to release
        store.navigate(1); runCurrent()
        store.openPhoto("c"); advanceUntilIdle()
        val c = store.state.value.remote!!.pendingDetail!!
        release.complete(Unit); advanceUntilIdle()
        assertEquals(c, store.state.value.remote!!.pendingDetail)
        store.openPhoto("d"); advanceUntilIdle()
        assertFalse(store.presentDetail(c.ticket, true))
        assertEquals("a", store.state.value.selected!!.id)
        present(store)
        assertEquals("d", store.state.value.selected!!.id)
    }

    @Test fun rapidDirectionsAccumulateWithoutBlockingAndCanReverseBeforeDecode() = runTest {
        val api = TransitionApi()
        val store = coldNeighborStore(api)
        val release = CompletableDeferred<Unit>()
        api.gate = "b" to release
        store.navigate(1); runCurrent()
        store.navigate(1); store.navigate(1); store.navigate(-1)
        release.complete(Unit); advanceUntilIdle()
        assertEquals("c", store.state.value.remote!!.pendingDetail!!.photo.id)
        assertEquals("a", store.state.value.selected!!.id)
        store.navigate(-1); advanceUntilIdle(); present(store)
        assertEquals("b", store.state.value.selected!!.id)
        store.navigate(-1); store.navigate(-1); advanceUntilIdle(); present(store)
        assertEquals("a", store.state.value.selected!!.id, "Do not wrap around a server boundary")
    }

    @Test fun closeAndQueryChangesInvalidatePendingDecodeAndNeighborPreparation() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        store.navigate(1); advanceUntilIdle()
        val ticket = store.state.value.remote!!.pendingDetail!!.ticket
        store.dispatch(Action.CloseDetail)
        assertFalse(store.presentDetail(ticket, true))
        assertEquals(Screen.GALLERY, store.state.value.screen)
        assertNull(store.state.value.remote!!.detail)
        assertTrue(store.state.value.remote!!.preparePhotos.isEmpty())
        store.openPhoto("c"); advanceUntilIdle()
        val other = store.state.value.remote!!.pendingDetail!!.ticket
        store.dispatch(Action.SetQuery(Query(search = "different"))); advanceUntilIdle()
        assertFalse(store.presentDetail(other, true))
        assertNull(store.state.value.remote!!.detail)
        assertEquals(Screen.GALLERY, store.state.value.screen)
    }

    @Test fun initialFailureIsNotPresentedAsAnOldGallerySummary() = runTest {
        val api = TransitionApi().apply { fail = "a" }
        val store = RemoteCurationStore(api, this)
        store.start("a"); advanceUntilIdle()
        assertNull(store.state.value.remote!!.detail)
        assertNotNull(store.state.value.remote!!.error)
        api.fail = null
        store.refresh(); advanceUntilIdle(); present(store)
        assertEquals("a", store.state.value.remote!!.detail!!.id)
    }

    @Test fun preparesOnlyImmediateNeighborsAndNeverRecursivelyWalksTheCollection() = runTest {
        val api = TransitionApi()
        val store = RemoteCurationStore(api, this)
        store.start("b"); advanceUntilIdle(); present(store); advanceUntilIdle()
        assertEquals(listOf("b", "a", "c"), api.details)
        assertEquals(listOf("a", "c"), store.state.value.remote!!.preparePhotos.map { it.id })
        assertFalse(store.state.value.writeEnabled)
        assertTrue(store.state.value.remote!!.preparePhotos.all { !it.writable })
    }

    @Test fun snapshotAndSameIdPreviewVersionChangesCannotReuseStagedMetadata() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        store.refresh(); advanceUntilIdle()
        val stale = store.state.value.remote!!.pendingDetail!!
        api.generation = "new-snapshot"
        api.version = "b".repeat(24)
        store.refresh(); advanceUntilIdle()
        assertEquals("new-snapshot", store.state.value.remote!!.generation)
        assertNull(store.state.value.remote!!.detail)
        assertFalse(store.presentDetail(stale.ticket, true))
        val fresh = store.state.value.remote!!.pendingDetail!!
        assertTrue(fresh.photo.presentation!!.previewUrl.endsWith(api.version))
        present(store)
        assertEquals(fresh.photo, store.state.value.remote!!.detail)
    }

    @Test fun bothWarmNeighborsStageWithoutAnyNetworkAndReverseBeforeValidation() = runTest {
        for (direction in listOf(-1, 1)) {
            val api = TransitionApi()
            val store = RemoteCurationStore(api, this)
            store.start("b"); advanceUntilIdle(); present(store); advanceUntilIdle()
            val reads = api.details.toList()
            api.statusGate = CompletableDeferred()
            store.navigate(direction)
            assertEquals(if (direction < 0) "a" else "c", store.state.value.remote!!.pendingDetail!!.photo.id)
            present(store); runCurrent()
            assertFalse(store.state.value.remote!!.loading)
            assertEquals(reads, api.details)
            store.navigate(-direction)
            assertEquals("b", store.state.value.remote!!.pendingDetail!!.photo.id)
            present(store); runCurrent()
            assertEquals(reads, api.details, "The old center remains prepared for an immediate reversal")
            api.statusGate!!.complete(Unit); advanceUntilIdle()
        }
    }

    @Test fun slowSecondNeighborDoesNotHoldTheFirstAndPreparationIsSerial() = runTest {
        val api = TransitionApi()
        val store = RemoteCurationStore(api, this)
        store.start("b"); advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        api.gate = "c" to release
        present(store); runCurrent()
        assertEquals(listOf("a"), store.state.value.remote!!.preparePhotos.map { it.id })
        assertEquals(1, api.peakDetails)
        store.navigate(-1); present(store); runCurrent()
        release.complete(Unit); advanceUntilIdle()
        assertEquals("a", store.state.value.remote!!.detail!!.id)
        assertEquals(listOf("b"), store.state.value.remote!!.preparePhotos.map { it.id })
        assertFalse(store.state.value.remote!!.preparePhotos.any { it.id == "c" }, "Canceled late preparation cannot populate another window")
    }

    @Test fun preparedDetailsAreLimitedToTheImmediateWindow() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        repeat(2) { store.navigate(1); advanceUntilIdle(); present(store); advanceUntilIdle() }
        assertEquals("c", store.state.value.remote!!.detail!!.id)
        assertEquals(listOf("b", "d"), store.state.value.remote!!.preparePhotos.map { it.id })
        val before = api.details.count { it == "a" }
        store.openPhoto("a"); advanceUntilIdle()
        assertEquals(before + 1, api.details.count { it == "a" }, "Former photos beyond the three-item window are cold")
    }

    @Test fun changedSnapshotInvalidatesWarmDetailAndPreviewVersionAfterValidation() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        api.statusGate = CompletableDeferred()
        store.navigate(1); present(store); runCurrent()
        assertEquals("b", store.state.value.remote!!.detail!!.id)
        api.generation = "next-snapshot"; api.version = "b".repeat(24)
        api.statusGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("next-snapshot", store.state.value.remote!!.generation)
        assertNull(store.state.value.remote!!.detail)
        val fresh = store.state.value.remote!!.pendingDetail!!
        assertTrue(fresh.photo.presentation!!.previewUrl.endsWith(api.version))
        present(store); advanceUntilIdle()
        assertTrue(store.state.value.remote!!.preparePhotos.all { it.presentation!!.previewUrl.endsWith(api.version) })
    }

    @Test fun filterAndSortChangesDiscardPreparedOrderingEvenWithinTheSameSnapshot() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        val before = api.details.size
        store.dispatch(Action.SetQuery(Query(search = "different", sort = PhotoSort.RECENTLY_ADDED))); advanceUntilIdle()
        store.openPhoto("b"); advanceUntilIdle()
        assertEquals(before + 1, api.details.size)
        assertEquals(PhotoSort.RECENTLY_ADDED, api.lastQuery.sort)
        assertEquals("different", api.lastQuery.search)
        assertTrue(store.state.value.remote!!.preparePhotos.isEmpty())
    }

    @Test fun failedWarmValidationRetainsThePairButRefreshIsAlwaysCold() = runTest {
        val api = TransitionApi()
        val store = readyStore(api)
        api.failStatus = true
        store.navigate(1); present(store); advanceUntilIdle()
        assertEquals("b", store.state.value.remote!!.detail!!.id)
        assertNotNull(store.state.value.remote!!.error)
        assertFalse(store.state.value.writeEnabled)
        api.failStatus = false
        val before = api.details.count { it == "b" }
        store.refresh(); advanceUntilIdle(); present(store); advanceUntilIdle()
        assertEquals(before + 1, api.details.count { it == "b" })
        assertNull(store.state.value.remote!!.error)
    }

    // Cold error/retry and response-race tests intentionally fail speculative preparation,
    // rather than accidentally exercising the newly warmed path and weakening their assertions.
    private suspend fun TestScope.coldNeighborStore(api: TransitionApi): RemoteCurationStore {
        api.fail = "b"
        return readyStore(api).also { api.fail = null }
    }

    private suspend fun TestScope.readyStore(api: TransitionApi): RemoteCurationStore {
        val store = RemoteCurationStore(api, this)
        store.start("a"); advanceUntilIdle(); present(store); advanceUntilIdle()
        return store
    }

    private fun present(store: RemoteCurationStore) {
        assertTrue(store.presentDetail(store.state.value.remote!!.pendingDetail!!.ticket, true))
    }

    private class TransitionApi : CurationReadApi {
        var generation = "snapshot"
        var version = "a".repeat(24)
        var fail: String? = null
        var gate: Pair<String, CompletableDeferred<Unit>>? = null
        val details = mutableListOf<String>()
        var lastQuery = Query()
        var statusGate: CompletableDeferred<Unit>? = null
        var failStatus = false
        var activeDetails = 0
        var peakDetails = 0
        private val ids = listOf("a", "b", "c", "d")
        override suspend fun status(): SnapshotStatusDto {
            statusGate?.await()
            if (failStatus) throw Error("fetch failed")
            return SnapshotStatusDto(generation, 4, ProjectionStateDto.READY, false, SyncStatusDto())
        }
        override suspend fun page(query: Query, page: Int) = PhotoPageDto(generation, page, 24, 4, 1, ids.map {
            PhotoSummaryDto(it, "$it.JPG", "JPEG", 1.5, null, null, null,
                EditorialSummaryDto(Flag.UNFLAGGED, 0, null, emptyList()), false, url(it))
        })
        override suspend fun detail(query: Query, id: String): PhotoDetailDto {
            details += id; lastQuery = query
            activeDetails++; peakDetails = maxOf(peakDetails, activeDetails)
            try {
                gate?.takeIf { it.first == id }?.let { withContext(NonCancellable) { it.second.await() } }
                if (id == fail) error("injected detail failure")
            } finally { activeDetails-- }
            val index = ids.indexOf(id)
            return PhotoDetailDto(id, "$id.JPG", "JPEG", 1.5,
                ObservedMetadataDto(null, "camera-$id", null, null, null, null, null, null, null, null, null, 600, 400, "AVAILABLE", null),
                EditorialDto(Flag.UNFLAGGED, index, null, listOf(id), "revision"), false, null, url(id),
                ids.getOrNull(index - 1), ids.getOrNull(index + 1))
        }
        private fun url(id: String) = "/api/v1/photos/$id/preview?max=1600&v=$version"
    }
}
